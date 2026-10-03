package com.github.catvod.spider;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.github.catvod.crawler.Spider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 浮动脚本加载器 —— 针对蜂蜜系(FongMi)影视壳的通用爬虫加载器.
 *
 * 设计原则:
 *  1) 以 FongMi/TV 上游源码为准: 宿主 BaseLoader.getSpider(key,api,ext,jar) 是四个 String 参数;
 *     上游 app.py 的 download() 对非 http 的 api 会把它当"脚本全文"写盘. 因此本地脚本的
 *     api 必须传脚本内容(而非路径), 并在末尾附一行 "# 文件名.py" 注释以满足 isPy() 判定.
 *  2) 不写死壳包名/版本: 通过 findHostRoot() 从当前 Activity 推断上游包名, 候选路径
 *     Class.forName 加载; 跨类加载器链解析(兼容魔改壳的"包装加载器"). 不依赖任何 R8 混淆后
 *     的方法名或类名.
 *  3) 只保留必要日志, 去掉复杂的配置类探针和冗余兜底.
 */
public class FloatSpider extends Spider {
    private static final String TAG = "FloatSpider";
    /** 蜂蜜系(FongMi)上游代码包名; 魔改壳改了 applicationId 时由 findHostRoot() 自动推断 */
    private static final String CANONICAL_ROOT = "com.fongmi.android.tv";

    private static WeakReference<Activity> topActivityRef;
    private static Class<?> baseLoaderClass, siteClass, vodConfigClass;
    private static String hostRoot;

    private Context context;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private List<File> fileList = new ArrayList<>();
    private List<String> extPaths = new ArrayList<>();
    private volatile Object delegateSpider;   // v41b: volatile —— 加载已挪后台线程, 壳 pool 线程读

    private ImageView floatButton;
    private Dialog scriptDialog;

    // ===================== 初始化 =====================

    @Override
    public void init(Context context, String cfg) {
        this.context = context;
        extPaths.clear();
        try {
            if (cfg != null && !cfg.trim().isEmpty()) {
                String raw = cfg.trim();
                if (raw.startsWith("{")) {
                    JsonObject jo = JsonParser.parseString(raw).getAsJsonObject();
                    if (jo.has("ext")) raw = jo.get("ext").getAsString();
                }
                for (String p : raw.split(",")) {
                    p = p.trim();
                    if (!p.isEmpty()) extPaths.add(normalizeExtPath(p));
                }
            }
        } catch (Throwable e) { Log.e(TAG, "解析 ext 失败", e); }
        registerLifecycle(context);
        loadFileList();
    }

