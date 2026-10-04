package com.github.catvod.spider;

import android.app.Activity;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Window;
import android.webkit.WebView;

import com.github.catvod.crawler.Spider;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

/**
 * FmController — WebView 生命周期 + Chrome/Toolbar/Viewport 状态管理.
 *
 * <p>从原版 WebHome 1456 行中抽出来, 让 DefaultFmActionHandler 通过 controller 回调
 * 改 chrome 模式 / 工具栏 / viewport, 而不是 no-op.
 */
public final class FmController {

    private static final String TAG = "FmController";

    private final Activity activity;
    private final WebView webView;
    private final FmActionHandler handler;
    private final WeakReference<Activity> activityRef;

    // Chrome 状态
    private String chromeMode = "edge";  // normal / edge / immersive
    private boolean toolbarVisible = true;
    private boolean systemBarsHidden = false;

    // Viewport
    private int safeTop = 0, safeBottom = 0, safeLeft = 0, safeRight = 0;
    private int statusBarHeight = 0, navigationBarHeight = 0;
    private int viewWidth = 0, viewHeight = 0;
    private float density = 1.0f;

    public FmController(Activity activity, WebView webView, FmActionHandler handler) {
        this.activity = activity;
        this.webView = webView;
        this.handler = handler;
        this.activityRef = new WeakReference<>(activity);
        if (activity != null) {
            this.density = activity.getResources().getDisplayMetrics().density;
        }
    }

    // ============== 来自 FmActionHandler 的回调 (setChrome 等) ==============

    public void onSetChrome(String optionsJson) {
        try {
            JSONObject options = new JSONObject(optionsJson);
            String mode = options.optString("mode", "edge");
            if (!TextUtils.isEmpty(mode)) {
                this.chromeMode = mode;
            }
            // 应用 chrome 模式 (尝试反射调壳的 ApiConfig, 失败本地记)
            applyChromeMode();
            injectViewportCss();
        } catch (JSONException ignored) {}
    }

    public void onRestoreChrome() {
        this.chromeMode = "edge";
        applyChromeMode();
        injectViewportCss();
    }

    public void onSetToolbar(boolean visible) {
        this.toolbarVisible = visible;
        applyToolbar();
    }

    public String onGetViewport() {
        try {
            JSONObject v = new JSONObject();
            v.put("chromeMode", chromeMode);
            v.put("toolbar", toolbarVisible);
            v.put("safeTop", safeTop);
            v.put("safeBottom", safeBottom);
            v.put("safeLeft", safeLeft);
            v.put("safeRight", safeRight);
            v.put("statusBarHeight", statusBarHeight);
            v.put("navigationBarHeight", navigationBarHeight);
            v.put("width", viewWidth > 0 ? viewWidth : 1920);
            v.put("height", viewHeight > 0 ? viewHeight : 1080);
            v.put("density", density);
            return v.toString();
        } catch (JSONException e) {
            return "{\"chromeMode\":\"" + chromeMode + "\"}";
        }
    }

