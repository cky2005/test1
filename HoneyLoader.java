package com.github.catvod.spider;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.github.catvod.crawler.Spider;

import com.whl.quickjs.wrapper.JSArray;
import com.whl.quickjs.wrapper.JSCallFunction;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.ModuleLoader;
import com.whl.quickjs.wrapper.QuickJSContext;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * HoneyLoader —— 供 FloatSpider 在「宿主 Spider Loader 被壳混淆、找不到」时调用的备用加载器。
 *
 * <p>思路: 蜂蜜系壳(FongMi/TV 的 R8 混淆构建)把 FongMi 自写的
 * {@code api.loader.BaseLoader#getSpider} 等包装类改名了, 但壳内两个<b>第三方引擎库未被混淆</b>,
 * 可直接驱动:
 * <ul>
 *   <li>JS: {@code com.whl.quickjs.wrapper.*} (quickjs-wrapper)</li>
 *   <li>PY: {@code com.chaquo.python.*} (Chaquopy)</li>
 * </ul>
 * 本类复刻 FongMi 的 {@code quickjs/crawler/Spider} 与 {@code chaquo/Loader + python/app.py} 契约,
 * 从而绕开被混淆的宿主 Loader。
 *
 * <p><b>对 FloatSpider 的影响</b>: 只在 {@code loader==null} 时被调用一次; 正常壳永远走不到这里。
 * 加载成功后本对象被直接赋给 {@code FloatSpider.delegateSpider},
 * 后续所有调用复用 FloatSpider 原有的 invokeDelegate 反射路径 —— 原逻辑零改动。
 *
 * <p><b>铁律</b>: 全部必须是顶层类, 严禁嵌套/匿名类(否则 d8 生成 {@code HoneyLoader$x.class} 会崩)。
 * 所有回调一律用 lambda(invokedynamic, 不生成 class 文件)。
 */
class HoneyLoader extends Spider {

    private static final String TAG = "HoneyLoader";

    private final Context context;
    private final String content;
    private final String apiURL;    // v30: py 探针用 —— app.py 的 spider(cache, api) 第二参数必须是 api URL
    private final String name;
    private final File scriptDir;   // 脚本所在目录(供 import 同目录模块用)

    private HoneyJsEngine js;
    private HoneyPyEngine py;

    private HoneyLoader(Context context, String fileName, String content, String apiURL, File scriptDir) {
        this.context = context;
        this.content = content;
        this.apiURL = apiURL;
        this.name = fileName;
        this.scriptDir = scriptDir;
    }

    /**
     * 尝试以蜂蜜壳方式加载脚本。
     *
     * @param activity 调用方 Activity(取 context / Toast 用)
     * @param file     脚本文件(仅用于取文件名)
     * @param content  脚本正文(调用方已读取并做过 sanitize/清洗)
     * @param apiURL   站点 api(HTTP URL, 形如 http://127.0.0.1:9978/file/...)。PY 探针必用;
     *                 壳 app.py 的 spider(cache, api): basename(api) 作落盘文件名,
     *                 http 开头则 requests 下载落盘, 非 http 会把 api 字符串本身当脚本
     *                 内容写文件 —— 传正文/本地路径都会炸(Errno 36 / 语法错误)。
     * @param isJs     true = JS(QuickJS), false = PY(Chaquopy)
     * @return true = 已接管(调用方直接 return 即可); false = 未接管(调用方保持原报错)
     */
    static boolean tryLoad(Activity activity, File file, String content, String apiURL, boolean isJs) {
        HoneyLoader loader = null;
        provenReset();  // v31: 每次加载重置探针结论(防上一次 py 残留)
        try {
            Blog.line("[HoneyLoader] tryLoad 进入: file=" + file.getName() + ", isJs=" + isJs
                    + ", apiURL=" + (apiURL == null ? "null" : apiURL));
            if (content == null || content.trim().isEmpty()) {
                Blog.line("[HoneyLoader] 内容为空, 放弃");
                return false;
            }
            String fileName = file.getName();
            String text = content;
            // v30: 删除旧"正文+尾注"拼接 —— 实测壳 app.py 先 basename(api) 再写文件,
            // 2 万字符正文活不过 basename 一步(OSError [Errno 36] File name too long)。
            // 正文仅用于 JS 引擎直接求值, PY 走 apiURL 由 app.py 下载落盘(壳原生同款)。

            File scriptDir = file.getParentFile();
            Blog.line("[HoneyLoader] 脚本目录: " + (scriptDir == null ? "null" : scriptDir.getAbsolutePath()));
            loader = new HoneyLoader(activity, fileName, text, apiURL, scriptDir);
            Blog.line("[HoneyLoader] 创建实例完成, 准备启动引擎");

            if (isJs) {
                Blog.line("[HoneyLoader] 启动 QuickJS 引擎...");
                loader.js = new HoneyJsEngine(loader);
                loader.js.setup();
                Blog.line("[HoneyLoader] QuickJS 引擎启动完成");
            } else {
                Blog.line("[HoneyLoader] 启动 Chaquopy 引擎...");
                loader.py = new HoneyPyEngine(loader);
                loader.py.setup();
                Blog.line("[HoneyLoader] Chaquopy 引擎启动完成");
            }

            // 引擎起来 ≠ 脚本能用。探一次 homeContent, 拿不到有效内容就算加载失败,
            // 让调用方保持原有行为(不接管, 后续仍走原兜底逻辑)。
            Blog.line("[HoneyLoader] 探针: 调用 homeContent(true) ...");
            String probe = loader.dispatch("homeContent", Boolean.TRUE);
            Blog.line("[HoneyLoader] 探针返回: " + (probe == null ? "null" : "(" + probe.length() + " chars) " + brief(probe)));
            if (probe == null || probe.trim().isEmpty()) {
                Log.w(TAG, "脚本无响应(homeContent 返回空), 视为加载失败: " + file.getAbsolutePath());
                Blog.line("[HoneyLoader] 探针返回空 -> 判定加载失败");
                if (loader.js != null) loader.js.destroy();
                return false;
            }

            Log.d(TAG, "蜂蜜壳加载成功: " + file.getAbsolutePath());
            Blog.line("[HoneyLoader] 加载成功");
            // v41b: 加载已挪后台线程 —— Toast.show 需 Looper, 经 main 投递(命名内部类, 铁律: 无匿名/编号类)
            new Handler(Looper.getMainLooper()).post(new LoadedToastTask(activity, fileName));
            last = loader;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "蜂蜜壳加载失败", t);
            Blog.line("[HoneyLoader] tryLoad 异常", t);
            // 引擎可能已半初始化, 释放防泄漏
            if (loader != null && loader.js != null) loader.js.destroy();
            return false;
        }
    }

    /** 日志用: 截断长字符串, 只留前 200 字符 */
    private static String brief(String s) {
        if (s == null) return "null";
        String t = s.replace("\n", " ").replace("\r", " ");
        return t.length() > 200 ? t.substring(0, 200) + " ...(截断)" : t;
    }

    /** 取最近一次成功加载的实例, 供调用方挂到 delegateSpider。 */
    static Spider lastLoaded() {
        return last;
    }

    private static volatile Spider last;

    // ===================== Spider 契约(由 FloatSpider.invokeDelegate 反射调用) =====================

    public void init(Context context, String extend) {
    }

    public String homeContent(boolean filter) {
        return dispatch("homeContent", filter);
    }

    public String homeVideoContent() {
        return dispatch("homeVideoContent");
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return dispatch("categoryContent", tid, pg, filter, extend);
    }

    public String detailContent(List<String> ids) {
        return dispatch("detailContent", ids);
    }

    public String searchContent(String key, boolean quick) {
        return dispatch("searchContent", key, quick);
    }

    public String searchContent(String key, boolean quick, String pg) {
        return dispatch("searchContent", key, quick, pg);
    }

    public String playerContent(String flag, String id, List<String> vipFlags) {
        return dispatch("playerContent", flag, id, vipFlags);
    }

    public String action(String action) {
        return dispatch("action", action);
    }

    /**
     * 统一出口: 转给 JS/PY 引擎。
     * 失败返回 null, 与原 invokeDelegate 返回 null 的行为一致, 使 FloatSpider 的兜底逻辑照常生效。
     */
    private String dispatch(String method, Object... args) {
        try {
            boolean isJs = js != null;
            Blog.line("[dispatch] " + method + " (引擎=" + (isJs ? "JS" : "PY") + ")");
            if ("homeContent".equals(method)) {
                return isJs ? asString(js.call("home", args[0])) : pyStr(py.call("homeContent", args[0]));
            }
            if ("homeVideoContent".equals(method)) {
                return isJs ? asString(js.call("homeVod")) : pyStr(py.call("homeVideoContent"));
            }
            if ("categoryContent".equals(method)) {
                if (isJs) return asString(js.call("category", args[0], args[1], args[2], js.toJsObject(asMap(args[3]))));
                return pyStr(py.call("categoryContent", args[0], args[1], args[2], json(args[3])));
            }
            if ("detailContent".equals(method)) {
                List<String> ids = asList(args[0]);
                return isJs ? asString(js.call("detail", ids.isEmpty() ? "" : ids.get(0))) : pyStr(py.call("detailContent", json(ids)));
            }
            if ("playerContent".equals(method)) {
                if (isJs) return asString(js.call("play", args[0], args[1], js.toJsArray(asList(args[2]))));
                return pyStr(py.call("playerContent", args[0], args[1], json(asList(args[2]))));
            }
            if ("action".equals(method)) {
                return isJs ? asString(js.call("action", args[0])) : pyStr(py.call("action", args[0]));
            }
            if ("searchContent".equals(method)) {
                if (isJs) return asString(args.length == 2 ? js.call("search", args[0], args[1]) : js.call("search", args[0], args[1], args[2]));
                return pyStr(py.call("searchContent", args));
            }
        } catch (Throwable t) {
            Log.e(TAG, "调用失败: " + method, t);
            Blog.line("[dispatch] " + method + " 异常", t);
        }
        return null;
    }

    /**
     * PyObject → String, 并把 None / 空串一律视作"无结果"返回 null。
     *
     * <p><b>为什么必须这样</b>: PyObject.toString() 对 Python 的 None 会给出字符串 "None",
     * 而 "None" 是非 null 的 String —— 会让 FloatSpider.homeContent 里的
     * {@code if (r instanceof String) return (String) r;} 直接把它当结果返回给壳,
     * 壳解析 "None" 失败, 表现为"获取不到爬虫列表"。同时也导致 FloatSpider 原有的
     * buildHomeClasses()/buildFloatItems() 兜底永不生效。
     */
    private static String pyStr(Object o) {
        if (o == null) return null;
        String s;
        try {
            s = o.toString();
        } catch (Throwable t) {
            return null;
        }
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || "None".equals(t) || "null".equals(t)) return null;
        return s;
    }

    // ===================== 工具 =====================

    private static String asString(Object v) {
        if (v == null) return null;
        if (v instanceof String) return (String) v;
        if (v instanceof JSObject) return ((JSObject) v).stringify();
        return String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> asMap(Object o) {
        return o instanceof Map ? (Map<String, String>) o : new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Object o) {
        return o instanceof List ? (List<String>) o : new ArrayList<>();
    }

    private static String json(Object o) {
        try {
            return new com.google.gson.Gson().toJson(o);
        } catch (Throwable t) {
            return "{}";
        }
    }

    private static String readText(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) bos.write(buf, 0, len);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    Context context() { return context; }
    String content() { return content; }

    String name() { return name; }

    /** v30: py 探针用 api URL(app.py 的 spider(cache, api) 第二参数) */
    String apiURL() {
        return apiURL == null ? "" : apiURL;
    }

    // v31: py 探针实际验证成功的 api 形态(null=未验证)。壳的 app.py 版本分裂:
    //   新版(混淆壳): http 开头 requests 下载落盘 —— URL 形态可用
    //   旧版: 非 http 把 api 字符串本身当脚本内容写文件 —— 内容形态可用, URL 必炸
    // 探针是唯一能在注册前分辨两者的环节, 探通的形态即注册应使用的形态。
    private static volatile String proven;

    static void provenReset() { proven = null; }

    static void provenSet(String api) { proven = api; }

    /** 取探针验证过的 api 形态; 未验证(或 js 流程)时回退调用方原值 */
    static String provenApi(String fallback) {
        return proven != null ? proven : fallback;
    }
    String fileName() { return name; }

    /** 脚本所在目录(供 QuickJS import 同目录模块) */
    File scriptDir() { return scriptDir; }

    /**
     * 供 QuickJS 模块系统使用的"安全模块名"。
     * 原始文件名可能含 [ ] ( ) 空格 中文 等字符, QuickJS 的模块名规范化会出问题,
     * 导致 ModuleLoader.getModuleStringCode 拿到 null -> "string code was null"。
     * 这里统一生成一个只含 ASCII 字母数字下划线的稳定名字。
     */
    String moduleName() {
        if (safeName == null) {
            StringBuilder sb = new StringBuilder("spider_");
            for (int i = 0; i < name.length(); i++) {
                char ch = name.charAt(i);
                if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                        || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-' || ch == '.') {
                    sb.append(ch);
                } else {
                    sb.append('_');
                }
            }
            safeName = sb.toString();
        }
        return safeName;
    }

    private String safeName;
}

