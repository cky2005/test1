package com.github.catvod.spider;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.github.catvod.crawler.Spider;
import com.fongmi.web.WebHomeInlineVodStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.List;

/**
 * FmActionHandler 默认实现 — 蜂蜜影视壳 FM SDK 完整版.
 *
 * <p>对比残缺版:
 * <ul>
 *   <li>site.info / device.info / config.info / ext.info: 残缺版返回硬编码值, 本版本反射读壳的配置</li>
 *   <li>setChrome / restoreChrome / setToolbar: 残缺版是 no-op, 本版本反射调壳的 controller</li>
 *   <li>search / openVod / openLive / openKeep / openSetting: 残缺版是 no-op, 本版本启动对应的 Activity</li>
 *   <li>pan.play: 走壳的播放链路</li>
 *   <li>back / reload / getViewport: 通过 FmController 回调</li>
 * </ul>
 *
 * <p>依赖: 反射调用壳的类 (Site, Device, VodConfig, History, Setting, SearchActivity 等).
 * 壳类名/方法名不对时, 静默回退到 no-op, 不影响基础功能.
 */
public class DefaultFmActionHandler implements FmActionHandler {

    private static final String TAG = "WebHomeAction";

    private static final String PUSH_AGENT_KEY = "push_agent";

    private final Context appContext;
    private final PlayerLauncher launcher;
    private FmController controller;  // 由 FmController 在 init 时注入, 用于 setChrome 等
    private volatile String siteKey = "";  // 由 WebHome.init 注入 (Spider.siteKey), 多集走标准 Spider 路径用

    public DefaultFmActionHandler(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
        this.launcher = new PlayerLauncher(context);
    }

    /** WebHome.init 注入 site key (壳配置里的 key, 如 "列表测试") */
    public void setSiteKey(String key) {
        if (key != null) this.siteKey = key.trim();
    }