    // 标准化 ext 路径，支持多种写法，最终都指向同一个实际路径
    // 支持: file:///storage/emulated/0/, file://storage/emulated/0/,
    //       /storage/emulated/0/, /sdcard/, http://127.0.0.1:9978/file/...
    private String normalizeExtPath(String path) {
        if (path == null || path.isEmpty()) return path;
        try {
            // 1. 处理 file:// URL
            if (path.startsWith("file://")) {
                path = path.substring("file://".length());
                // 去除可能多余的斜杠: file:///storage → /storage
                while (path.startsWith("/")) path = path.substring(1);
                path = "/" + path;
            }
            // 2. 处理 http:// URL (壳内置 HTTP 服务器的 /file/ 前缀)
            if (path.startsWith("http://")) {
                // http://127.0.0.1:9978/file/abc/spider3/py → /sdcard/abc/spider3/py
                String[] parts = path.split("/");
                StringBuilder sb = new StringBuilder();
                boolean inFile = false;
                for (String part : parts) {
                    if ("file".equals(part)) { inFile = true; continue; }
                    if (inFile) sb.append("/").append(part);
                }
                if (sb.length() > 0) {
                    path = "/sdcard" + sb.toString();
                } else {
                    path = "/sdcard";
                }
            }
            // 3. 统一 /sdcard 为 /storage/emulated/0 (或反过来)
            if (path.startsWith("/sdcard")) {
                path = "/storage/emulated/0" + path.substring("/sdcard".length());
            } else if (path.startsWith("/storage/emulated/0")) {
                // 保持不变
            }
            // 4. 去除尾部斜杠
            while (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
            return path;
        } catch (Throwable e) {
            Log.e(TAG, "路径标准化失败: " + path, e);
            return path;
        }
    }

    private static synchronized void registerLifecycle(Context context) {
        if (context == null) return;
        try {
            Context app = context.getApplicationContext();
            if (app instanceof Application) {
                ((Application) app).registerActivityLifecycleCallbacks(FloatLifecycle.INSTANCE);
            }
        } catch (Throwable ignored) {}
    }

    // ===================== 目录枚举(纯 File API, 递归子目录) =====================

    private void loadFileList() {
        fileList.clear();
        FilenameFilter filter = FloatFileFilter.INSTANCE;
        for (String path : extPaths) {
            File dir = new File(path);
            if (!dir.isDirectory()) continue;
            collectRecursive(dir, filter);
        }
    }

    private void collectRecursive(File dir, FilenameFilter filter) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collectRecursive(f, filter);
            else if (filter.accept(dir, f.getName())) fileList.add(f);
        }
    }

    // ===================== 爬虫接口(委托给已加载脚本) =====================

    @Override
    public String homeContent(boolean filter) {
        Object r = invokeDelegate("homeContent", new Class<?>[]{boolean.class}, filter);
        if (r instanceof String) return (String) r;
        // 仅返回分类(悬浮脚本), list 留空 —— list 为空时框架不会生成"推荐" tab;
        // 首页条目(show_float)改由 categoryContent 在"悬浮脚本"分类下给出, 避免重复显示.
        return buildHomeClasses();
    }

    // 首页视频(推荐): 返回空, 不提供推荐内容, 首页不出现"推荐"分类
    // 注意: 覆盖父类时不声明 throws Exception, 兼容父方法带/不带 throws 的两种 catvod 版本
    @Override
    public String homeVideoContent() {
        JsonObject jo = new JsonObject();
        jo.add("list", new JsonArray());
        return jo.toString();
    }

    private String buildHomeClasses() {
        JsonObject jo = new JsonObject();
        JsonArray ca = new JsonArray();
        JsonObject c = new JsonObject();
        c.addProperty("type_id", "float_scripts");
        c.addProperty("type_name", "悬浮脚本");
        ca.add(c);
        jo.add("class", ca);
        jo.add("list", new JsonArray());
        // pagecount=1: 单页即止, 避免框架按默认 pagecount=0 视为无限翻页而重复拉取
        jo.addProperty("pagecount", 1);
        return jo.toString();
    }

    // "悬浮脚本"分类下的唯一条目: 点击唤出脚本选择器(经 action=show_float)
    private String buildFloatItems() {
        JsonObject jo = new JsonObject();
        JsonArray la = new JsonArray();
        JsonObject v = new JsonObject();
        v.addProperty("vod_id", "show_float");
        v.addProperty("vod_name", "点击显示脚本选择器");
        v.addProperty("vod_pic", "");
        v.addProperty("vod_remarks", "唤出脚本列表");
        v.addProperty("action", "show_float");
        la.add(v);
        jo.add("list", la);
        // pagecount=1(且 page=1): 关键! 不写时框架默认 pagecount=0 -> 视为无限翻页,
        // 会反复调用 categoryContent 并追加同一个 show_float, 造成"条目重复显示多个".
        jo.addProperty("page", 1);
        jo.addProperty("pagecount", 1);
        return jo.toString();
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String,String> extend) {
        Object r = invokeDelegate("categoryContent", new Class<?>[]{String.class,String.class,boolean.class,HashMap.class}, tid, pg, filter, extend);
        if (r instanceof String) return (String) r;
        // 不自动弹出文件列表: 仅确保悬浮按钮存在(可点击唤出), 真正的弹窗只由
        // "点击列表条目(action=show_float)" 或 "点击悬浮按钮" 触发.
        ensureFloatButton(getTopActivity());
        return buildFloatItems();
    }

    public String action(String action) {
        if (action != null && action.contains("show_float")) {
            mainHandler.post(new FloatShowTask(this));
            return "{}";
        }
        Object r = invokeDelegate("action", new Class<?>[]{String.class}, action);
        return r instanceof String ? (String) r : "{}";
    }

    public String detailContent(List<String> ids) {
        Object r = invokeDelegate("detailContent", new Class<?>[]{List.class}, ids);
        return r instanceof String ? (String) r : "{}";
    }

    public String playerContent(String flag, String id, List<String> vipFlags) {
        Object r = invokeDelegate("playerContent", new Class<?>[]{String.class,String.class,List.class}, flag, id, vipFlags);
        return r instanceof String ? (String) r : "{}";
    }

    public String searchContent(String key, boolean quick) {
        Object r = invokeDelegate("searchContent", new Class<?>[]{String.class,boolean.class}, key, quick);
        if (r instanceof String) return (String) r;
        r = invokeDelegate("searchContent", new Class<?>[]{String.class,boolean.class,String.class}, key, quick, "1");
        return r instanceof String ? (String) r : "{}";
    }

    private Object invokeDelegate(String name, Class<?>[] types, Object... args) {
        if (delegateSpider == null) return null;
        try {
            Method m = findMethod(delegateSpider.getClass(), name, types);
            if (m != null) { m.setAccessible(true); return m.invoke(delegateSpider, args); }
            for (Method x : delegateSpider.getClass().getMethods()) {
                if (x.getName().equals(name) && x.getParameterTypes().length == args.length) {
                    x.setAccessible(true);
                    return x.invoke(delegateSpider, args);
                }
            }
        } catch (Throwable e) { Log.e(TAG, "调用 " + name + " 失败", e); }
        return null;
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... types) {
        try { return c.getMethod(name, types); } catch (Throwable ignored) {}
        while (c != null) {
            try { return c.getDeclaredMethod(name, types); } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    private Activity getTopActivity() {
        if (topActivityRef != null) {
            Activity a = topActivityRef.get();
            if (a != null && !a.isFinishing() && !a.isDestroyed()) return a;
        }
        if (context instanceof Activity) {
            Activity a = (Activity) context;
            if (!a.isFinishing() && !a.isDestroyed()) return a;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cma = at.getMethod("currentActivityThread");
            Object th = cma.invoke(null);
            Field af = at.getDeclaredField("mActivities");
            af.setAccessible(true);
            Object acts = af.get(th);
            if (acts instanceof Map) {
                for (Object e : ((Map<?,?>) acts).values()) {
                    try {
                        Field pa = e.getClass().getDeclaredField("activity");
                        pa.setAccessible(true);
                        Activity a = (Activity) pa.get(e);
                        if (a != null && !a.isFinishing() && !a.isDestroyed()) return a;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ===================== 悬浮 UI =====================

    private void showFloatButtonAndDialog() {
        Activity a = getTopActivity();
        if (a == null || a.isFinishing() || a.isDestroyed()) { Log.e(TAG, "Activity 不可用"); return; }
        ensureFloatButtonCreated(a);
        showCenterScriptDialog(a);
    }

    // 仅创建悬浮按钮(不弹窗): 让用户可通过"点击悬浮按钮"唤出脚本列表, 且不自动弹出文件窗
    private void ensureFloatButton(Activity a) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        ensureFloatButtonCreated(a);
    }

    private void ensureFloatButtonCreated(Activity activity) {
        if (floatButton != null && floatButton.getParent() != null) return;
        floatButton = new ImageView(activity);
        floatButton.setImageResource(android.R.drawable.ic_menu_agenda);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE2196F3);
        bg.setCornerRadius(100);
        floatButton.setBackground(bg);
        floatButton.setPadding(25, 25, 25, 25);
        floatButton.setOnTouchListener(new AppTouchListener(activity, this));
        try {
            FrameLayout decor = (FrameLayout) activity.getWindow().getDecorView();
            int size = (int) (activity.getResources().getDisplayMetrics().density * 52);
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(size, size);
            p.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            p.rightMargin = 30;
            decor.addView(floatButton, p);
        } catch (Throwable e) { Log.e(TAG, "添加悬浮按钮失败", e); }
    }

    private void showCenterScriptDialog(final Activity activity) {
        if (scriptDialog != null && scriptDialog.isShowing()) return;
        loadFileList();
        scriptDialog = new Dialog(activity);
        scriptDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        // 使用垂直 LinearLayout 防止重叠
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xFF1E1E1E);
        card.setCornerRadius(24);
        root.setBackground(card);
        root.setPadding(30, 20, 30, 30);

        // 顶部: 搜索框 + 3 个 Tab (全部/PY/JS)
        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);

        EditText search = new EditText(activity);
        search.setHint("搜索脚本名字...");
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(0xFFFFFFFF);
        search.setHintTextColor(0xFF888888);
        search.setPadding(20, 12, 20, 12);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(0xFF2D2D2D);
        searchBg.setCornerRadius(10);
        search.setBackground(searchBg);

        RadioGroup tabs = new RadioGroup(activity);
        tabs.setOrientation(RadioGroup.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(0, 12, 0, 12);
        String[] tabNames = {"全部", "PY", "JS"};
        int[] tabIds = {1001, 1002, 1003};
        for (int i = 0; i < tabNames.length; i++) {
            RadioButton rb = new RadioButton(activity);
            rb.setText(tabNames[i]);
            rb.setId(tabIds[i]);
            rb.setTextSize(14);
            rb.setTextColor(0xFFAAAAAA);
            rb.setPadding(16, 6, 16, 6);
            tabs.addView(rb, new RadioGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        tabs.check(tabIds[0]);

        top.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        top.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        GridView grid = new GridView(activity);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(20);
        grid.setVerticalSpacing(20);
        grid.setGravity(Gravity.CENTER);
        grid.setVerticalScrollBarEnabled(true);

        final java.util.List<File> filtered = new java.util.ArrayList<>();
        final int[] selectedTab = {tabIds[0]};
        ScriptGridAdapter adapter = new ScriptGridAdapter(activity, filtered);
        grid.setAdapter(adapter);

        search.addTextChangedListener(new FloatTextWatcher(this, filtered, adapter, search, selectedTab));
        tabs.setOnCheckedChangeListener(new FloatTabListener(this, filtered, adapter, search, selectedTab));
        applyFilter(filtered, adapter, "", selectedTab[0]);

        grid.setOnItemClickListener(new FloatGridClickListener(this, activity, filtered));

        // 总高度限制为屏幕60%, 内部: top 包裹内容, GridView 填充剩余
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        int totalH = (int) (activity.getResources().getDisplayMetrics().heightPixels * .6f);
        container.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0);
        gridParams.weight = 1;
        container.addView(grid, gridParams);
        root.addView(container, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, totalH));
        root.addView(new View(activity), new LinearLayout.LayoutParams(0, 0));
        scriptDialog.setContentView(root);
        Window w = scriptDialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * .85f);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
        scriptDialog.show();
    }

    private void applyFilter(java.util.List<File> filtered, ScriptGridAdapter adapter, String keyword, int tabId) {
        filtered.clear();
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        for (File f : fileList) {
            String name = f.getName().toLowerCase();
            boolean matchTab = (tabId == 1001) ||
                               (tabId == 1002 && name.endsWith(".py")) ||
                               (tabId == 1003 && name.endsWith(".js"));
            if (matchTab && (kw.isEmpty() || name.contains(kw))) {
                filtered.add(f);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private static class ScriptGridAdapter extends BaseAdapter {
        private final Context context;
        private final List<File> files;
        ScriptGridAdapter(Context c, List<File> f) { context = c; files = f; }
        @Override public int getCount() { return files.isEmpty() ? 1 : files.size(); }
        @Override public Object getItem(int p) { return files.isEmpty() ? null : files.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = new TextView(context);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(20, 30,20, 30);
            tv.setTextSize(14);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(12);
            if (files.isEmpty()) {
                tv.setText("未找到 py / js 文件");
                tv.setTextColor(Color.GRAY);
                bg.setColor(0xFF2D2D2D);
            } else {
                File f = files.get(position);
                tv.setText(f.getName());
                if (f.getName().toLowerCase().endsWith(".py")) {
                    tv.setTextColor(0xFF4CAF50);
                    bg.setColor(0xFF263238);
                } else {
                    tv.setTextColor(0xFFFFC107);
                    bg.setColor(0xFF3E2723);
                }
            }
            tv.setBackground(bg);
            return tv;
        }
    }

    private static class AppTouchListener implements View.OnTouchListener {
        private final Activity activity;
        private final FloatSpider spider;
        private float lastX, lastY;
        private boolean dragging;
        AppTouchListener(Activity a, FloatSpider s) { activity = a; spider = s; }
        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = e.getRawX(); lastY = e.getRawY(); dragging = false; return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - lastX, dy = e.getRawY() - lastY;
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        dragging = true;
                        v.setX(v.getX() + dx);
                        v.setY(v.getY() + dy);
                        lastX = e.getRawX(); lastY = e.getRawY();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) spider.showCenterScriptDialog(activity);
                    return true;
            }
            return false;
        }
    }

    // ===================== 加载脚本并注册 =====================

    /** v41b 防重入: 后台加载进行中, 忽略重复点击 */
    private static volatile boolean sLoadBusy = false;

    private void loadAndRunScript(Activity activity, File file) {
        // v41: 点击立即关闭弹窗(无动画) —— 弹窗先消失再加载
        if (scriptDialog != null) { try { scriptDialog.dismiss(); } catch (Throwable ignored) {} }
        // v20: 监视开关入口 —— 必须先于 Blog.begin(它会清空 bl.log), 否则点第二次
        // (停止监视)时会把第一轮记录的调用日志全部清掉。
        // monitor*.js = 方法调用级监视开关(v18 ART hook, 唯一监视体系)
        String lowerName0 = file != null ? file.getName().toLowerCase() : "";
        if (file != null && lowerName0.contains("monitor")) {
            Blog.lineForce(">>> [" + FloatSpider.VER + "] 方法调用监视开关(monitor 触发)");
            toggleCallMonitor(activity);
            Blog.end();
            return;
        }
        // v41b: 加载挪后台线程 —— py(Chaquopy) 冷启动 ~7s, 壳原生站源也在 pool 线程加载
        // (bl.log 实锤 da.h0() [pool-8-thread-1]); 点击线程同步等待会冻结 UI 触发系统"无响应"。
        // 流程整体原样换载体(ScriptLoadTask), 无锁无状态变更; JS 同样走后台(统一路径, ~170ms 无感)。
        // 每步日志照旧落 bl.log, 有问题可逐步定位。
        if (sLoadBusy) { try { Toast.makeText(activity, "正在加载中, 请稍候…", Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {} return; }
        String nm = file != null ? file.getName() : "(null)";
        if (nm.toLowerCase().endsWith(".py")) {
            try { Toast.makeText(activity, "Python 首次加载需数秒, 请稍候…", Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
        }
        sLoadBusy = true;
        new Thread(new ScriptLoadTask(this, activity, file), "FloatSpider-Load").start();
    }

    /** 命名静态内部类(铁律): 后台执行原加载流程主体(loadScriptCore), 线程载体唯一变化 */
    private static final class ScriptLoadTask implements Runnable {
        private final FloatSpider owner;
        private final Activity activity;
        private final File file;
        ScriptLoadTask(FloatSpider owner, Activity activity, File file) { this.owner = owner; this.activity = activity; this.file = file; }   // 显式构造器(铁律)
        @Override
        public void run() {
            try { owner.loadScriptCore(activity, file); }
            finally { sLoadBusy = false; }
        }
    }

    /** 命名静态内部类(铁律): 失败 toast 回 main 投递(Toast.show 非主线程需 Looper) */
    private static final class FailToastTask implements Runnable {
        private final Activity activity;
        private final String msg;
        FailToastTask(Activity a, String m) { activity = a; msg = m; }   // 显式构造器(铁律)
        @Override
        public void run() {
            try { Toast.makeText(activity, msg, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
        }
    }

    private void loadScriptCore(Activity activity, File file) {
        // 监视会话进行中: 追加模式加载日志, 不清空监视记录(v20 修复)
        if (sCallMonitoring) Blog.beginAppend(file != null ? file.getName() : "(null)");
        else Blog.begin(file != null ? file.getName() : "(null)");
        try {
            Blog.line("=== 开始加载脚本: " + (file == null ? "(null)" : file.getAbsolutePath()) + " ===");
            File target = sanitizeScript(file);
            String name = file.getName();
            String path = target.getAbsolutePath();
            String lower = name.toLowerCase();
            boolean isJs = lower.endsWith(".js");
            Blog.line("清洗后路径: " + path);
            Blog.line("类型: " + (isJs ? "JS (QuickJS)" : "PY (Chaquopy)"));

            String content = readText(target);
            if (content == null || content.trim().isEmpty()) throw new Exception("脚本内容为空: " + path);
            Blog.line("脚本正文长度: " + content.length() + " 字符");

            String api, ext;
            if (isJs) {
                // JS 脚本: api 传 HTTP URL (触发 isJs), ext 传脚本内容 (给 init 解析)
                // 壳内置 HTTP 服务器在 http://127.0.0.1:9978/，本地文件必须走 /file/<sdcard相对路径> 前缀
                String sdcardRelPath = getSdcardRelativePath(path);
                if (sdcardRelPath == null) throw new Exception("无法计算 sdcard 相对路径: " + path);
                String fileUrl = "http://127.0.0.1:9978/file/" + sdcardRelPath.replace(File.separator, "/");
                api = fileUrl;
                ext = content; // 传原始脚本内容给 init, 不是目录URL
                Log.i(TAG, "JS 脚本: api=" + api + ", ext=" + (ext != null ? "content(" + ext.length() + " chars)" : "null"));
                Blog.line("JS api (HTTP URL): " + api);
                Blog.line("JS ext: " + (ext != null ? "content(" + ext.length() + " chars)" : "null"));
            } else {
                // v30 根因修复: 壳原生 py 链 app.py 的 spider(cache, api) 第二参数是 api URL,
                // basename(api) 作落盘文件名, http 开头 requests 下载, 非 http 把 api 字符串
                // 当脚本内容写文件 —— 旧形态"正文+尾注"在 basename 一步就 Errno 36(日志实锤)。
                // 与 JS 站(v29 已验证成功)同构: api = 9978 本地服务 URL, Python 侧可直接访问。
                String sdcardRelPath = getSdcardRelativePath(path);
                if (sdcardRelPath == null) throw new Exception("无法计算 sdcard 相对路径: " + path);
                api = "http://127.0.0.1:9978/file/" + sdcardRelPath.replace(File.separator, "/");
                ext = "";
                Blog.line("PY api (HTTP URL): " + api + ", 含 .py: " + api.contains(".py"));
            }

            String key = "float_" + md5(path);
            ClassLoader cl = activity.getClassLoader();
            String root = findHostRoot(activity);
            Blog.line("宿主包名 root: " + root);
            Class<?> loader = findBaseLoader(cl, root);
            Class<?> site = findSiteClass(cl, root);
            Class<?> config = findVodConfig(cl, root);
            Blog.line("反射查找结果 -> BaseLoader: " + (loader == null ? "未找到" : loader.getName()));
            Blog.line("反射查找结果 -> Site: " + (site == null ? "未找到" : site.getName()));
            Blog.line("反射查找结果 -> VodConfig: " + (config == null ? "未找到" : config.getName()));
            if (loader == null) {
                // v41b: 免验证 —— 用户拍板"都交给壳"。混淆壳直接 URL 形态注册, py 由壳 py 链
                // 在 pool 线程加载(与壳原生站源同款), js 由壳 JsLoader 用 ext 内容加载。
                // HoneyLoader 引擎/探针整体跳过(省 py ~7s / js ~170ms 注册耗时), delegateSpider
                // 兜底随之取消; 坏脚本/形态不符的后果 = 首页空或报错(壳不崩), 与壳原生一致。
                Blog.line(">>> 宿主 Spider Loader 未找到(被壳混淆), 免验证直接注册(壳自加载)");

                // ---- 复刻正常壳流程: 创建 Site -> 注册进站源列表 -> setHome -> 跳转 ----
                String jar = config != null ? getCurrentJar(config) : "";
                Object siteObj = null;
                if (site != null) {
                    try {
                        siteObj = createSite(site, key, name, api, ext, jar);
                        Blog.line("Site 创建成功: key=" + key + ", name=" + name);
                        Blog.line("[Site] api=" + summarizeForLog(api) + ", ext=" + summarizeForLog(ext) + ", jar=" + summarizeForLog(jar));
                        // v41b: 免验证 —— provenApi(探针结论)不再适用, 直接用注册形态;
                        selfTestApi(api, content, name, siteObj, path);   // 9978 /file/ 可达性自检(毫秒级, 仅日志结论), 不回改 api
                        Blog.line("[Site] 自检完成");
                    } catch (Throwable t) {
                        Blog.line("Site 创建失败", t);
                    }
                }
                boolean registered = false;
                if (siteObj != null && site != null) {
                    // 新版 FongMi: 站源列表由 bean.Site 静态方法管理(findAll/saveSettings), 方法名未被混淆
                    registered = addSiteViaStaticSite(site, siteObj, key);
                    if (registered) {
                        Blog.line(">>> 站源已加入列表(Site.findAll/saveSettings): " + name);
                        syncViaLoader(activity, site, siteObj, key);   // 内存层同步+Home切换(保持壳内存状态一致)
                        // v28 定调(用户 + 开源上游实锤): 注册 + 壳原生 setHome(Site) 等价配方
                        // (x() 切站 + RefreshEvent.home() 一次广播), 之后首页/分类/列表/
                        // 详情/播放全流程由壳自动完成 —— 不做任何重试/兜底/直提任务。
                        Blog.line(">>> 站源已注册并按壳原生配方切换 —— 壳自动加载[" + name + "]首页");
                    } else {
                        // v41: CfgFinder 兜底已砍 —— 静态注册不可用即放弃(主路径反射注册不受影响)
                        Blog.line(">>> Site 静态注册不可用, 放弃注册(CfgFinder 兜底已砍)");
                    }
                }
                // v9/v28: 不做任何 Activity 跳转 —— 切站与首页刷新由壳原生配方完成
                // (syncViaLoader 的 x() + postHomeRefresh 的一次 RefreshEvent.home())。
                // v8 事故: 跳转调用漏删, Intent 直启播放页抢走了首页刷新。
                if (!registered) {
                    Blog.line(">>> 站源未入列表(手动进入或查看上方失败原因)");
                }
                Blog.line(">>> 脚本加载完成: " + name + " registered=" + registered + " delegate=" + (delegateSpider != null));
                Blog.end();
                return;
            }
            Blog.line("宿主 Loader 存在, 走原有逻辑");

            Object loaderObj = invokeStatic(loader, "get");
            if (loaderObj == null) throw new Exception("Loader.get() 失败");
            Blog.line("Loader.get() 成功: " + loaderObj.getClass().getName());

            String jar = config != null ? getCurrentJar(config) : "";

            Object siteObj = null;
            if (site != null) {
                try {
                    siteObj = createSite(site, key, name, api, ext, jar);
                    Blog.line("Site 创建成功: key=" + key + ", name=" + name);
                } catch (Throwable t) {
                    Log.e(TAG, "创建 Site 失败", t);
                    Blog.line("Site 创建失败", t);
                }
            } else {
                Blog.line("Site 类不存在, 跳过站点创建");
            }

            // 优先取得宿主 Spider(代理调用链); 失败不致命, 站点注册成功即可用
            Object spider = null;
            try {
                spider = invokeGetSpider(loaderObj, siteObj, key, api, ext, jar);
                Blog.line("宿主 Spider 获取: " + (spider == null ? "null" : spider.getClass().getName()));
                // v31: py 形态自适应(未混淆壳走此分支, 原名类可反射到壳原生 BaseLoader)。
                // 壳 app.py 版本分裂: 新版 http 下载(URL 形态可用), 旧版把非 http 的 api
                // 字符串当脚本内容写文件(内容形态可用, URL 必炸 -> getSpider 内 catch 返
                // 回 SpiderNull)。URL 形态得 SpiderNull 时换「正文+尾注」形态重试;
                // PyLoader.computeIfAbsent 已把 SpiderNull 缓存在旧 key 上, 必须换 key 防污染。
                if (isSpiderNull(spider) && !isJs) {
                    String form2 = content;
                    if (!form2.endsWith("\n")) form2 = form2 + "\n";
                    form2 = form2 + "# " + name;
                    String key2 = key + "_c";
                    Object sp2 = null;
                    try { sp2 = invokeGetSpider(loaderObj, siteObj, key2, form2, ext, jar); } catch (Throwable t1) { Blog.line("[v31] 内容形态 getSpider 异常", t1); }
                    Blog.line("[v31] py URL 形态得 SpiderNull, 内容形态重试: " + (sp2 == null ? "null" : sp2.getClass().getName()));
                    if (!isSpiderNull(sp2)) {
                        spider = sp2;
                        api = form2;
                        key = key2;
                        // 站点对象同步为新形态(注册/setHome/setRecent 全用新值)
                        try { invoke(siteObj, "setKey", new Class<?>[]{String.class}, key); } catch (Throwable ignored) {}
                        try { invoke(siteObj, "setApi", new Class<?>[]{String.class}, api); } catch (Throwable ignored) {}
                        Blog.line("[v31] py 采用内容形态注册: key=" + key + ", api 长度=" + api.length());
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "获取宿主 Spider 失败(将尝试仅注册站点)", t);
                Blog.line("获取宿主 Spider 异常", t);
            }
            if (spider != null && !isSpiderNull(spider)) delegateSpider = spider;

            // 注册到首页站点列表: 尽力而为, 找不到 VodConfig/Site 或注册失败时脚本仍可作为代理爬虫使用
            boolean registered = false;
            if (config != null && siteObj != null) {
                callSetRecent(loaderObj, site, siteObj, key, api, ext, path);
                try {
                    addSite(config, siteObj, key);
                    registered = true;
                    // setHome 内含 RefreshEvent.home() —— 这是界面刷新的真正触发点;
                    // 同时把已加载脚本设为首页站点, 让 推荐(首页)直接呈现脚本真实内容,
                    // 取代 buildDefaultHome() 的"悬浮脚本"占位分类(避免占位重复/不刷新).
                    try { setHome(config, siteObj); } catch (Throwable t) { Log.e(TAG, "setHome 失败(不影响站点已入列表)", t); }
                } catch (Throwable t) { Log.e(TAG, "站点注册失败(不影响脚本加载)", t); }
            }

            if (spider == null && !registered) {
                throw new Exception("无法装载脚本: 未取得宿主 Spider 且站点注册失败");
            }

            Blog.line("站点注册: " + registered);
            Blog.line("=== 加载成功: " + name + " ===");
            Log.i(TAG, "脚本加载成功: " + name + " registered=" + registered + " delegate=" + (spider != null));
            Blog.end();
        } catch (Throwable e) {
            Blog.line("=== 加载失败 ===", e);
            Log.e(TAG, "脚本加载失败", e);
            // v41b: toast 经 main 投递 —— 本方法现在跑在后台线程, Toast.show 需 Looper
            new Handler(Looper.getMainLooper()).post(new FailToastTask(activity, "加载失败:\n" + describe(e)));
            Blog.end();
        }
    }

    // 计算文件相对于 extPaths 目录的相对路径
    private String getRelativePath(String absPath) {
        for (String base : extPaths) {
            try {
                File baseFile = new File(base);
                String baseCanonical = baseFile.getCanonicalPath();
                File absFile = new File(absPath);
                String absCanonical = absFile.getCanonicalPath();
                if (absCanonical.startsWith(baseCanonical + File.separator)) {
                    return absCanonical.substring(baseCanonical.length() + 1);
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // 计算文件相对于 /sdcard 的路径 (用于 shell HTTP 服务器)
    private String getSdcardRelativePath(String absPath) {
        try {
            File absFile = new File(absPath);
            String absCanonical = absFile.getCanonicalPath();
            // 支持 /sdcard, /storage/emulated/0 等常见路径前缀
            String[] prefixes = {
                "/sdcard", "/storage/emulated/0", "/mnt/sdcard", "/sdcard/", "/storage/emulated/0/"
            };
            for (String prefix : prefixes) {
                if (absCanonical.startsWith(prefix)) {
                    String suffix = absCanonical.substring(prefix.length());
                    if (suffix.startsWith("/") || suffix.startsWith(File.separator)) {
                        suffix = suffix.substring(1);
                    }
                    return suffix;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // v30: apiOf/apiComment 已删除 —— "正文+尾注"形态是 py 探针 Errno 36 根因,
    // py 站 api 现与 JS 站同构(http://127.0.0.1:9978/file/...), 由壳 app.py 下载落盘。

    private File sanitizeScript(File src) {
        try {
            if (src == null || !src.isFile()) return src;
            String lower = src.getName().toLowerCase();
            if (!lower.endsWith(".py") && !lower.endsWith(".js")) return src;
            byte[] raw = readAll(src);
            if (raw.length == 0) return src;
            byte[] clean = cleanBytes(raw);
            if (clean == raw) return src;
            File dir = new File(context.getCacheDir(), "floatspider_sanitized");
            if (!dir.exists() && !dir.mkdirs()) return src;
            String base = src.getName().replaceAll("[^A-Za-z0-9._-]", "_");
            File out = new File(dir, md5(new String(clean, "UTF-8")) + "_" + base);
            if (!out.exists()) {
                FileOutputStream fos = new FileOutputStream(out);
                try { fos.write(clean); } finally { fos.close(); }
            }
            return out;
        } catch (Throwable t) { Log.e(TAG, "脚本预处理失败, 回退原文件", t); return src; }
    }

    private static byte[] cleanBytes(byte[] raw) {
        int start = 0;
        if (raw.length >= 2) {
            boolean be = (raw[0] & 0xFF) == 0xFE && (raw[1] & 0xFF) == 0xFF;
            boolean le = (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE;
            if (be || le) {
                try {
                    String s = new String(raw, 2, raw.length - 2, be ? "UTF-16BE" : "UTF-16LE");
                    return s.getBytes("UTF-8");
                } catch (Throwable ignored) { start = 2; }
            }
        }
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) start = 3;
        int p = start;
        while (p < raw.length && raw[p] == 0) p++;
        if (p == 0) return raw;
        byte[] out = new byte[raw.length - p];
        System.arraycopy(raw, p, out, 0, out.length);
        return out;
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, (int) f.length()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally { in.close(); }
    }

    private static String readText(File f) {
        try {
            byte[] raw = readAll(f);
            if (raw.length == 0) return null;
            return new String(raw, "UTF-8");
        } catch (Throwable t) { Log.e(TAG, "读取脚本内容失败: " + f, t); return null; }
    }

    // ===================== 宿主类定位(不写死壳包名) =====================

    private static String findHostRoot(Activity a) {
        if (hostRoot != null) return hostRoot;
        try {
            String n = a.getClass().getName();
            int p = n.indexOf(".ui.activity.");
            if (p < 0) p = n.indexOf(".activity.");
            if (p > 0) { hostRoot = n.substring(0, p); return hostRoot; }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Class<?> findBaseLoader(ClassLoader cl, String root) {
        if (baseLoaderClass != null) return baseLoaderClass;
        baseLoaderClass = findClass(cl, root, new String[]{"api.loader.BaseLoader","loader.BaseLoader","api.BaseLoader","BaseLoader"});
        return baseLoaderClass;
    }

    private static Class<?> findSiteClass(ClassLoader cl, String root) {
        if (siteClass != null) return siteClass;
        siteClass = findClass(cl, root, new String[]{"bean.Site","model.Site","bean.site.Site","api.Site","Site"});
        return siteClass;
    }

    private static Class<?> findVodConfig(ClassLoader cl, String root) {
        if (vodConfigClass != null) return vodConfigClass;
        String[] bases = (root == null ? new String[]{CANONICAL_ROOT} : new String[]{root, CANONICAL_ROOT});
        String[] pkgs = {"api.config","config","bean","api",""};
        String[] names = {"VodConfig","Config"};
        for (String base : bases) {
            for (String pkg : pkgs) {
                for (String nm : names) {
                    String fq = (pkg.isEmpty() ? base : base + "." + pkg) + "." + nm;
                    Class<?> c = loadHostClass(cl, fq);
                    if (c != null) { vodConfigClass = c; return c; }
                }
            }
        }
        return null;
    }

    // 按候选相对路径在多个根包下尝试加载类(兼容壳改包名)
    private static Class<?> findClass(ClassLoader cl, String root, String[] relative) {
        String[] bases = (root == null ? new String[]{CANONICAL_ROOT} : new String[]{root, CANONICAL_ROOT});
        for (String base : bases) {
            for (String rel : relative) {
                Class<?> c = loadHostClass(cl, base + "." + rel);
                if (c != null) return c;
            }
        }
        return null;
    }

    // 跨类加载器链解析宿主类: 兼容"包装加载器把宿主类放在父加载器、且自身不向上委托"的魔改壳
    private static Class<?> loadHostClass(ClassLoader preferred, String name) {
        if (name == null || name.isEmpty()) return null;
        List<ClassLoader> loaders = new ArrayList<>();
        addChain(preferred, loaders);
        addChain(Thread.currentThread().getContextClassLoader(), loaders);
        if (baseLoaderClass != null) addChain(baseLoaderClass.getClassLoader(), loaders);
        for (ClassLoader l : loaders) {
            try { return Class.forName(name, false, l); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static void addChain(ClassLoader cl, List<ClassLoader> out) {
        ClassLoader cur = cl;
        while (cur != null) {
            if (!out.contains(cur)) out.add(cur);
            try { cur = cur.getParent(); } catch (Throwable ignored) { break; }
        }
    }

    // ===================== 反射工具 =====================

    private static Object invokeStatic(Class<?> c, String name, Object... args) throws Exception {
        Method m = findCompatibleMethod(c, name, args);
        if (m == null) throw new NoSuchMethodException(name);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static Object invoke(Object obj, String name, Class<?>[] types, Object... args) throws Exception {
        if (obj == null) throw new NullPointerException(name);
        Method m = findMethod(obj.getClass(), name, types);
        if (m == null) m = findCompatibleMethod(obj.getClass(), name, args);
        if (m == null) throw new NoSuchMethodException(name);
        m.setAccessible(true);
        return m.invoke(obj, args);
    }

    private static Method findCompatibleMethod(Class<?> c, String name, Object... args) {
        Class<?> x = c;
        while (x != null) {
            for (Method m : x.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
                Class<?>[] p = m.getParameterTypes();
                boolean ok = true;
                for (int i = 0; i < p.length; i++) {
                    if (args[i] == null) continue;
                    if (!wrap(p[i]).isAssignableFrom(wrap(args[i].getClass()))) { ok = false; break; }
                }
                if (ok) return m;
            }
            x = x.getSuperclass();
        }
        return null;
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class) return Byte.class;
        if (c == short.class) return Short.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        if (c == char.class) return Character.class;
        return c;
    }

    private static Field findField(Class<?> c, String name) {
        while (c != null) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (Throwable ignored) { c = c.getSuperclass(); }
        }
        return null;
    }

    // 取配置单例: 优先名为 get() 的 static 无参方法, 否则任意 static 无参且返回自身类型的实例
    private static Object getConfigInstance(Class<?> configClass) {
        try {
            for (Method m : configClass.getMethods()) {
                if (m.getName().equals("get") && Modifier.isStatic(m.getModifiers()) && m.getParameterTypes().length == 0) {
                    m.setAccessible(true);
                    return m.invoke(null);
                }
            }
            Class<?> c = configClass;
            while (c != null && c != Object.class) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0 || m.getReturnType() != configClass) continue;
                    try { m.setAccessible(true); Object v = m.invoke(null); if (v != null && configClass.isInstance(v)) return v; } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // 找站点列表: 优先 getSites(), 否则任意无参返回 List 的方法
    private static Object findSitesList(Object cfg, Class<?> siteType) {
        Method named = findMethod(cfg.getClass(), "getSites");
        if (named != null) {
            try { named.setAccessible(true); Object l = named.invoke(cfg); if (l instanceof List) return l; } catch (Throwable ignored) {}
        }
        for (Method m : cfg.getClass().getMethods()) {
            if (m.getParameterTypes().length != 0 || Modifier.isStatic(m.getModifiers()) || m.getReturnType() != List.class) continue;
            try { m.setAccessible(true); Object l = m.invoke(cfg); if (l instanceof List) return l; } catch (Throwable ignored) {}
        }
        return null;
    }

    // 多策略调用宿主 getSpider(忠于 FongMi: 标准签名为 getSpider(key,api,ext,jar) 四个 String)
    private static Object invokeGetSpider(Object loaderObj, Object siteObj, String key, String api, String ext, String jar) throws Exception {
        Method m = findMethod(loaderObj.getClass(), "getSpider", String.class, String.class, String.class, String.class);
        if (m != null) return invokeM(m, loaderObj, key, api, ext, jar);
        // 壳为 getSpider(Site) 形式
        if (siteObj != null) {
            for (Method x : loaderObj.getClass().getMethods()) {
                Class<?>[] p = x.getParameterTypes();
                if (p.length == 1 && !Modifier.isStatic(x.getModifiers()) && x.getReturnType() != void.class && p[0].isInstance(siteObj))
                    return x.invoke(loaderObj, siteObj);
            }
        }
        // R8 改名兜底: 全 String 参数、非 void 返回
        String[] fill = {key, api, ext, jar, "", "", ""};
        for (Method x : loaderObj.getClass().getMethods()) {
            Class<?>[] p = x.getParameterTypes();
            if (p.length < 3 || p.length > 6) continue;
            boolean allStr = true;
            for (Class<?> t : p) if (t != String.class) { allStr = false; break; }
            if (allStr && x.getReturnType() != void.class && !Modifier.isStatic(x.getModifiers())) {
                Object[] a = new Object[p.length];
                for (int i = 0; i < p.length; i++) a[i] = i < fill.length ? fill[i] : "";
                return x.invoke(loaderObj, a);
            }
        }
        throw new Exception("无法定位宿主 Spider 装载方法");
    }

    private static Object invokeM(Method m, Object obj, Object... args) throws Exception {
        m.setAccessible(true);
        return m.invoke(obj, args);
    }

    // v31: 判定壳 getSpider 是否返回了 SpiderNull(壳内部加载失败时的静默缓存占位,
    // 类名 SpiderNull 未被混淆)。null 与 SpiderNull 一律视为"该形态不可用"。
    private static boolean isSpiderNull(Object sp) {
        if (sp == null) return true;
        try {
            return sp.getClass().getName().contains("SpiderNull");
        } catch (Throwable t) {
            return false;
        }
    }

    private static void callSetRecent(Object loader, Class<?> siteClass, Object siteObj, String key, String api, String ext, String path) {
        try { invoke(loader, "setRecent", new Class<?>[]{String.class,String.class,String.class}, key, api, path); return; } catch (Throwable ignored) {}
        try { invoke(loader, "setRecent", new Class<?>[]{String.class,String.class,String.class,String.class}, key, api, ext, path); return; } catch (Throwable ignored) {}
        try { if (siteObj != null) invoke(loader, "setRecent", new Class<?>[]{siteClass}, siteObj); } catch (Throwable ignored) {}
    }

    // 构造站点对象并填入脚本信息(type=3 表示 spider 站点)
    private static Object createSite(Class<?> siteClass, String key, String name, String api, String ext, String jar) throws Exception {
        Object site = null;
        try { Method m = siteClass.getMethod("get", String.class, String.class); site = m.invoke(null, key, name); } catch (Throwable ignored) {}
        if (site == null) {
            Constructor<?> c = siteClass.getDeclaredConstructor();
            c.setAccessible(true);
            site = c.newInstance();
            invoke(site, "setKey", new Class<?>[]{String.class}, key);
            invoke(site, "setName", new Class<?>[]{String.class}, name);
        }
        invoke(site, "setApi", new Class<?>[]{String.class}, api);
        try { invoke(site, "setExt", new Class<?>[]{String.class}, ext); } catch (Throwable ignored) {}
        try { invoke(site, "setJar", new Class<?>[]{String.class}, jar); } catch (Throwable ignored) {}
        // v29 根因修复: 壳判断"是否走爬虫 Spider 链"的唯一开关是 site.getType()==3
        // (壳内 wn3.y(Site) 实锤: 仅当 type==3 时 fh3/ih3 等包装类才调 site.spider(),
        //  否则把 api 当普通 web 接口拉取解析 → 界面空白、JS 引擎零活动)。
        // 壳版 Site 无 setType 方法(smali setter 清单实锤), v28 的 setType 反射一直静默失败,
        // type 从未写入 → 改为字段反射兜底, 并回读 getType() 验证。
        boolean typeOk = false;
        try { invoke(site, "setType", new Class<?>[]{int.class}, 3); typeOk = true; } catch (Throwable ignored) {}
        if (!typeOk) {
            try { setIntField(site, "type", 3); typeOk = true; } catch (Throwable ignored) {}
        }
        Object typeNow = null;
        try { typeNow = invoke(site, "getType", new Class<?>[]{}); } catch (Throwable ignored) {}
        Blog.line("[v31] type=3 写入" + (typeOk ? "成功" : "失败") + ", 回读 getType()=" + typeNow + " (壳走 Spider 链开关)");
        try { invoke(site, "setSearchable", new Class<?>[]{int.class}, 1); } catch (Throwable ignored) {}
        try { invoke(site, "setChangeable", new Class<?>[]{int.class}, 1); } catch (Throwable ignored) {}
        return site;
    }

    // v29: 按名反射写 int/Integer 字段(沿父类链查找); 壳对 Room/Gson 实体字段保留原名(type 实锤未混淆)
    private static void setIntField(Object obj, String name, int value) throws Exception {
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, Integer.valueOf(value));
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /**
     * 新版 FongMi 注册路径: 站源列表由 bean.Site 静态方法管理。
     * Site.findAll():List      —— 取全部站点(静态)
     * Site.saveSettings(List)  —— 持久化(静态)
     * site.setSelected(boolean)—— 标记首页/当前站点
     * 全部是 keep 后的真实方法名, 直接按名反射, 失败返回 false 走旧路径。
     */
    private static boolean addSiteViaStaticSite(Class<?> siteCls, Object site, String key) {
        try {
            Method findAll = siteCls.getMethod("findAll");
            if (!Modifier.isStatic(findAll.getModifiers())) return false;
            Object list = findAll.invoke(null);
            if (!(list instanceof List)) return false;
            List sites = (List) list;
            Blog.line("[SiteReg] findAll 返回 " + sites.size() + " 个站点");
            // 移除同 key 旧项
            for (Object old : new ArrayList<>(sites)) {
                try {
                    if (key.equals(String.valueOf(invoke(old, "getKey", new Class<?>[]{})))) {
                        sites.remove(old);
                        Blog.line("[SiteReg] 移除旧站源: " + key);
                        break;
                    }
                } catch (Throwable ignored) {}
            }
            // 其它站点取消选中, 新站源标记为当前/首页
            // v32: 老版壳 Site 无 setSelected(有 setActivated), 两个命名都试
            for (Object old : sites) {
                try { invoke(old, "setSelected", new Class<?>[]{boolean.class}, Boolean.FALSE); } catch (Throwable ignored) {}
                try { invoke(old, "setActivated", new Class<?>[]{boolean.class}, Boolean.FALSE); } catch (Throwable ignored) {}
            }
            boolean marked = false;
            try { invoke(site, "setSelected", new Class<?>[]{boolean.class}, Boolean.TRUE); marked = true; } catch (Throwable ignored) {}
            if (!marked) { try { invoke(site, "setActivated", new Class<?>[]{boolean.class}, Boolean.TRUE); marked = true; } catch (Throwable ignored) {} }
            Blog.line("[SiteReg] 本站选中标记(" + (marked ? "成功" : "失败") + ")");
            sites.add(site);
            // v32 持久化: 新版 saveSettings(List) -> 老版 Site.save() 单条 -> 均失败才 return false
            // (v31 及之前: saveSettings 失败仍 return true —— 老版壳上内存 add 无效, 是假成功)
            boolean saved = false;
            try {
                Method save = siteCls.getMethod("saveSettings", List.class);
                if (Modifier.isStatic(save.getModifiers())) save.invoke(null, list);
                else save.invoke(site, list);
                saved = true;
                Blog.line("[SiteReg] saveSettings 完成");
            } catch (Throwable t) {
                Blog.line("[SiteReg] saveSettings 不可用(" + t.getClass().getSimpleName() + "), 试老版 Site.save() 单条持久化");
            }
            if (!saved) {
                // 老版 bean.Site: public void save() —— 单条 insertOrUpdate, findAll 从 DB 回读可见
                try {
                    Method save = siteCls.getMethod("save");
                    save.setAccessible(true);
                    save.invoke(site);
                    saved = true;
                    Blog.line("[SiteReg] Site.save() 完成(老版单条持久化)");
                } catch (Throwable t) {
                    Blog.line("[SiteReg] Site.save() 也失败: " + t);
                }
            }
            if (!saved) {
                Blog.line("[SiteReg] 无可用持久化方法, 放弃静态注册");
                return false;
            }
            // 回读验证(仅诊断, 不决定返回值 —— 防破坏 v31 已验证壳的路径)
            try {
                Object check = findAll.invoke(null);
                if (check instanceof List) {
                    boolean found = false;
                    for (Object o : (List) check) {
                        try { if (key.equals(String.valueOf(invoke(o, "getKey", new Class<?>[]{})))) { found = true; break; } } catch (Throwable ignored) {}
                    }
                    Blog.line("[SiteReg] 回读验证: findAll=" + ((List) check).size() + " 个, 含本站=" + found);
                }
            } catch (Throwable ignored) {}
            return true;
        } catch (Throwable t) {
            Blog.line("[SiteReg] 静态注册异常: " + t);
            return false;
        }
    }

    /**
     * 内存层同步(增强, 任何失败都不影响已完成的 DB 注册):
     * 反编译 ub4.java 实锤新版壳结构:
     *   ub4 extends fi; 单例在 tb4.a(静态字段); v() 返回内存站源列表; b() 返回 Config.vod();
     *   x(Config, Site, boolean z) { e=site; site.setSelected(true); config.setHome(site.getKey()); if(z) config.save(); }
     * 流程: 找管理器类 -> 取单例 -> 站点塞进 v() 列表 -> 调 x() 切 Home(不落库, 与壳加载配置时的 z=false 一致)。
     */
    private static void syncViaLoader(Activity activity, Class<?> siteCls, Object site, String key) {
        try {
            // v41: 管理器多候选硬编码 —— t3.i(壳三) -> ub4(壳一), 替代 CfgFinder 全 dex 扫描
            Class<?> loaderCls = null;
            for (String n : new String[]{"t3.i", "ub4"}) {
                try { loaderCls = Class.forName(n, false, activity.getClassLoader()); break; } catch (Throwable t) { /* 下一个 */ }
            }
            if (loaderCls == null) { Blog.line("[LoaderSync] 未找到站源管理器(t3.i/ub4 硬编码均未中, 跳过内存同步)"); return; }
            Blog.line("[LoaderSync] 站源管理器: " + loaderCls.getName());
            Object loader = staticInstanceOf(loaderCls, activity);
            if (loader == null) { Blog.line("[LoaderSync] 单例未获取(跳过内存同步)"); return; }
            Blog.line("[LoaderSync] 单例已获取");
            // v(): 无参返回 List 且元素为 Site 的方法(与 s():List<Parse> 用元素类型区分)
            Method listGetter = null, emptyFallback = null;
            for (Method m : loaderCls.getDeclaredMethods()) {
                try {
                    if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0) continue;
                    if (m.getReturnType() != List.class) continue;
                    m.setAccessible(true);
                    Object lv = m.invoke(loader);
                    if (!(lv instanceof List)) continue;
                    List l = (List) lv;
                    boolean hasSite = false, hasOther = false;
                    for (Object o : l) {
                        if (o == null) continue;
                        if (siteCls.isInstance(o)) hasSite = true; else hasOther = true;
                    }
                    if (hasSite && !hasOther) { listGetter = m; break; }
                    if (l.isEmpty() && emptyFallback == null) emptyFallback = m;
                } catch (Throwable t) { /* 下一个 */ }
            }
            if (listGetter == null) listGetter = emptyFallback;   // 都为空时取首个备选但不 add
            if (listGetter != null) {
                try {
                    listGetter.setAccessible(true);
                    List l = (List) listGetter.invoke(loader);
                    boolean exists = false;
                    for (Object o : l) {
                        try {
                            if (siteCls.isInstance(o) && key.equals(String.valueOf(invoke(o, "getKey", new Class<?>[]{})))) { exists = true; break; }
                        } catch (Throwable ignored) {}
                    }
                    if (exists) {
                        Blog.line("[LoaderSync] 内存列表已含本站(" + listGetter.getName() + ")");
                    } else if (!l.isEmpty()) {
                        l.add(site);
                        Blog.line("[LoaderSync] 已加入内存列表(" + listGetter.getName() + "): 共 " + l.size() + " 项");
                    } else {
                        Blog.line("[LoaderSync] 内存列表为空, 不 add(防误入解析列表), 继续 Home 切换");
                    }
                } catch (Throwable t) {
                    Blog.line("[LoaderSync] 内存列表更新失败: " + t);
                }
            } else {
                Blog.line("[LoaderSync] 未识别内存列表 getter");
            }
            // b(): 无参返回 bean.Config 的方法
            Object cfgObj = null;
            for (Method m : loaderCls.getDeclaredMethods()) {
                try {
                    if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0) continue;
                    if (!m.getReturnType().getName().endsWith(".Config")) continue;
                    m.setAccessible(true);
                    cfgObj = m.invoke(loader);
                    if (cfgObj != null) break;
                } catch (Throwable t) { /* 下一个 */ }
            }
            // v32 切站四级降级(逐级尝试, 命中即止; 每级结果全部落日志):
            // L1 setHome(Site)/L2 setHome(String): 上游原生入口, 内部自带 RefreshEvent.home() —— 免广播;
            // L3 x(Config,Site,boolean) 签名特征(名字无关, 混淆不影响) + 广播;
            // L4 Config.setHome(key)+save(bean 层 keep 名字, 日志实锤 bean 方法未混淆) + 广播;
            // L5 都不可用: 仅广播 —— 站点已落库(SiteReg v32 回退), 壳刷新后站源列表可见可手动切。
            Method homeSetter = null;
            boolean homeSetterKey = false;
            for (Method m : loaderCls.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (Modifier.isStatic(m.getModifiers()) || p.length != 1 || m.getReturnType() != void.class) continue;
                if (!"setHome".equals(m.getName())) continue;
                if (p[0] == siteCls) { homeSetter = m; homeSetterKey = false; break; }
                if (p[0] == String.class && homeSetter == null) { homeSetter = m; homeSetterKey = true; }
            }
            if (homeSetter != null) {
                try {
                    homeSetter.setAccessible(true);
                    homeSetter.invoke(loader, homeSetterKey ? key : site);
                    Blog.line("[LoaderSync] L" + (homeSetterKey ? "2" : "1") + " 完成: setHome(" + (homeSetterKey ? "key" : "Site") + ") 调用成功(上游原生, 自带广播)");
                    return;
                } catch (Throwable t) {
                    Blog.line("[LoaderSync] L" + (homeSetterKey ? "2" : "1") + " setHome 调用失败: " + t + ", 降下一级");
                }
            }
            // x(Config, Site, boolean): Home 切换
            Method homeSwitch = null;
            for (Method m : loaderCls.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (Modifier.isStatic(m.getModifiers()) || p.length != 3) continue;
                if (p[1] != siteCls || p[2] != boolean.class) continue;
                if (!p[0].getName().endsWith(".Config")) continue;
                homeSwitch = m; break;
            }
            if (homeSwitch != null && cfgObj != null) {
                try {
                    homeSwitch.setAccessible(true);
                    // z=true 对齐壳原生切站(用户点站源=config.save() 持久保存):
                    // 若首页事件响应从持久层读 home key, z=false 会读到旧站导致首页不加载本站
                    homeSwitch.invoke(loader, cfgObj, site, Boolean.TRUE);
                    Blog.line("[LoaderSync] L3 完成: Home 切换(" + homeSwitch.getName() + ", z=true 已持久化)");
                    refreshHome(activity, site);
                    return;
                } catch (Throwable t) {
                    Blog.line("[LoaderSync] L3 Home 切换失败: " + t + ", 降下一级");
                }
            }
            // v32 L4: 老版壳兜底 —— 管理器无 setHome/x 特征时, 直改 bean 层 Config.home(bean 方法名 keep 保留)
            if (cfgObj != null) {
                try {
                    invoke(cfgObj, "setHome", new Class<?>[]{String.class}, key);
                    Blog.line("[LoaderSync] L4a: Config.setHome(key) 直改完成");
                    try { invoke(cfgObj, "save", new Class<?>[]{}); Blog.line("[LoaderSync] L4b: Config.save() 落库完成"); }
                    catch (Throwable t2) { Blog.line("[LoaderSync] L4b: Config.save() 不可用(仅内存): " + t2.getClass().getSimpleName()); }
                    refreshHome(activity, site);
                    return;
                } catch (Throwable t) {
                    Blog.line("[LoaderSync] L4 Config.setHome 失败: " + t + ", 降下一级");
                }
            } else {
                Blog.line("[LoaderSync] L3/L4 前置缺失: homeSwitch=" + (homeSwitch == null ? "无" : homeSwitch.getName()) + ", cfgObj=null");
            }
            // v32 L5: 仅广播 —— 站点已在 DB(SiteReg save 回退), 壳收到 HOME 事件重载配置后站源列表可见
            Blog.line("[LoaderSync] L5: 仅广播(站点已落库, 壳重载配置后可在站源列表手动切换)");
            postHomeRefresh();
        } catch (Throwable t) {
            Blog.line("[LoaderSync] 内存同步失败(不影响站源注册)", t);
        }
    }

    /**
     * v28: 壳原生切站配方最后一步(开源上游实锤, FongMi/TV api/config/VodConfig.java):
     *   public void setHome(Site site) { setHome(getConfig(), site, true); RefreshEvent.home(); }
     * 壳内 R8 已把该方法内联进站源点击处理(gd3.f), 逐字节等价序列:
     *   ub4.x(config, site, true) -> dt0.b().e(new c23(1))
     * c23(1) 即 RefreshEvent.home()(Type.HOME 无载荷; bl_1 壳自产事件 a=1 b=null c=null 同款)。
     * 前半步 x() 由 syncViaLoader 完成, 本方法只补最后一步广播: 一次, 无重试无兜底。
     * 之后首页加载(homeContent -> 分类 tab -> 第一个分类列表)全部由壳自动执行。
     * v32: EventBus/事件类定位改为特征优先(CfgFinder dex 扫描顺带收集: 静态单例+post(Object) 特征;
     * @Subscribe 注解参数=壳真事件类), dt0/c23 硬编码仅作第一壳兼容兜底 —— 新壳混淆短名每版必变。
     * v35: 定位逻辑提取为 locateBroadcast()(同步检查); syncViaLoader 据此决定走广播还是
     * 启动壳详情页 —— 第三壳实测无广播机制; 日志实锤手动切站=i.t(落数据)+启动详情页(壳原生加载),
     * recreate 只重建 Activity 不重读壳运行态单例, 已废弃。
     */
    private static void postHomeRefresh() {
        try {
            // 与壳原生同线程(main)投递; greenrobot EventBus 从 main post 时同步派发
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new HomeRefreshTask());
        } catch (Throwable t) {
            Blog.lineForce("[" + VER + "] RefreshEvent.home 调度失败: " + t);
        }
    }

    /**
     * v35 定稿: 切站最后一步统一入口。壳 dex 静态解析 + 四轮实测实锤:
     *   ① 壳原生站源点击 handler S3/G.j(Site) 反汇编 = t3.a.d() 取 Config
     *      + t3.i.t(config,site,true) 落数据 + N6.d.b().e(new z3.h(1)) 广播;
     *   ② z3.h 字段 {int a, String b, Vod c}, a=int type —— 手动切站日志实锤
     *      type=1(HOME) 广播后首页 fragment 收到 -> pool 线程 homeContent -> 分类渲染;
     *   ③ z3.h.a() 便捷方法 = N6.d.b().e(new z3.h(3)) —— type=3 不是 HOME,
     *      实测仅触发壳内部重切站, 不触发 homeContent(v35 首版踩坑, 已废);
     *   ④ t3.i.t 方法体 = 纯落数据(setActivated+setHome+save), 不提交加载。
     * 最终配方: i.t 落库(已有) + 反射构造 new z3.h(1) + N6.d.b().e(evt)
     *   —— 与壳点击站源最后一步逐字节等价。
     * 广播可用(第一/二壳 greenrobot)仍走原配方 postHomeRefresh()。
     */
    private static void refreshHome(Activity activity, Object site) {
        if (locateBroadcast() != null) {
            Blog.line("[LoaderSync] 广播三件套可用, 走 RefreshEvent.home 原配方");
            postHomeRefresh();
            return;
        }
        if (postShellRefresh()) return;
        Blog.line("[LoaderSync] 壳刷新事件不可用(站源已落库, 站单手动切换或重启壳生效)");
    }

    /**
     * v35 定稿: 复刻壳原生点击 handler 最后一步 —— new z3.h(1) + N6.d.b().e(evt)。
     * 事件类定位: CfgFinder.refreshEvtCls 特征(I+String+Vod 三件套)优先, z3.h 硬编码兜底。
     * 总线定位: locateShellBus()(N6.d 硬编码 + CfgFinder.shellBusCls 特征兜底)。
     * type 实锤: S3/G.j(Site) 反汇编 const/4 v2,1 = new z3.h(1) —— 1=HOME, 手动切站
     * 同款; z3.h.a() 用 type=3(实测无效, 首版踩坑)。因此必须自建事件而非调 a()。
     */
    private static boolean postShellRefresh() {
        try {
            // v41: 事件类纯硬编码(z3.h, 当前壳 dex 实锤), 替代 CfgFinder.refreshEvtCls 特征
            Class<?> evt;
            try { evt = Class.forName("z3.h"); } catch (Throwable ignored) { evt = null; }
            if (evt == null) { Blog.line("[LoaderSync] 壳 RefreshEvent 类未命中(z3.h 硬编码未中)"); return false; }
            Object[] bp = locateShellBus();
            if (bp == null) { Blog.line("[LoaderSync] 壳事件总线未命中(N6.d 硬编码未中)"); return false; }
            Object evtObj = null;
            for (Constructor<?> k : evt.getDeclaredConstructors()) {
                Class<?>[] p = k.getParameterTypes();
                if (p.length == 1 && p[0] == int.class) {
                    k.setAccessible(true);
                    evtObj = k.newInstance(Integer.valueOf(1));   // 1=HOME, S3/G.j dex 实锤
                    break;
                }
            }
            if (evtObj == null) { Blog.line("[LoaderSync] " + evt.getName() + " 无 (I) 构造器(指纹漂移?)"); return false; }
            final Object fBus = bp[0];
            final Method fPost = (Method) bp[1];
            final Object fEvt = evtObj;
            // main 线程投递: 壳事件经 main 队列派发, 与原生点击 handler 同线程
            new Handler(Looper.getMainLooper()).post(new ShellRefreshTask(fBus, fPost, fEvt));
            Blog.line("[LoaderSync] 已调度壳原生刷新广播 new " + evt.getName() + "(1) —— type=1(HOME), 与壳点击站源逐字节等价");
            return true;
        } catch (Throwable t) {
            Blog.line("[LoaderSync] 壳刷新广播异常: " + t);
            return false;
        }
    }

    /**
     * v35: 壳自研事件总线定位 —— N6.d(单例 b(), e(Object)=post)。
     * 硬编码优先: Class.forName("N6.d")(第三壳 dex 实锤); CfgFinder.shellBusCls 特征兜底。
     * 单例: static 无参返回自身类的方法(b()); post: 实例 (Object)void 方法(e(Object))。
     * 返回 {bus实例, post方法, busCls} 或 null。
     */
    private static Object[] locateShellBus() {
        try {
            // v41: 总线纯硬编码(N6.d, 当前壳 dex 实锤), 替代 CfgFinder.shellBusCls 特征
            Class<?> busCls;
            try { busCls = Class.forName("N6.d"); } catch (Throwable ignored) { busCls = null; }
            if (busCls == null) return null;
            Object bus = null;
            for (Method sm : busCls.getDeclaredMethods()) {
                if (!Modifier.isStatic(sm.getModifiers()) || sm.getParameterTypes().length != 0 || sm.getReturnType() != busCls) continue;
                sm.setAccessible(true); bus = sm.invoke(null); if (bus != null) break;
            }
            if (bus == null) { Blog.line("[LoaderSync] " + busCls.getName() + " 无 static 单例方法(指纹漂移?)"); return null; }
            Method post = null;
            for (Method pm : busCls.getDeclaredMethods()) {
                if (Modifier.isStatic(pm.getModifiers()) || pm.isSynthetic()) continue;
                Class<?>[] pp = pm.getParameterTypes();
                if (pp.length == 1 && pp[0] == Object.class && pm.getReturnType() == void.class) { post = pm; break; }
            }
            if (post == null) { Blog.line("[LoaderSync] " + busCls.getName() + " 无 (Object)void 实例方法(指纹漂移?)"); return null; }
            post.setAccessible(true);
            return new Object[]{bus, post, busCls};
        } catch (Throwable t) {
            Blog.line("[LoaderSync] 壳事件总线定位异常: " + t);
            return null;
        }
    }

    /** 命名静态内部类(铁律): main 线程执行 bus.post(evt) —— 复刻 N6.d.b().e(new z3.h(1)) */
    private static final class ShellRefreshTask implements Runnable {
        private final Object bus;
        private final Method post;
        private final Object evt;
        ShellRefreshTask(Object b, Method p, Object e) { bus = b; post = p; evt = e; }   // 显式构造器(铁律)
        @Override
        public void run() {
            try {
                post.invoke(bus, evt);
                Blog.lineForce("[" + VER + "] 已广播壳刷新事件 type=1(HOME) —— 首页 homeContent 重载由壳自动执行");
            } catch (Throwable t) {
                Blog.lineForce("[" + VER + "] 壳刷新广播执行失败(站源已落库, 手动切站生效): " + t);
            }
        }
    }

    /**
     * v35: 广播三件套同步定位(只检查不执行, 无副作用)。
     * 返回 {bus实例, post方法, 事件实例, busCls, evtCls} 或 null(任一环节缺失)。
     * 定位链: CfgFinder 特征收集 -> dt0/c23 硬编码兜底(第一壳)。
     */
    private static Object[] locateBroadcast() {
        try {
            // v41: greenrobot 总线纯硬编码(dt0, 第一/二壳 greenrobot), 替代 CfgFinder.eventBusCls 特征
            Class<?> busCls;
            try { busCls = Class.forName("dt0"); } catch (Throwable ignored) { busCls = null; }
            if (busCls == null) return null;
            Object bus = null;
            for (Method sm : busCls.getDeclaredMethods()) {
                if (!Modifier.isStatic(sm.getModifiers()) || sm.getParameterTypes().length != 0 || sm.getReturnType() != busCls) continue;
                sm.setAccessible(true); bus = sm.invoke(null); if (bus != null) break;
            }
            if (bus == null) return null;
            Method post = null;
            for (Method pm : busCls.getDeclaredMethods()) {
                if (Modifier.isStatic(pm.getModifiers()) || pm.isSynthetic()) continue;
                Class<?>[] pp = pm.getParameterTypes();
                if (pp.length == 1 && pp[0] == Object.class) {
                    if ("post".equals(pm.getName())) { post = pm; break; }   // 名字命中优先(greenrobot API)
                    if (post == null) post = pm;
                }
            }
            if (post == null) return null;
            post.setAccessible(true);
            // v41: 事件类纯硬编码(c23, 第一/二壳 greenrobot RefreshEvent), 替代 subscribeEvtCls 特征遍历
            Class<?> evtCls;
            try { evtCls = Class.forName("c23"); } catch (Throwable ignored) { evtCls = null; }
            if (evtCls == null) return null;
            Object evt = null;
            for (Constructor<?> ct : evtCls.getDeclaredConstructors()) {
                Class<?>[] pt = ct.getParameterTypes();
                if (pt.length == 1 && pt[0] == int.class) { ct.setAccessible(true); evt = ct.newInstance(Integer.valueOf(1)); break; }
            }
            if (evt == null) return null;
            return new Object[]{bus, post, evt, busCls, evtCls};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 命名静态内部类(铁律): main 线程执行 EventBus.post(RefreshEvent.home()) —— 壳原生切站广播
     *  v35: 定位复用 locateBroadcast(), 日志标注来源(特征/硬编码) */
    private static final class HomeRefreshTask implements Runnable {
        HomeRefreshTask() { }   // 显式构造器(铁律): 防 javac 生成合成桥编号类
        @Override
        public void run() {
            try {
                Object[] bc = locateBroadcast();
                if (bc == null) throw new Exception("EventBus/事件类未定位(dt0/c23 硬编码未命中)");
                Method post = (Method) bc[1];
                post.invoke(bc[0], bc[2]);
                Blog.lineForce("[" + VER + "] 已广播 RefreshEvent.home(): bus=" + ((Class<?>) bc[3]).getName() + "(dt0硬编码)"
                        + ", evt=" + ((Class<?>) bc[4]).getName() + "(c23硬编码)"
                        + " —— 首页加载全流程由壳自动执行");
            } catch (Throwable t) {
                Blog.lineForce("[" + VER + "] RefreshEvent.home 广播失败(不影响站源注册): " + t);
            }
        }
    }

    /** 取管理器单例: 先看自身 static 字段, 再取持有者 static 字段(v41 多候选硬编码: t3.h 壳三 / tb4 壳一) */
    private static Object staticInstanceOf(Class<?> loaderCls, Activity activity) {
        Object self = staticFieldOf(loaderCls, loaderCls);
        if (self != null) return self;
        for (String n : new String[]{"t3.h", "tb4"}) {
            try {
                Class<?> holderCls = Class.forName(n, false, activity.getClassLoader());
                Object r = staticFieldOf(holderCls, loaderCls);
                if (r != null) { Blog.line("[LoaderSync] 单例持有者: " + holderCls.getName()); return r; }
            } catch (Throwable t) { /* 下一个候选 */ }
        }
        Blog.line("[LoaderSync] 单例未获取(t3.h/tb4 均未中)");
        return null;
    }

    /** 类内 static 且类型匹配的字段取值(非 static 或不匹配返回 null) */
    private static Object staticFieldOf(Class<?> c, Class<?> type) {
        try {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType() != type || !Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(null);
                if (v != null && type.isInstance(v)) return v;
            }
        } catch (Throwable t) { /* 忽略 */ }
        return null;
    }

    /** 弹 Toast(监视开启/停止提示), v18 监视器与补刷共用 */
    private static void monToast(Activity a, String msg) {
        try { android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
    }

    /** 值摘要: null / 类名 / toString 截断 */
    private static String brief(Object v, int max) {
        if (v == null) return "null";
        String s = String.valueOf(v);
        if (s.length() > max) s = s.substring(0, max) + "...";
        return s;
    }

    static final String VER = "v41";   // 版本号: v41 = v35(完整日志) + 点击立即关弹窗 + 硬编码(多候选 t3.i/ub4·t3.h/tb4) + 加载挪后台(py 冷启动不卡 UI)

    // ---- v28 设计原则(用户定调 + 开源上游实锤): 注册后调壳原生 setHome(Site) 等价配方,
    //      之后首页/分类/列表/详情/播放全流程由壳自动完成, 我们不做任何重试/兜底/直提任务。
    //      上游源码(FongMi/TV) api/config/VodConfig.java:
    //        public void setHome(Site site) { setHome(getConfig(), site, true); RefreshEvent.home(); }
    //      壳内 R8 已把该方法内联进站源点击处理(gd3.f), 逐字节等价:
    //        ub4.x(config, site, true) -> dt0.b().e(new c23(1))
    //      v26 败因 = 广播后 1.5s/4s 反复重播+直提任务, 打断壳正在进行的自动加载;
    //      v27 败因 = 连这一次原生广播也删了, 切站后首页不刷新。
    //      v28 = 只保留这唯一一次原生广播。 ----
    private static volatile int sStackDumps = 0;      // th0.L 调用栈 dump 限流

    // ==================== v18 方法调用级监视器(ART hook) ====================
    // 用途: 点 monitor.js 开/关。基于 canyie/pine 0.3.0(ART 运行时方法 hook, 纯
    // 用户态无需 root)在壳进程内拦截 Java 方法调用, 每次调用的 类.方法(参数) =>
    // 返回值/异常 实时落盘 /storage/emulated/0/bl.log —— 原生切站/跑爬虫的真实
    // 调用链逐条可见, 被混淆的接口(ub4/dt0/混淆 viewModel...)从此有名字有参数。
    // 依赖: libpine.so 须先推到手机(见说明.txt), 本类自动复制到壳私有目录再加载。

    private static boolean sCallMonitoring = false;              // 开关状态
    private static final List<Object> sUnhooks = new ArrayList<>();   // 全部 Unhook
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> sLogQ =
            new java.util.concurrent.ConcurrentLinkedQueue<String>(); // 日志队列(hook线程入, 写盘线程出)
    private static volatile boolean sLogWorkerRun = false;       // 写盘线程生命周期
    private static LogWorker sLogWorker;                         // 写盘线程
    private static final ThreadLocal<int[]> TL_DEPTH = new ThreadLocal<>();   // 调用深度(防 toString 递归风暴)

    private static void toggleCallMonitor(Activity activity) {
        if (sCallMonitoring) stopCallMonitoring(activity);
        else startCallMonitoring(activity);
    }

    /** 深度计数(ThreadLocal 可变 int) */
    private static int[] monDepth() {
        int[] d = TL_DEPTH.get();
        if (d == null) { d = new int[1]; TL_DEPTH.set(d); }
        return d;
    }

    /** 入队(写盘线程批量落盘); 超限丢弃防内存爆 */
    private static void monEnqueue(String line) {
        try { if (sLogQ.size() < 20000) sLogQ.add(line); } catch (Throwable ignored) {}
    }

    /**
     * 找 libpine.so 并复制到壳私有 code_cache(可执行挂载), 返回目标绝对路径。
     * 文件名按设备位数: 64 位机找 libpine64.so, 32 位找 libpine32.so(见说明.txt)。
     * 候选源 = sdcard 常见可写位置; code_cache 已有(上次复制过)直接复用。
     */
    private static String preparePineSo(Context ctx) {
        boolean abi64 = true;
        try {
            String[] abis = android.os.Build.SUPPORTED_ABIS;
            if (abis != null && abis.length > 0) abi64 = abis[0].contains("64");
        } catch (Throwable ignored) {}
        String name = abi64 ? "libpine64.so" : "libpine32.so";
        File dst = new File(ctx.getCodeCacheDir(), name);
        if (dst.exists() && dst.length() > 0) return dst.getAbsolutePath();
        String pkg = ctx.getPackageName();
        String[] srcs = {
                "/storage/emulated/0/Android/data/" + pkg + "/files/" + name,
                "/storage/emulated/0/" + name,
                "/storage/emulated/0/Download/" + name,
                "/storage/emulated/0/ModPkg/" + name,
        };
        File src = null;
        for (String p : srcs) { File f = new File(p); if (f.exists() && f.length() > 0) { src = f; break; } }
        if (src == null) {
            StringBuilder sb = new StringBuilder("[Mon2] 未找到 ").append(name).append(", 已尝试:");
            for (String p : srcs) sb.append("\n  ").append(p);
            Blog.lineForce(sb.toString());
            return null;
        }
        try {
            dst.getParentFile().mkdirs();
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.close();
            in.close();
            Blog.lineForce("[Mon2] so 就绪: " + src.getAbsolutePath() + " -> " + dst.getAbsolutePath() + " (" + dst.length() + " bytes)");
            return dst.getAbsolutePath();
        } catch (Throwable t) {
            Blog.lineForce("[Mon2] 复制 so 失败: " + t);
            return null;
        }
    }

    /** 开启方法监视: 加载 Pine -> 启动写盘线程 -> 分组 hook(每组独立容错) */
    private static void startCallMonitoring(Activity activity) {
        try {
            Blog.begin("监视会话-方法调用(" + VER + ")");   // 新会话: 清空旧日志(标题带版本, 肉眼核对)
            String so = preparePineSo(activity);
            if (so == null) { monToast(activity, "缺少 libpine.so —— 推送方法见说明.txt"); return; }
            try {
                top.canyie.pine.PineConfig.libLoader = new PineSoLoader(so);
                top.canyie.pine.Pine.ensureInitialized();
            } catch (Throwable t) {
                Blog.lineForce("[Mon2] Pine 初始化失败(设备不兼容?): " + t);
                monToast(activity, "Pine 初始化失败: " + t);
                return;
            }
            Blog.lineForce("[Mon2] Pine 就绪: SDK " + android.os.Build.VERSION.SDK_INT + ", " + (top.canyie.pine.Pine.is64Bit() ? "64" : "32") + " 位, hook 引擎挂载完成");
            startLogWorker();

            sUnhooks.clear();
            int total = 0;
            // 1) HomeActivity 全部声明方法(含 private) —— 界面入口层
            total += hookMethods(activity.getClass(), "HomeActivity");
            // 2) HomeActivity 各字段实例的类(疑似 viewModel/adapter —— 原生切站回调真正所在;
            //    只 hook 混淆短名类与壳包类, androidx/google 标准库跳过防噪音)
            total += hookFieldClasses(activity);
            // 3) v41 特征组: 管理器/事件类纯硬编码(CfgFinder 已砍) —— t3.i/z3.h(当前壳); 旧壳短名 3b 兜底
            Class<?> mgrCls = null;
            try {
                ClassLoader cl = activity.getClassLoader();
                mgrCls = Class.forName("t3.i", false, cl);
                Blog.lineForce("[Mon2][特征] 管理器=" + mgrCls.getName() + " (t3.i 硬编码)");
                if (mgrCls != null) total += hookMethods(mgrCls, "管理器特征");   // x()/setHome/v() 全暴露
                Class<?> evtCls = null;
                try { evtCls = Class.forName("z3.h", false, cl); } catch (Throwable ignored) { evtCls = null; }
                if (evtCls != null) {
                    total += hookMethods(evtCls, "事件类特征");
                    total += hookCtors(evtCls, "事件类特征");   // 抓事件实例化瞬间的构造参数
                }
            } catch (Throwable t) {
                Blog.lineForce("[Mon2][特征] 特征组失败: " + t);
            }
            // 3b) 旧壳短名兜底(第一混淆壳 ub4/dt0/zt1 名字仍有效; 其它壳只多一行"未找到"日志)
            total += hookNamed("ub4", null);
            total += hookNamed("dt0", null);
            total += hookNamed("zt1", null);
            // 4) quickjs 爬虫引擎候选(找到哪个 hook 哪个) —— 爬虫执行层
            String[] qs = {"com.fongmi.quickjs.crawler.Spider", "com.whl.quickjs.wrapper.QuickJSContext", "com.github.catvod.crawler.Spider"};
            for (String qn : qs) total += hookNamed(qn, null);
            // 5) 我们自己注入的站源类(盲区修复: hook 基类抓不到子类覆写方法, 壳调
            //    FloatSpider.homeContent/init 必须直接 hook 本类才见得到; 只 hook
            //    Spider 语义方法, 悬浮窗内部方法不记防噪音)
            String[] sm = {"init", "homeContent", "categoryContent", "detailContent", "playerContent", "searchContent", "homeVideoContent", "action", "liveContent", "manualVideoCheck", "isVideoFormat", "proxy", "destroy"};
            total += hookNamed("com.github.catvod.spider.FloatSpider", sm);
            // 6) 站源列表详单(特征管理器优先, 旧壳 ub4 兜底): 打 hash->站名映射
            dumpSiteList(activity, mgrCls);
            // 7) c23(RefreshEvent) 类解剖 —— v32 已特征化: c23 失效回落 Subscribe 特征事件类
            dumpC23Anatomy();
            // (v25 的壳 dex 自动提取已按用户要求移除 —— 用户手头就有壳 apk)

            sCallMonitoring = true;
            Blog.lineForce("[Mon2][" + FloatSpider.VER + "] 方法监视已开启: 共 hook " + total + " 个方法 —— 现在正常操作壳(点站源/选爬虫/进详情), 每次方法调用都会写 bl.log; 完成后再点 monitor.js 停止");
            monToast(activity, "方法监视开启(" + total + " 方法) —— 操作壳后点 monitor.js 停止");
        } catch (Throwable t) {
            Blog.lineForce("[Mon2] 开启失败: " + t);
            monToast(activity, "开启失败: " + t);
        }
    }

    /** hook 一个类的全部声明方法(含 private) */
    private static int hookMethods(Class<?> c, String tag) {
        return hookMethods(c, tag, null);
    }

    /** hook 一个类的全部声明方法(含 private); onlyNames 非空则只 hook 指定名 */
    private static int hookMethods(Class<?> c, String tag, String[] onlyNames) {
        if (c == null) return 0;
        int n = 0;
        StringBuilder hooked = new StringBuilder();
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (onlyNames != null) {
                    boolean hit = false;
                    for (String only : onlyNames) if (m.getName().equals(only)) { hit = true; break; }
                    if (!hit) continue;
                }
                try {
                    m.setAccessible(true);
                    if (top.canyie.pine.Pine.isHooked(m)) continue;
                    Object u = top.canyie.pine.Pine.hook(m, CALL_LOGGER);
                    if (u != null) { sUnhooks.add(u); n++; hooked.append(m.getName()).append(' '); }
                } catch (Throwable t) {
                    Blog.lineForce("[Mon2] hook 失败 " + c.getName() + "." + m.getName() + ": " + t);
                }
            }
        } catch (Throwable t) {
            Blog.lineForce("[Mon2] 枚举方法失败 " + c.getName() + ": " + t);
        }
        if (n > 0) Blog.lineForce("[Mon2][组" + tag + "] " + c.getName() + " hook " + n + " 个: " + hooked);
        return n;
    }

    /** 按类名 hook(forName 失败 = 壳里没有该类, 记一行跳过) */
    private static int hookNamed(String className, String[] onlyNames) {
        try {
            return hookMethods(Class.forName(className), className, onlyNames);
        } catch (Throwable t) {
            Blog.lineForce("[Mon2][组" + className + "] 类未找到(跳过): " + t.getClass().getSimpleName());
            return 0;
        }
    }

    /** v33: hook 类的全部声明构造器(Pine 支持 Member 级) —— 事件类 new 瞬间的参数直接可见 */
    private static int hookCtors(Class<?> c, String tag) {
        int n = 0;
        try {
            for (Constructor<?> ct : c.getDeclaredConstructors()) {
                try {
                    ct.setAccessible(true);
                    if (top.canyie.pine.Pine.isHooked(ct)) continue;
                    Object u = top.canyie.pine.Pine.hook(ct, CALL_LOGGER);
                    if (u != null) { sUnhooks.add(u); n++; }
                } catch (Throwable t) {
                    Blog.lineForce("[Mon2] hook 构造器失败 " + c.getName() + ": " + t.getClass().getSimpleName());
                    break;
                }
            }
        } catch (Throwable ignored) {}
        if (n > 0) Blog.lineForce("[Mon2][组" + tag + "] " + c.getName() + " 构造器 hook " + n + " 个");
        return n;
    }

    /** 扫 activity 字段实例并 hook 其类(viewModel/adapter 等) */
    private static int hookFieldClasses(Activity activity) {
        int total = 0;
        try {
            List<Class<?>> done = new ArrayList<>();
            for (Class<?> c = activity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Object v;
                    try { f.setAccessible(true); v = f.get(activity); } catch (Throwable t) { continue; }
                    if (v == null) continue;
                    Class<?> vc = v.getClass();
                    String vn = vc.getName();
                    // 排除: 标准库(噪音大) + 我们自己的类
                    if (vn.startsWith("java.") || vn.startsWith("javax.") || vn.startsWith("android.") || vn.startsWith("androidx.")
                            || vn.startsWith("com.google.") || vn.startsWith("com.github.catvod.spider.")) continue;
                    boolean dup = false;
                    for (Class<?> d : done) if (d == vc) { dup = true; break; }
                    if (dup) continue;
                    done.add(vc);
                    total += hookMethods(vc, "字段." + f.getName());
                }
            }
        } catch (Throwable t) {
            Blog.lineForce("[Mon2] 字段扫描失败: " + t);
        }
        return total;
    }

    /** 站源列表详单: 展开管理器内存列表逐个 Site 打 hash -> 站名(key), 确认目标站在不在壳列表里
     *  v34: 单例获取加 staticInstanceOf 全 dex 持有者扫描(v33 实测 t3.i 无自身 static 字段);
     *  列表方法按"无参返回 List 且元素为 Site"选(v33 实测 t3.i 有 r/n/o 三个无参 List, i.r() 实锤是真列表) */
    private static void dumpSiteList(Activity activity, Class<?> mgrCls) {
        Object inst = null;
        Class<?> vc = null;
        String src = null;
        if (mgrCls != null) {
            vc = mgrCls;
            inst = staticFieldOf(mgrCls, mgrCls);
            if (inst == null) inst = staticInstanceOf(mgrCls, activity);   // v34: 全 dex 扫 static 字段持有者
            if (inst != null) src = "特征管理器";
        }
        if (inst == null) {
            try {
                vc = Class.forName("ub4");
                try { java.lang.reflect.Field f = Class.forName("tb4").getDeclaredField("a"); f.setAccessible(true); inst = f.get(null); } catch (Throwable ignored) {}
                if (inst == null) inst = staticFieldOf(vc, vc);
                if (inst != null) src = "ub4(旧壳)";
            } catch (Throwable ignored) {}
        }
        if (inst == null || vc == null) { Blog.lineForce("[Mon2][站单] 管理器实例未取到(特征+ub4 均失败)"); return; }
        Class<?> siteCls = null;
        try { siteCls = Class.forName("com.fongmi.android.tv.bean.Site", false, vc.getClassLoader()); } catch (Throwable ignored) {}
        // 列表方法: 旧壳 v() 按名, 其它壳按"无参返回 List 且(元素含 Site 或 siteCls 不可用)"选
        Method listM = null, emptyFallback = null;
        try { if (vc.getMethod("v").invoke(inst) instanceof List) listM = vc.getMethod("v"); } catch (Throwable ignored) {}
        if (listM == null) {
            for (Method m : vc.getDeclaredMethods()) {
                try {
                    if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0) continue;
                    if (m.getReturnType() != List.class) continue;
                    m.setAccessible(true);
                    Object lv = m.invoke(inst);
                    if (!(lv instanceof List)) continue;
                    List l = (List) lv;
                    boolean hasSite = false, hasOther = false;
                    for (Object o : l) {
                        if (o == null) continue;
                        if (siteCls != null ? siteCls.isInstance(o) : false) hasSite = true; else hasOther = true;
                    }
                    if (siteCls != null && hasSite && !hasOther) { listM = m; break; }   // Site 元素实锤
                    if (l.isEmpty() && emptyFallback == null) emptyFallback = m;
                } catch (Throwable ignored) {}
            }
        }
        if (listM == null) {
            listM = emptyFallback;
            if (listM != null) Blog.lineForce("[Mon2][站单] 无 Site 实锤列表, 用首个空列表备选: " + listM.getName());
        }
        if (listM == null) { Blog.lineForce("[Mon2][站单] 列表方法未找到(" + src + ")"); return; }
        List<?> sites;
        try { listM.setAccessible(true); sites = (List<?>) listM.invoke(inst); }
        catch (Throwable t) { Blog.lineForce("[Mon2][站单] 列表调用失败(" + src + "." + listM.getName() + "): " + t); return; }
        Blog.lineForce("[Mon2][站单] 站源列表(" + src + "." + listM.getName() + ") " + (sites == null ? 0 : sites.size()) + " 项:");
        if (sites == null) return;
        for (Object st : sites) {
            String name = null, key = null;
            for (String mn : new String[]{"getSiteName", "getName"}) {
                try { Object r = st.getClass().getMethod(mn).invoke(st); if (r != null) { name = String.valueOf(r); break; } } catch (Throwable ignored) {}
            }
            for (String mn : new String[]{"getSiteKey", "getKey"}) {
                try { Object r = st.getClass().getMethod(mn).invoke(st); if (r != null) { key = String.valueOf(r); break; } } catch (Throwable ignored) {}
            }
            Blog.lineForce("[Mon2][站单] " + System.identityHashCode(st) + " -> " + name + " (key=" + key + ")");
        }
    }

    /** 停止: 全部 unhook + 写盘线程排空退出; 日志保留在 bl.log */
    private static void stopCallMonitoring(Activity activity) {
        int n = 0;
        for (Object u : sUnhooks) {
            try { ((top.canyie.pine.callback.MethodHook.Unhook) u).unhook(); n++; } catch (Throwable ignored) {}
        }
        sUnhooks.clear();
        sCallMonitoring = false;
        monEnqueue("=== 方法监视停止: 解除 " + n + " 个 hook ===");
        sLogWorkerRun = false;
        try { if (sLogWorker != null) sLogWorker.join(2000); } catch (Throwable ignored) {}
        Blog.lineForce("[Mon2] 方法监视已停止: 解除 " + n + " 个 hook, 调用记录全部在 bl.log");
        monToast(activity, "方法监视已停止(" + n + " 个 hook 解除) —— 看 bl.log");
    }

    private static void startLogWorker() {
        if (sLogWorker != null && sLogWorker.isAlive()) return;
        sLogWorkerRun = true;
        sLogWorker = new LogWorker();
        sLogWorker.setDaemon(true);
        sLogWorker.start();
    }

    /** 单例记录器: 所有被 hook 方法共享, 从 frame.method 区分目标 */
    private static final CallLogger CALL_LOGGER = new CallLogger();

    /** v33 磁铁方法集: Spider 语义接口被调时打壳侧调用栈 —— 壳的 loader/管理器链直接暴露 */
    private static final java.util.Set<String> MAGNET_METHODS = new java.util.HashSet<String>(
            java.util.Arrays.asList("init", "homeContent", "homeVideoContent", "categoryContent",
                    "detailContent", "playerContent", "searchContent", "proxy", "liveContent"));

    /** v34: 磁铁链滚动 hook 已登记的类(防重复枚举; 磁铁方法并发调用于多线程, 访问须同步) */
    private static final java.util.Set<Class<?>> sMagnetHooked = new java.util.HashSet<Class<?>>();

    /** v34: 磁铁栈回溯(频率限制内) —— 全类名打印(v33 SimpleName 丢包名无法回 dex 定位) +
     *  系统帧全滤只留壳侧链 + 壳帧类自动入 hook 组(滚动扩展: 磁铁栈里的 p/P/E/Q 等调度类下次直接见轨迹) */
    private static void magnetStack(Member m) {
        try {
            String cls = m.getDeclaringClass().getName();
            if (!cls.equals("com.github.catvod.crawler.Spider") && !cls.equals("com.fongmi.quickjs.crawler.Spider")) return;
            if (!MAGNET_METHODS.contains(m.getName())) return;
            if (sStackDumps >= 12) return;
            sStackDumps++;
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder("[Mon2][磁铁栈#").append(sStackDumps).append("] ").append(m.getName()).append(": ");
            java.util.List<Class<?>> chain = new ArrayList<Class<?>>();
            int n = 0;
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn.startsWith("top.canyie.pine") || cn.startsWith("java.") || cn.startsWith("javax.")
                        || cn.startsWith("dalvik.") || cn.equals("VMStack") || cn.startsWith("android.")
                        || cn.startsWith("androidx.") || cn.startsWith("android.support.")
                        || cn.startsWith("com.android.") || cn.startsWith("com.github.catvod")
                        || cn.startsWith("com.fongmi.quickjs") || cn.startsWith("com.whl.quickjs")) continue;
                sb.append(cn).append('.').append(e.getMethodName()).append('(').append(e.getLineNumber()).append(") <- ");
                if (++n >= 15) break;
                try {
                    Class<?> fc = Class.forName(cn, false, m.getDeclaringClass().getClassLoader());
                    synchronized (sMagnetHooked) {
                        if (!sMagnetHooked.contains(fc) && !fc.isInterface() && !fc.isEnum() && !fc.isAnnotation()) {
                            chain.add(fc);
                            sMagnetHooked.add(fc);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            Blog.lineForce(sb.toString());
            if (!chain.isEmpty() && sCallMonitoring) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new MagnetChainTask(chain));
            }
        } catch (Throwable ignored) {}
    }

    /** v34: 磁铁链滚动 hook 任务(main 线程异步执行, 命名内部类铁律: 显式构造器防合成编号类) */
    private static final class MagnetChainTask implements Runnable {
        private final java.util.List<Class<?>> todo;
        MagnetChainTask(java.util.List<Class<?>> todo) { this.todo = todo; }   // 显式构造器(铁律)
        @Override
        public void run() {
            for (Class<?> fc : todo) hookMethods(fc, "磁铁链");
        }
    }

    /** 调用签名: 类名.方法名(参数摘要) */
    private static String monSig(top.canyie.pine.Pine.CallFrame frame) {
        Member m = frame.method;
        StringBuilder sb = new StringBuilder(m.getDeclaringClass().getSimpleName()).append('.').append(m.getName()).append('(');
        Object[] args = frame.args;
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(brief(args[i], 150));
            }
        }
        return sb.append(')').toString();
    }

    /** 记录器本体(命名内部类): before 记 进入+参数, after 记 返回值/异常 */
    private static final class CallLogger extends top.canyie.pine.callback.MethodHook {
        // 显式包私有构造器: 防 javac 合成桥 + FloatSpider$1 占位类(铁律)
        CallLogger() {}
        @Override
        public void beforeCall(top.canyie.pine.Pine.CallFrame frame) {
            // v33: 磁铁栈回溯放在深度过滤之前 —— 顶层入口调用栈最干净
            try { magnetStack(frame.method); } catch (Throwable ignored) {}
            int[] d = monDepth();
            d[0]++;
            if (d[0] != 1) return;   // 嵌套调用(toString 链等)不记, 防日志风暴
            try {
                monEnqueue("-> " + monSig(frame) + "  [" + Thread.currentThread().getName() + "]");
            } catch (Throwable ignored) {}
        }
        @Override
        public void afterCall(top.canyie.pine.Pine.CallFrame frame) {
            int[] d = monDepth();
            if (d[0] == 1) {
                try {
                    if (frame.hasThrowable()) monEnqueue("!! " + monSig(frame) + " 抛 " + frame.getThrowable());
                    else monEnqueue("<- " + monSig(frame) + " => " + brief(frame.getResult(), 300));
                } catch (Throwable ignored) {}
            }
            if (d[0] > 0) d[0]--;
            monIntercept(frame);
        }
    }

    /** v22 拦截点: 纯旁观记录(壳自产 c23 字段 dump + th0.L 调用栈), 不做任何干预动作 */
    private static void monIntercept(top.canyie.pine.Pine.CallFrame frame) {
        try {
            Member m = frame.method;
            String cls = m.getDeclaringClass().getName();
            String nm = m.getName();
            if (cls.equals("com.fongmi.android.tv.ui.activity.HomeActivity") && nm.equals("onRefreshEvent")) {
                Object[] a = frame.args;
                if (a != null && a.length > 0 && a[0] != null) {
                    dumpEvtFields(a[0], "壳自产c23");
                }
                return;
            }
            if (cls.equals("th0") && nm.equals("L")) {
                // v24: 参数无条件记录(嵌套调用的 -> 记录被防风暴过滤, 广播链里它是深层)
                Object[] a2 = frame.args;
                StringBuilder sbp = new StringBuilder("[Mon2][th0.L] ");
                if (a2 != null) {
                    for (int i = 0; i < a2.length; i++) sbp.append(brief(a2[i], 80)).append(i < a2.length - 1 ? ", " : "");
                }
                Blog.lineForce(sbp.toString());
                if (sStackDumps < 3) {
                    sStackDumps++;
                    StackTraceElement[] st = Thread.currentThread().getStackTrace();
                    StringBuilder sb = new StringBuilder("[Mon2][栈th0.L#").append(sStackDumps).append("] ");
                    int n = 0;
                    for (StackTraceElement e : st) {
                        String cn = e.getClassName();
                        if (cn.startsWith("top.canyie.pine") || cn.startsWith("java.lang.Thread")
                                || cn.startsWith("com.github.catvod.spider")) continue;
                        sb.append(cn.substring(cn.lastIndexOf('.') + 1)).append('.').append(e.getMethodName())
                          .append('(').append(e.getLineNumber()).append(") <- ");
                        if (++n >= 15) break;
                    }
                    Blog.lineForce(sb.toString());
                }
                return;
            }
            // v27: 原 ub4.x 切站检测分支已删 —— 监视器只旁观, 补刷机制整体移除
        } catch (Throwable ignored) {}
    }

    /** 实例字段全 dump(壳自产 c23 vs 我们构造的 c23, 对照找差异) */
    private static void dumpEvtFields(Object evt, String tag) {
        try {
            StringBuilder sb = new StringBuilder("[Mon2][").append(tag).append("] 字段值: ");
            for (Class<?> c = evt.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.isSynthetic() || f.isAccessible()) {}
                    f.setAccessible(true);
                    sb.append(f.getName()).append('=').append(brief(f.get(evt), 60)).append(' ');
                }
            }
            Blog.lineForce(sb.toString());
        } catch (Throwable t) {
            Blog.lineForce("[Mon2][" + tag + "] 字段 dump 失败: " + t);
        }
    }

    /** c23 类解剖: 字段清单 + 构造器签名 + 方法名单(监视开启时打一次); v41: CfgFinder 已砍, 只留 c23 硬编码 */
    private static void dumpC23Anatomy() {
        try {
            Class<?> c = Class.forName("c23");
            StringBuilder sb = new StringBuilder("[Mon2][c23解剖] 字段: ");
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                sb.append(f.getName()).append(':').append(f.getType().getSimpleName()).append(' ');
            }
            Blog.lineForce(sb.toString());
            sb = new StringBuilder("[Mon2][c23解剖] 构造器: ");
            for (Constructor<?> ct : c.getDeclaredConstructors()) {
                sb.append('(');
                for (Class<?> p : ct.getParameterTypes()) sb.append(p.getSimpleName()).append(',');
                sb.append(") ");
            }
            Blog.lineForce(sb.toString());
            sb = new StringBuilder("[Mon2][c23解剖] 方法: ");
            for (Method mm : c.getDeclaredMethods()) {
                sb.append(mm.getName()).append('/').append(mm.getParameterTypes().length).append(' ');
            }
            Blog.lineForce(sb.toString());
        } catch (Throwable t) {
            Blog.lineForce("[Mon2][c23解剖] 失败(c23 不存在?): " + t);
        }
    }


    /** 写盘线程(命名内部类): 队列批量 -> bl.log; hook 回调在原线程, IO 全在此线程 */
    private static final class LogWorker extends Thread {
        LogWorker() { super("float_monlog"); }
        @Override
        public void run() {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US);
            StringBuilder batch = new StringBuilder();
            while (sLogWorkerRun || !sLogQ.isEmpty()) {
                String line = sLogQ.poll();
                if (line == null) {
                    if (batch.length() > 0) { Blog.lineForce(batch.toString()); batch.setLength(0); }
                    try { Thread.sleep(80); } catch (InterruptedException e) { break; }
                    continue;
                }
                batch.append(fmt.format(new java.util.Date())).append(' ').append(line).append('\n');
                if (batch.length() > 8000) { Blog.lineForce(batch.toString()); batch.setLength(0); }
            }
            if (batch.length() > 0) Blog.lineForce(batch.toString());
        }
    }

    /** so 加载器(命名内部类): Pine 初始化时按绝对路径加载已复制的 libpine.so */
    private static final class PineSoLoader implements top.canyie.pine.Pine.LibLoader {
        private final String soPath;
        PineSoLoader(String p) { soPath = p; }   // 显式构造器(铁律)
        @Override
        public void loadLib() {
            System.load(soPath);
            Blog.lineForce("[Mon2] libpine.so 已加载: " + soPath);
        }
    }

    /**
     * 站点 api 自检: 壳加载爬虫时会去 GET api, 404 则整条链报废(空播放页)。
     * JS 方案默认 api=http://127.0.0.1:9978/file/{sdcard相对路径}, 依赖壳 /file/ 映射。
     * 网络必须放子线程(NetworkOnMainThreadException), 主线程限时等待结果。
     * 不通时切本地绝对路径方案(比脚本全文更可能被壳 JS spider 当文件读)。
     */
    private static String selfTestApi(String api, String content, String name, Object site, String localPath) {
        if (api == null || !api.startsWith("http")) return api;
        int code = -1;
        try {
            java.util.concurrent.FutureTask<Integer> ft = new java.util.concurrent.FutureTask<Integer>(new HttpCheckTask(api));
            Thread th = new Thread(ft, "float_selftest");
            th.setDaemon(true);
            th.start();
            code = ft.get(4, java.util.concurrent.TimeUnit.SECONDS).intValue();
        } catch (Throwable t) {
            Blog.line("[SelfTest] 自检等待异常: " + t);
        }
        Blog.line("[SelfTest] GET " + api + " -> HTTP " + code);
        if (code == 200) return api;
        String newApi = localPath != null ? localPath : content + "\n" + (name.endsWith(".py") ? "# " : "// ") + name;
        Blog.line("[SelfTest] /file/ 不通(HTTP " + code + "), 切本地路径方案: api=" + summarizeForLog(newApi));
        try {
            invoke(site, "setApi", new Class<?>[]{String.class}, newApi);
            Blog.line("[SelfTest] site.setApi 已同步");
        } catch (Throwable t) {
            Blog.line("[SelfTest] setApi 失败: " + t);
        }
        return newApi;
    }

    /** 命名静态内部类(铁律): 子线程跑 HTTP GET, 返回状态码 */
    private static final class HttpCheckTask implements java.util.concurrent.Callable<Integer> {
        private final String url;
        HttpCheckTask(String url) { this.url = url; }
        @Override
        public Integer call() {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(1500);
                c.setReadTimeout(1500);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                try { java.io.InputStream is = code == 200 ? c.getInputStream() : c.getErrorStream(); if (is != null) is.close(); } catch (Throwable ignored) {}
                c.disconnect();
                return Integer.valueOf(code);
            } catch (Throwable t) {
                Blog.line("[SelfTest] GET api 异常: " + t);
                return Integer.valueOf(-1);
            }
        }
    }

    /** Site 参数诊断: 长内容只显示长度+末尾 40 字符(脚本全文很长, 首行/尾行决定壳的 isPy/isJs 判定) */
    private static String summarizeForLog(String s) {
        if (s == null) return "null";
        if (s.length() <= 60) return "\"" + s + "\"";
        return s.length() + " chars, tail=\"" + s.substring(s.length() - 40) + "\"";
    }

    private static void addSite(Class<?> configClass, Object site, String key) throws Exception {
        Object cfg = getConfigInstance(configClass);
        if (cfg == null) throw new Exception(configClass.getSimpleName() + ".get() 失败");
        Object list = findSitesList(cfg, site.getClass());
        if (!(list instanceof List)) throw new Exception("未找到站点列表(配置类=" + configClass.getName() + ")");
        List<?> sites = (List<?>) list;
        for (Object old : new ArrayList<>(sites)) {
            try {
                Object oldKey = invoke(old, "getKey", new Class<?>[]{});
                if (key.equals(String.valueOf(oldKey))) { ((List) sites).remove(old); break; }
            } catch (Throwable ignored) {}
        }
        ((List) sites).add(site);
    }

    private static void setHome(Class<?> configClass, Object site) throws Exception {
        Object cfg = getConfigInstance(configClass);
        if (cfg == null) throw new Exception(configClass.getSimpleName() + ".get() 失败");
        Method m = findMethod(cfg.getClass(), "setHome", site.getClass());
        if (m == null) {
            for (Method x : cfg.getClass().getMethods()) {
                if (x.getName().equals("setHome") && x.getParameterTypes().length == 1) { m = x; break; }
            }
        }
        if (m == null) throw new Exception("未找到 setHome()");
        m.setAccessible(true);
        m.invoke(cfg, site);
    }

    private static String getCurrentJar(Class<?> configClass) {
        try {
            Object cfg = getConfigInstance(configClass);
            Object home = invoke(cfg, "getHome", new Class<?>[]{});
            Object jar = invoke(home, "getJar", new Class<?>[]{});
            return jar == null ? "" : String.valueOf(jar);
        } catch (Throwable ignored) {}
        return "";
    }

    private static Throwable unwrap(Throwable e) {
        Throwable t = e;
        while (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t;
    }

    private static String describe(Throwable t) {
        Throwable root = t;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            if (root instanceof java.lang.reflect.InvocationTargetException
                    || root.getClass() == RuntimeException.class
                    || root.getClass() == Exception.class) root = root.getCause();
            else break;
        }
        String msg = root.getMessage();
        if (msg == null || msg.trim().isEmpty()) msg = String.valueOf(root);
        String head = root.getClass().getName();
        return head.equals(root.getClass().getSimpleName()) ? msg : head + ": " + msg;
    }

    private static String md5(String s) {
        try {
            byte[] b = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder x = new StringBuilder();
            for (byte v : b) x.append(String.format("%02x", v & 255));
            return x.toString();
        } catch (Throwable e) { return Integer.toHexString(s.hashCode()); }
    }


    // ===================== 静态内部类(替代匿名类, 避免生成 FloatSpider$N.class) =====================

    /** 替代原第153行的匿名 ActivityLifecycleCallbacks */
    private static final class FloatLifecycle implements Application.ActivityLifecycleCallbacks {
        static final FloatLifecycle INSTANCE = new FloatLifecycle();
        @Override public void onActivityResumed(Activity a) { topActivityRef = new WeakReference<>(a); }
        @Override public void onActivityDestroyed(Activity a) { if (topActivityRef != null && topActivityRef.get() == a) topActivityRef = null; }
        @Override public void onActivityCreated(Activity a, Bundle b) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityPaused(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    }

    /** 替代原第431行的匿名 TextWatcher */
    private static final class FloatTextWatcher implements android.text.TextWatcher {
        private final FloatSpider owner;
        private final java.util.List<File> filtered;
        private final ScriptGridAdapter adapter;
        private final android.widget.EditText search;
        private final int[] selectedTab;
        FloatTextWatcher(FloatSpider owner, java.util.List<File> filtered, ScriptGridAdapter adapter,
                         android.widget.EditText search, int[] selectedTab) {
            this.owner = owner;
            this.filtered = filtered;
            this.adapter = adapter;
            this.search = search;
            this.selectedTab = selectedTab;
        }
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
            owner.applyFilter(filtered, adapter, search.getText().toString(), selectedTab[0]);
        }
        @Override public void afterTextChanged(android.text.Editable s) {}
    }

    /** 替代原 loadFileList() 里的 FilenameFilter lambda */
    private static final class FloatFileFilter implements FilenameFilter {
        static final FloatFileFilter INSTANCE = new FloatFileFilter();
        @Override public boolean accept(File dir, String name) {
            String n = name.toLowerCase();
            return n.endsWith(".py") || n.endsWith(".js");
        }
    }

    /** 替代原 tabs.setOnCheckedChangeListener 的 lambda */
    private static final class FloatTabListener implements android.widget.RadioGroup.OnCheckedChangeListener {
        private final FloatSpider owner;
        private final java.util.List<File> filtered;
        private final ScriptGridAdapter adapter;
        private final android.widget.EditText search;
        private final int[] selectedTab;
        FloatTabListener(FloatSpider owner, java.util.List<File> filtered, ScriptGridAdapter adapter,
                         android.widget.EditText search, int[] selectedTab) {
            this.owner = owner;
            this.filtered = filtered;
            this.adapter = adapter;
            this.search = search;
            this.selectedTab = selectedTab;
        }
        @Override public void onCheckedChanged(android.widget.RadioGroup group, int checkedId) {
            selectedTab[0] = checkedId;
            owner.applyFilter(filtered, adapter, search.getText().toString(), checkedId);
        }
    }

    /** 替代原 mainHandler.post(this::showFloatButtonAndDialog) 的方法引用 */
    private static final class FloatShowTask implements Runnable {
        private final FloatSpider owner;
        FloatShowTask(FloatSpider owner) { this.owner = owner; }
        @Override public void run() { owner.showFloatButtonAndDialog(); }
    }

    /** 替代原 grid.setOnItemClickListener 的 lambda */
    private static final class FloatGridClickListener implements android.widget.AdapterView.OnItemClickListener {
        private final FloatSpider owner;
        private final Activity activity;
        private final java.util.List<File> filtered;
        FloatGridClickListener(FloatSpider owner, Activity activity, java.util.List<File> filtered) {
            this.owner = owner;
            this.activity = activity;
            this.filtered = filtered;
        }
        @Override public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
            if (position < filtered.size()) owner.loadAndRunScript(activity, filtered.get(position));
            if (owner.scriptDialog != null) owner.scriptDialog.dismiss();
        }
    }
}