/**
 * JS 引擎: 直连壳内 com.whl.quickjs.wrapper.QuickJSContext 加载 catvod js 爬虫。
 * 必须是顶层类(铁律)。
 */
class HoneyJsEngine {

    private final HoneyLoader owner;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private QuickJSContext ctx;
    private JSObject jsSpider;

    HoneyJsEngine(HoneyLoader owner) { this.owner = owner; }

    /**
     * 启动引擎。铁律: QuickJSContext 的 create/evaluate/getProperty/call 必须在同一线程,
     * 所以 setup 也必须提交到与 call() 相同的单线程 executor, 否则报
     * "Must be call same thread in QuickJSContext.create!"。
     */
    void setup() throws Exception {
        executor.submit(new JsSetupTask(this)).get();
    }

    /** JsSetupTask 调用体(必须跑在引擎线程上) */
    void setupSync() throws Exception {
        Blog.line("[JS] QuickJSContext.create() ...");
        final QuickJSContext c = QuickJSContext.create();
        ctx = c;
        Blog.line("[JS] 注册 JsBridge (_http/md5X/joinUrl/local/s2t/t2s) ...");
        JsBridge.register(c, owner);                       // _http / md5X / joinUrl / local
        Blog.line("[JS] evaluate HTTP_JS 模板 ...");
        c.evaluate(HoneyTemplates.HTTP_JS);                // req / http / global 别名
        Blog.line("[JS] setModuleLoader ...");
        c.setModuleLoader(new HoneyModuleLoader(c, owner)); // 供 spider.js 的 import 解析
        String user = owner.content().replace("__JS_SPIDER__", "globalThis.__JS_SPIDER__");
        // 模块名必须是"安全"的: QuickJS 的模块解析对 [ ] ( ) 空格 等字符敏感,
        // 用原始文件名会让 normalize 后匹配不上 ModuleLoader, 报 "string code was null"。
        final String modName = owner.moduleName();
        Blog.line("[JS] evaluateModule 用户脚本 (" + user.length() + " chars)");
        Blog.line("[JS]   原始文件名: " + owner.fileName());
        Blog.line("[JS]   安全模块名: " + modName);
        // 首选: 按 ES Module 求值(支持 import/export)
        boolean moduleOk = false;
        try {
            c.evaluateModule(user, modName);
            moduleOk = true;
            Blog.line("[JS] 用户脚本 evaluateModule 成功");
        } catch (Throwable t) {
            Blog.line("[JS] evaluateModule 失败, 降级为 evaluate(普通脚本)", t);
        }

        if (moduleOk) {
            Blog.line("[JS] evaluateModule SPIDER_JS 模板 ...");
            String spiderTpl = String.format(HoneyTemplates.SPIDER_JS, modName);
            try {
                c.evaluateModule(spiderTpl, modName + "_spider");
            } catch (Throwable t) {
                Blog.line("[JS] SPIDER_JS 模板 evaluateModule 失败, 改用 evaluate", t);
                c.evaluate(HoneyTemplates.SPIDER_JS_EVAL);
            }
        } else {
            // 降级: 直接把用户脚本当普通脚本求值(多数脚本用 export default / __jsEvalReturn 都能跑)
            Blog.line("[JS] 降级路径: evaluate(用户脚本) ...");
            c.evaluate(user);
            Blog.line("[JS] 降级路径: evaluate(SPIDER_JS_EVAL) ...");
            c.evaluate(HoneyTemplates.SPIDER_JS_EVAL);
        }
        Blog.line("[JS] 读取 globalThis.__JS_SPIDER__ ...");
        Object obj = c.getGlobalObject().getProperty("__JS_SPIDER__");
        Blog.line("[JS] __JS_SPIDER__ 类型: " + (obj == null ? "null" : obj.getClass().getName()));
        if (!(obj instanceof JSObject)) throw new Exception("JS 脚本未导出 __JS_SPIDER__");
        jsSpider = (JSObject) obj;
        Blog.line("[JS] 引擎就绪");
    }