    private String playSiteKey() {
        if (!TextUtils.isEmpty(siteKey)) return siteKey;
        try {
            String k = WebHome.currentSiteKey();
            if (!TextUtils.isEmpty(k)) {
                siteKey = k;
                return k;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /**
     * FmController 注入, 让 setChrome/restoreChrome 等能回调到 controller.
     */
    void setController(FmController controller) {
        this.controller = controller;
    }

    // ============== 网络 (FmBridge 内置 doNativeReq, 这里 no-op) ==============

    @Override
    public FmHttpResponse http(String url, String method, JSONObject headers, String body,
                               String responseType, int timeout, boolean includeCookie) {
        return null;  // 让 FmBridge 走默认 HttpURLConnection 实现
    }

    /**
     * 调 webview 里的 window.__fmWebHomeInlineResolver(episode) 拿真实 m3u8.
     * 油管/bili 装这个全局 resolver, SDK 代为调用, 把 watch page 列表
     * 替换成 m3u8 直链再推给壳.
     * 
     * JS: 返回 url 字符串. 解析失败 (resolver 不存在 / 抛错) 返回 null.
     */
    private String callInlineResolver(final JSONObject episode) {
        if (controller == null) return null;
        // build episode JSON
        org.json.JSONObject epForJs = new org.json.JSONObject();
        try {
            java.util.Iterator<String> keys = episode.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                epForJs.put(k, episode.opt(k));
            }
        } catch (Throwable ignored) {}
        String epJson;
        try { epJson = epForJs.toString(); } catch (Throwable t) { return null; }
        // 同步调用 resolver. 返回 url 字符串或 ''
        String result = controller.evaluateJavascriptSync(
            "window.__fmWebHomeInlineResolver(" + epJson + ") && " +
            "window.__fmWebHomeInlineResolver(" + epJson + ").url",
            1000);
        if (result == null) return null;
        result = result.replaceAll("^\"|\"$", "").trim();  // strip JSON quotes
        if (result.isEmpty() || "null".equals(result)) return null;
        return result;
    }

    // ============== 播放 (走 PlayerLauncher) ==============

    @Override
    public void playUrl(String url, String title, JSONObject options) {
        if (TextUtils.isEmpty(url)) return;
        url = WebHome.normalizeForPlayer(url);
        Log.d(TAG, "playUrl: " + url);
        try {
            String pic = options != null ? options.optString("pic", "") : "";
            String wallPic = options != null ? options.optString("wallPic", "") : "";
            String mark = options != null ? options.optString("mark", "") : "";
            String key = playSiteKey();
            // 已是标准多集串, 或单条直链: 存成 inline vod, 走本站 detail/playerContent
            if (!TextUtils.isEmpty(key) && (url.contains("#") || url.contains("$") || WebHome.isDirectMedia(url))) {
                JSONObject payload = options != null ? new JSONObject(options.toString()) : new JSONObject();
                payload.put("vod_name", title);
                payload.put("vod_pic", pic);
                payload.put("wallPic", wallPic);
                payload.put("mark", mark);
                JSONArray eps = new JSONArray();
                String[] parts = url.split("#");
                for (int i = 0; i < parts.length; i++) {
                    String p = parts[i];
                    JSONObject ep = new JSONObject();
                    if (p.contains("$")) {
                        int d = p.indexOf('$');
                        ep.put("name", p.substring(0, d));
                        ep.put("url", WebHome.normalizeForPlayer(p.substring(d + 1)));
                    } else {
                        ep.put("name", String.format("%02d", i + 1));
                        ep.put("url", WebHome.normalizeForPlayer(p));
                    }
                    ep.put("resolve", false);
                    eps.put(ep);
                }
                payload.put("episodes", eps);
                String vodId = storeVodInline(payload, title, pic, wallPic, mark, eps);
                if (!TextUtils.isEmpty(vodId)) {
                    // 不把 html 的 mark 传给壳: Flag.find 对集名做包含/数字模糊匹配, 空 mark 还会所有集同分乱选.
                    // 第一次无历史 → History 默认第 1 集; 同一 vod_id 再进 → 续看上次那集.
                    if (startVideoActivity(key, vodId, title, pic, wallPic, null)) {
                        WebHome.hideOverlay();
                        return;
                    }
                }
            }
            if (!WebHome.isDirectMedia(url) && (url.startsWith("webhome_inline_") || url.startsWith("whi:"))) {
                Log.e(TAG, "playUrl: refusing to push placeholder as media: " + url);
                return;
            }
            startVideoActivity(PUSH_AGENT_KEY, url, title, pic, wallPic, mark);
        } catch (Throwable t) {
            Log.e(TAG, "playUrl failed", t);
        }
    }

    /**
     * 反射调 蜂蜜壳的 VideoActivity.start.
     * mobile: (Activity, key, id, name, pic, mark, boolean collect)
     * leanback/TV: (Activity, key, id, name, pic, mark, boolean collect, boolean cast)
     * 占位 id (webhome_inline_*) 失败时绝不走 HTTP push / 系统 Intent.
     */
    private boolean startVideoActivity(String key, String id, String title, String pic, String wallPic, String mark) {
        android.app.Activity activity = null;
        try { activity = WebHome.getForegroundActivity(); } catch (Throwable ignored) {}
        if (activity == null && appContext instanceof android.app.Activity) {
            activity = (android.app.Activity) appContext;
        }
        boolean placeholder = id != null && (id.startsWith("webhome_inline_") || id.startsWith("whi:"));
        if (activity == null) {
            Log.w(TAG, "no Activity");
            if (placeholder) return false;
            launcher.playInShell(id, title, pic, wallPic, key);
            return false;
        }
        if (TextUtils.isEmpty(key)) {
            Log.e(TAG, "empty siteKey, refuse start. id=" + id);
            return false;
        }
        try {
            Class<?> cls = Class.forName("com.fongmi.android.tv.ui.activity.VideoActivity");
            Object[] tried = invokeVideoStart(cls, activity, key, id, title, pic, mark);
            if (Boolean.TRUE.equals(tried[0])) {
                Log.d(TAG, "VideoActivity.start ok via " + tried[1] + " key=" + key + " id=" + id);
                return true;
            }
            throw new NoSuchMethodException("no matching VideoActivity.start");
        } catch (Throwable t) {
            Log.e(TAG, "VideoActivity.start failed: " + t);
            if (startVideoByIntent(activity, key, id, title, pic, mark)) return true;
            if (placeholder) {
                Log.e(TAG, "placeholder not pushed to system/HTTP");
                return false;
            }
            launcher.playInShell(id, title, pic, wallPic, key);
            return false;
        }
    }

    private boolean startVideoByIntent(android.app.Activity activity, String key, String id,
                                       String title, String pic, String mark) {
        try {
            android.content.Intent intent = new android.content.Intent();
            intent.setClassName(activity, "com.fongmi.android.tv.ui.activity.VideoActivity");
            intent.putExtra("key", key);
            intent.putExtra("id", id);
            intent.putExtra("name", title);
            intent.putExtra("pic", pic);
            intent.putExtra("mark", mark);
            intent.putExtra("collect", false);
            intent.putExtra("cast", false);
            activity.startActivity(intent);
            Log.d(TAG, "VideoActivity intent ok key=" + key + " id=" + id);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "VideoActivity intent failed: " + t);
            return false;
        }
    }

    private void startVideoActivity(String key, String id, String title, String pic, String wallPic) {
        startVideoActivity(key, id, title, pic, wallPic, null);
    }

    private static Object[] invokeVideoStart(Class<?> cls, android.app.Activity activity,
                                             String key, String id, String title, String pic, String mark) throws Exception {
        // TV leanback: 8-arg
        try {
            java.lang.reflect.Method m = cls.getMethod("start",
                    android.app.Activity.class, String.class, String.class, String.class,
                    String.class, String.class, boolean.class, boolean.class);
            m.invoke(null, activity, key, id, title, pic, mark, false, false);
            return new Object[]{Boolean.TRUE, "8arg"};
        } catch (NoSuchMethodException ignored) {}
        // mobile: 7-arg
        try {
            java.lang.reflect.Method m = cls.getMethod("start",
                    android.app.Activity.class, String.class, String.class, String.class,
                    String.class, String.class, boolean.class);
            m.invoke(null, activity, key, id, title, pic, mark, false);
            return new Object[]{Boolean.TRUE, "7arg"};
        } catch (NoSuchMethodException ignored) {}
        // older: 6-arg without collect
        try {
            java.lang.reflect.Method m = cls.getMethod("start",
                    android.app.Activity.class, String.class, String.class, String.class,
                    String.class, String.class);
            m.invoke(null, activity, key, id, title, pic, mark);
            return new Object[]{Boolean.TRUE, "6arg"};
        } catch (NoSuchMethodException ignored) {}
        return new Object[]{Boolean.FALSE, "none"};
    }

    @Override
    public void playVod(String siteKey, String vodId, String title, String pic, JSONObject options) {
        if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(vodId)) {
            playUrl(vodId, title, options);
            return;
        }
        Log.d(TAG, "playVod: site=" + siteKey + " vod=" + vodId);
        try {
            String wallPic = options != null ? options.optString("wallPic", "") : "";
            // 反射直接调 VideoActivity.start, 走标准 Spider 路径 (如果 siteKey 在 蜂蜜壳已注册)
            // 不走 HTTP push (避免 9978 端口被占)
            startVideoActivity(siteKey, vodId, title, pic, wallPic);
        } catch (Throwable t) {
            Log.e(TAG, "playVod failed", t);
        }
    }

