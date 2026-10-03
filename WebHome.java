package com.github.catvod.spider;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.github.catvod.crawler.Spider;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public class WebHome extends Spider {
    private static final int MAX_ACTIVITY_RETRIES = 18;
    private static final String TAG = "WebHome";
    private static volatile Context appContext;
    private static volatile boolean lifecycleInstalled;
    private static volatile Overlay overlay;
    private static volatile String lastSiteKey = "";
    private static volatile WeakReference<Activity> foreground = new WeakReference<>(null);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final int COOKIE_TTL_MS = 5 * 60 * 1000;
    private static final int HEADER_TTL_MS = 10 * 60 * 1000;
    private static final int REFRESH_MAX_PENDING = 64;
    private static final Map<String, TimedValue> COOKIE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, TimedValue> HEADER_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> REFRESHING = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final ExecutorService HTTP_EXECUTOR = newBackgroundThreadPool(8, 64);

    /*
     * P2-e 后台线程池：核心 8 / 最大 64,空闲 60s 回收,CallerRunsPolicy 让提交端兜底避免抛错。
     * 线程优先级降为 MIN_PRIORITY,与 UI 主线程抢 CPU 的情况显著减少。
     * 相比 newFixedThreadPool(12) 常驻 12 线程的做法,TV 盒低内存下更友好。
     */
    private static ExecutorService newBackgroundThreadPool(int core, int max) {
        return new ThreadPoolExecutor(core, max, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(REFRESH_MAX_PENDING), new ThreadFactory() {
                    private final AtomicInteger seq = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "webhome-net-" + seq.getAndIncrement());
                        try { t.setPriority(Thread.MIN_PRIORITY); } catch (Throwable ignored) {}
                        return t;
                    }
                }, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /* P1-c TTL 值封装 */
    private static final class TimedValue {
        final Object value;
        volatile long ts = System.currentTimeMillis();
        TimedValue(Object value) { this.value = value; }
        boolean isExpired(int ttlMs) { return ttlMs <= 0 ? false : System.currentTimeMillis() - ts > ttlMs; }
    }

    /*
     * 域名线路记忆：首次遇到某图片域名时先走代理兑底，
     * 同时后台探测该域名能否直连；探测通过则后续同域名
     * 图片直接返回原图 URL，交给 WebView 原生加载
     * （Chromium 自带 HTTP/2、连接复用和磁盘缓存，比 Java 代理快）。
     */
    private static final int ROUTE_UNKNOWN = 0;
    private static final int ROUTE_DIRECT = 1;
    private static final int ROUTE_PROXY = 2;
    private static final long ROUTE_TTL_MILLIS = 30 * 60 * 1000L;
    /*
     * 探测超时与等待上限：健康 CDN 几百毫秒内出结果；
     * JS 侧最多只等 PROBE_MAX_WAIT，超时立刻走代理兑底，
     * 探测继续在后台跑完，结果对后续请求生效。
     */
    private static final int PROBE_CONNECT_TIMEOUT = 500;
    private static final int PROBE_READ_TIMEOUT = 1000;
    private static final long PROBE_MAX_WAIT_MILLIS = 2000L;
    private static final int PROBE_HEAD_BYTES = 1 * 1024;
    private static final ConcurrentHashMap<String, DomainRoute> DOMAIN_ROUTES = new ConcurrentHashMap<>();

    private static final class DomainRoute {
        volatile int mode = ROUTE_UNKNOWN;
        volatile long checkedAt;
        volatile boolean probing;
    }

    /*
     * 内存图片缓存：图片首次由 WebView 拉取时边转发边收集字节，
     * 读完异步入缓存；下次同图直接内存秒回，不再走网络。
     */
    private static final int MAX_MEMORY_CACHE_BYTES = 48 * 1024 * 1024;
    private static final int MAX_CACHE_ITEM_BYTES = 5 * 1024 * 1024;
    private static final long DEFAULT_FRESH_MILLIS = 5 * 60 * 1000L;
    private static final LruCache<String, CachedResource> MEMORY_CACHE = new LruCache<String, CachedResource>(MAX_MEMORY_CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, CachedResource value) {
            return value.bytes.length;
        }
    };
    private static volatile boolean httpCacheInstalled;

    private static volatile FmActionHandler globalHandler;
    private String extend = "";
    private com.fongmi.web.WebHomeExtConfig extConfig;

    public static void setHandler(FmActionHandler handler) {
        globalHandler = handler;
    }

    public static void useDefaultHandler() {
        if (!(globalHandler instanceof DefaultFmActionHandler)) {
            globalHandler = new DefaultFmActionHandler(appContext);
        }
        try {
            ((DefaultFmActionHandler) globalHandler).setSiteKey(currentSiteKey());
        } catch (Throwable ignored) {}
    }

    @Override
    public void init(Context context, String str) {
        if (context != null) {
            Context applicationContext = context.getApplicationContext();
            if (applicationContext != null) context = applicationContext;
            appContext = context;
            installLifecycleTracker(appContext);
            installHttpCache(appContext);
            try { LocalMediaServer.get().ensureStarted(); } catch (Throwable ignored) {}
        }
        this.extend = str == null ? "" : str.trim();
        // 加载 ext 配置: 分类列表. 字符串 → 读 json 文件; 对象 → 直接用.
        try {
            this.extConfig = com.fongmi.web.WebHomeExtConfig.load(this.extend);
        } catch (Throwable t) {
            android.util.Log.w("WebHome", "failed to load ext config: " + t.getMessage());
            this.extConfig = new com.fongmi.web.WebHomeExtConfig();
        }
        rememberSiteKey(runtimeSiteKey());
        try {
            if (globalHandler instanceof DefaultFmActionHandler) {
                ((DefaultFmActionHandler) globalHandler).setSiteKey(currentSiteKey());
            }
        } catch (Throwable ignored) {}
    }

    /*
     * HttpURLConnection 默认不带磁盘缓存（setUseCaches 是空操作），
     * 装上系统 HttpResponseCache 后，源站带 Cache-Control 的响应
     * 会落盘，冷启动二次进页时图片直接读磁盘，不再打网络。
     */
    private static void installHttpCache(Context context) {
        if (httpCacheInstalled) return;
        try {
            /* 提高同主机 keep-alive 连接池上限，首页几十张海报可并行复用连接 */
            System.setProperty("http.maxConnections", "24");
            File dir = new File(context.getCacheDir(), "webhome_http_cache");
            if (!dir.exists()) dir.mkdirs();
            if (android.net.http.HttpResponseCache.getInstalled() == null) {
                android.net.http.HttpResponseCache.install(dir, 64L * 1024 * 1024);
            }
            httpCacheInstalled = true;
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String homeContent(boolean z) {
        // 返回内容.
        // 单分类 (用户最常用): 直接列条目 (扁平, 不需要点分类)
        // 多分类: 列出分类 (用户点分类再看条目)
        if (extConfig == null) {
            return "{\"class\":[],\"list\":[]}";
        }
        java.util.List<String> cats = extConfig.getCategoryNames();
        if (cats.size() == 1) {
            // 扁平: 直接把第一个分类的所有条目放 list
            try {
                return categoryContent(cats.get(0), "1", false, null);
            } catch (Exception e) {
                return "{\"list\":[]}";
            }
        }
        // 多分类: 列分类
        // 关键: 用 type_id 字段 (不是 type). 蜂蜜壳 期望 type_id (Class.typeId)
        StringBuilder classes = new StringBuilder("[");
        int i = 0;
        for (String cat : cats) {
            if (i > 0) classes.append(",");
            // type_id 必须是字符串 (如 "1", "2", "3"), 不是数字
            classes.append("{\"type_id\":\"").append(i + 1)
                   .append("\",\"type_name\":\"").append(escapeJson(cat)).append("\"}");
            i++;
        }
        classes.append("]");
        return "{\"class\":" + classes + "}";
    }

    @Override
    public String homeVideoContent() {
        return "{\"list\":[]}";
    }

    /**
     * 返回指定分类下的条目列表.
     * 蜂蜜壳的 VodBrowse 调这个, 用户进了分类后看到条目列表.
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter, java.util.HashMap<String, String> extend) throws Exception {
        if (extConfig == null) return "{\"list\":[]}";
        // tid 是分类名. 蜂蜜壳的 VodBrowse 用 type_id 索引分类, 我们直接用 name.
        String catName = tid;
        try {
            int idx = Integer.parseInt(tid);
            java.util.List<String> names = extConfig.getCategoryNames();
            if (idx >= 1 && idx <= names.size()) catName = names.get(idx - 1);
        } catch (Throwable ignored) {}

        // 收集所有 (entry, url) 对
        java.util.List<String> entries = extConfig.getEntryNames(catName);
        int total = 0;
        java.util.List<String[]> pairs = new java.util.ArrayList<>();
        for (String entry : entries) {
            String url = extConfig.getUrl(catName, entry);
            if (url != null) {
                pairs.add(new String[]{entry, url});
                total++;
            }
        }

        // 分页. pg 默认 "1", 每页 limit 默认 30
        int currPage = 1;
        try { currPage = Math.max(1, Integer.parseInt(pg)); } catch (Throwable ignored) {}
        int limit = 30;
        if (extend != null && extend.get("limit") != null) {
            try { limit = Math.max(1, Integer.parseInt(extend.get("limit"))); } catch (Throwable ignored) {}
        }
        int pageCount = Math.max(1, (total + limit - 1) / limit);
        if (currPage > pageCount) currPage = pageCount;

        int start = (currPage - 1) * limit;
        int end = Math.min(start + limit, total);

        StringBuilder list = new StringBuilder("[");
        for (int i = start; i < end; i++) {
            String entry = pairs.get(i)[0];
            String url = pairs.get(i)[1];
            if (i > start) list.append(",");
            String id = extConfig.getId(catName, entry);
            // 关键: 用 action 字段 (不是 click!). 蜂蜜壳 type=3 站源, 点条目时:
            // 1) action 字段不为空 → 调 Spider.action(action), 不进 detail/player
            // 2) 我们 action 实现里打开 URL 到 WebView
            // 加 style.type="list" 让壳用列表样式显示
            list.append("{\"vod_id\":\"").append(escapeJson(id))
               .append("\",\"vod_name\":\"").append(escapeJson(entry))
               .append("\",\"vod_remarks\":\"").append(escapeJson(catName + "|" + url))
               .append("\",\"type_name\":\"").append(escapeJson(catName))
               .append("\",\"style\":{\"type\":\"list\"}")
               .append(",\"action\":\"").append(escapeJson(url))
               .append("\"}");
        }
        list.append("]");
        return "{\"page\":" + currPage
             + ",\"pagecount\":" + pageCount
             + ",\"limit\":" + limit
             + ",\"total\":" + total
             + ",\"list\":" + list + "}";
    }

    /**
     * 返回条目详情.
     * inline vod (html 点播放后存的) → 剧集占位名单, 不解直链.
     * 列表点进来的 wh_* → 仍走 ext 配置.
     */
    @Override
    public String detailContent(java.util.List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return "{\"list\":[]}";
        String id = ids.get(0);
        if (com.fongmi.web.WebHomeInlineVodStore.has(id)) {
            return com.fongmi.web.WebHomeInlineVodStore.detailJson(id);
        }
        if (extConfig == null) return "{\"list\":[]}";
        String[] pair = extConfig.getFromId(id);
        if (pair == null) return "{\"list\":[]}";
        String url = extConfig.getUrl(pair[0], pair[1]);
        if (url == null) return "{\"list\":[]}";
        String vod = "{\"vod_id\":\"" + escapeJson(id) + "\","
                  + "\"vod_name\":\"" + escapeJson(pair[1]) + "\","
                  + "\"vod_remarks\":\"" + escapeJson(pair[0] + "|" + url) + "\"}";
        return "{\"list\":[" + vod + "]}";
    }

    /**
     * 按集解析. id 为 whi:<vodId>:<index> 时调页面 resolver; 其它走 ext / 直链.
     */
    @Override
    public String playerContent(String flag, String id, java.util.List<String> vipFlags) throws Exception {
        if (id == null) return "{}";
        if (com.fongmi.web.WebHomeInlineVodStore.isToken(id)) {
            return playInlineToken(flag, id);
        }
        String url = null;
        String[] pair = extConfig != null ? extConfig.getFromId(id) : null;
        if (pair != null) {
            url = extConfig.getUrl(pair[0], pair[1]);
        } else if (id.startsWith("http") || id.startsWith("file:") || id.startsWith("./") || id.startsWith("/")
                || id.startsWith("data:")) {
            url = id;
        } else {
            url = id;
        }
        if (url == null) return "{}";
        return "{\"url\":\"" + escapeJson(url) + "\",\"parse\":0,\"flag\":\"" + escapeJson(flag == null ? "" : flag) + "\"}";
    }

    private String playInlineToken(String flag, String token) {
        String[] parts = com.fongmi.web.WebHomeInlineVodStore.parseToken(token);
        if (parts == null) return "{}";
        String vodId = parts[0];
        int index;
        try { index = Integer.parseInt(parts[1]); } catch (Exception e) { return "{}"; }

        String cached = com.fongmi.web.WebHomeInlineVodStore.getResolved(vodId, index);
        if (!TextUtils.isEmpty(cached)) {
            return emitPlay(cached, sniffFormat(cached, null), null, flag);
        }

        com.google.gson.JsonObject ep = com.fongmi.web.WebHomeInlineVodStore.getEpisode(vodId, index);
        if (ep == null) return "{\"url\":\"\",\"msg\":\"episode missing\"}";

        String hint = normalizeForPlayer(com.fongmi.web.WebHomeInlineVodStore.episodeMediaHint(ep));
        String page = normalizeForPlayer(com.fongmi.web.WebHomeInlineVodStore.episodePageUrl(ep));
        java.util.Map<String, String> headers = com.fongmi.web.WebHomeInlineVodStore.headersOf(ep);
        String format = com.fongmi.web.WebHomeInlineVodStore.getStr(ep, "format", "");

        boolean resolve = false;
        try {
            if (ep.has("resolve") && ep.get("resolve").isJsonPrimitive()) {
                resolve = ep.get("resolve").getAsBoolean();
            }
        } catch (Throwable ignored) {}

        // 已是直链 / data URI: 转本地 http 后直接播, 不必调 JS
        String hosted = hostMedia(hint);
        if (isDirectMedia(hosted)) {
            com.fongmi.web.WebHomeInlineVodStore.putResolved(vodId, index, hosted);
            return emitPlay(hosted, sniffFormat(hosted, format), headers, flag);
        }

        if (resolve) {
            String resolved = "";
            try {
                org.json.JSONObject epJson = new org.json.JSONObject(ep.toString());
                if (!epJson.has("name")) epJson.put("name", com.fongmi.web.WebHomeInlineVodStore.getName(ep, index));
                String raw = resolveInlineEpisode(epJson, 25000);
                resolved = stripPlayScheme(extractResolvedUrl(raw));
                String f2 = extractResolvedFormat(raw);
                if (!TextUtils.isEmpty(f2)) format = f2;
                java.util.Map<String, String> h2 = extractResolvedHeaders(raw);
                if (h2 != null && !h2.isEmpty()) headers = h2;
            } catch (Throwable t) {
                android.util.Log.w("WebHome", "inline resolve failed: " + t.getMessage());
            }
            hosted = hostMedia(resolved);
            if (isDirectMedia(hosted)) {
                com.fongmi.web.WebHomeInlineVodStore.putResolved(vodId, index, hosted);
                return emitPlay(hosted, sniffFormat(hosted, format), headers, flag);
            }
            if (!TextUtils.isEmpty(hosted) && isDirectMedia(hostMedia(hint))) {
                hosted = hostMedia(hint);
                com.fongmi.web.WebHomeInlineVodStore.putResolved(vodId, index, hosted);
                return emitPlay(hosted, sniffFormat(hosted, format), headers, flag);
            }
        }

        // 555 这类: 剧集 url 是播放页, 让壳 parse=1 嗅探. 忽略 video:// 头之后就是 http 网页.
        String sniff = !TextUtils.isEmpty(page) ? page : hint;
        sniff = stripPlayScheme(sniff);
        if (isWebpage(sniff)) {
            com.fongmi.web.WebHomeInlineVodStore.putResolved(vodId, index, sniff);
            return com.fongmi.web.WebHomeInlineVodStore.playResult(sniff, "", headers, flag, 1);
        }
        return "{\"url\":\"\",\"msg\":\"resolve failed\"}";
    }

    private static String emitPlay(String url, String format, java.util.Map<String, String> headers, String flag) {
        return com.fongmi.web.WebHomeInlineVodStore.playResult(url, format, headers, flag, 0);
    }

    static String stripPlayScheme(String url) {
        if (url == null) return "";
        String u = url.trim();
        while (true) {
            String lower = u.toLowerCase();
            if (lower.startsWith("video://")) { u = u.substring(8); continue; }
            if (lower.startsWith("tvbox://")) { u = u.substring(8); continue; }
            if (lower.startsWith("intent://") && lower.contains("#intent")) {
                int http = lower.indexOf("http");
                if (http > 0) u = u.substring(http);
                else break;
                continue;
            }
            break;
        }
        return u.trim();
    }

    static String hostMedia(String url) {
        String u = stripPlayScheme(url);
        if (TextUtils.isEmpty(u)) return "";
        try {
            String hosted = LocalMediaServer.get().hostIfNeeded(u);
            if (!TextUtils.isEmpty(hosted)) return hosted;
        } catch (Throwable ignored) {}
        return u;
    }

    /**
     * 进 playerContent / playUrl 出口前把播放地址 normalize:
     * 剥 video://, 展开 9978/webResource → 18080/proxy, data URI → 本地 .mpd.
     * 不动 fm.res() 给页面的资源.
     */
    public static String normalizeForPlayer(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String u = stripPlayScheme(url);
        if (TextUtils.isEmpty(u)) return "";
        try {
            String hosted = LocalMediaServer.get().hostIfNeeded(u);
            return TextUtils.isEmpty(hosted) ? u : hosted;
        } catch (Throwable ignored) {
            return u;
        }
    }

    static boolean isWebpage(String url) {
        if (TextUtils.isEmpty(url) || isDirectMedia(url)) return false;
        String u = url.toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("file:");
    }

    static boolean isDirectMedia(String url) {
        if (url == null || url.isEmpty()) return false;
        String u = stripPlayScheme(url).toLowerCase();
        if (u.startsWith("data:")) return true;
        if (u.contains("/mpd/") && u.contains(".mpd")) return true;
        if (u.contains(".m3u8") || u.contains(".mpd") || u.contains(".mp4") || u.contains(".flv") || u.contains(".m4a")) return true;
        if (u.contains("application/dash+xml") || u.contains("application/x-mpegurl")) return true;
        return false;
    }

    private static String sniffFormat(String url, String given) {
        if (!TextUtils.isEmpty(given)) return given;
        if (url == null) return "";
        String u = url.toLowerCase();
        if (u.contains(".m3u8") || u.contains("mpegurl")) return "application/x-mpegURL";
        if (u.contains(".mpd") || u.contains("dash+xml") || u.contains("/mpd/")) return "application/dash+xml";
        return "";
    }

    private static String extractResolvedUrl(String raw) {
        String s = unwrapJsString(raw);
        if (TextUtils.isEmpty(s) || "null".equals(s) || "{}".equals(s)) return "";
        if (s.startsWith("{")) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(s);
                String u = o.optString("url", "");
                if (TextUtils.isEmpty(u)) u = o.optString("mediaUrl", "");
                return u;
            } catch (Throwable ignored) { return ""; }
        }
        return s;
    }

    private static String extractResolvedFormat(String raw) {
        String s = unwrapJsString(raw);
        if (s != null && s.startsWith("{")) {
            try { return new org.json.JSONObject(s).optString("format", ""); } catch (Throwable ignored) {}
        }
        return "";
    }

    private static java.util.Map<String, String> extractResolvedHeaders(String raw) {
        String s = unwrapJsString(raw);
        if (s == null || !s.startsWith("{")) return null;
        try {
            org.json.JSONObject o = new org.json.JSONObject(s).optJSONObject("headers");
            if (o == null) return null;
            java.util.Map<String, String> m = new java.util.HashMap<>();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, o.optString(k));
            }
            return m;
        } catch (Throwable ignored) { return null; }
    }

    private static String unwrapJsString(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            try { return new org.json.JSONTokener(s).nextValue().toString(); } catch (Throwable ignored) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    /**
     * 处理条目点击 (来自 categoryContent 的 action 字段).
     * 蜂蜜壳 type=3 站源, 点条目时如果条目有 action 字段, 蜂蜜壳会调这个方法, **不进 detailContent/playerContent**.
     * 这里 action 就是条目 URL, 我们打开 URL 到 SDK 的 WebView Overlay (带 FM SDK 注入).
     */
    @Override
    public String action(String action) throws Exception {
        if (action == null || action.isEmpty()) return "{}";
        // 反射调 SDK 自己的 open() (有 WebView Overlay), 打开 URL 到带 FM SDK 的 WebView
        try {
            java.lang.reflect.Method m = WebHome.class.getDeclaredMethod("open", String.class, String.class, int.class);
            m.setAccessible(true);
            m.invoke(null, action, currentSiteKey(), 0);
            return "{}";
        } catch (Throwable t) {
            android.util.Log.w("WebHome", "action() failed: " + t.getMessage());
            return "{}";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }

    @Override
    public void destroy() {
        close();
    }

    private String runtimeSiteKey() {
        return this.siteKey == null ? "" : this.siteKey.trim();
    }

    public static String currentSiteKey() {
        String k = lastSiteKey == null ? "" : lastSiteKey.trim();
        if (!TextUtils.isEmpty(k)) return k;
        Overlay o = overlay;
        if (o != null && !TextUtils.isEmpty(o.sourceKey)) return o.sourceKey.trim();
        return "";
    }

    static void rememberSiteKey(String key) {
        if (!TextUtils.isEmpty(key)) lastSiteKey = key.trim();
    }

    private static void open(final String str, final String str2, final int i) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Activity activity = activity();
                if (!usable(activity)) {
                    if (i < MAX_ACTIVITY_RETRIES) {
                        MAIN.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                open(str, str2, i + 1);
                            }
                        }, 180L);
                    }
                    return;
                }
                remember(activity);
                String normalize = normalize(str);
                if (normalize.length() == 0) return;
                /* 提前预热 DNS/TLS：页面正式加载前先打一次握手，图片和页面都受益 */
                warmUp(normalize);
                if (overlay != null) {
                    overlay.destroyWeb();
                    overlay = null;
                }
                overlay = new Overlay(activity, normalize, str2);
                overlay.show();
            }
        });
    }

    private static void close() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (overlay != null) overlay.destroyWeb();
                overlay = null;
            }
        });
    }

    /** 进播放页时只藏 Overlay, 不毁 WebView, 留给 playerContent 调 JS resolver. */
    public static void hideOverlay() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (overlay != null) overlay.hideForPlayback();
            }
        });
    }

    /**
     * 调页面 window.__fmWebHomeInlineResolver(ep). 支持返回 Promise.
     * 结果是 url 字符串, 或 {"url":"...","format":"...","headers":{}} JSON.
     */
    public static String resolveInlineEpisode(org.json.JSONObject episode, long timeoutMs) {
        Overlay o = overlay;
        if (o == null || o.controller == null || episode == null) return null;
        String epJson = episode.toString();
        return o.controller.evaluateJavascriptSync(
                "window.__fmWebHomeInlineResolver && window.__fmWebHomeInlineResolver(" + epJson + ")",
                timeoutMs);
    }

    private static void installLifecycleTracker(Context context) {
        if (lifecycleInstalled || !(context instanceof Application)) return;
        synchronized (LOCK) {
            if (lifecycleInstalled) return;
            ((Application) context).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity a, Bundle b) {
                    remember(a);
                }

                @Override
                public void onActivityStarted(Activity a) {
                    remember(a);
                }

                @Override
                public void onActivityResumed(Activity a) {
                    remember(a);
                    restoreOverlayIfNeeded(a);
                }

                @Override
                public void onActivityPaused(Activity a) {
                }

                @Override
                public void onActivityStopped(Activity a) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity a, Bundle b) {
                }

                @Override
                public void onActivityDestroyed(Activity a) {
                    if (foreground.get() == a) foreground = new WeakReference<>(null);
                }
            });
            lifecycleInstalled = true;
        }
    }

    private static void remember(Activity a) {
        if (usable(a)) foreground = new WeakReference<>(a);
    }

    private static void restoreOverlayIfNeeded(Activity a) {
        Overlay o = overlay;
        if (o == null || a == null) return;
        String name = a.getClass().getName();
        if (name.contains("VideoActivity") || name.contains("LiveActivity")) return;
        try {
            if (!o.isShowing()) o.show();
        } catch (Throwable ignored) {}
    }

    private static boolean usable(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    private static Activity activity() {
        Activity a = foreground.get();
        if (usable(a)) return a;
        return findActivity();
    }

    /**
     * 公开方法: 供 PlayerLauncher 等需要 Activity 的模块用.
     */
    public static Activity getForegroundActivity() {
        Activity a = foreground.get();
        if (usable(a)) return a;
        return findActivity();
    }

    /**
     * 公开方法: 获取当前 Overlay 的 WebView. CF 验证弹窗 (HTML/CSS 注入式) 需要.
     */
    public static WebView getOverlayWebView() {
        Overlay o = overlay;
        return o == null ? null : o.web;
    }

    private static Activity findActivity() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Field f = at.getDeclaredField("mActivities");
            f.setAccessible(true);
            Object obj = f.get(thread);
            if (obj instanceof Map) {
                for (Object r : ((Map<?, ?>) obj).values()) {
                    if (r == null) continue;
                    Field pf = r.getClass().getDeclaredField("paused");
                    pf.setAccessible(true);
                    if (Boolean.TRUE.equals(pf.get(r))) continue;
                    Field af = r.getClass().getDeclaredField("activity");
                    af.setAccessible(true);
                    Object act = af.get(r);
                    if (act instanceof Activity && usable((Activity) act)) return (Activity) act;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String normalize(String str) {
        if (str == null) return "";
        String trim = str.trim();
        if (trim.startsWith("file://") || trim.startsWith("http://") || trim.startsWith("https://")) return trim;
        if (trim.startsWith("/")) return Uri.fromFile(new File(trim)).toString();
        return trim;
    }

    private static void warmUp(final String pageUrl) {
        if (!(pageUrl.startsWith("http://") || pageUrl.startsWith("https://"))) return;
        HTTP_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(pageUrl).openConnection();
                    conn.setRequestMethod("HEAD");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", defaultUA());
                    conn.getResponseCode();
                } catch (Throwable ignored) {
                } finally {
                    if (conn != null) {
                        try {
                            conn.disconnect();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        });
    }

    // ================= Native 异步网络请求 =================

    public static String doNativeReq(String urlStr, String optJson) {
        JSONObject res = new JSONObject();
        HttpURLConnection conn = null;
        try {
            JSONObject opt = TextUtils.isEmpty(optJson) ? new JSONObject() : new JSONObject(optJson);
            String method = opt.optString("method", "GET").toUpperCase();
            JSONObject headers = opt.optJSONObject("headers");
            String body = opt.optString("body", "");
            int timeout = opt.optInt("timeout", 20) * 1000;
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(true);
            conn.setDefaultUseCaches(true);
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
            boolean hasUA = false;
            boolean hasCookie = false;
            if (headers != null) {
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    String v = headers.optString(k, "");
                    conn.setRequestProperty(k, v);
                    if ("user-agent".equalsIgnoreCase(k)) hasUA = true;
                    if ("cookie".equalsIgnoreCase(k) && !TextUtils.isEmpty(v)) hasCookie = true;
                }
            }
            if (!hasCookie) {
                String cookie = getCachedCookie(urlStr);
                if (!TextUtils.isEmpty(cookie)) conn.setRequestProperty("Cookie", cookie);
            }
            if (!hasUA) {
                conn.setRequestProperty("User-Agent", defaultUA());
            }
            if ("POST".equals(method) || "PUT".equals(method)) {
                conn.setDoOutput(true);
                if (!TextUtils.isEmpty(body)) {
                    /* P0: write 后必须 flush + close,否则 chunked buffer 尾部可能发不出去 */
                    OutputStream os = conn.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                }
            }
            int code = conn.getResponseCode();
            res.put("status", code);
            res.put("ok", code >= 200 && code < 300);
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is != null) {
                String encoding = conn.getContentEncoding();
                if ("gzip".equalsIgnoreCase(encoding)) is = new GZIPInputStream(is);
                else if ("deflate".equalsIgnoreCase(encoding)) is = new InflaterInputStream(is);
                // 不要限制读取大小 - Invidious 搜索结果有 50-200KB, 限制 32KB 会截断 JSON 让列表加载失败
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
                is.close();
                res.put("body", new String(baos.toByteArray(), "UTF-8"));
            } else {
                res.put("body", "");
            }
        } catch (Throwable t) {
            try {
                res.put("ok", false);
                res.put("status", 0);
                res.put("error", t.getMessage());
            } catch (Throwable ignored) {
            }
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
        return res.toString();
    }

    /* ================= 域名直连探测与线路记忆 ================= */

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Throwable t) {
            return "";
        }
    }

    private static DomainRoute routeFor(String host) {
        DomainRoute r = DOMAIN_ROUTES.get(host);
        if (r == null) {
            r = new DomainRoute();
            DomainRoute old = DOMAIN_ROUTES.putIfAbsent(host, r);
            if (old != null) r = old;
        }
        return r;
    }

    /*
     * 同步短探测 + 全域名阻塞决策：首个图片触发探测（短超时），
     * 同域名其它请求在同一把锁上等结果，整批图片一起拿到线路决策；
     * 探测只读图片头部几 KB 验证魔数即断开，不下载整张图。
     */
    private static int decideRoute(final String host, final String sampleUrl) {
        DomainRoute r = routeFor(host);
        synchronized (r) {
            if (r.mode != ROUTE_UNKNOWN && System.currentTimeMillis() - r.checkedAt > ROUTE_TTL_MILLIS) {
                r.mode = ROUTE_UNKNOWN; /* 线路记忆过期，重测 */
            }
            if (r.mode != ROUTE_UNKNOWN) return r.mode;

            if (!r.probing) {
                r.probing = true;
                        final DomainRoute fr = r;
                        final String fHost = host;
                        final String fUrl = sampleUrl;
                        Runnable probeTask = new Runnable() {
                            @Override
                            public void run() {
                                int mode = ROUTE_PROXY;
                                HttpURLConnection conn = null;
                                try {
                                    conn = (HttpURLConnection) new URL(fUrl).openConnection();
                                    conn.setRequestMethod("GET");
                                    conn.setConnectTimeout(PROBE_CONNECT_TIMEOUT);
                                    conn.setReadTimeout(PROBE_READ_TIMEOUT);
                                    conn.setInstanceFollowRedirects(true);
                                    conn.setUseCaches(false);
                                    conn.setRequestProperty("Accept", "image/*,*/*;q=0.8");
                                    conn.setRequestProperty("Accept-Encoding", "identity");
                                    conn.setRequestProperty("User-Agent", defaultUA());
                                    int code = conn.getResponseCode();
                                    String type = conn.getContentType();
                                    boolean ok = code == 200 && type != null && type.toLowerCase().startsWith("image/");
                                    if (ok) {
                                        /*
                                         * 只读头部几 KB 验证魔数即断开，不下载整张图；
                                         * 判定走代理时首图由代理正常拉取（缓存兑底）。
                                         */
                                        InputStream is = conn.getInputStream();
                                        ok = is != null && looksLikeImage(readHead(is, PROBE_HEAD_BYTES));
                                        if (is != null) try { is.close(); } catch (Throwable ignored) {}
                                    }
                                    android.util.Log.d("WebHome", "probeDirect " + fHost + " -> " + (ok ? "DIRECT" : "PROXY") + " code=" + code);
                                    if (ok) mode = ROUTE_DIRECT;
                                } catch (Throwable t) {
                                    mode = ROUTE_PROXY;
                                } finally {
                                    if (conn != null) {
                                        try {
                                            conn.disconnect();
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                }
                                synchronized (fr) {
                                    fr.mode = mode;
                                    fr.checkedAt = System.currentTimeMillis();
                                    fr.probing = false;
                                    fr.notifyAll();
                                }
                            }
                        };
                        try {
                            HTTP_EXECUTOR.execute(probeTask);
                        } catch (Throwable t) {
                            /* 线程池拒绝等异常：立即判 PROXY，不阻塞 JS */
                            r.mode = ROUTE_PROXY;
                            r.checkedAt = System.currentTimeMillis();
                            r.probing = false;
                        }
            }

            /*
             * 只等有限时间：探测出了结果就用决策；
             * 超时先走代理兑底，本次请求不再干等。
             */
            long deadline = System.currentTimeMillis() + PROBE_MAX_WAIT_MILLIS;
            while (r.mode == ROUTE_UNKNOWN && System.currentTimeMillis() < deadline) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                try {
                    r.wait(remain);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return r.mode == ROUTE_UNKNOWN ? ROUTE_PROXY : r.mode;
        }
    }

    /* 运行中代理拉图成功且无需关键头 = 该域名可直连，就地升级线路 */
    private static void promoteDirect(String host) {
        if (TextUtils.isEmpty(host)) return;
        DomainRoute r = routeFor(host);
        synchronized (r) {
            if (r.mode == ROUTE_UNKNOWN) {
                r.mode = ROUTE_DIRECT;
                r.checkedAt = System.currentTimeMillis();
            }
        }
    }

    /* 只读探测流的前 limit 字节，读完即返回，配合 disconnect 丢弃剩余响应体 */
    private static byte[] readHead(InputStream in, int limit) {
        if (in == null) return new byte[0];
        try {
            byte[] head = new byte[limit];
            int off = 0, n;
            while (off < limit && (n = in.read(head, off, limit - off)) != -1) off += n;
            return off == limit ? head : Arrays.copyOf(head, off);
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    /*
     * 魔数校验：JPEG/PNG/GIF/WebP/BMP/AVIF(HEIC) 直接通过；
     * 未知魔数时只要不是 HTML/错误页也放行，兼顾识别
     * "返回 200 但内容是防盗链提示页" 的假图片。
     */
    private static boolean looksLikeImage(byte[] head) {
        if (head == null || head.length < 4) return false;
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) return true; /* JPEG */
        if ((head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return true; /* PNG */
        if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F') return true; /* GIF */
        if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head.length >= 12 && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') return true; /* WebP */
        if (head[0] == 'B' && head[1] == 'M') return true; /* BMP */
        if (head.length >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') return true; /* AVIF/HEIC */
        String s = new String(head, 0, Math.min(head.length, 64)).toLowerCase();
        return !(s.contains("<!doctype") || s.contains("<html") || s.contains("<?xml"));
    }

    /*
     * 借鉴壳 CustomWebView.checkHeader 的思路：把 SDK 传来的 Cookie
     * 同步进系统 CookieManager，这样带 Cookie 的图片域名也能走
     * 直连线路（WebView 加载图片时会自动携带同域 Cookie）。
     */
    private static void syncCookie(String url, String cookie) {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setCookie(url, cookie);
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasCriticalHeader(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return false;
        for (String k : headers.keySet()) {
            String lk = k.toLowerCase();
            if (lk.equals("referer") || lk.equals("user-agent") || lk.equals("authorization")) {
                String v = headers.get(k);
                if (!TextUtils.isEmpty(v)) return true;
            }
        }
        return false;
    }

    /*
     * Referer/UA/Authorization 无法附加在 <img> 直连请求上，仍必须走代理；
     * Cookie 已通过 syncCookie 落到 CookieManager，不再是关键头。
     */
    private static boolean hasCriticalHeader(JSONObject headers) {
        if (headers == null) return false;
        try {
            Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if ("referer".equalsIgnoreCase(k) || "user-agent".equalsIgnoreCase(k)
                        || "authorization".equalsIgnoreCase(k)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ================= fm.res 资源代理 =================

    private static class WebResourceData {
        int code = 200;
        String message = "OK";
        String contentType = "image/*";
        String contentRange = "";
        String contentLength = "";
        String etag = "";
        String cacheControl = "";
        String lastModified = "";
        String expires = "";
        String acceptRanges = "";
        InputStream stream;
    }

    public static String defaultUA() {
        return "Mozilla/5.0 (Linux; Android 14; ELI-AN00) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    }


    /**
     * 创建 CF 验证 Dialog (WebView). 用于在 doNativeReq 之后,
     * 弹一个全屏 WebView 让用户解决 Cloudflare 挑战.
     *
     * @param activity 用于显示 dialog
     * @param urlStr 要验证的 URL (CF 会自动跳转到 challenge 页面)
     * @param onDismiss dialog 关闭时回调
     */

    private static String getCachedCookie(String url) {
        try {
            URL u = new URL(url);
            String host = u.getHost();
            if (TextUtils.isEmpty(host)) return null;
            /* P1-c: TTL,过期回源 CookieManager */
            TimedValue tv = COOKIE_CACHE.get(host);
            if (tv != null) {
                if (!tv.isExpired(COOKIE_TTL_MS)) return (String) tv.value;
                COOKIE_CACHE.remove(host, tv);
            }
            String cookie = CookieManager.getInstance().getCookie(url);
            if (!TextUtils.isEmpty(cookie)) COOKIE_CACHE.put(host, new TimedValue(cookie));
            return cookie;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> getCachedHeaders(String headersJson) {
        if (TextUtils.isEmpty(headersJson)) return Collections.emptyMap();
        TimedValue tv = HEADER_CACHE.get(headersJson);
        if (tv != null && !tv.isExpired(HEADER_TTL_MS)) {
            Object v = tv.value;
            return v instanceof Map ? (Map<String, String>) v : Collections.emptyMap();
        } else if (tv != null) {
            HEADER_CACHE.remove(headersJson, tv);
        }
        try {
            JSONObject obj = new JSONObject(headersJson);
            Map<String, String> map = new HashMap<>();
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, obj.optString(key, ""));
            }
            Map<String, String> result = Collections.unmodifiableMap(map);
            HEADER_CACHE.put(headersJson, new TimedValue(result));
            return result;
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
    }

    private static String safeHeader(HttpURLConnection conn, String name) {
        try {
            String value = conn.getHeaderField(name);
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static WebResourceData fetchResourceData(Uri uri, String extraRange) {
        if (uri == null) return null;
        // 个别壳 (Android 16/SDK 36) 上 Uri.parse() 对某些字符串会返回非 hierarchical Uri
        // (如 bare 路径), getQueryParameter 会抛 UnsupportedOperationException.
        // 兼容处理: getQueryParameter 抛异常时返回 null (走 fallback 不缓存)
        String targetUrl, headersJson;
        try {
            targetUrl = uri.getQueryParameter("url");
            headersJson = uri.getQueryParameter("headers");
        } catch (Throwable t) {
            android.util.Log.w("WebHome", "fetchResourceData: getQueryParameter failed: " + t.getMessage());
            return null;
        }
        if (TextUtils.isEmpty(targetUrl)) return null;
        final long start = System.currentTimeMillis();

        /*
         * Range 请求（视频拖动）不缓存；图片类完整 GET 响应走缓存：
         * 内存 LRU 命中 -> 直接秒回；过期 -> 先秒回旧图再后台刷新。
         */
        boolean cacheable = TextUtils.isEmpty(extraRange);
        String memKey = cacheKey(targetUrl, headersJson);

        if (cacheable) {
            CachedResource hit = MEMORY_CACHE.get(memKey);
            if (hit != null) {
                if (hit.isFresh()) {
                    android.util.Log.d("WebHome", "fm.res CACHE " + (System.currentTimeMillis() - start) + "ms " + targetUrl);
                    return fromCache(hit);
                }
                refreshAsync(memKey, targetUrl, headersJson, hit);
                android.util.Log.d("WebHome", "fm.res STALE " + (System.currentTimeMillis() - start) + "ms " + targetUrl);
                return fromCache(hit);
            }
        }

        // data: URI 特殊处理 - data URI 不能用 new URL() 解析, 也不走 HTTP
        if (targetUrl.startsWith("data:")) {
            return fetchDataUri(targetUrl, extraRange);
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(targetUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(true);
            conn.setDefaultUseCaches(true);
            /* keep-alive 已由 HttpURLConnection 默认管理,显式设置既冗余又挤占头字节 */
            conn.setRequestProperty("Accept-Encoding", "identity");

            Map<String, String> headers = getCachedHeaders(headersJson);
            boolean hasUA = false;
            boolean hasCookie = false;

            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String hKey = entry.getKey();
                String value = entry.getValue();
                if (TextUtils.isEmpty(hKey)) continue;
                conn.setRequestProperty(hKey, value == null ? "" : value);
                if ("user-agent".equalsIgnoreCase(hKey)) hasUA = true;
                if ("cookie".equalsIgnoreCase(hKey) && !TextUtils.isEmpty(value)) hasCookie = true;
            }

            if (!hasCookie) {
                String cookie = getCachedCookie(targetUrl);
                if (!TextUtils.isEmpty(cookie)) conn.setRequestProperty("Cookie", cookie);
            }

            if (!hasUA) conn.setRequestProperty("User-Agent", defaultUA());

            if (!TextUtils.isEmpty(extraRange)) conn.setRequestProperty("Range", extraRange);

            conn.connect();

            int responseCode = conn.getResponseCode();
            String responseMessage = conn.getResponseMessage();
            InputStream is = responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();

            if (is == null) {
                conn.disconnect();
                return null;
            }

            WebResourceData data = new WebResourceData();
            data.code = responseCode;
            data.message = TextUtils.isEmpty(responseMessage) ? "OK" : responseMessage;
            data.contentType = TextUtils.isEmpty(conn.getContentType()) ? "image/*" : conn.getContentType();
            data.contentRange = safeHeader(conn, "Content-Range");
            data.contentLength = safeHeader(conn, "Content-Length");
            data.etag = safeHeader(conn, "ETag");
            data.cacheControl = safeHeader(conn, "Cache-Control");
            data.lastModified = safeHeader(conn, "Last-Modified");
            data.expires = safeHeader(conn, "Expires");
            data.acceptRanges = safeHeader(conn, "Accept-Ranges");

            /*
             * 完整 200 的图片响应：边转发边收集，WebView 读完流后
             * 异步入内存缓存，不给本次响应增加任何延迟；
             * 同时代理拉图成功本身就证明该域名可裸访，就地升级直连。
             */
            if (cacheable && responseCode == 200 && data.contentType.startsWith("image/")) {
                /* P2-f: 复用已解析的 headers Map 判断,避免重复 new JSONObject */
                if (!hasCriticalHeader(headers)) promoteDirect(hostOf(targetUrl));

                final String fUrl = targetUrl;
                final String fHeaders = headersJson;
                final String fType = data.contentType;
                final String fEtag = data.etag;
                final String fLast = data.lastModified;
                final String fCc = data.cacheControl;
                data.stream = new TeeInputStream(is) {
                    @Override
                    protected void onComplete(byte[] bytes) {
                        putCache(cacheKey(fUrl, fHeaders),
                                new CachedResource(bytes, fType, fEtag, fLast, parseMaxAge(fCc)));
                    }
                };
            } else {
                data.stream = is;
            }

            android.util.Log.d("WebHome", "fm.res " + responseCode + " " + (System.currentTimeMillis() - start) + "ms " + targetUrl);
            return data;
        } catch (Throwable t) {
            android.util.Log.e("WebHome", "fm.res error: " + targetUrl, t);
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }

    /* ================= 图片内存缓存 ================= */

    private static final class CachedResource {
        final byte[] bytes;
        final String contentType;
        final String etag;
        final String lastModified;
        final long fetchedAt;
        final long freshMillis;

        CachedResource(byte[] bytes, String contentType, String etag, String lastModified, long freshMillis) {
            this.bytes = bytes;
            this.contentType = contentType == null || contentType.length() == 0 ? "image/*" : contentType;
            this.etag = etag == null ? "" : etag;
            this.lastModified = lastModified == null ? "" : lastModified;
            this.fetchedAt = System.currentTimeMillis();
            this.freshMillis = freshMillis;
        }

        boolean isFresh() {
            return freshMillis <= 0 ? false : System.currentTimeMillis() - fetchedAt < freshMillis;
        }
    }

    private static void putCache(String key, CachedResource r) {
        if (r != null && r.bytes.length > 0 && r.bytes.length <= MAX_CACHE_ITEM_BYTES) MEMORY_CACHE.put(key, r);
    }

    private static String cacheKey(String url, String headersJson) {
        return url + "|" + (headersJson == null ? 0 : headersJson.hashCode());
    }

    private static long parseMaxAge(String cacheControl) {
        if (TextUtils.isEmpty(cacheControl)) return DEFAULT_FRESH_MILLIS;
        String cc = cacheControl.toLowerCase();
        if (cc.contains("no-store") || cc.contains("no-cache")) return 0;
        int idx = cc.indexOf("max-age=");
        if (idx >= 0) {
            try {
                long v = Long.parseLong(cacheControl.substring(idx + 8).split("[,; ]")[0].trim()) * 1000L;
                if (v > 0) return v;
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_FRESH_MILLIS;
    }

    private static WebResourceData fromCache(CachedResource hit) {
        WebResourceData data = new WebResourceData();
        data.code = 200;
        data.message = "OK";
        data.contentType = hit.contentType;
        data.contentLength = String.valueOf(hit.bytes.length);
        data.etag = hit.etag;
        data.lastModified = hit.lastModified;
        /* 同步给 Chromium 一个短 max-age，几秒内重绘连拦截器都不进 */
        data.cacheControl = "max-age=300";
        data.acceptRanges = "none";
        data.stream = new ByteArrayInputStream(hit.bytes);
        return data;
    }

    /**
     * 解析 data: URI, 直接返回内容 (不走 HTTP).
     * 油管/bili 的 DASH MPD 用 data URI 推, new URL() 解析不了, HTTP 412 也可能崩.
     */
    private static WebResourceData fetchDataUri(String dataUri, String extraRange) {
        WebResourceData data = new WebResourceData();
        try {
            // data:application/dash+xml;base64,XXXXX
            // data:application/dash+xml,XXXXX (urlencoded)
            int colonIdx = dataUri.indexOf(':');
            int commaIdx = dataUri.indexOf(',', colonIdx);
            if (colonIdx < 0 || commaIdx < 0) {
                android.util.Log.w("WebHome", "fetchDataUri: invalid format");
                WebResourceData err = new WebResourceData();
                err.code = 400;
                err.message = "Bad data URI";
                err.contentType = "text/plain";
                err.stream = new ByteArrayInputStream(err.message.getBytes());
                return err;
            }
            String meta = dataUri.substring(colonIdx + 1, commaIdx);
            String content = dataUri.substring(commaIdx + 1);
            byte[] bytes;
            if (meta.contains(";base64")) {
                bytes = android.util.Base64.decode(content, android.util.Base64.DEFAULT);
            } else {
                bytes = java.net.URLDecoder.decode(content, "UTF-8").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            String contentType = "text/plain";
            for (String m : meta.split(";")) {
                if (m.startsWith("application/") || m.startsWith("text/") || m.startsWith("image/")) {
                    contentType = m.trim();
                }
            }
            data.code = 200;
            data.message = "OK";
            data.contentType = contentType;
            data.contentLength = String.valueOf(bytes.length);
            data.cacheControl = "max-age=300";
            data.acceptRanges = "none";
            data.stream = new ByteArrayInputStream(bytes);
            android.util.Log.d("WebHome", "fetchDataUri: " + dataUri.substring(0, Math.min(60, dataUri.length())) + "... size=" + bytes.length);
            return data;
        } catch (Throwable t) {
            android.util.Log.e("WebHome", "fetchDataUri failed: " + t.getMessage());
            WebResourceData err = new WebResourceData();
            err.code = 500;
            err.message = "Data URI parse error";
            err.contentType = "text/plain";
            err.stream = new ByteArrayInputStream(err.message.getBytes());
            return err;
        }
    }

    /*
     * 边转发边收集字节的流包装器：WebView 边读边拿数据（零额外延迟），
     * 读完后把完整字节交给 onComplete 异步入内存缓存。
     */
    private static abstract class TeeInputStream extends InputStream {
        private final InputStream in;
        private final ByteArrayOutputStream tee = new ByteArrayOutputStream();
        private boolean completed;

        TeeInputStream(InputStream in) {
            this.in = in;
        }

        protected abstract void onComplete(byte[] bytes);

        private void complete() {
            if (completed) return;
            completed = true;
            final byte[] bytes = tee.toByteArray();
            if (bytes.length > 0 && bytes.length <= MAX_CACHE_ITEM_BYTES) {
                HTTP_EXECUTOR.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            onComplete(bytes);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        }

        @Override
        public int read() throws java.io.IOException {
            int b = in.read();
            if (b == -1) complete();
            else tee.write(b);
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws java.io.IOException {
            int n = in.read(buf, off, len);
            if (n == -1) complete();
            else if (n > 0) tee.write(buf, off, n);
            return n;
        }

        @Override
        public int available() throws java.io.IOException {
            return in.available();
        }

        @Override
        public void close() throws java.io.IOException {
            try {
                complete();
            } finally {
                in.close();
            }
        }
    }

    /*
     * stale-while-revalidate：旧图已先返回给用户，这里后台用
     * If-None-Match / If-Modified-Since 条件请求刷新，命中 304 续期不耗流量。
     */
    private static void refreshAsync(final String key, final String targetUrl, final String headersJson, final CachedResource stale) {
        if (!REFRESHING.add(key)) return;
        final Runnable task = new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(targetUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(15000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setUseCaches(false);
                    /* keep-alive 已由 HttpURLConnection 自动管理,无需显式设置 */
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    if (!TextUtils.isEmpty(stale.etag)) conn.setRequestProperty("If-None-Match", stale.etag);
                    if (!TextUtils.isEmpty(stale.lastModified)) conn.setRequestProperty("If-Modified-Since", stale.lastModified);
                    boolean hasUA = false;
                    for (Map.Entry<String, String> e : getCachedHeaders(headersJson).entrySet()) {
                        if (TextUtils.isEmpty(e.getKey())) continue;
                        conn.setRequestProperty(e.getKey(), e.getValue() == null ? "" : e.getValue());
                        if ("user-agent".equalsIgnoreCase(e.getKey())) hasUA = true;
                    }
                    if (!hasUA) conn.setRequestProperty("User-Agent", defaultUA());
                    String cookie = getCachedCookie(targetUrl);
                    if (!TextUtils.isEmpty(cookie)) conn.setRequestProperty("Cookie", cookie);
                    int code = conn.getResponseCode();
                    if (code == 304) {
                        putCache(key, new CachedResource(stale.bytes, stale.contentType, stale.etag, stale.lastModified,
                                parseMaxAge(safeHeader(conn, "Cache-Control"))));
                    } else if (code == 200) {
                        InputStream is = conn.getInputStream();
                        if (is != null) {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
                            is.close();
                            byte[] bytes = baos.toByteArray();
                            putCache(key, new CachedResource(bytes, conn.getContentType(), safeHeader(conn, "ETag"),
                                    safeHeader(conn, "Last-Modified"), parseMaxAge(safeHeader(conn, "Cache-Control"))));
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    REFRESHING.remove(key);
                    if (conn != null) {
                        try {
                            conn.disconnect();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        };
        /* P2-d: 提交本身失败时回收 key,防止永久泄漏 */
        try {
            HTTP_EXECUTOR.execute(task);
        } catch (Throwable t) {
            REFRESHING.remove(key);
            android.util.Log.e("WebHome", "refreshAsync submit failed", t);
        }
    }

    /*
     * 资源 URL 统一出口：直连/代理线路分流 + webResource 代理 URL 拼装。
     * public static 供 FmBridge.resourceUrl 复用，保证 SDK 侧任何
     * JS 路径生成的图片地址都走同一套路由决策，不再有绕过分流的漏网路径。
     */
    public static String resolveResourceUrl(String url, String options) {
        try {
            JSONObject opt = TextUtils.isEmpty(options) ? new JSONObject() : new JSONObject(options);
            JSONObject headers = opt.optJSONObject("headers");

            /* SDK 带了 Cookie 就同步进 CookieManager，为直连线路铺路 */
            if (headers != null && !TextUtils.isEmpty(url)) {
                String ck = headers.optString("cookie", headers.optString("Cookie", ""));
                if (!TextUtils.isEmpty(ck)) syncCookie(url, ck);
            }

            /*
             * 线路分流（先探测、后决策、全域名记忆）：
             * 带 Referer/UA 等关键头的域名只能走代理（防盗链）；
             * 其余域名首次同步探测，DIRECT 则后续整屏图片
             * 直接返回原图 URL，由 WebView 原生加载（最快）。
             */
            if (!TextUtils.isEmpty(url) && (url.startsWith("http://") || url.startsWith("https://"))
                    && (headers == null || !hasCriticalHeader(headers))) {
                String host = hostOf(url);
                if (!TextUtils.isEmpty(host)) {
                    if (decideRoute(host, url) == ROUTE_DIRECT) return url;
                }
            }

            String encodedUrl = URLEncoder.encode(url, "UTF-8");
            String encodedHeaders = "";
            if (headers != null) encodedHeaders = "&headers=" + URLEncoder.encode(headers.toString(), "UTF-8");
            return "http://127.0.0.1:9978/webResource?url=" + encodedUrl + encodedHeaders;
        } catch (Throwable t) {
            return url;
        }
    }

    // ================= Native Bridge =================

    public static class NativeBridge {
        @JavascriptInterface
        public void asyncReq(final String reqId, final String url, final String options) {
            HTTP_EXECUTOR.execute(new Runnable() {
                @Override
                public void run() {
                    // 1. 先做一次请求
                    String resultJson = doNativeReq(url, options);

                    // CF 验证功能已被用户放弃, 这里直接返回原响应
                    // (如果需要 CF 验证, 请用户提供能 work 的实现, 不要再让我写)

                    final String finalResultJson = resultJson;
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            if (overlay != null && overlay.web != null) {
                                String js = "if(window.fm&&window.fm._onReqResult){window.fm._onReqResult(" + JSONObject.quote(reqId) + "," + JSONObject.quote(finalResultJson) + ");}";
                                overlay.web.evaluateJavascript(js, null);
                            }
                        }
                    });
                }
            });
        }

        @JavascriptInterface
        public void syncResult(String token, String value) {
            FmController.complete(token, value);
        }

        @JavascriptInterface
        public String res(String url, String options) {
            return resolveResourceUrl(url, options);
        }

        @JavascriptInterface
        public String mediaPut(String body, String type) {
            try { return LocalMediaServer.get().put(body, type); } catch (Throwable t) { return ""; }
        }

        @JavascriptInterface
        public String mediaProxy(String url) {
            try { return LocalMediaServer.get().proxyUrl(url); } catch (Throwable t) { return url; }
        }

        @JavascriptInterface
        public String mediaOrigin() {
            try { return LocalMediaServer.get().origin(); } catch (Throwable t) { return ""; }
        }

        /*
         * JS 错误回退：直连图挂了(防盗链/网络波动)时由页面 img error
         * 事件调到这里，把该域名记回代理线路，并当场返回代理 URL 让 JS 换 src。
         */
        @JavascriptInterface
        public String directFailed(String host, String url) {
            try {
                if (TextUtils.isEmpty(host)) host = hostOf(url);
                if (!TextUtils.isEmpty(host)) {
                    DomainRoute r = routeFor(host);
                    synchronized (r) {
                        r.mode = ROUTE_PROXY;
                        r.checkedAt = System.currentTimeMillis();
                    }
                }
                if (TextUtils.isEmpty(url)) return "";
                return "http://127.0.0.1:9978/webResource?url=" + URLEncoder.encode(url, "UTF-8");
            } catch (Throwable t) {
                return url == null ? "" : url;
            }
        }
    }

    // ================= Overlay =================

    private static final class Overlay extends Dialog {
        private final String source;
        private final String sourceKey;
        private WebView web;
        private FmController controller;

        Overlay(Activity activity, String source, String sourceKey) {
            super(activity, 0x0103000a);
            this.source = source;
            this.sourceKey = sourceKey == null ? "" : sourceKey;
            rememberSiteKey(this.sourceKey);
            try {
                if (globalHandler instanceof DefaultFmActionHandler) {
                    ((DefaultFmActionHandler) globalHandler).setSiteKey(currentSiteKey());
                }
            } catch (Throwable ignored) {}
        }

        @Override
        protected void onCreate(Bundle bundle) {
            super.onCreate(bundle);
            requestWindowFeature(1);
            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(0xFF000000);
            web = new WebView(getContext());
            root.addView(web, new FrameLayout.LayoutParams(-1, -1));
            setContentView(root);
            Window window = getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(0xFF000000));
                window.setLayout(-1, -1);
                window.addFlags(512);
                hideSystemBars(window);
            }
            setupWebView(web);
            load(web, source);
            setOnKeyListener(new DialogInterface.OnKeyListener() {
                @Override
                public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                        if (handleOverlayBack()) return true;
                        return true;
                    }
                    return false;
                }
            });
        }

        @Override
        public void onBackPressed() {
            if (!handleOverlayBack()) super.onBackPressed();
        }

        /**
         * SPA (bili/vidhub) 没有 WebView 历史. 先问页面 __fmConsumeBack (异步),
         * 再 web.goBack, 都没有才关 Overlay. 不能在主线程同步等 JS, 否则会死锁.
         */
        boolean handleOverlayBack() {
            if (web == null) {
                dismiss();
                return true;
            }
            try {
                web.evaluateJavascript(
                        "(function(){try{" +
                        "if(typeof window.__fmConsumeBack==='function'){return !!window.__fmConsumeBack();}" +
                        "if(window.history&&history.state&&(history.state.sub||history.state.sheet)){history.back();return true;}" +
                        "var h=location.hash||'';if(h&&h!=='#'&&h!=='#/'){history.back();return true;}" +
                        "return false;" +
                        "}catch(e){return false;}})()",
                        new android.webkit.ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String value) {
                                String s = value == null ? "" : value.trim();
                                boolean consumed = "true".equalsIgnoreCase(s) || "\"true\"".equalsIgnoreCase(s);
                                if (consumed) return;
                                if (web != null && web.canGoBack()) {
                                    web.goBack();
                                    return;
                                }
                                dismiss();
                            }
                        });
                return true;
            } catch (Throwable t) {
                if (web.canGoBack()) {
                    web.goBack();
                    return true;
                }
                dismiss();
                return true;
            }
        }

        @Override
        public void dismiss() {
            destroyWeb();
        }

        void hideForPlayback() {
            try {
                CookieManager.getInstance().flush();
            } catch (Throwable ignored) {
            }
            try { hide(); } catch (Throwable ignored) {}
        }

        void destroyWeb() {
            if (web != null) {
                try {
                    web.stopLoading();
                    web.removeJavascriptInterface("fongmiBridge");
                    web.removeJavascriptInterface("_nativeBridge");
                    web.loadUrl("about:blank");
                    web.clearHistory();
                    web.removeAllViews();
                    web.destroy();
                } catch (Throwable ignored) {
                }
                web = null;
            }
            controller = null;
            try { super.dismiss(); } catch (Throwable ignored) {}
            if (WebHome.overlay == this) WebHome.overlay = null;
        }

        @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
        private void setupWebView(WebView v) {
            v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            if (Build.VERSION.SDK_INT >= 26) v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);

            WebSettings s = v.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            s.setSupportZoom(false);
            s.setBuiltInZoomControls(false);
            s.setDisplayZoomControls(false);
            s.setTextZoom(100);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            s.setAllowFileAccess(true);
            s.setAllowContentAccess(true);
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);

            if (Build.VERSION.SDK_INT >= 23) s.setOffscreenPreRaster(true);

            v.setBackgroundColor(0xFF000000);
            v.setOverScrollMode(View.OVER_SCROLL_NEVER);
            v.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
            v.setFocusable(true);
            v.setFocusableInTouchMode(true);
            v.requestFocus();

            try {
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                cm.setAcceptThirdPartyCookies(v, true);
            } catch (Throwable ignored) {
            }

            FmActionHandler h = globalHandler;
            if (h == null) {
                useDefaultHandler();
                h = globalHandler;
            }
            if (h instanceof DefaultFmActionHandler) {
                ((DefaultFmActionHandler) h).setSiteKey(currentSiteKey());
            }
            v.addJavascriptInterface(new FmBridge(v, h), "fongmiBridge");
            v.addJavascriptInterface(new NativeBridge(), "_nativeBridge");
            Activity act = activity();
            controller = new FmController(act, v, h);
            if (h instanceof DefaultFmActionHandler) {
                ((DefaultFmActionHandler) h).setController(controller);
            }
            v.setWebChromeClient(new WebChromeClient());

            v.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return handleUrl(view, url);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                    if (req == null || req.getUrl() == null) return true;
                    return handleUrl(view, req.getUrl().toString());
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                    if (url != null && isOurProxy(url)) {
                        try {
                            return handleWebResourceResponse(Uri.parse(url), null);
                        } catch (Throwable t) {
                            // 个别壳 (Android 16/SDK 36) 上 Uri.parse 对非 hierarchical 字符串会抛
                            return super.shouldInterceptRequest(view, url);
                        }
                    }
                    return super.shouldInterceptRequest(view, url);
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                    if (req != null && req.getUrl() != null) {
                        String urlStr = req.getUrl().toString();
                        if (isOurProxy(urlStr)) {
                            String range = null;
                            if (req.getRequestHeaders() != null) {
                                range = req.getRequestHeaders().get("Range");
                                if (range == null) range = req.getRequestHeaders().get("range");
                            }
                            try {
                                return handleWebResourceResponse(req.getUrl(), range);
                            } catch (Throwable t) {
                                // 个别壳 (Android 16/SDK 36) 上非 hierarchical Uri 抛异常
                                return super.shouldInterceptRequest(view, req);
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, req);
                }

                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    super.onPageStarted(view, url, favicon);
                    /*
                     * 注入移至 onPageFinished 单次执行，避免 onPageStarted 中预注入又被 onPageFinished
                     * 注入一遍,既省一次 FmSdk.get 读取与 JS 求值,也避免钩子被注册两次
                     */
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    try {
                        CookieManager.getInstance().flush();
                    } catch (Throwable ignored) {
                    }
                    injectSdk(view);
                }
            });
        }

        private WebResourceResponse handleWebResourceResponse(Uri uri, String range) {
            WebResourceData data = fetchResourceData(uri, range);
            /*
             * 借鉴壳 CustomWebView 的 empty 模式：失败时绝不能 return null，
             * 否则 WebView 会真去请求 127.0.0.1:9978 等一个 404，白耗一个往返。
             */
            if (data == null) return emptyResponse();

            String mimeType = "application/octet-stream";
            String encoding = null;

            if (!TextUtils.isEmpty(data.contentType)) {
                String[] parts = data.contentType.split(";");
                mimeType = parts[0].trim();
                for (int i = 1; i < parts.length; i++) {
                    String p = parts[i].trim();
                    if (p.toLowerCase().startsWith("charset=")) encoding = p.substring(8).trim();
                }
            }

            if (Build.VERSION.SDK_INT >= 21) {
                Map<String, String> respHeaders = new HashMap<>();
                respHeaders.put("Access-Control-Allow-Origin", "*");
                respHeaders.put("Access-Control-Allow-Credentials", "true");
                respHeaders.put("Access-Control-Allow-Headers", "*");

                if (!TextUtils.isEmpty(data.contentRange)) respHeaders.put("Content-Range", data.contentRange);
                if (!TextUtils.isEmpty(data.contentLength)) respHeaders.put("Content-Length", data.contentLength);
                if (!TextUtils.isEmpty(data.etag)) respHeaders.put("ETag", data.etag);
                if (!TextUtils.isEmpty(data.cacheControl)) respHeaders.put("Cache-Control", data.cacheControl);
                if (!TextUtils.isEmpty(data.lastModified)) respHeaders.put("Last-Modified", data.lastModified);
                if (!TextUtils.isEmpty(data.expires)) respHeaders.put("Expires", data.expires);
                if (!TextUtils.isEmpty(data.acceptRanges)) respHeaders.put("Accept-Ranges", data.acceptRanges);

                /*
                 * 源站没给缓存头时，为 200 图片补一个默认 max-age：
                 * 让 WebView(Chromium) 自己缓存代理响应，二次渲染
                 * 不再进拦截器、不再发起任何网络请求，这是消除
                 * "框架秒出、图片慢半拍" 最关键的一层。
                 */
                if (data.code == 200 && TextUtils.isEmpty(data.cacheControl) && mimeType.startsWith("image/")) {
                    respHeaders.put("Cache-Control", "max-age=86400");
                }

                return new WebResourceResponse(mimeType, encoding, data.code, data.message, respHeaders, data.stream);
            }

            return new WebResourceResponse(mimeType, encoding, data.stream);
        }

        /*
         * 精确识别自家 9978 代理路径：原 .contains("/webResource") 全串扫描既慢且有可能误命中；
         * 改用 host+path 前缀判断,避免误拦其它站恰好含该段的资源
         */
        private static boolean isOurProxy(String url) {
            return url.startsWith("http://127.0.0.1:9978/webResource") || url.startsWith("https://127.0.0.1:9978/webResource");
        }

        /*
         * 立即返回的 404 空响应：拦截器内快速终结失败请求，
         * 避免 WebView 落到真实 9978 服务器上等响应。
         */
        private WebResourceResponse emptyResponse() {
            try {
                if (Build.VERSION.SDK_INT >= 21) {
                    Map<String, String> h = new HashMap<>();
                    h.put("Access-Control-Allow-Origin", "*");
                    h.put("Cache-Control", "no-store");
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", h, new ByteArrayInputStream(new byte[0]));
                }
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            } catch (Throwable t) {
                return null;
            }
        }

        private void injectSdk(WebView v) {
            try {
                String js = FmSdk.get("normal", false);
                String reqPolyfill = "if(window.fm&&!window.fm._patched){" +
                        "window.fm._patched=true;" +
                        "window.fm._reqCallbacks={};" +
                        "window.fm._reqSeq=0;" +
                        "window.fm.req=function(u,o){return new Promise(function(resolve){" +
                        "var id='req_'+(++window.fm._reqSeq)+'_'+Date.now();" +
                        "window.fm._reqCallbacks[id]=function(res){" +
                        "if(o&&o.responseType==='json'&&typeof res.body==='string'){try{res.body=JSON.parse(res.body);}catch(e){}}" +
                        "resolve(res);};" +
                        "try{_nativeBridge.asyncReq(id,u,JSON.stringify(o||{}));}" +
                        "catch(e){delete window.fm._reqCallbacks[id];resolve({ok:false,status:0,error:e.message});}" +
                        "});};" +
                        "window.fm._onReqResult=function(id,resJsonStr){" +
                        "var cb=window.fm._reqCallbacks[id];" +
                        "if(cb){delete window.fm._reqCallbacks[id];try{cb(JSON.parse(resJsonStr));}catch(e){cb({ok:false,status:0,error:e.message});}}" +
                        "};" +
                        "window.fm.res=function(u,o){try{return _nativeBridge.res(u,JSON.stringify(o||{}));}catch(e){return u;}};" +
                        "window.fm.media=window.fm.media||{};" +
                        "window.fm.media.put=function(b,t){try{return _nativeBridge.mediaPut(b,t||'application/dash+xml');}catch(e){return '';}};" +
                        "window.fm.media.proxy=function(u){try{return _nativeBridge.mediaProxy(u);}catch(e){return u;}};" +
                        "window.fm.media.origin=function(){try{return _nativeBridge.mediaOrigin();}catch(e){return '';}};" +
                        "window.fm.put=window.fm.media.put;" +
                        "window.fm.proxy=window.fm.media.proxy;" +
                        "}";

                String routeHook = "if(!window.__whRouteHooked){window.__whRouteHooked=true;" +
                        "document.addEventListener('error',function(ev){" +
                        "var el=ev.target;if(!el||el.tagName!=='IMG')return;" +
                        "var src=el.src||'';" +
                        "if(!src||src.indexOf('/webResource')!==-1||src.indexOf('data:')===0)return;" +
                        "if(src.indexOf('http://')!==0&&src.indexOf('https://')!==0)return;" +
                        "var m=src.match(/^(?:https?:)?\\/\\/([^\\/#?]+)/);" +
                        "if(!m)return;" +
                        "try{var proxy=_nativeBridge.directFailed(m[1],src);if(proxy){el.src=proxy;}}catch(e){}" +
                        "},true);}";

                v.evaluateJavascript(js + "\n" + reqPolyfill + "\n" + routeHook, null);
            } catch (Throwable t) {
                android.util.Log.e("WebHome", "injectSdk failed", t);
            }
        }

        private boolean handleUrl(WebView view, String url) {
            if (url == null || url.length() == 0) return true;
            if ("webhome://close".equalsIgnoreCase(url)) {
                dismiss();
                return true;
            }
            if (url.startsWith("file://") || url.startsWith("http://") || url.startsWith("https://")) return false;
            view.loadUrl(url);
            return true;
        }

        private void load(WebView webView, String url) {
            try {
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
                    webView.loadUrl(url);
                } else {
                    webView.loadDataWithBaseURL(null, "<h1>WebHome 路径无效</h1><small>" + url + "</small>", "text/html", "UTF-8", null);
                }
            } catch (Throwable th) {
                webView.loadDataWithBaseURL(null, "<h1>加载失败</h1><small>" + th.getMessage() + "</small>", "text/html", "UTF-8", null);
            }
        }

        private void hideSystemBars(Window w) {
            if (w != null) w.getDecorView().setSystemUiVisibility(5894);
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            if (hasFocus) hideSystemBars(getWindow());
        }
    }
}