    Object call(final String func, final Object... args) throws Exception {
        return executor.submit(new JsCallTask(this, func, args)).get();
    }

    /** JsCallTask 调用体(原 lambda 体) */
    Object callSync(String func, Object[] args) throws Exception {
        JSFunction f = jsSpider.getJSFunction(func);
        if (f == null) throw new Exception("JS 未实现方法: " + func);
        Object r;
        try { r = f.call(args); } finally { f.release(); }
        return unwrap(r);
    }

    /** 若返回 Promise, 等它 resolve。 */
    private Object unwrap(Object r) throws Exception {
        if (!(r instanceof JSObject)) return r;
        JSObject obj = (JSObject) r;
        JSFunction then = obj.getJSFunction("then");
        if (then == null) return obj;
        final CompletableFuture<Object> fut = new CompletableFuture<>();
        try {
            then.call(new ThenHandler(fut));
        } finally {
            then.release();
        }
        return fut.get(60, TimeUnit.SECONDS);
    }

    JSObject toJsObject(Map<String, String> map) {
        JSObject o = ctx.createNewJSObject();
        if (map != null) for (String k : map.keySet()) o.setProperty(k, map.get(k) == null ? "" : map.get(k));
        return o;
    }

    JSArray toJsArray(List<String> list) {
        JSArray a = ctx.createNewJSArray();
        if (list != null) for (int i = 0; i < list.size(); i++) a.set(list.get(i), i);
        return a;
    }