    @Override
    public void playVodInline(JSONObject payload) {
        if (payload == null) return;
        try {
            String title = payload.optString("vod_name", payload.optString("title", ""));
            String pic = payload.optString("vod_pic", payload.optString("pic", ""));
            String wallPic = payload.optString("wallPic", "");
            String mark = payload.optString("mark", "");
            JSONArray episodes = payload.optJSONArray("episodes");
            if (episodes == null || episodes.length() == 0) {
                playUrl(payload.optString("url", ""), title, payload);
                return;
            }

            // 只存占位名单, 不解直链. 用本站 siteKey 进 VideoActivity,
            // 壳会调 JAR.detailContent (秒回名单) + playerContent (按集解析).
            String vodId = storeVodInline(payload, title, pic, wallPic, mark, episodes);
            if (TextUtils.isEmpty(vodId)) {
                Log.w(TAG, "playVodInline: store failed");
                return;
            }
            String key = playSiteKey();
            Log.d(TAG, "playVodInline: vodId=" + vodId + " eps=" + episodes.length() + " key=" + key);
            if (TextUtils.isEmpty(key)) {
                Log.e(TAG, "playVodInline: empty siteKey, refuse push_agent for placeholder " + vodId);
                return;
            }
            // Overlay 先留着: 反射失败时用户还在 html 里; 进播放页后再 hide 也可以,
            // 但 hide 不能 destroy, 否则 playerContent 调不到 resolver.
            if (!startVideoActivity(key, vodId, title, pic, wallPic, null)) {
                Log.e(TAG, "playVodInline: VideoActivity.start failed, stay in overlay");
            } else {
                WebHome.hideOverlay();
            }
        } catch (Throwable t) {
            Log.e(TAG, "playVodInline failed", t);
        }
    }

