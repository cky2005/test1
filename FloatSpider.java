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

import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

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
 *  4) 全文件禁用 lambda 与匿名内部类(兼容 javac 8 + d8 工具链), 全部用命名静态内部类.
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
        // v42: delegateSpider 代理链已随免验证移除, 直接返回占位分类
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
        // 不自动弹出文件列表: 仅确保悬浮按钮存在(可点击唤出), 真正的弹窗只由
        // "点击列表条目(action=show_float)" 或 "点击悬浮按钮" 触发.
        ensureFloatButton(getTopActivity());
        return buildFloatItems();
    }

    public String action(String action) {
        if (action != null && action.contains("show_float")) {
            mainHandler.post(new FloatShowTask(this));
        }
        return "{}";
    }

    public String detailContent(List<String> ids) {
        return "{}";
    }

    public String playerContent(String flag, String id, List<String> vipFlags) {
        return "{}";
    }

    public String searchContent(String key, boolean quick) {
        return "{}";
    }

    // v42: invokeDelegate/delegateSpider 代理链已删(免验证后无赋值路径, 恒 null);
    // findMethod 是通用反射工具, 其余调用点仍需要, 保留。
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

    // ===================== Pro 版脚本中心弹窗 =====================

    private void showCenterScriptDialog(final Activity activity) {
        if (scriptDialog != null && scriptDialog.isShowing()) return;
        loadFileList();
        scriptDialog = new Dialog(activity);
        scriptDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        scriptDialog.setCancelable(true);
        scriptDialog.setCanceledOnTouchOutside(true);

        final float density = activity.getResources().getDisplayMetrics().density;

        // ============ 根容器: 渐变卡片 + 细描边 ============
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(buildCardBackground(density));
        root.setPadding(dpv(18, density), dpv(16, density), dpv(18, density), dpv(18, density));

        // ============ 标题栏: 标题 + 副标题 + 关闭按钮 ============
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBox = new LinearLayout(activity);
        titleBox.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(activity);
        title.setText("脚本中心");
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFF5F5F5);
        titleBox.addView(title);

        TextView subtitle = new TextView(activity);
        subtitle.setText("选择并运行你的脚本");
        subtitle.setTextSize(11);
        subtitle.setTextColor(0xFF7A7A7A);
        subtitle.setPadding(0, dpv(2, density), 0, 0);
        titleBox.addView(subtitle);

        TextView closeBtn = new TextView(activity);
        closeBtn.setText("✕");
        closeBtn.setTextSize(14);
        closeBtn.setTextColor(0xFFAAAAAA);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setBackground(buildCloseBackground(density));
        closeBtn.setPadding(dpv(10, density), dpv(6, density), dpv(10, density), dpv(6, density));
        // 命名静态内部类(禁用匿名类/lambda)
        closeBtn.setOnClickListener(new FloatCloseClick(this));

        header.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(closeBtn);

        // ============ 搜索框: 图标 + 输入框 ============
        LinearLayout searchBox = new LinearLayout(activity);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setBackground(buildSearchBackground(density));
        searchBox.setPadding(dpv(12, density), 0, dpv(12, density), 0);

        TextView searchIcon = new TextView(activity);
        searchIcon.setText("🔍");
        searchIcon.setTextSize(13);
        searchBox.addView(searchIcon);

        final EditText search = new EditText(activity);
        search.setHint("搜索脚本名字...");
        search.setSingleLine(true);
        search.setTextSize(13);
        search.setTextColor(0xFFEEEEEE);
        search.setHintTextColor(0xFF666666);
        search.setBackgroundColor(Color.TRANSPARENT);
        search.setPadding(dpv(8, density), dpv(11, density), 0, dpv(11, density));
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBox.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // ============ 胶囊 Tab (全部/PY/JS) ============
        final RadioGroup tabs = new RadioGroup(activity);
        tabs.setOrientation(RadioGroup.HORIZONTAL);
        tabs.setPadding(dpv(4, density), dpv(4, density), dpv(4, density), dpv(4, density));
        tabs.setBackground(buildTabBarBackground(density));
        String[] tabNames = {"全部", "PY", "JS"};
        final int[] tabIds = {1001, 1002, 1003};
        for (int i = 0; i < tabNames.length; i++) {
            RadioButton rb = new RadioButton(activity);
            rb.setText(tabNames[i]);
            rb.setId(tabIds[i]);
            rb.setTextSize(13);
            rb.setButtonDrawable(null);
            rb.setGravity(Gravity.CENTER);
            rb.setPadding(dpv(18, density), dpv(6, density), dpv(18, density), dpv(6, density));
            rb.setBackground(buildTabPillBackground(density));
            RadioGroup.LayoutParams lp = new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dpv(2, density), 0, dpv(2, density), 0);
            tabs.addView(rb, lp);
        }
        tabs.check(tabIds[0]);
        refreshTabStyles(tabs, tabIds[0]);   // 初始高亮"全部"

        // ============ GridView ============
        GridView grid = new GridView(activity);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(dpv(12, density));
        grid.setVerticalSpacing(dpv(12, density));
        grid.setGravity(Gravity.CENTER);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalScrollBarEnabled(true);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));

        final java.util.List<File> filtered = new java.util.ArrayList<>();
        final int[] selectedTab = {tabIds[0]};
        final ScriptGridAdapter adapter = new ScriptGridAdapter(activity, filtered);
        grid.setAdapter(adapter);

        // 命名静态内部类(禁用匿名类/lambda)
        tabs.setOnCheckedChangeListener(new FloatTabListener(this, filtered, adapter, search, selectedTab));
        search.addTextChangedListener(new FloatTextWatcher(this, filtered, adapter, search, selectedTab));
        applyFilter(filtered, adapter, "", selectedTab[0]);
        grid.setOnItemClickListener(new FloatGridClickListener(this, activity, filtered));

        // ============ 组装: 总高度限制为屏幕 62% ============
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        int totalH = (int) (activity.getResources().getDisplayMetrics().heightPixels * .62f);

        container.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchParams.topMargin = dpv(14, density);
        container.addView(searchBox, searchParams);

        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabParams.topMargin = dpv(12, density);
        container.addView(tabs, tabParams);

        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        gridParams.weight = 1;
        gridParams.topMargin = dpv(14, density);
        container.addView(grid, gridParams);

        root.addView(container, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, totalH));
        scriptDialog.setContentView(root);

        // ============ 窗口参数 ============
        Window w = scriptDialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * .88f);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            w.setDimAmount(0.6f);
        }
        scriptDialog.show();
        playShowAnimation(root);
    }

    // ==================== 工具与样式方法 ====================

    /** dp 转像素(静态, 不依赖 this/Context) */
    private static int dpv(float v, float density) {
        return (int) (v * density + 0.5f);
    }

    /** 进场动画: 缩放 + 淡入 */
    private void playShowAnimation(View root) {
        root.setScaleX(0.9f);
        root.setScaleY(0.9f);
        root.setAlpha(0f);
        root.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(180L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /** 播放退出动画后再 dismiss */
    private void dismissWithAnimation() {
        if (scriptDialog == null || !scriptDialog.isShowing()) return;
        final Dialog d = scriptDialog;
        View root = d.getWindow() != null ? d.getWindow().getDecorView() : null;
        if (root != null) {
            root.animate().scaleX(0.9f).scaleY(0.9f).alpha(0f)
                    .setDuration(150L)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(new FloatDismissTask(d))
                    .start();
        } else {
            d.dismiss();
        }
    }

    /** 卡片: 深色渐变 + 半透明描边 */
    private GradientDrawable buildCardBackground(float density) {
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF26262B, 0xFF1A1A1E});
        gd.setCornerRadius(dpv(12, density));
        gd.setStroke(dpv(1, density), 0x33FFFFFF);
        return gd;
    }

    /** 搜索框: 凹陷底色 + 描边 */
    private GradientDrawable buildSearchBackground(float density) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0xFF121216);
        gd.setCornerRadius(dpv(100, density));
        gd.setStroke(dpv(1, density), 0x22FFFFFF);
        return gd;
    }

    /** Tab 栏外框 */
    private GradientDrawable buildTabBarBackground(float density) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0xFF121216);
        gd.setCornerRadius(dpv(100, density));
        return gd;
    }

    /** Tab 选中胶囊: StateListDrawable 区分选中态(蓝色渐变) */
    private StateListDrawable buildTabPillBackground(float density) {
        GradientDrawable selected = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF4F8CFF, 0xFF2F5FD8});
        selected.setCornerRadius(dpv(100, density));

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.TRANSPARENT);
        normal.setCornerRadius(dpv(100, density));

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_checked}, selected);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    /** 关闭按钮: 圆形 + 按压反馈 */
    private StateListDrawable buildCloseBackground(float density) {
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.OVAL);
        normal.setColor(0x22FFFFFF);

        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.OVAL);
        pressed.setColor(0x44FFFFFF);

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    /** Tab 选中态: 白色加粗 / 未选中灰色 */
    private void refreshTabStyles(RadioGroup group, int checkedId) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof RadioButton) {
                RadioButton rb = (RadioButton) child;
                boolean checked = rb.getId() == checkedId;
                rb.setTextColor(checked ? 0xFFFFFFFF : 0xFF8A8A8A);
                rb.setTypeface(checked ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
        }
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
            tv.setPadding(20, 30, 20, 30);
            tv.setTextSize(14);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(100);
            if (files.isEmpty()) {
                tv.setText("未找到 py / js 文件");
                tv.setTextColor(Color.GRAY);
                bg.setColor(0xFF2D2D2D);
            } else {
                File f = files.get(position);
                tv.setText(f.getName());
                if (f.getName().toLowerCase().endsWith(".py")) {
                    tv.setTextColor(0xFFEEEEEE);
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
        // v41b: 加载挪后台线程 —— py(Chaquopy) 冷启动 ~7s, 壳原生站源也在 pool 线程加载
        // (bl.log 实锤 da.h0() [pool-8-thread-1]); 点击线程同步等待会冻结 UI 触发系统"无响应"。
        // 流程整体原样换载体(ScriptLoadTask), 无锁无状态变更; JS 同样走后台(统一路径, ~170ms 无感)。
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
        try {
            File target = sanitizeScript(file);
            String name = file.getName();
            String path = target.getAbsolutePath();
            String lower = name.toLowerCase();
            boolean isJs = lower.endsWith(".js");

            String content = readText(target);
            if (content == null || content.trim().isEmpty()) throw new Exception("脚本内容为空: " + path);

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
            } else {
                // v30 根因修复: 壳原生 py 链 app.py 的 spider(cache, api) 第二参数是 api URL,
                // basename(api) 作落盘文件名, http 开头 requests 下载, 非 http 把 api 字符串
                // 当脚本内容写文件 —— 旧形态"正文+尾注"在 basename 一步就 Errno 36(日志实锤)。
                // 与 JS 站(v29 已验证成功)同构: api = 9978 本地服务 URL, Python 侧可直接访问。
                String sdcardRelPath = getSdcardRelativePath(path);
                if (sdcardRelPath == null) throw new Exception("无法计算 sdcard 相对路径: " + path);
                api = "http://127.0.0.1:9978/file/" + sdcardRelPath.replace(File.separator, "/");
                ext = "";
            }

            String key = "float_" + md5(path);
            ClassLoader cl = activity.getClassLoader();
            String root = findHostRoot(activity);
            Class<?> loader = findBaseLoader(cl, root);
            Class<?> site = findSiteClass(cl, root);
            Class<?> config = findVodConfig(cl, root);
            if (loader == null) {
                // v41b: 免验证 —— 用户拍板"都交给壳"。混淆壳直接 URL 形态注册, py 由壳 py 链
                // 在 pool 线程加载(与壳原生站源同款), js 由壳 JsLoader 用 ext 内容加载。
                // HoneyLoader 引擎/探针整体跳过(省 py ~7s / js ~170ms 注册耗时);
                // 坏脚本/形态不符的后果 = 首页空或报错(壳不崩), 与壳原生一致。

                // ---- 复刻正常壳流程: 创建 Site -> 注册进站源列表 -> setHome -> 跳转 ----
                String jar = config != null ? getCurrentJar(config) : "";
                Object siteObj = null;
                if (site != null) {
                    try {
                        siteObj = createSite(site, key, name, api, ext, jar);
                    } catch (Throwable ignored) {}
                }
                boolean registered = false;
                if (siteObj != null && site != null) {
                    // 新版 FongMi: 站源列表由 bean.Site 静态方法管理(findAll/saveSettings), 方法名未被混淆
                    registered = addSiteViaStaticSite(site, siteObj, key);
                    if (registered) {
                        syncViaLoader(activity, site, siteObj, key);   // 内存层同步+Home切换(保持壳内存状态一致)
                        // v28 定调(用户 + 开源上游实锤): 注册 + 壳原生 setHome(Site) 等价配方
                        // (x() 切站 + RefreshEvent.home() 一次广播), 之后首页/分类/列表/
                        // 详情/播放全流程由壳自动完成 —— 不做任何重试/兜底/直提任务。
                    } else {
                        // v41: CfgFinder 兜底已砍 —— 静态注册不可用即放弃(主路径反射注册不受影响)
                    }
                }
                // v9/v28: 不做任何 Activity 跳转 —— 切站与首页刷新由壳原生配方完成
                // (syncViaLoader 的 x() + postHomeRefresh 的一次 RefreshEvent.home())。
                // v8 事故: 跳转调用漏删, Intent 直启播放页抢走了首页刷新。
                if (!registered) {
                }
                return;
            }

            Object loaderObj = invokeStatic(loader, "get");
            if (loaderObj == null) throw new Exception("Loader.get() 失败");

            String jar = config != null ? getCurrentJar(config) : "";

            Object siteObj = null;
            if (site != null) {
                try {
                    siteObj = createSite(site, key, name, api, ext, jar);
                } catch (Throwable t) {
                    Log.e(TAG, "创建 Site 失败", t);
                }
            } else {
            }

            // v42: 宿主 Spider 获取段已删 —— 免验证方案下脚本加载一律交给壳
            // (URL 形态由壳 py/Js 链自加载), spider 对象无需在本类持有。

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

            if (!registered) {
                throw new Exception("无法装载脚本: 站点注册失败");
            }

            Log.i(TAG, "脚本加载成功: " + name + " registered=" + registered);
        } catch (Throwable e) {
            Log.e(TAG, "脚本加载失败", e);
            // v41b: toast 经 main 投递 —— 本方法现在跑在后台线程, Toast.show 需 Looper
            new Handler(Looper.getMainLooper()).post(new FailToastTask(activity, "加载失败:\n" + describe(e)));
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
            // 移除同 key 旧项
            for (Object old : new ArrayList<>(sites)) {
                try {
                    if (key.equals(String.valueOf(invoke(old, "getKey", new Class<?>[]{})))) {
                        sites.remove(old);
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
            sites.add(site);
            // v32 持久化: 新版 saveSettings(List) -> 老版 Site.save() 单条 -> 均失败才 return false
            // (v31 及之前: saveSettings 失败仍 return true —— 老版壳上内存 add 无效, 是假成功)
            boolean saved = false;
            try {
                Method save = siteCls.getMethod("saveSettings", List.class);
                if (Modifier.isStatic(save.getModifiers())) save.invoke(null, list);
                else save.invoke(site, list);
                saved = true;
            } catch (Throwable ignored) {}
            if (!saved) {
                // 老版 bean.Site: public void save() —— 单条 insertOrUpdate, findAll 从 DB 回读可见
                try {
                    Method save = siteCls.getMethod("save");
                    save.setAccessible(true);
                    save.invoke(site);
                    saved = true;
                } catch (Throwable ignored) {}
            }
            if (!saved) {
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
                }
            } catch (Throwable ignored) {}
            return true;
        } catch (Throwable t) {
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
            if (loaderCls == null) { return; }
            Object loader = staticInstanceOf(loaderCls, activity);
            if (loader == null) { return; }
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
                    } else if (!l.isEmpty()) {
                        l.add(site);
                    } else {
                    }
                } catch (Throwable ignored) {}
            } else {
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
                    return;
                } catch (Throwable ignored) {}
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
                    refreshHome(activity, site);
                    return;
                } catch (Throwable ignored) {}
            }
            // v32 L4: 老版壳兜底 —— 管理器无 setHome/x 特征时, 直改 bean 层 Config.home(bean 方法名 keep 保留)
            if (cfgObj != null) {
                try {
                    invoke(cfgObj, "setHome", new Class<?>[]{String.class}, key);
                    try { invoke(cfgObj, "save", new Class<?>[]{}); }
                    catch (Throwable ignored) {}
                    refreshHome(activity, site);
                    return;
                } catch (Throwable ignored) {}
            } else {
            }
            // v32 L5: 仅广播 —— 站点已在 DB(SiteReg save 回退), 壳收到 HOME 事件重载配置后站源列表可见
            postHomeRefresh();
        } catch (Throwable ignored) {}
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
        } catch (Throwable ignored) {}
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
            postHomeRefresh();
            return;
        }
        if (postShellRefresh()) return;
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
            if (evt == null) { return false; }
            Object[] bp = locateShellBus();
            if (bp == null) { return false; }
            Object evtObj = null;
            for (Constructor<?> k : evt.getDeclaredConstructors()) {
                Class<?>[] p = k.getParameterTypes();
                if (p.length == 1 && p[0] == int.class) {
                    k.setAccessible(true);
                    evtObj = k.newInstance(Integer.valueOf(1));   // 1=HOME, S3/G.j dex 实锤
                    break;
                }
            }
            if (evtObj == null) { return false; }
            final Object fBus = bp[0];
            final Method fPost = (Method) bp[1];
            final Object fEvt = evtObj;
            // main 线程投递: 壳事件经 main 队列派发, 与原生点击 handler 同线程
            new Handler(Looper.getMainLooper()).post(new ShellRefreshTask(fBus, fPost, fEvt));
            return true;
        } catch (Throwable t) {
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
            if (bus == null) { return null; }
            Method post = null;
            for (Method pm : busCls.getDeclaredMethods()) {
                if (Modifier.isStatic(pm.getModifiers()) || pm.isSynthetic()) continue;
                Class<?>[] pp = pm.getParameterTypes();
                if (pp.length == 1 && pp[0] == Object.class && pm.getReturnType() == void.class) { post = pm; break; }
            }
            if (post == null) { return null; }
            post.setAccessible(true);
            return new Object[]{bus, post, busCls};
        } catch (Throwable t) {
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
            } catch (Throwable ignored) {}
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
            } catch (Throwable ignored) {}
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
                if (r != null) { return r; }
            } catch (Throwable t) { /* 下一个候选 */ }
        }
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

    static final String VER = "v43";   // 版本号: v43 = v42 - B级(监控 Mon2 整体删除/Pine 依赖砍掉) + 日志文件系删除(Blog/bl.log/selfTestApi 自检, logcat Log.x 保留)

    // ---- v28 设计原则(用户定调 + 开源上游实锤): 注册后调壳原生 setHome(Site) 等价配方,
    //      之后首页/分类/列表/详情/播放全流程由壳自动完成, 我们不做任何重试/兜底/直提任务。
    //      上游源码(FongMi/TV) api/config/VodConfig.java:
    //        public void setHome(Site site) { setHome(getConfig(), site, true); RefreshEvent.home(); }
    //      壳内 R8 已把该方法内联进站源点击处理(gd3.f), 逐字节等价:
    //        ub4.x(config, site, true) -> dt0.b().e(new c23(1))
    //      v26 败因 = 广播后 1.5s/4s 反复重播+直提任务, 打断壳正在进行的自动加载;
    //      v27 败因 = 连这一次原生广播也删了, 切站后首页不刷新。
    //      v28 = 只保留这唯一一次原生广播。 ----



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

    /** 替代原 tabs.setOnCheckedChangeListener 的 lambda; Pro 版新增: 同步刷新 Tab 高亮样式 */
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
            owner.refreshTabStyles(group, checkedId);   // Pro 版: 选中白色加粗 / 未选中灰色
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

    /** Pro 版新增: 替代 closeBtn 的匿名 OnClickListener —— 点击播放退出动画后关闭弹窗 */
    private static final class FloatCloseClick implements View.OnClickListener {
        private final FloatSpider owner;
        FloatCloseClick(FloatSpider owner) { this.owner = owner; }
        @Override public void onClick(View v) {
            owner.dismissWithAnimation();
        }
    }

    /** Pro 版新增: 替代 dismissWithAnimation 里的匿名 Runnable —— 动画结束后 dismiss */
    private static final class FloatDismissTask implements Runnable {
        private final Dialog dialog;
        FloatDismissTask(Dialog dialog) { this.dialog = dialog; }
        @Override public void run() {
            try { dialog.dismiss(); } catch (Throwable ignored) {}
        }
    }
}