    /** 释放引擎(同样必须在引擎线程上执行)。加载失败路径调用, 防止 native 内存泄漏。 */
    void destroy() {
        try {
            executor.submit(new JsDestroyTask(this)).get(5, TimeUnit.SECONDS);
        } catch (Throwable t) {
            Blog.line("[JS] destroy 提交异常", t);
        }
    }

    /** JsDestroyTask 调用体(必须跑在引擎线程上) */
    void destroySync() {
        try {
            if (jsSpider != null) { jsSpider.release(); jsSpider = null; }
            if (ctx != null) { ctx.destroy(); ctx = null; }
            Blog.line("[JS] 引擎已释放");
        } catch (Throwable t) {
            Blog.line("[JS] destroy 异常", t);
        }
    }
}

/**
 * PY 引擎: 直连壳内 com.chaquo.python.*(Chaquopy)加载 catvod py 爬虫。
 * 必须是顶层类(铁律)。
 */
class HoneyPyEngine {

    private final HoneyLoader owner;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Object app;     // com.chaquo.python.PyObject
    private Object spider;  // 用户 Spider 实例

    HoneyPyEngine(HoneyLoader owner) { this.owner = owner; }

    void setup() throws Exception {
        executor.submit(new PySetupTask(this)).get();
    }

    /** PySetupTask 调用体(原 lambda 体) */
    @SuppressWarnings("unchecked")
    void setupSync() throws Exception {
        try {
            Blog.line("[PY] Class.forName(com.chaquo.python.Python) ...");
            Class<?> pyCls = Class.forName("com.chaquo.python.Python");
            Blog.line("[PY] Python.getInstance() ...");
            Object python = pyCls.getMethod("getInstance").invoke(null);
            Blog.line("[PY] getModule(\"app\") ...");
            Object module = pyCls.getMethod("getModule", String.class).invoke(python, "app");
            app = module;
            Blog.line("[PY] app 模块: " + (module == null ? "null" : module.getClass().getName()));
            File cache = new File(owner.context().getCacheDir(), "honey_py");
            cache.mkdirs();
            // v31 根因修复: 壳 app.py 的 spider(cache, api) 第二参数是 api URL(非脚本正文):
            //   basename(api) 作落盘文件名 -> http 开头走 requests 下载 -> SourceFileLoader 加载。
            // 旧代码传 2 万字符正文, basename 一步即 OSError [Errno 36] File name too long(日志实锤)。
            Blog.line("[PY] 调用 app.spider(cacheDir, apiURL) ... cache=" + cache.getAbsolutePath());
            Blog.line("[PY] apiURL=" + owner.apiURL() + ", 正文长度=" + owner.content().length() + "(仅日志参考)");
            Object obj = null;
            String form = owner.apiURL();
            try {
                obj = callAttr(module, "spider", cache.getAbsolutePath(), form);
            } catch (Throwable t1) {
                // v31: URL 形态失败 -> 该壳 app.py 可能是旧版(非 http 会把 api 字符串当脚本内容
                // 写文件, 即"内容形态"契约)。换内容形态(正文+尾注, 与旧版探针成功形态一致)重试,
                // 成功则把该形态记为 proven, 注册站点时使用 —— 双壳自适应。
                Blog.line("[PY] app.spider(URL) 失败, 换内容形态重试: " + t1);
                String form2 = owner.content();
                if (!form2.endsWith("\n")) form2 = form2 + "\n";
                form2 = form2 + "# " + owner.name();
                obj = callAttr(module, "spider", cache.getAbsolutePath(), form2);
                form = form2;
            }
            HoneyLoader.provenSet(form);
            Blog.line("[PY] app.spider() 返回: " + (obj == null ? "null" : obj.getClass().getName()) + ", api 形态=" + (form.length() > 128 ? "content(" + form.length() + " chars)" : form));
            if (obj == null) throw new Exception("app.spider() 返回空");
            spider = obj;
            // v30: 补壳原生同款 init(app.py: init(ru, extend) -> ru.init(extend))。
            // py 子类可能依赖 init 建连接池/配置, 探针 homeContent 前必须初始化;
            // extend 传空串(py 惯例默认值, 避免子类 json.loads(脚本正文) 误炸)。
            Blog.line("[PY] 探针 init(ru, \"\") ...");
            callAttr(module, "init", spider, "");
            Blog.line("[PY] 引擎就绪");
        } catch (Exception e) {
            Blog.line("[PY] setup 异常", e);
            throw e;
        } catch (Throwable t) {
            Blog.line("[PY] setup 异常(Throwable)", t);
            throw new Exception(t);
        }
    }

    Object call(String func, Object... args) throws Exception {
        return executor.submit(new PyCallTask(this, func, args)).get();
    }

    /** PyCallTask 调用体(原 lambda 体) */
    Object callSync(String func, Object[] args) throws Exception {
        try {
            Object[] full = new Object[args.length + 1];
            full[0] = spider;
            System.arraycopy(args, 0, full, 1, args.length);
            return callAttr(app, func, full);
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new Exception(t);
        }
    }

    private static Object callAttr(Object target, String name, Object... args) throws Exception {
        try {
            return target.getClass().getMethod("callAttr", String.class, Object[].class).invoke(target, name, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            throw (c instanceof Exception) ? (Exception) c : new Exception(c);
        }
    }
}

// ==================================================================
//  脚本模板(与 FongMi quickjs/assets/js/lib 一致)
// ==================================================================

class HoneyTemplates {