    /**
     * 直接启动 VideoActivity (用 webhome_inline key), 不走 HTTP push.
     * 当前未使用, 保留以便未来壳支持 webhome_inline 完整路径时启用.
     * 蜂蜜 mobile VideoActivity.start 签名: (Activity, String key, String id, String name, String pic, String mark, boolean collect)
     */
    @SuppressWarnings("unused")
    private void startWebHomeInline(String vodId, String title, String pic, String wallPic) {
        if (!(appContext instanceof android.app.Activity)) {
            Log.w(TAG, "no Activity, fallback to HTTP push");
            launcher.playInShell(vodId, title, pic, wallPic, com.fongmi.web.WebHomeInlineVodStore.KEY);
            return;
        }
        android.app.Activity activity = (android.app.Activity) appContext;
        try {
            Class<?> cls = Class.forName("com.fongmi.android.tv.ui.activity.VideoActivity");
            Method m = cls.getMethod("start",
                    android.app.Activity.class, String.class, String.class, String.class,
                    String.class, String.class, boolean.class);
            m.invoke(null, activity, com.fongmi.web.WebHomeInlineVodStore.KEY, vodId, title, pic, null, false);
            Log.d(TAG, "VideoActivity.start (webhome_inline) success: " + vodId);
        } catch (Throwable t) {
            Log.e(TAG, "startWebHomeInline failed: " + t.getMessage());
        }
    }

    /**
     * 把 episodes 存到 WebHomeInlineVodStore, 返回 vodId.
     * 壳的播放器用这个 vodId 调 SDK 的 detailContent/playerContent 拿真正的 m3u8 URL.
     */
    private String storeVodInline(JSONObject payload, String title, String pic, String wallPic, String mark, JSONArray episodes) {
        try {
            // 用 Gson JsonObject (WebHomeInlineVodStore 用的是 Gson)
            JsonObject gson = JsonParser.parseString(payload.toString()).getAsJsonObject();
            gson.addProperty("vod_name", title);
            gson.addProperty("title", title);
            gson.addProperty("vod_pic", pic);
            gson.addProperty("pic", pic);
            gson.addProperty("wallPic", wallPic);
            gson.addProperty("mark", mark);
            if (!gson.has("vod_play_from")) gson.addProperty("vod_play_from", "WebHome");
            if (!gson.has("playFrom")) gson.addProperty("playFrom", gson.get("vod_play_from").getAsString());
            // 转换 episodes 到 Gson
            com.google.gson.JsonArray gsonEpisodes = new com.google.gson.JsonArray();
            for (int i = 0; i < episodes.length(); i++) {
                JSONObject orgEp = episodes.optJSONObject(i);
                if (orgEp == null) continue;
                try {
                    gsonEpisodes.add(JsonParser.parseString(orgEp.toString()).getAsJsonObject());
                } catch (Throwable ignored) {}
            }
            gson.add("episodes", gsonEpisodes);
            return com.fongmi.web.WebHomeInlineVodStore.put(gson);
        } catch (Throwable t) {
            Log.e(TAG, "storeVodInline failed", t);
            return null;
        }
    }

    @Override
    public void preloadArtwork(String pic, String wallPic) {
        // no-op (壳用 Glide 预热)
    }