    public boolean handleBack() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return false;
    }

    public void reload() {
        if (webView != null) webView.reload();
    }

    /**
     * 同步 evaluateJavascript. expr 可以是值、函数调用、或返回 Promise 的表达式。
     * 异步结果通过 window.__fmSyncResult(token, json) 回传, 调用线程阻塞等待。
     */
    public String evaluateJavascriptSync(String expr, long timeoutMs) {
        if (webView == null) return null;
        final StringBuilder result = new StringBuilder();
        final boolean[] done = {false};
        final String token = "s" + System.nanoTime();
        pending.put(token, new Pending(result, done));
        final String js =
                "(function(){try{" +
                "var __t=" + JSONObject.quote(token) + ";" +
                "function __ok(v){try{if(window._nativeBridge&&window._nativeBridge.syncResult){window._nativeBridge.syncResult(__t, JSON.stringify(v==null?'':v));}else if(window.fongmiBridge&&window.fongmiBridge.syncResult){window.fongmiBridge.syncResult(__t, JSON.stringify(v==null?'':v));}}catch(e){}}" +
                "var r=(" + expr + ");" +
                "if(r&&typeof r.then==='function'){r.then(function(v){__ok(v);}).catch(function(){__ok('');});}" +
                "else{__ok(r);}" +
                "}catch(e){try{if(window._nativeBridge&&window._nativeBridge.syncResult)window._nativeBridge.syncResult(" + JSONObject.quote(token) + ",'\"\"');}catch(e2){}}})()";
        try {
            Runnable run = () -> {
                try { webView.evaluateJavascript(js, null); } catch (Throwable t) {
                    complete(token, "");
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) run.run();
            else new android.os.Handler(Looper.getMainLooper()).post(run);
            synchronized (result) {
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (!done[0]) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) break;
                    try { result.wait(remain); } catch (InterruptedException ie) { break; }
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("FmController", "evaluateJavascriptSync failed: " + t.getMessage());
        } finally {
            pending.remove(token);
        }
        return result.toString();
    }

    public static void complete(String token, String value) {
        if (token == null) return;
        Pending p = pending.remove(token);
        if (p == null) return;
        synchronized (p.result) {
            if (value != null) p.result.append(value);
            p.done[0] = true;
            p.result.notifyAll();
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Pending> pending = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class Pending {
        final StringBuilder result;
        final boolean[] done;
        Pending(StringBuilder result, boolean[] done) {
            this.result = result;
            this.done = done;
        }
    }

    // ============== Chrome/Toolbar 应用 ==============

    /**
     * 应用 chrome 模式 (尝试反射调壳的 ApiConfig, 失败本地记).
     * chromeMode: normal/edge/immersive
     */
    private void applyChromeMode() {
        if (activity == null) return;
        Window window = activity.getWindow();
        if (window == null) return;
        try {
            switch (chromeMode) {
                case "immersive":
                    systemBarsHidden = true;
                    window.getDecorView().setSystemUiVisibility(
                            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                    break;
                case "edge":
                    systemBarsHidden = false;
                    window.getDecorView().setSystemUiVisibility(
                            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                    break;
                case "normal":
                default:
                    systemBarsHidden = false;
                    window.getDecorView().setSystemUiVisibility(0);
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyChromeMode failed: " + t.getMessage());
        }
    }

    private void applyToolbar() {
        // 反射调壳的 Setting 设置 toolbar
        try {
            Class<?> settingCls = Class.forName("com.fongmi.android.tv.setting.Setting");
            settingCls.getMethod("setToolbar", boolean.class).invoke(null, toolbarVisible);
        } catch (Throwable t) {
            Log.d(TAG, "Setting.setToolbar not available: " + t.getMessage());
        }
    }

    private void injectViewportCss() {
        if (webView == null) return;
        // 注入 CSS 变量, 页面可以用 var(--fm-safe-top) 等
        try {
            String js = "javascript:(function(){" +
                    "var s=document.documentElement.style;" +
                    "s.setProperty('--fm-chrome-mode','" + chromeMode + "');" +
                    "s.setProperty('--fm-safe-top','" + pxToDp(safeTop) + "px');" +
                    "s.setProperty('--fm-safe-bottom','" + pxToDp(safeBottom) + "px');" +
                    "s.setProperty('--fm-safe-left','" + pxToDp(safeLeft) + "px');" +
                    "s.setProperty('--fm-safe-right','" + pxToDp(safeRight) + "px');" +
                    "s.setProperty('--fm-status-bar','" + pxToDp(statusBarHeight) + "px');" +
                    "s.setProperty('--fm-nav-bar','" + pxToDp(navigationBarHeight) + "px');" +
                    "})()";
            webView.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.d(TAG, "injectViewportCss failed: " + t.getMessage());
        }
    }

    private int pxToDp(int px) {
        return (int) (px / density);
    }

    // ============== Viewport 测量 ==============

    public void updateViewport(int width, int height) {
        this.viewWidth = width;
        this.viewHeight = height;
        // 尝试读系统 insets (反射, 失败用 0)
        tryMeasureInsets();
        injectViewportCss();
    }

    private void tryMeasureInsets() {
        if (activity == null) return;
        try {
            // 反射: WindowInsetsCompat.getInsets (androidx.core)
            Class<?> compatCls = Class.forName("androidx.core.view.WindowInsetsCompat");
            Object insets = compatCls.getMethod("getRootWindowInsets",
                    android.view.View.class).invoke(null, activity.getWindow().getDecorView());
            if (insets != null) {
                Object systemBars = compatCls.getMethod("getInsets", int.class).invoke(insets, 0);
                if (systemBars != null) {
                    this.statusBarHeight = (int) systemBars.getClass().getField("top").getInt(systemBars);
                    this.navigationBarHeight = (int) systemBars.getClass().getField("bottom").getInt(systemBars);
                }
            }
        } catch (Throwable t) {
            // androidx.core 不存在, 用 0
        }
    }
}