    static final String HTTP_JS =
            "let req = (url, options) => http(url, Object.assign({async: false}, options));\n" +
            "function http(url, options = {}) {\n" +
            "    if (options?.async === false) return _http(url, options)\n" +
            "    return new Promise(resolve => _http(url, Object.assign({\n" +
            "        complete: res => resolve(res)\n" +
            "    }, options))).catch(err => {\n" +
            "        console.error(err.name, err.message, err.stack)\n" +
            "        return { ok: false, status: 500, url }\n" +
            "    })\n" +
            "}\n" +
            "function defineGlobalAlias(name) {\n" +
            "    const descriptor = Object.getOwnPropertyDescriptor(globalThis, name);\n" +
            "    if (descriptor && !descriptor.configurable) return;\n" +
            "    Object.defineProperty(globalThis, name, {\n" +
            "        enumerable: true, configurable: true,\n" +
            "        get() { return globalThis; }, set() {}\n" +
            "    });\n" +
            "}\n" +
            "['global', 'window', 'self'].forEach(defineGlobalAlias);\n";

    static final String SPIDER_JS =
            "import * as spider from '%s'\n" +
            "if (!globalThis.__JS_SPIDER__) {\n" +
            "    if (spider.__jsEvalReturn) {\n" +
            "        globalThis.req = http\n" +
            "        globalThis.__JS_SPIDER__ = spider.__jsEvalReturn()\n" +
            "    } else if (spider.default) {\n" +
            "        globalThis.__JS_SPIDER__ = typeof spider.default === 'function' ? spider.default() : spider.default\n" +
            "    }\n" +
            "}\n";

    /**
     * 不依赖 import 的降级模板:
     * 直接把脚本里可能导出的东西接到 globalThis.__JS_SPIDER__。
     * 用在 evaluateModule 失败的场合。
     */
    static final String SPIDER_JS_EVAL =
            "(function(){\n" +
            "  if (globalThis.__JS_SPIDER__) return\n" +
            "  var s = globalThis.spider || globalThis.__jsEvalReturn || null\n" +
            "  if (typeof __jsEvalReturn === 'function') { globalThis.req = http; globalThis.__JS_SPIDER__ = __jsEvalReturn() }\n" +
            "  else if (globalThis.__jsEvalReturn && typeof globalThis.__jsEvalReturn === 'function') { globalThis.req = http; globalThis.__JS_SPIDER__ = globalThis.__jsEvalReturn() }\n" +
            "  else if (typeof default_ !== 'undefined') { globalThis.__JS_SPIDER__ = typeof default_ === 'function' ? default_() : default_ }\n" +
            "})();\n";
}

/**
 * QuickJS 模块加载器: 给 spider.js 的 {@code import * as spider from '<name>'} 返回用户脚本源码。
 * 必须是顶层类(铁律): ModuleLoader 是抽象类, 匿名子类会生成 HoneyLoader$N.class 导致 d8 崩溃。
 */
class HoneyModuleLoader extends ModuleLoader {

    private final HoneyLoader owner;

    HoneyModuleLoader(QuickJSContext ctx, HoneyLoader owner) {
        this.owner = owner;
    }

    @Override public boolean isBytecodeMode() { return false; }

    @Override public byte[] getModuleBytecode(String moduleName) { return null; }

    /**
     * 模块解析: 按 catvod 约定, spider.js 会 import 三种模块。
     *
     *   1) assets://js/lib/cat.js   —— 壳内置公共库(工具函数 _ / req 等), 从 APK assets 读
     *   2) ./xxx.js 或同目录文件    —— 脚本旁边的本地文件
     *   3) 用户脚本自身             —— 返回其源码
     *
     * 【绝不能返回 null 之外的错误内容】: 以前用"什么都返回用户脚本"的兜底,
     * 会把 cat.js 的请求也喂成用户脚本, 导致 "Could not find export '_'"。
     */
    @Override public String getModuleStringCode(String moduleName) {
        Blog.line("[JS/ModuleLoader] 请求模块: " + moduleName);

        // ---- 1. assets:// 壳内置库 ----
        if (moduleName != null && moduleName.startsWith("assets://")) {
            String assetPath = moduleName.substring("assets://".length());
            String cached = loadAsset(assetPath);
            if (cached != null) {
                Blog.line("[JS/ModuleLoader]   -> 从壳 assets 读到 " + assetPath + " (" + cached.length() + " chars)");
                // 打印格式判定与前 400 字符, 便于确认它是 ESM 还是 CommonJS。
                // 压缩后的 bundle 常写作 export{a as _} (无空格), 所以 export 与 export{ 都要匹配。
                int exIdx = cached.indexOf("export");
                boolean hasEsm = exIdx >= 0;
                boolean hasCjs = cached.contains("module.exports") || cached.contains("exports.");
                Blog.line("[JS/ModuleLoader]      格式: " + (hasEsm ? "ESM(含 export, 首次出现@" + exIdx + ")" : hasCjs ? "CommonJS(含 module.exports/exports)" : "未知"));
                if (hasEsm) {
                    int s = Math.max(0, exIdx - 40);
                    int e = Math.min(cached.length(), exIdx + 120);
                    Blog.line("[JS/ModuleLoader]      export 上下文: ..." + cached.substring(s, e).replace("\n", " ") + "...");
                }
                Blog.line("[JS/ModuleLoader]      开头: " + head(cached, 400));
                Blog.line("[JS/ModuleLoader]      末尾: " + head2(cached, 300));
                return cached;
            }
            Blog.line("[JS/ModuleLoader]   -> assets 里没找到 " + assetPath);
            return null;
        }

        // ---- 2. 用户脚本自身 ----
        if (isSelf(moduleName)) {
            Blog.line("[JS/ModuleLoader]   -> 用户脚本自身, 返回源码");
            return owner.content();
        }

        // ---- 3. 脚本同目录的本地文件 ----
        String local = loadLocal(moduleName);
        if (local != null) {
            Blog.line("[JS/ModuleLoader]   -> 从同目录读到 " + moduleName + " (" + local.length() + " chars)");
            return local;
        }

        // ---- 4. 都不命中: 返回 null, 让 QuickJS 报明确错误(而不是塞错内容) ----
        Blog.line("[JS/ModuleLoader]   -> 未命中, 返回 null");
        return null;
    }

    /** 取前 n 个字符, 换行替换为可见分隔 */
    private static String head(String s, int n) {
        if (s == null) return "null";
        String t = s.replace("\r\n", "\\n").replace("\n", "\\n");
        return t.length() > n ? t.substring(0, n) + "..." : t;
    }