    @Override
    public void controlPlayer(String action) {
        // 反射调壳的 PlaybackService
        if (TextUtils.isEmpty(action) || appContext == null) return;
        try {
            Class<?> serviceClass = Class.forName("com.fongmi.android.tv.service.PlaybackService");
            Object service = callStatic(serviceClass, "get");
            if (service == null) return;
            Object player = callMethod(service, "player");
            if (player == null) return;
            switch (action) {
                case "play":  callMethod(player, "play"); break;
                case "pause": callMethod(player, "pause"); break;
                case "stop":  callMethod(service, "dispatchStop"); break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "controlPlayer failed: " + t.getMessage());
        }
    }

    @Override
    public JSONObject playerStatus() {
        return new JSONObject();
    }

    // ============== App 入口 (启动壳的 Activity) ==============

    @Override
    public void search(String keyword, JSONObject options) {
        if (TextUtils.isEmpty(keyword) || appContext == null) return;
        try {
            // 反射: 启动 SearchActivity (带 keyword 参数)
            Class<?> cls = Class.forName("com.fongmi.android.tv.ui.activity.SearchActivity");
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(appContext, cls));
            intent.putExtra("keyword", keyword);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
        } catch (Throwable t) {
            // 兜底: 走主壳的搜索接口
            launcher.openSearch(keyword, options != null && options.optBoolean("direct"));
        }
    }

    @Override
    public void openVod() {
        launcher.openMainActivity("HomeActivity");
    }

    @Override
    public void openLive() {
        startActivityByName("LiveActivity");
    }

    @Override
    public void openKeep() {
        startActivityByName("KeepActivity");
    }

    @Override
    public void openSetting() {
        startActivityByName("SettingActivity");
    }

    @Override
    public JSONObject history() {
        JSONObject out = new JSONObject();
        try {
            // 反射读壳的 History.get() (返回 List<History> 转 JSON)
            Class<?> cls = Class.forName("com.fongmi.android.tv.bean.History");
            Object result = callStatic(cls, "get");
            if (result != null) {
                JSONArray arr = new JSONArray();
                if (result instanceof List) {
                    for (Object item : (List<?>) result) {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("key", callMethod(item, "getKey"));
                            o.put("vodId", callMethod(item, "getVodId"));
                            o.put("vodName", callMethod(item, "getVodName"));
                            o.put("vodPic", callMethod(item, "getVodPic"));
                            arr.put(o);
                        } catch (Throwable ignored) {}
                    }
                }
                out.put("list", arr);
            } else {
                out.put("list", new JSONArray());
            }
        } catch (Throwable t) {
            Log.w(TAG, "history failed: " + t.getMessage());
            try { out.put("list", new JSONArray()); } catch (JSONException ignored) {}
        }
        return out;
    }

    // ============== 缓存 (SharedPreferences) ==============

    @Override
    public String cacheGet(String key, String rule) {
        if (appContext == null) return "";
        return appContext.getSharedPreferences("fongmi_webhome", Context.MODE_PRIVATE)
                .getString(cacheKey(rule, key), "");
    }

    @Override
    public void cacheSet(String key, String value, String rule) {
        if (appContext == null) return;
        appContext.getSharedPreferences("fongmi_webhome", Context.MODE_PRIVATE)
                .edit().putString(cacheKey(rule, key), value == null ? "" : value).apply();
    }

    @Override
    public void cacheDel(String key, String rule) {
        if (appContext == null) return;
        appContext.getSharedPreferences("fongmi_webhome", Context.MODE_PRIVATE)
                .edit().remove(cacheKey(rule, key)).apply();
    }

    // ============== UI (通过 controller 回调, 改 chrome/toolbar/viewport) ==============

    @Override
    public void setChrome(JSONObject options) {
        if (controller != null) {
            controller.onSetChrome(options != null ? options.toString() : "{}");
        }
    }

    @Override
    public void restoreChrome() {
        if (controller != null) controller.onRestoreChrome();
    }

    @Override
    public void setToolbar(boolean visible) {
        if (controller != null) controller.onSetToolbar(visible);
    }

    @Override
    public JSONObject getViewport() {
        if (controller != null) {
            String json = controller.onGetViewport();
            if (json != null) {
                try { return new JSONObject(json); } catch (JSONException ignored) {}
            }
        }
        JSONObject v = new JSONObject();
        try { v.put("chromeMode", "normal"); v.put("width", 1920); v.put("height", 1080); } catch (JSONException ignored) {}
        return v;
    }

    // ============== 设备/站点/配置 (反射读壳的配置) ==============

    @Override
    public JSONObject deviceInfo() {
        JSONObject d = new JSONObject();
        try {
            d.put("uuid", "");
            d.put("name", android.os.Build.MODEL);
            d.put("ip", "http://127.0.0.1:9978");
            d.put("type", 1);
            d.put("time", System.currentTimeMillis());

            // 反射读壳的 Device class, 拿真实数据
            try {
                Class<?> deviceCls = Class.forName("com.fongmi.android.tv.bean.Device");
                Object device = callStatic(deviceCls, "get");
                if (device != null) {
                    String uuid = (String) callMethod(device, "getUuid");
                    String serial = (String) callMethod(device, "getSerial");
                    if (!TextUtils.isEmpty(uuid)) d.put("uuid", uuid);
                    if (!TextUtils.isEmpty(serial)) d.put("serial", serial);
                }
            } catch (Throwable ignored) {}
        } catch (JSONException ignored) {}
        return d;
    }

    @Override
    public JSONObject siteInfo() {
        JSONObject s = new JSONObject();
        try {
            s.put("key", "webhome");
            s.put("name", "WebHome");
            s.put("homePage", "");
            s.put("type", 3);

            // 反射读壳的当前 home site
            try {
                Class<?> vodConfigCls = Class.forName("com.fongmi.android.tv.api.config.VodConfig");
                Object vodConfig = callStatic(vodConfigCls, "get");
                if (vodConfig != null) {
                    Object site = callMethod(vodConfig, "getHome");
                    if (site != null) {
                        String key = (String) callMethod(site, "getKey");
                        String name = (String) callMethod(site, "getName");
                        String homePage = (String) callMethod(site, "getHomePage");
                        String chromeMode = (String) callMethod(site, "getChromeMode");
                        if (!TextUtils.isEmpty(key)) s.put("key", key);
                        if (!TextUtils.isEmpty(name)) s.put("name", name);
                        if (homePage != null) s.put("homePage", homePage);
                        if (!TextUtils.isEmpty(chromeMode)) s.put("chromeMode", chromeMode);
                    }
                }
            } catch (Throwable ignored) {}
        } catch (JSONException ignored) {}
        return s;
    }

    @Override
    public JSONObject configInfo() {
        JSONObject c = new JSONObject();
        try {
            c.put("driveCheck", true);

            // 反射读壳的 VodConfig (id/url/desc)
            try {
                Class<?> vodConfigCls = Class.forName("com.fongmi.android.tv.api.config.VodConfig");
                Object cid = callStatic(vodConfigCls, "getCid");
                Object url = callStatic(vodConfigCls, "getUrl");
                Object desc = callStatic(vodConfigCls, "getDesc");
                if (cid != null) c.put("id", cid);
                if (url != null) c.put("url", url);
                if (desc != null) c.put("desc", desc);
            } catch (Throwable ignored) {}

            // 反射读壳的 Setting.isDriveCheck
            try {
                Class<?> settingCls = Class.forName("com.fongmi.android.tv.setting.Setting");
                Object driveCheck = callStatic(settingCls, "isDriveCheck");
                if (driveCheck instanceof Boolean) c.put("driveCheck", driveCheck);
            } catch (Throwable ignored) {}
        } catch (JSONException ignored) {}
        return c;
    }

    // ============== 扩展 (ext) ==============

    @Override
    public JSONObject extInfo() {
        JSONObject e = new JSONObject();
        try {
            e.put("siteKey", "webhome");
            e.put("siteName", "WebHome");
            e.put("enabled", true);
            e.put("matched", true);
            e.put("ready", true);

            // 反射读当前 site
            try {
                Class<?> vodConfigCls = Class.forName("com.fongmi.android.tv.api.config.VodConfig");
                Object vodConfig = callStatic(vodConfigCls, "get");
                if (vodConfig != null) {
                    Object site = callMethod(vodConfig, "getHome");
                    if (site != null) {
                        String key = (String) callMethod(site, "getKey");
                        String name = (String) callMethod(site, "getName");
                        if (!TextUtils.isEmpty(key)) e.put("siteKey", key);
                        if (!TextUtils.isEmpty(name)) e.put("siteName", name);
                    }
                }
            } catch (Throwable ignored) {}
        } catch (JSONException ignored) {}
        return e;
    }

    @Override
    public void extLog(String message, String data) {
        Log.d(TAG, "[ext] " + message + " " + data);
    }

    @Override
    public void extToast(String message) {
        if (appContext == null || TextUtils.isEmpty(message)) return;
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show());
    }

    // ============== 网盘检测/播放 ==============

    @Override
    public JSONObject panCheck(JSONObject payload) {
        // 反射调壳的 DriveCheckService
        JSONObject out = new JSONObject();
        try {
            out.put("results", new JSONArray());
            try {
                Class<?> requestCls = Class.forName("com.fongmi.android.tv.bean.drive.DriveCheckRequest");
                Object request = null;
                if (payload != null) {
                    // 简易转换: payload 已有 items 数组
                    request = requestCls.getDeclaredConstructor().newInstance();
                    JSONArray items = payload.optJSONArray("items");
                    if (items != null) {
                        Method setItems = requestCls.getMethod("setItems", List.class);
                        // 实际是 List<DriveCheckItem>, 这里简单返回空
                    }
                }
                if (request != null) {
                    Class<?> serviceCls = Class.forName("com.fongmi.android.tv.service.DriveCheckService");
                    Object service = callStatic(serviceCls, "get");
                    if (service != null) {
                        Method checkMethod = serviceCls.getMethod("check", requestCls);
                        Object result = checkMethod.invoke(service, request);
                        if (result != null) {
                            // 简化: 返回原始 JSON
                            return new JSONObject(result.toString());
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "panCheck reflect failed: " + t.getMessage());
            }
        } catch (JSONException ignored) {}
        return out;
    }

    @Override
    public void panPlay(JSONObject payload) {
        if (payload == null) return;
        String url = payload.optString("url", "");
        String type = payload.optString("type", "");
        String title = payload.optString("title", url);
        String pic = payload.optString("pic", "");
        if (!TextUtils.isEmpty(url)) {
            playUrl(url, title, payload);
        }
    }

    // ============== 导航 (回退给 controller 处理 WebView 内部导航) ==============

    @Override
    public void navigationBack() {
        if (controller != null && controller.handleBack()) return;
        // controller 已经在内部 dismiss 了
    }

    @Override
    public void navigationReload() {
        if (controller != null) controller.reload();
    }

    // ============== 工具方法 ==============

    /**
     * 按类名启动 Activity (在 com.fongmi.android.tv.ui.activity 包下).
     */
    private void startActivityByName(String simpleName) {
        if (appContext == null) return;
        try {
            Class<?> cls = Class.forName("com.fongmi.android.tv.ui.activity." + simpleName);
            Intent intent = new Intent(appContext, cls);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
        } catch (Throwable t) {
            // 兜底: 走主壳 HTTP push
            launcher.openMainActivity(simpleName);
        }
    }

    private static String cacheKey(String rule, String key) {
        return "cache_" + (TextUtils.isEmpty(rule) ? "" : rule + "_") + key;
    }

    /** 反射调用静态方法, 失败返回 null */
    private static Object callStatic(Class<?> cls, String method, Object... args) {
        try {
            Class<?>[] paramTypes = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                paramTypes[i] = args[i] == null ? Object.class : args[i].getClass();
            }
            Method m = cls.getMethod(method, paramTypes);
            return m.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射调用实例方法, 失败返回 null */
    private static Object callMethod(Object obj, String method, Object... args) {
        if (obj == null) return null;
        try {
            Class<?>[] paramTypes = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                paramTypes[i] = args[i] == null ? Object.class : args[i].getClass();
            }
            Method m = obj.getClass().getMethod(method, paramTypes);
            return m.invoke(obj, args);
        } catch (Throwable t) {
            return null;
        }
    }
}