    /** 取字符串末尾 n 个字符(压缩 bundle 的 export 语句一般在文件末尾) */
    private static String head2(String s, int n) {
        if (s == null) return "null";
        String t = s.replace("\r\n", "\\n").replace("\n", "\\n");
        return t.length() > n ? "..." + t.substring(t.length() - n) : t;
    }

    /** 模块名是否指向用户脚本自身 */
    private boolean isSelf(String moduleName) {
        if (moduleName == null) return false;
        if (moduleName.equals(owner.moduleName())) return true;
        if (moduleName.equals(owner.fileName())) return true;
        if (moduleName.endsWith("/" + owner.fileName())) return true;
        return false;
    }

    /**
     * 从宿主 APK 的 assets 读文件。
     * assets://js/lib/cat.js  ->  assets 路径 "js/lib/cat.js"
     * 带缓存: cat.js 是公共库, 每个脚本都会 import, 避免重复读。
     */
    private String loadAsset(String assetPath) {
        if (assetPath == null || assetPath.isEmpty()) return null;
        String hit = ASSET_CACHE.get(assetPath);
        if (hit != null) return hit;
        java.io.InputStream in = null;
        try {
            Context ctx = owner.context();
            if (ctx == null) return null;
            in = ctx.getAssets().open(assetPath);
            String code = readStream(in);
            if (code != null) ASSET_CACHE.put(assetPath, code);
            return code;
        } catch (Throwable t) {
            Blog.line("[JS/ModuleLoader]   assets 读失败: " + assetPath, t);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    /** assets 缓存(静态, 跨脚本复用) */
    private static final java.util.HashMap<String, String> ASSET_CACHE = new java.util.HashMap<>();

    /** 从脚本同目录读本地模块 */
    private String loadLocal(String moduleName) {
        try {
            if (moduleName == null) return null;
            String rel = moduleName;
            if (rel.startsWith("./")) rel = rel.substring(2);
            File dir = owner.scriptDir();
            if (dir == null) return null;
            File f = new File(dir, rel);
            if (!f.isFile()) {
                // 再试: 模块名可能就是绝对路径
                f = new File(rel);
                if (!f.isFile()) return null;
            }
            return readFile(f);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readStream(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String readFile(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try { return readStream(in); } finally { in.close(); }
    }

    /**
     * 模块名归一化。
     * 相对路径基于"当前模块"解析; 其它原样返回。
     */
    @Override public String moduleNormalizeName(String baseModuleName, String moduleName) {
        if (moduleName == null) return null;
        if (moduleName.startsWith(".") && baseModuleName != null) {
            int idx = baseModuleName.lastIndexOf('/');
            if (idx >= 0) return baseModuleName.substring(0, idx + 1) + moduleName.substring(2);
        }
        return moduleName;
    }
}

/**
 * QuickJS 全局桥: 复刻 FongMi {@code method.Global} + {@code method.Local}。
 * 必须是顶层类(铁律)。
 */
class JsBridge {

    private static final String TAG = "HoneyJsBridge";

    static void register(final QuickJSContext ctx, final HoneyLoader owner) {
        JSObject g = ctx.getGlobalObject();
        g.setProperty("_http", new BridgeHttp(ctx));
        g.setProperty("md5X", new BridgeMd5());
        g.setProperty("joinUrl", new BridgeJoinUrl());

        JSObject local = ctx.createNewJSObject();
        local.setProperty("get", new BridgeLocalGet(owner));
        local.setProperty("set", new BridgeLocalSet(owner));
        local.setProperty("delete", new BridgeLocalDelete(owner));
        g.setProperty("local", local);
        g.setProperty("localStorage", local);

        g.setProperty("s2t", new BridgeIdentity());
        g.setProperty("t2s", new BridgeIdentity());
    }

    // ---------------- 网络 ----------------

    static JSObject http(QuickJSContext ctx, String url, JSObject options) {
        try {
            String method = optStr(options, "method", "get");
            String postType = optStr(options, "postType", "json");
            String body = optStr(options, "body", null);
            int timeout = optInt(options, "timeout", 10000);
            int buffer = optInt(options, "buffer", 0);
            int redirect = optInt(options, "redirect", 1);
            String data = optStr(options, "data", null);
            JSObject headers = options != null ? options.getJSObject("headers") : null;

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setInstanceFollowRedirects(redirect == 1);
            if (headers != null) {
                JSArray names = headers.getOwnPropertyNames();
                if (names != null) {
                    int n = names.length();
                    for (int i = 0; i < n; i++) {
                        Object k = names.get(i);
                        if (k == null) continue;
                        Object v = headers.getProperty(k.toString());
                        if (v != null) conn.setRequestProperty(k.toString(), v.toString());
                    }
                    names.release();
                }
            }
            if ("post".equalsIgnoreCase(method)) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                String payload = data != null ? data : (body != null ? body : "");
                OutputStream os = null;
                try {
                    os = conn.getOutputStream();
                    os.write(payload.getBytes("UTF-8"));
                } finally {
                    if (os != null) try { os.close(); } catch (Exception ignored) {}
                }
            } else if ("header".equalsIgnoreCase(method)) {
                conn.setRequestMethod("HEAD");
            } else {
                conn.setRequestMethod("GET");
            }
            int code = conn.getResponseCode();

            JSObject res = ctx.createNewJSObject();
            JSObject rh = ctx.createNewJSObject();
            for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() == null) continue;
                List<String> vals = e.getValue();
                if (vals == null || vals.isEmpty()) continue;
                if (vals.size() == 1) rh.setProperty(e.getKey(), vals.get(0));
                else {
                    JSArray a = ctx.createNewJSArray();
                    for (int i = 0; i < vals.size(); i++) a.set(vals.get(i), i);
                    rh.setProperty(e.getKey(), a);
                }
            }
            byte[] bytes = read(conn, code);
            res.setProperty("code", code);
            res.setProperty("headers", rh);
            if (buffer == 1) res.setProperty("content", toJsArray(ctx, bytes));
            else if (buffer == 3) res.setProperty("content", bytes);
            else if (buffer == 2) res.setProperty("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP));
            else res.setProperty("content", new String(bytes, charset(conn)));
            conn.disconnect();
            return res;
        } catch (Throwable t) {
            Log.e(TAG, "_http 失败: " + url, t);
            return error(ctx);
        }
    }

    private static byte[] read(HttpURLConnection conn, int code) throws Exception {
        InputStream in = null;
        try {
            in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) return new byte[0];
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) bos.write(buf, 0, len);
            return bos.toByteArray();
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    private static String charset(HttpURLConnection conn) {
        String ct = conn.getContentType();
        if (ct != null) {
            for (String s : ct.split(";")) {
                s = s.trim();
                if (s.toLowerCase().startsWith("charset=")) return s.substring(8);
            }
        }
        return "UTF-8";
    }

    private static JSObject error(QuickJSContext ctx) {
        JSObject res = ctx.createNewJSObject();
        res.setProperty("code", "");
        res.setProperty("content", "");
        res.setProperty("headers", ctx.createNewJSObject());
        return res;
    }

    private static JSArray toJsArray(QuickJSContext ctx, byte[] bytes) {
        JSArray a = ctx.createNewJSArray();
        if (bytes != null) for (int i = 0; i < bytes.length; i++) a.set((int) bytes[i], i);
        return a;
    }

    // ---------------- 本地缓存 ----------------

    static String localGet(HoneyLoader owner, Object[] args) {
        try {
            String rule = args != null && args.length > 0 && args[0] != null ? args[0].toString() : "";
            String key = args != null && args.length > 1 && args[1] != null ? args[1].toString() : "";
            return loadLocal(owner).get("cache_" + rule + "_" + key);
        } catch (Throwable t) {
            return "";
        }
    }

    static void localSet(HoneyLoader owner, Object[] args) {
        try {
            String rule = args != null && args.length > 0 && args[0] != null ? args[0].toString() : "";
            String key = args != null && args.length > 1 && args[1] != null ? args[1].toString() : "";
            String val = args != null && args.length > 2 && args[2] != null ? args[2].toString() : "";
            Map<String, String> map = loadLocal(owner);
            map.put("cache_" + rule + "_" + key, val);
            saveLocal(owner, map);
        } catch (Throwable ignored) {}
    }

    static void localDelete(HoneyLoader owner, Object[] args) {
        try {
            String rule = args != null && args.length > 0 && args[0] != null ? args[0].toString() : "";
            String key = args != null && args.length > 1 && args[1] != null ? args[1].toString() : "";
            Map<String, String> map = loadLocal(owner);
            map.remove("cache_" + rule + "_" + key);
            saveLocal(owner, map);
        } catch (Throwable ignored) {}
    }

    private static Map<String, String> loadLocal(HoneyLoader owner) {
        Map<String, String> map = new HashMap<>();
        try {
            File f = localFile(owner);
            if (!f.exists()) return map;
            InputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) bos.write(buf, 0, len);
            in.close();
            org.json.JSONObject jo = new org.json.JSONObject(new String(bos.toByteArray(), "UTF-8"));
            Iterator<String> it = jo.keys();
            while (it.hasNext()) {
                String k = it.next();
                map.put(k, jo.optString(k));
            }
        } catch (Throwable ignored) {}
        return map;
    }

    private static void saveLocal(HoneyLoader owner, Map<String, String> map) {
        try {
            org.json.JSONObject jo = new org.json.JSONObject();
            for (Map.Entry<String, String> e : map.entrySet()) jo.put(e.getKey(), e.getValue());
            OutputStream os = new FileOutputStream(localFile(owner));
            os.write(jo.toString().getBytes("UTF-8"));
            os.close();
        } catch (Throwable ignored) {}
    }

    private static File localFile(HoneyLoader owner) {
        File dir = new File(owner.context().getFilesDir(), "honey_js");
        dir.mkdirs();
        return new File(dir, "local.json");
    }

    // ---------------- 小工具 ----------------

    private static String optStr(JSObject o, String key, String def) {
        if (o == null) return def;
        try {
            Object v = o.getProperty(key);
            return v == null ? def : v.toString();
        } catch (Throwable t) {
            return def;
        }
    }

    private static int optInt(JSObject o, String key, int def) {
        if (o == null) return def;
        try {
            Object v = o.getProperty(key);
            if (v == null) return def;
            if (v instanceof Number) return ((Number) v).intValue();
            return Integer.parseInt(v.toString());
        } catch (Throwable t) {
            return def;
        }
    }

    static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    static String joinUrl(String parent, String child) {
        if (parent == null || parent.isEmpty()) return child;
        if (child == null || child.isEmpty()) return parent;
        if (child.startsWith("http")) return child;
        if (parent.endsWith("/") && child.startsWith("/")) return parent + child.substring(1);
        if (!parent.endsWith("/") && !child.startsWith("/")) return parent + "/" + child;
        return parent + child;
    }
}

// ===================== 静态内部类(替代 lambda, 避免生成编号类) =====================

/** JsBridge.register 用到的 JSCallFunction 实现 (全部为顶层之上的静态命名类) */
final class BridgeHttp implements JSCallFunction {
    private final QuickJSContext ctx;
    BridgeHttp(QuickJSContext ctx) { this.ctx = ctx; }
    public Object call(Object... args) {
        String url = args != null && args.length > 0 && args[0] != null ? args[0].toString() : null;
        JSObject options = args != null && args.length > 1 && args[1] instanceof JSObject ? (JSObject) args[1] : null;
        return JsBridge.http(ctx, url, options);
    }
}

final class BridgeMd5 implements JSCallFunction {
    public Object call(Object... args) {
        return args != null && args.length > 0 && args[0] != null ? JsBridge.md5(args[0].toString()) : "";
    }
}

final class BridgeJoinUrl implements JSCallFunction {
    public Object call(Object... args) {
        String parent = args != null && args.length > 0 && args[0] != null ? args[0].toString() : "";
        String child = args != null && args.length > 1 && args[1] != null ? args[1].toString() : "";
        return JsBridge.joinUrl(parent, child);
    }
}

final class BridgeLocalGet implements JSCallFunction {
    private final HoneyLoader owner;
    BridgeLocalGet(HoneyLoader owner) { this.owner = owner; }
    public Object call(Object... args) { return JsBridge.localGet(owner, args); }
}

final class BridgeLocalSet implements JSCallFunction {
    private final HoneyLoader owner;
    BridgeLocalSet(HoneyLoader owner) { this.owner = owner; }
    public Object call(Object... args) { JsBridge.localSet(owner, args); return null; }
}

final class BridgeLocalDelete implements JSCallFunction {
    private final HoneyLoader owner;
    BridgeLocalDelete(HoneyLoader owner) { this.owner = owner; }
    public Object call(Object... args) { JsBridge.localDelete(owner, args); return null; }
}

final class BridgeIdentity implements JSCallFunction {
    public Object call(Object... args) {
        return args != null && args.length > 0 && args[0] != null ? args[0].toString() : "";
    }
}

/** Promise then 回调 */
final class ThenHandler implements JSCallFunction {
    private final java.util.concurrent.CompletableFuture<Object> fut;
    ThenHandler(java.util.concurrent.CompletableFuture<Object> fut) { this.fut = fut; }
    public Object call(Object... a) {
        fut.complete(a != null && a.length > 0 ? a[0] : null);
        return null;
    }
}


/** JS 引擎 setup 任务(必须与 call 跑在同一线程) */
final class JsSetupTask implements java.util.concurrent.Callable<Object> {
    private final HoneyJsEngine eng;
    JsSetupTask(HoneyJsEngine eng) { this.eng = eng; }
    public Object call() throws Exception { eng.setupSync(); return null; }
}

/** JS 引擎释放任务(必须与 call 跑在同一线程) */
final class JsDestroyTask implements java.util.concurrent.Callable<Object> {
    private final HoneyJsEngine eng;
    JsDestroyTask(HoneyJsEngine eng) { this.eng = eng; }
    public Object call() { eng.destroySync(); return null; }
}

/** JS 调用任务 */
final class JsCallTask implements java.util.concurrent.Callable<Object> {
    private final HoneyJsEngine eng;
    private final String func;
    private final Object[] args;
    JsCallTask(HoneyJsEngine eng, String func, Object[] args) { this.eng = eng; this.func = func; this.args = args; }
    public Object call() throws Exception {
        return eng.callSync(func, args);
    }
}

/** Python setup 任务 */
final class PySetupTask implements java.util.concurrent.Callable<Object> {
    private final HoneyPyEngine eng;
    PySetupTask(HoneyPyEngine eng) { this.eng = eng; }
    public Object call() throws Exception { eng.setupSync(); return null; }
}

/** Python 调用任务 */
final class PyCallTask implements java.util.concurrent.Callable<Object> {
    private final HoneyPyEngine eng;
    private final String func;
    private final Object[] args;
    PyCallTask(HoneyPyEngine eng, String func, Object[] args) { this.eng = eng; this.func = func; this.args = args; }
    public Object call() throws Exception { return eng.callSync(func, args); }
}


/**
 * 调试日志(顶层类, 铁律: 不能有编号类)。
 * 只在点爬虫加载期间写入 /storage/emulated/0/bl.log, 其它时候所有调用直接返回, 零开销。
 */
final class Blog {

    static final String PATH = "/storage/emulated/0/bl.log";

    private static volatile boolean on = false;
    private static long start = 0L;

    /** 开启日志(点爬虫时调用)。清空旧日志, 只保留本次加载过程。 */
    static void begin(String scriptName) {
        on = true;
        start = System.currentTimeMillis();
        try {
            java.io.FileWriter w = new java.io.FileWriter(PATH, false);
            w.write("================================================\n");
            w.write("FloatSpider 加载日志\n");
            w.write("开始: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new java.util.Date(start)) + "\n");
            w.write("脚本: " + scriptName + "\n");
            w.write("================================================\n");
            w.close();
        } catch (Throwable ignored) {}
    }

    /** 结束日志 */
    static void end() {
        if (on) line("==== 本次加载过程结束 ====");
        on = false;
    }

    /** 开启日志(追加模式, v20): 监视会话进行中脚本加载不清空 bl.log, 保留监视记录 */
    static void beginAppend(String scriptName) {
        on = true;
        start = System.currentTimeMillis();
        try {
            java.io.FileWriter w = new java.io.FileWriter(PATH, true);
            w.write("---- 脚本加载(监视中, 追加记录): " + scriptName + " ----\n");
            w.close();
        } catch (Throwable ignored) {}
    }

    /** 写一行 */
    static void line(String msg) {
        if (!on) return;
        try {
            java.io.FileWriter w = new java.io.FileWriter(PATH, true);
            w.write(String.format(java.util.Locale.US, "[%6d ms] %s\n",
                    System.currentTimeMillis() - start, msg));
            w.close();
        } catch (Throwable ignored) {}
    }

    /** 写一行 + 异常堆栈 */
    static void line(String msg, Throwable e) {
        if (!on) return;
        line(msg);
        if (e == null) return;
        try {
            java.io.FileWriter w = new java.io.FileWriter(PATH, true);
            java.io.PrintWriter pw = new java.io.PrintWriter(w);
            e.printStackTrace(pw);
            pw.flush();
            w.close();
        } catch (Throwable ignored) {}
    }

    /** 强制写一行(不受 end() 开关限制): 供后台线程(预验证矩阵等)在加载流程结束后落盘结果 */
    static void lineForce(String msg) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(PATH, true);
            w.write(String.format(java.util.Locale.US, "[%6d ms] %s\n",
                    System.currentTimeMillis() - start, msg));
            w.close();
        } catch (Throwable ignored) {}
    }

    /** 强制写一行 + 异常堆栈 */
    static void lineForce(String msg, Throwable e) {
        lineForce(msg);
        if (e == null) return;
        try {
            java.io.FileWriter w = new java.io.FileWriter(PATH, true);
            java.io.PrintWriter pw = new java.io.PrintWriter(w);
            e.printStackTrace(pw);
            pw.flush();
            w.close();
        } catch (Throwable ignored) {}
    }
}

/** 命名静态内部类(铁律): main 线程 show 加载成功 toast(v41b 加载挪后台后 Toast 需 Looper) */
final class LoadedToastTask implements Runnable {
    private final Activity activity;
    private final String fileName;
    LoadedToastTask(Activity a, String n) { activity = a; fileName = n; }   // 显式构造器(铁律)
    @Override
    public void run() {
        try { Toast.makeText(activity, "已加载: " + fileName, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }
}
