# -*- coding: utf-8 -*-
import sys, os, json, time, threading, urllib.request, urllib.parse, http.cookiejar, traceback, gzip

try:
    from java import jclass, dynamic_proxy
except ImportError:
    jclass = None
    dynamic_proxy = None

from base.spider import Spider

CONFIG = {}

# 验证后的 cookie 保存目录（文件名 = <网站域名>.json）
CF_COOKIE_DIR = '/storage/emulated/0/tmp/123'

# 内存环形日志（供「日志」条目回看）
_LOG_BUF = []
_LOG_MAX = 300

# 0jable.py 实测有效的标准 UA
UA_DESKTOP = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
             "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
UA_MOBILE = ("Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 "
             "(KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1")

# Cloudflare 挑战页特征(命中即判定为"没拿到正文") —— 完全对齐 0jable.py
_CHALLENGE = ("just a moment", "cf_chl_opt", "cf-browser-verification", "attention required",
              "enable javascript and cookies", "checking your browser", "cf-mitigated")

_dialog_proxy_classes = {}

def _ensure_dialog_proxy_classes():
    """懒初始化并缓存 dynamic_proxy 子类（模块级锚定，防 GC 崩溃）。"""
    if _dialog_proxy_classes:
        return _dialog_proxy_classes
    Runnable = jclass("java.lang.Runnable")
    DialogInterface = jclass("android.content.DialogInterface")

    class _RunProxy(dynamic_proxy(Runnable)):
        def run(self):
            fn = getattr(self, "_fn", None)
            if fn is not None:
                try:
                    fn()
                except Exception:
                    try:
                        if getattr(self, "_log", None) is not None:
                            self._log("UI 执行异常:\n" + traceback.format_exc())
                    except Exception:
                        print(traceback.format_exc())

    class _ClickProxy(dynamic_proxy(DialogInterface.OnClickListener)):
        def onClick(self, dialog, which):
            fn = getattr(self, "_fn", None)
            if fn is not None:
                try:
                    fn(dialog, which)
                except Exception:
                    try:
                        if getattr(self, "_log", None) is not None:
                            self._log("点击回调异常:\n" + traceback.format_exc())
                    except Exception:
                        print(traceback.format_exc())

    _dialog_proxy_classes["run"] = _RunProxy
    _dialog_proxy_classes["click"] = _ClickProxy
    return _dialog_proxy_classes


# ================= WebView 直连抓取专用（独立新增, 不与原弹窗共用代理缓存） =================
_WV_PROXY_CLASSES = {}

def _ensure_wv_proxy_classes():
    """WebView直连抓取专用 dynamic_proxy 子类（独立缓存锚定, 防 GC 崩溃）。
    注意: dynamic_proxy 只能代理 Java 接口(Runnable/ValueCallback),
    WebViewClient 是类不能代理 —— 参考原版弹窗用裸 WebViewClient()。"""
    if _WV_PROXY_CLASSES:
        return _WV_PROXY_CLASSES
    Runnable = jclass("java.lang.Runnable")
    ValueCallback = jclass("android.webkit.ValueCallback")

    class _WvRunP(dynamic_proxy(Runnable)):
        def run(self):
            fn = getattr(self, "_fn", None)
            if fn is not None:
                try:
                    fn()
                except Exception:
                    try:
                        if getattr(self, "_log", None) is not None:
                            self._log("延迟任务异常:\n" + traceback.format_exc())
                    except Exception:
                        print(traceback.format_exc())

    class _ValueCbP(dynamic_proxy(ValueCallback)):
        def onReceiveValue(self, value):
            fn = getattr(self, "_fn", None)
            if fn is not None:
                try:
                    fn(value)
                except Exception:
                    try:
                        if getattr(self, "_log", None) is not None:
                            self._log("取值回调异常:\n" + traceback.format_exc())
                    except Exception:
                        print(traceback.format_exc())

    _WV_PROXY_CLASSES["run"] = _WvRunP
    _WV_PROXY_CLASSES["valuecb"] = _ValueCbP
    return _WV_PROXY_CLASSES


def dp2px(act, dp):
    TypedValue = jclass("android.util.TypedValue")
    metrics = act.getResources().getDisplayMetrics()
    return int(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, float(dp), metrics))


def Typeface_MONOSPACE(act):
    tf = jclass("android.graphics.Typeface")
    return tf.MONOSPACE


class MiniDialog(object):
    """极简弹窗实现（仅含本示例所需方法）。"""

    def __init__(self, spider=None, config=None):
        cfg = config or {}
        self.spider = spider
        self.width_ratio = float(cfg.get("width_ratio", 0.88))
        self.max_refs = int(cfg.get("max_refs", 60))
        self._refs = []

    def log(self, msg):
        try:
            if self.spider is not None and hasattr(self.spider, "_log"):
                self.spider._log(msg)
                return
        except Exception:
            pass
        try:
            print("[%s] [Dialog] %s" % (time.strftime("%H:%M:%S"), msg))
        except Exception:
            pass

    def _activity(self):
        try:
            AT = jclass("java.lang.Class").forName("android.app.ActivityThread")
            cur = AT.getMethod("currentActivityThread").invoke(None)
            f = AT.getDeclaredField("mActivities")
            f.setAccessible(True)
            map_obj = f.get(cur)
            values = map_obj.values().toArray() if hasattr(map_obj, "values") else map_obj.toArray()
            for r in values:
                rc = r.getClass()
                pf = rc.getDeclaredField("paused")
                pf.setAccessible(True)
                if not pf.getBoolean(r):
                    af = rc.getDeclaredField("activity")
                    af.setAccessible(True)
                    a = af.get(r)
                    if a:
                        return a
        except Exception:
            pass
        return None

    def available(self):
        return not (jclass is None or dynamic_proxy is None)

    def _keep(self, *objs):
        self._refs.extend([o for o in objs if o is not None])
        if len(self._refs) > self.max_refs:
            self._refs = self._refs[-self.max_refs:]

    def run_on_ui(self, ui_fn):
        if not self.available():
            self.log("无 java 环境，弹窗不可用")
            return False
        act = self._activity()
        if not act:
            self.log("未获取到 Activity，弹窗不可用")
            return False
        p = _ensure_dialog_proxy_classes()

        def _make():
            r = p["run"]()
            r._fn = lambda: ui_fn(act)
            r._log = self.log
            return r

        try:
            r = _make()
            act.getWindow().getDecorView().post(r)
            self._keep(r)
        except Exception:
            Handler = jclass("android.os.Handler")
            Looper = jclass("android.os.Looper")
            r2 = _make()
            Handler(Looper.getMainLooper()).post(r2)
            self._keep(r2)
        return True

    def run_bg(self, fn, *args, **kwargs):
        def _work():
            try:
                fn(*args, **kwargs)
            except Exception as e:
                self.log("后台任务异常: " + str(e))
        t = threading.Thread(target=_work, daemon=True)
        t.start()
        return t

    def _apply_size(self, dialog, height_ratio=-1):
        try:
            window = dialog.getWindow()
            if window:
                metrics = dialog.getContext().getResources().getDisplayMetrics()
                width = int(metrics.widthPixels * self.width_ratio)
                if height_ratio and height_ratio > 0:
                    height = int(metrics.heightPixels * height_ratio)
                    window.setLayout(width, height)
                else:
                    window.setLayout(width, -2)
        except Exception as e:
            self.log("设置窗口尺寸失败: " + str(e))

    def _make_click(self, fn, *args):
        p = _ensure_dialog_proxy_classes()
        c = p["click"]()
        c._fn = lambda d, w: fn(d, w, *args)
        c._log = self.log
        self._keep(c)
        return c

    def show_log(self, title="信息", lines=None, text="", height_ratio=0.62,
                 copyable=True, on_close=None, extra_button=None):
        body = text or ""
        if lines:
            body = (body + "\n" if body else "") + "\n".join(str(x) for x in lines)

        def on_ui(act):
            Builder = jclass("android.app.AlertDialog$Builder")
            TextView = jclass("android.widget.TextView")
            ScrollView = jclass("android.widget.ScrollView")
            Color = jclass("android.graphics.Color")
            Gravity = jclass("android.view.Gravity")
            TypedValue = jclass("android.util.TypedValue")

            tv = TextView(act)
            tv.setText(body or "(空)")
            tv.setTextIsSelectable(True)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.0)
            tv.setTypeface(Typeface_MONOSPACE(act))
            tv.setTextColor(Color.parseColor("#334155"))
            tv.setLineSpacing(0, 1.25)
            pad = dp2px(act, 12)
            tv.setPadding(pad, pad, pad, pad)
            tv.setGravity(Gravity.TOP | Gravity.START)

            scroll = ScrollView(act)
            scroll.addView(tv)

            builder = Builder(act)
            builder.setTitle(str(title))
            builder.setView(scroll)

            def close(dialog, which):
                try:
                    if dialog:
                        dialog.dismiss()
                except Exception:
                    pass
                if on_close:
                    try:
                        on_close()
                    except Exception as e:
                        self.log("on_close 异常: " + str(e))

            if copyable:
                def do_copy(dialog, which):
                    try:
                        clipboard = act.getSystemService(act.CLIPBOARD_SERVICE)
                        ClipData = jclass("android.content.ClipData")
                        clipboard.setPrimaryClip(ClipData.newPlainText(str(title), body))
                        Toast = jclass("android.widget.Toast")
                        Toast.makeText(act, "已复制", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    except Exception as e:
                        self.log("复制失败: " + str(e))
                builder.setNegativeButton("复制", self._make_click(do_copy))
            if extra_button:
                try:
                    btn_label, btn_fn = extra_button
                    builder.setNeutralButton(str(btn_label), self._make_click(
                        lambda d, w: btn_fn()))
                except Exception as e:
                    self.log("添加附加按钮失败: " + str(e))
            builder.setPositiveButton("关闭", self._make_click(close))
            dialog = builder.create()
            self._keep(dialog, tv, scroll)
            dialog.show()
            self._apply_size(dialog, height_ratio)

        return self.run_on_ui(on_ui)

    def show_form(self, title, fields, on_confirm, height_ratio=-1):
        def on_ui(act):
            Builder = jclass("android.app.AlertDialog$Builder")
            LinearLayout = jclass("android.widget.LinearLayout")
            TextView = jclass("android.widget.TextView")
            EditText = jclass("android.widget.EditText")
            ScrollView = jclass("android.widget.ScrollView")
            Color = jclass("android.graphics.Color")
            TypedValue = jclass("android.util.TypedValue")
            Gravity = jclass("android.view.Gravity")
            pad = dp2px(act, 12)

            scroll = ScrollView(act)
            container = LinearLayout(act)
            container.setOrientation(LinearLayout.VERTICAL)
            container.setPadding(pad, pad, pad, pad)
            edits = {}
            for fld in fields:
                lbl = TextView(act)
                lbl.setText(fld.get("label", fld.get("key", "")))
                lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.0)
                lbl.setTextColor(Color.parseColor("#475569"))
                container.addView(lbl)
                et = EditText(act)
                et.setText(fld.get("default", "") or "")
                if fld.get("multiline"):
                    et.setSingleLine(False)
                    et.setMinLines(5)
                    et.setGravity(Gravity.TOP)
                else:
                    et.setSingleLine(True)
                et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.0)
                container.addView(et)
                edits[fld["key"]] = et
            scroll.addView(container)

            builder = Builder(act)
            builder.setTitle(str(title))
            builder.setView(scroll)
            builder.setPositiveButton("确定", self._make_click(lambda d, w: _on_ok(d, w)))
            builder.setNegativeButton("取消", self._make_click(lambda d, w: None))

            def _on_ok(d, w):
                vals = {}
                for k, et in edits.items():
                    try:
                        vals[k] = et.getText().toString()
                    except Exception:
                        vals[k] = ""
                try:
                    d.dismiss()
                except Exception:
                    pass
                try:
                    on_confirm(vals)
                except Exception as e:
                    self.log("on_confirm 异常: " + str(e))

            dialog = builder.create()
            self._keep(dialog, scroll, container)
            dialog.show()
            if height_ratio and height_ratio > 0:
                self._apply_size(dialog, height_ratio)

        return self.run_on_ui(on_ui)

    def toast(self, msg, duration=1):
        def on_ui(act):
            try:
                Toast = jclass("android.widget.Toast")
                Toast.makeText(act, str(msg), int(duration)).show()
            except Exception as e:
                self.log("Toast 异常: " + str(e))
        return self.run_on_ui(on_ui)


class Spider(Spider):

    def getName(self):
        return 'CF验证测试'

    def init(self, extend=''):
        global CONFIG
        if extend:
            try:
                ext = json.loads(extend) if isinstance(extend, str) else extend
                if isinstance(ext, dict):
                    CONFIG.update(ext)
            except Exception:
                pass
        self.dlg = MiniDialog(self)
        try:
            import requests
            self._has_requests = True
        except Exception:
            self._has_requests = False

    def _log(self, msg):
        global _LOG_BUF
        line = "[%s] %s" % (time.strftime('%H:%M:%S'), msg)
        _LOG_BUF.append(line)
        if len(_LOG_BUF) > _LOG_MAX:
            _LOG_BUF[:] = _LOG_BUF[-_LOG_MAX:]
        print(line)

    def _log_block(self, title, lines):
        self._log("==== %s ====" % title)
        for x in lines:
            self._log(x)

    def _toast(self, act, text):
        text = str(text)
        if self.dlg is not None and hasattr(self.dlg, 'toast'):
            self.dlg.toast(text)
            return
        print('[Toast] ' + text)

    def homeContent(self, filter):
        return {'class': [{'type_id': 'cf', 'type_name': '验证测试'}], 'list': []}

    def categoryContent(self, tid, pg, filter, extend):
        if tid == 'cf':
            return self._cf_category()
        return {'page': 1, 'pagecount': 1, 'limit': 10, 'total': 0, 'list': []}

    def _cf_category(self):
        items = [
            self._entry('cf::test', 'CF链接', '手动过 Cloudflare 验证测试'),
            self._entry('cf::pure_py_test', '纯Py请求获取源码(0jable同款)', '完全参考 0jable.py 的标准库策略获取源码'),
            self._entry('cf::webview_fetch', 'WebView直连抓取', '用WebView网络栈直接取页面(不触发CF挑战)'),
            self._entry('cf::webview_retry_fetch', 'WebView直连(CF重试)', 'WebView取到CF源码时自动在后台重载重试过盾'),
            self._entry('cf::log', '日志', '查看最近一次测试日志'),
            self._entry('cf::sniff_input', '自动嗅探', '输入链接直接进详情嗅探播放(带防盗链)'),
        ]
        return {'page': 1, 'pagecount': 1, 'limit': len(items),
                'total': len(items), 'list': items}

    @staticmethod
    def _entry(action_str, name, remark):
        return {'vod_id': action_str, 'vod_name': name, 'vod_pic': '',
                'vod_remarks': remark, 'style': {'type': 'list'}, 'action': action_str}

    def action(self, action_str):
        if action_str == 'cf::test':
            if self.dlg is not None:
                self.dlg.run_on_ui(lambda a: self._test_ui(a))
            else:
                self._toast(None, '弹窗插件不可用')
        elif action_str == 'cf::pure_py_test':
            if self.dlg is not None:
                self.dlg.run_on_ui(lambda a: self._pure_py_test_ui(a))
            else:
                self._toast(None, '弹窗插件不可用')
        elif action_str == 'cf::sniff_input':
            if self.dlg is not None:
                self.dlg.run_on_ui(lambda a: self._sniff_ui(a))
            else:
                self._toast(None, '弹窗插件不可用')
        elif action_str == 'cf::webview_fetch':
            if self.dlg is not None:
                self.dlg.run_on_ui(lambda a: self._webview_fetch_ui(a))
            else:
                self._toast(None, '弹窗插件不可用')
        elif action_str == 'cf::webview_retry_fetch':
            if self.dlg is not None:
                self.dlg.run_on_ui(lambda a: self._webview_retry_fetch_ui(a))
            else:
                self._toast(None, '弹窗插件不可用')
        elif action_str == 'cf::log':
            self._show_log_dialog()
        return {}

    def _show_log_dialog(self):
        if self.dlg is None:
            self._toast(None, '弹窗插件不可用')
            return
        self.dlg.show_log('最近日志', lines=list(_LOG_BUF) or ['（暂无日志）'], height_ratio=0.7)

    # ---------------- 新增: WebView直连抓取 ----------------
    # 实测依据(真机): 浏览器/WebView 请求本站不触发 CF 验证, 而 py(urllib) 因 TLS 指纹
    # 被发 403 challenge 且真浏览器没被挑战 => cf_clearance 永远不存在, cookie 路线无效;
    # 改用 WebView(Chromium 网络栈)当抓取器直接取页面 DOM。
    def _webview_fetch_ui(self, act):
        if self.dlg is None:
            self._toast(None, '弹窗插件不可用')
            return
        self.dlg.show_form(
            'WebView直连抓取',
            [{'key': 'url', 'label': '输入要抓取的网址',
              'default': 'https://cn.xsz-av.com/'}],
            lambda vals: self._run_webview_fetch(vals.get('url', '').strip()))

    # ---------------- 新增: WebView直连抓取 (CF自动重试重载) ----------------
    def _webview_retry_fetch_ui(self, act):
        if self.dlg is None:
            self._toast(None, '弹窗插件不可用')
            return
        self.dlg.show_form(
            'WebView直连抓取(CF自动重试)',
            [{'key': 'url', 'label': '输入要抓取的网址',
              'default': 'https://cn.xsz-av.com/'}],
            lambda vals: self._run_webview_retry_fetch(vals.get('url', '').strip()))

    def _run_webview_retry_fetch(self, url):
        if not url.startswith('http'):
            self._toast(None, '链接无效')
            return
        self._log('[WebView直连重试] 链接: ' + url)
        if self.dlg is not None:
            self.dlg.run_bg(self._webview_retry_fetch, url)
        else:
            self._toast(None, '弹窗插件不可用')

    def _webview_retry_fetch(self, url):
        if self.dlg is None or jclass is None:
            self._toast(None, '弹窗插件不可用')
            return
        self._log('[WebView极速重试] 启动高频过盾通道: ' + url)
        state = {'tries': 0, 'reloads': 0, 'done': False, 'wv': None, 'dialog': None,
                 'has_cf_cookie': False}
        MAX_ATTEMPTS = 30         # 500ms 一次，30次 ≈ 15秒上限
        MAX_RELOADS = 3           # 触发 CF 挑战页时的最高 reload 重载次数
        INTERVAL_MS = 500         # 极速轮询间隔

        def _close():
            if not state['done']:
                state['done'] = True
                try:
                    if state['dialog'] is not None:
                        state['dialog'].dismiss()
                except Exception:
                    pass

        def _post_delayed(fn, ms):
            Handler = jclass('android.os.Handler')
            Looper = jclass('android.os.Looper')
            p = _ensure_wv_proxy_classes()
            r = p["run"]()
            r._fn = fn
            r._log = self.dlg.log
            self.dlg._keep(r)
            Handler(Looper.getMainLooper()).postDelayed(r, int(ms))

        def _check_cf_cookie():
            """主动检查系统 CookieManager 是否已拿到 cf_clearance 凭证(过盾信号)"""
            try:
                CookieManager = jclass('android.webkit.CookieManager')
                ck = CookieManager.getInstance().getCookie(url) or ''
                if 'cf_clearance' in ck:
                    return True
            except Exception:
                pass
            return False

        def _grab():
            if state['done']:
                return
            try:
                # 极速优化: 一旦捕获到 cf_clearance Cookie, 立即主动 reload 进真页(不等轮询)
                if not state['has_cf_cookie'] and _check_cf_cookie():
                    state['has_cf_cookie'] = True
                    state['reloads'] += 1
                    self._log('[WebView极速重试] 实时捕获到 cf_clearance Cookie! 立即主动 reload() 进真页...')
                    try:
                        state['wv'].reload()
                    except Exception as e:
                        self._log('WebView 主动 reload 异常: ' + str(e))
                    _post_delayed(_grab, 800)
                    return
                p = _ensure_wv_proxy_classes()
                cb = p["valuecb"]()
                cb._fn = _on_html
                cb._log = self.dlg.log
                self.dlg._keep(cb)
                state['wv'].evaluateJavascript(
                    '(function(){var h=document.documentElement.outerHTML||"";'
                    'return {rs:document.readyState,t:document.title||"",'
                    'len:h.length,h:h};})()', cb)
            except Exception:
                self._log('WebView极速重试取源码异常:\n' + traceback.format_exc())

        def _on_html(json_str):
            if state['done']:
                return
            data = None
            try:
                if json_str and json_str != 'null':
                    data = json.loads(json_str)
                    if isinstance(data, str):
                        data = json.loads(data)
            except Exception as e:
                self._log('解析WebView重试返回失败: ' + str(e))
                data = None

            html = data.get('h', '') if isinstance(data, dict) else ''
            rs = str(data.get('rs', '')) if isinstance(data, dict) else ''
            title = str(data.get('t', '')).lower() if isinstance(data, dict) else ''
            loaded = rs in ('complete', 'interactive')

            # 挑战页特征判定(补充中文标题「请稍候」及 DOM 标记 challenges.cloudflare.com)
            challenge = ('just a moment' in title or 'attention required' in title
                          or 'checking your browser' in title or '请稍候' in title
                          or 'challenges.cloudflare.com' in html.lower()
                          or 'cf-mitigated' in html.lower())

            if loaded and not challenge and len(html) >= 400:
                self._log('[WebView极速重试] 成功拿到网站源码! len=%d title=%s (第%d次探测, 重载%d次)' % (
                    len(html), data.get('t', ''), state['tries'] + 1, state['reloads']))
                _close()
                self._show_result(url, 200, html, tag='WebView直连(CF极速重试)')
                return

            state['tries'] += 1
            if state['tries'] >= MAX_ATTEMPTS:
                self._log('[WebView极速重试] 达到探测上限(%d次), 放弃' % state['tries'])
                _close()
                self._toast(None, '重试抓取失败，请改用「CF链接」手动验证')
                return

            if loaded and challenge:
                if state['reloads'] < MAX_RELOADS:
                    state['reloads'] += 1
                    self._log('[WebView极速重试] 第%d次探测检测到CF挑战页(title=%s), 执行第%d次 reload()...' % (
                        state['tries'], data.get('t', ''), state['reloads']))
                    try:
                        state['wv'].reload()
                    except Exception as e:
                        self._log('WebView reload 异常: ' + str(e))
                    _post_delayed(_grab, 1000)
                    return
                else:
                    self._log('[WebView极速重试] 第%d次探测仍然是CF挑战页, 已达重载上限(%d次), 继续静默轮询...' % (
                        state['tries'], MAX_RELOADS))

            reason = '未加载完(rs=%s)' % rs if not loaded else ('挑战页(title=%s)' % title if challenge else '内容过短(%d)' % len(html))
            self._log('[WebView极速重试] 第%d次探测: %s, 继续轮询...' % (state['tries'], reason))
            _post_delayed(_grab, INTERVAL_MS)

        def on_ui(act):
            WebView = jclass('android.webkit.WebView')
            WebViewClient = jclass('android.webkit.WebViewClient')
            LinearLayout = jclass('android.widget.LinearLayout')
            TextView = jclass('android.widget.TextView')
            Builder = jclass('android.app.AlertDialog$Builder')
            CookieManager = jclass('android.webkit.CookieManager')
            TypedValue = jclass('android.util.TypedValue')
            pad = int(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8.0,
                                                act.getResources().getDisplayMetrics()))
            container = LinearLayout(act)
            container.setOrientation(LinearLayout.VERTICAL)
            tip = TextView(act)
            tip.setText('WebView 极速抓取中…')
            tip.setPadding(pad, pad, pad, pad)
            wv = WebView(act)
            settings = wv.getSettings()
            settings.setJavaScriptEnabled(True)
            settings.setDomStorageEnabled(True)
            # 极速优化 1: 禁用图片下载(抓源码不需要图片, 大幅加快加载)
            settings.setBlockNetworkImage(True)
            # 极速优化 2: 复用 Chromium 缓存
            settings.setCacheMode(jclass('android.webkit.WebSettings').LOAD_DEFAULT)
            CookieManager.getInstance().setAcceptCookie(True)
            wv.setWebViewClient(WebViewClient())
            wv.loadUrl(url)
            container.addView(tip)
            container.addView(wv)
            self.dlg._keep(container, tip, wv)
            state['wv'] = wv

            def _cancel(d, w):
                state['done'] = True
                self._log('[WebView极速重试] 已取消')

            builder = Builder(act)
            builder.setTitle('WebView 极速抓取(CF重试)')
            builder.setView(container)
            builder.setNegativeButton('取消', self.dlg._make_click(_cancel))
            dialog = builder.create()
            state['dialog'] = dialog
            self.dlg._keep(dialog)
            dialog.show()
            # 极速优化 3: 启动探测延迟缩短至 300ms
            _post_delayed(_grab, 300)

        self.dlg.run_on_ui(on_ui)

    def _run_webview_fetch(self, url):
        if not url.startswith('http'):
            self._toast(None, '链接无效')
            return
        self._log('[WebView抓取] 链接: ' + url)
        if self.dlg is not None:
            self.dlg.run_bg(self._webview_fetch, url)
        else:
            self._toast(None, '弹窗插件不可用')

    def _webview_fetch(self, url):
        if self.dlg is None or jclass is None:
            self._toast(None, '弹窗插件不可用')
            return
        self._log('[WebView抓取] 启动 Chromium 通道: ' + url)
        state = {'tries': 0, 'done': False, 'wv': None, 'dialog': None}
        MAX_ATTEMPTS = 12          # 12次 x 2.5秒 ≈ 30秒上限
        INTERVAL_MS = 2500

        def _close():
            if not state['done']:
                state['done'] = True
                try:
                    if state['dialog'] is not None:
                        state['dialog'].dismiss()
                except Exception:
                    pass

        def _post_delayed(fn, ms):
            Handler = jclass('android.os.Handler')
            Looper = jclass('android.os.Looper')
            p = _ensure_wv_proxy_classes()
            r = p["run"]()
            r._fn = fn
            r._log = self.dlg.log
            self.dlg._keep(r)
            Handler(Looper.getMainLooper()).postDelayed(r, int(ms))

        def _grab():
            if state['done']:
                return
            try:
                p = _ensure_wv_proxy_classes()
                cb = p["valuecb"]()
                cb._fn = _on_html
                cb._log = self.dlg.log
                self.dlg._keep(cb)
                # 一次带回 readyState/title/正文, 用于精确判定(不能复用 _is_cf:
                # CF 会往正常页面注入 challenge-platform 标记, 会把正常页误判成挑战页)
                state['wv'].evaluateJavascript(
                    '(function(){var h=document.documentElement.outerHTML||"";'
                    'return {rs:document.readyState,t:document.title||"",'
                    'len:h.length,h:h};})()', cb)
            except Exception:
                self._log('WebView取源码异常:\n' + traceback.format_exc())

        def _on_html(json_str):
            if state['done']:
                return
            data = None
            try:
                if json_str and json_str != 'null':
                    data = json.loads(json_str)
                    if isinstance(data, str):     # 兼容双层JSON
                        data = json.loads(data)
            except Exception as e:
                self._log('解析WebView返回失败: ' + str(e))
                data = None
            html = data.get('h', '') if isinstance(data, dict) else ''
            rs = str(data.get('rs', '')) if isinstance(data, dict) else ''
            title = str(data.get('t', '')).lower() if isinstance(data, dict) else ''
            loaded = rs in ('complete', 'interactive')
            # 挑战页判定只看标题(实测依据: 正常页含CF注入标记, 特征串判定会误报)
            challenge = ('just a moment' in title or 'attention required' in title
                         or 'checking your browser' in title)
            if loaded and not challenge and len(html) >= 400:
                self._log('[WebView抓取] 成功: len=%d title=%s (第%d次探测)' % (
                    len(html), data.get('t', ''), state['tries'] + 1))
                _close()
                self._show_result(url, 200, html, tag='WebView直连')
                return
            state['tries'] += 1
            if state['tries'] >= MAX_ATTEMPTS:
                self._log('[WebView抓取] %d次探测未拿到正文, 放弃' % state['tries'])
                _close()
                self._toast(None, 'WebView通道未取到正文，请改用「CF链接」条目手动验证')
                return
            if not loaded:
                reason = '加载未完(rs=%s)' % (rs or '?')
            elif challenge:
                reason = '挑战页(title=%s)' % data.get('t', '')
            else:
                reason = '内容过短(len=%d)' % len(html)
            self._log('[WebView抓取] 第%d次探测: %s, 重试' % (state['tries'], reason))
            _post_delayed(_grab, INTERVAL_MS)

        def on_ui(act):
            WebView = jclass('android.webkit.WebView')
            WebViewClient = jclass('android.webkit.WebViewClient')
            LinearLayout = jclass('android.widget.LinearLayout')
            TextView = jclass('android.widget.TextView')
            Builder = jclass('android.app.AlertDialog$Builder')
            CookieManager = jclass('android.webkit.CookieManager')
            TypedValue = jclass('android.util.TypedValue')
            pad = int(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8.0,
                                                act.getResources().getDisplayMetrics()))
            container = LinearLayout(act)
            container.setOrientation(LinearLayout.VERTICAL)
            tip = TextView(act)
            tip.setText('WebView 直连抓取中…')
            tip.setPadding(pad, pad, pad, pad)
            wv = WebView(act)
            wv.getSettings().setJavaScriptEnabled(True)
            wv.getSettings().setDomStorageEnabled(True)
            CookieManager.getInstance().setAcceptCookie(True)
            # 参考原版弹窗: 裸 WebViewClient(), 不覆写(dynamic_proxy 只支持接口)
            wv.setWebViewClient(WebViewClient())
            wv.loadUrl(url)
            container.addView(tip)
            container.addView(wv)
            self.dlg._keep(container, tip, wv)
            state['wv'] = wv

            def _cancel(d, w):
                state['done'] = True
                self._log('[WebView抓取] 已取消')

            builder = Builder(act)
            builder.setTitle('WebView 直连抓取')
            builder.setView(container)
            builder.setNegativeButton('取消', self.dlg._make_click(_cancel))
            dialog = builder.create()
            state['dialog'] = dialog
            self.dlg._keep(dialog)
            dialog.show()
            # 不依赖 onPageFinished(无法覆写类): 定时轮询取 DOM, 成功即停
            _post_delayed(_grab, 2000)

        self.dlg.run_on_ui(on_ui)

    def _sniff_ui(self, act):
        if self.dlg is None:
            self._toast(None, '弹窗插件不可用')
            return
        self.dlg.show_form(
            '自动嗅探',
            [{'key': 'url', 'label': '输入要嗅探播放的页面链接', 'default': ''}],
            lambda vals: self._run_sniff(vals.get('url', '').strip()))

    def _run_sniff(self, url):
        if not url.startswith('http'):
            self._toast(None, '链接无效')
            return
        self._sniff_url = url
        self._log('嗅探链接: ' + url)
        if self.dlg is not None:
            self.dlg.run_on_ui(lambda a: self._open_detail(a, url))
        else:
            self._toast(None, '弹窗插件不可用')

    # 优先: Intent 直拉本站详情 -> detailContent -> playerContent
    # (parse=1 + 防盗链 header, WebView 加载页面即带 UA/Referer/Cookie, 全自动)
    # 实测混淆 app(com.fongmi.vodplus): Activity 类名保留, 但静态方法 start 被 R8 改名,
    # 故不用反射调 start, 改用 Intent.setClassName + putExtra(公共API, extra key 字符串常量不混淆)
    # 兜底: 探测失败 -> SEND 推送 -> PUSH 详情(默认推送线路不带 header, 需手动选「嗅探」线路)
    def _open_detail(self, act, url):
        try:
            name = self._video_activity_name(act)
            key = str(getattr(self, 'siteKey', '') or '')
            if name and key:
                Intent = jclass('android.content.Intent')
                it = Intent()
                it.setClassName(act, name)
                it.putExtra('key', key)
                it.putExtra('id', url)
                it.putExtra('name', '嗅探 ' + self._host(url))
                act.startActivity(it)
                self._log('已拉起本站详情: %s id=%s' % (name, url))
                return
            self._log('反射入口不可用(VideoActivity=%s siteKey=%s), 转SEND推送' % (name, key))
        except Exception:
            self._log('反射拉起详情失败:\n' + traceback.format_exc())
        self._push_play(act, url)

    def _video_activity_name(self, act):
        candidates = []
        try:
            cur = str(act.getClass().getName())
            if '.ui.activity.' in cur:
                candidates.append(cur.split('.ui.activity.')[0] + '.ui.activity.VideoActivity')
        except Exception:
            pass
        candidates.append('com.fongmi.android.tv.ui.activity.VideoActivity')
        try:
            candidates.append(str(act.getPackageName()) + '.ui.activity.VideoActivity')
        except Exception:
            pass
        loader = act.getClass().getClassLoader()
        for name in candidates:
            try:
                if loader.loadClass(name) is not None:
                    return name
            except Exception:
                continue
        return ''

    # 通用推送(零反射, 不受混淆影响):
    # ACTION_SEND -> 影视壳 HomeActivity.onNewIntent -> VideoActivity.push -> PUSH 伪站点详情
    # 网页URL播放时走 app 内置 WebView 嗅探(共享系统 CookieManager, CF验证的cookie直接可用, Referer自动带)
    def _push_play(self, act, url):
        try:
            if jclass is None:
                self._toast(act, 'java 环境不可用')
                return
            Intent = jclass('android.content.Intent')
            it = Intent()
            it.setAction(Intent.ACTION_SEND)
            it.putExtra(Intent.EXTRA_TEXT, url)
            it.setType('text/plain')
            it.setPackage(act.getPackageName())
            act.startActivity(it)
            self._log('已推送(SEND): ' + url)
        except Exception:
            self._log('推送异常:\n' + traceback.format_exc())
            self._toast(act, '推送失败，请查看日志')

    def _test_ui(self, act):
        if self.dlg is None:
            self._toast(None, '弹窗插件不可用')
            return
        self.dlg.show_form(
            'CF链接',
            [{'key': 'url', 'label': '输入要测试的链接', 'default': ''}],
            lambda vals: self._run_test(vals.get('url', '').strip()))

    def _run_test(self, url):
        if not url:
            self._toast(None, '链接为空')
            return
        if self.dlg is not None:
            self.dlg.run_bg(self._do_test, url)
        else:
            self._do_test(url)

    def _do_test(self, url):
        self._log('开始测试: ' + url)
        headers = self._build_jable_headers(url)
        status, text, cookies = None, '', {}
        cf = True

        try:
            status, text, cookies = self._fetch_urllib_jable(url, headers)
            self._log('[1 urllib直连] status=%s len=%d' % (status, len(text)))
            if status == 200 and self._looks_ok(text):
                self._log('首个测试即通过，判定为非 CF 页，直接展示源码')
                self._show_result(url, status, text, tag='urllib直连')
                return
            cf = self._is_cf(text, status)
        except Exception as e:
            self._log('[1 urllib直连] 失败: ' + str(e))

        if cf:
            self._log('判定为 CF 拦截页，进入手动验证流程')
            self._solve_cf(url, headers,
                           lambda new_cookies: self._on_verified(url, new_cookies))
        else:
            self._show_result(url, status, text)

    # ---------------- 核心：0jable.py 同款纯 Py 请求与有效性判定 ----------------
    def _pure_py_test_ui(self, act):
        if self.dlg is None:
            self._toast(None, '弹窗插件不可用')
            return
        self.dlg.show_form(
            '纯Py请求获取源码',
            [{'key': 'url', 'label': '输入要抓取的网址', 'default': 'https://jable.tv'}],
            lambda vals: self._run_pure_py_test(vals.get('url', '').strip()))

    def _run_pure_py_test(self, url):
        if not url:
            self._toast(None, '链接为空')
            return
        if self.dlg is not None:
            self.dlg.run_bg(self._do_pure_py_test, url)
        else:
            self._do_pure_py_test(url)

    def _do_pure_py_test(self, url):
        self._log('开始 0jable 同款纯 Py 抓取: ' + url)
        
        # 尝试带上已保存的本地 Cookie（若有）
        saved_cookies = self._load_saved_cookies(url)
        headers = self._build_jable_headers(url)
        if saved_cookies:
            cookie_str = '; '.join('%s=%s' % (k, v) for k, v in saved_cookies.items())
            headers['Cookie'] = cookie_str
            self._log('已自动附加本地保存的 Cookie 协同请求')

        text = ""
        status = 200
        try:
            # 严格复刻 0jable.py 的 urllib 标准库请求逻辑
            req = urllib.request.Request(url, headers=headers)
            opener = urllib.request.build_opener()
            resp = opener.open(req, timeout=15)
            raw = resp.read()
            enc = (resp.headers.get("Content-Encoding") or "").lower()
            if "gzip" in enc:
                try:
                    raw = gzip.decompress(raw)
                except Exception:
                    pass
            status = getattr(resp, "status", 200) or 200
            text = raw.decode("utf-8", "ignore")
            
            self._log('请求完成: status=%s, 源码长度=%d' % (status, len(text)))
            
            # 使用 0jable.py 的 looks_ok 判定
            if self._looks_ok(text):
                self._log('成功拿到有效网页正文！')
                self._show_result(url, status, text, tag='0jable同款纯Py直连')
                return
            else:
                self._log('源码未能通过 looks_ok 校验（疑似命中 CF 挑战页）')
        except Exception as e:
            self._log('请求异常: ' + str(e))

        self._log('纯Py直连未成功返回正文')
        if self.dlg is not None:
            self.dlg.run_on_ui(lambda act: self.dlg.show_log(
                '抓取结果', text='纯Py请求返回内容无效或受阻，建议使用「CF链接」手动验证过盾。', height_ratio=0.4))

    @staticmethod
    def _looks_ok(txt):
        """完全对齐 0jable.py 的 looks_ok 静态方法"""
        if not txt or len(txt) < 400:
            return False
        low = txt[:4000].lower()
        for k in _CHALLENGE:
            if k in low:
                return False
        return True

    def _build_jable_headers(self, referer=""):
        """完全对齐 0jable.py 的标准 headers"""
        root_url = self._root(referer) if referer else "https://jable.tv/"
        return {
            "User-Agent": UA_DESKTOP,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
            "Referer": root_url,
            "Upgrade-Insecure-Requests": "1",
            "Connection": "close",
        }

    def _fetch_urllib_jable(self, url, headers):
        cj = http.cookiejar.CookieJar()
        req = urllib.request.Request(url, headers=headers)
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
        resp = opener.open(req, timeout=15)
        raw = resp.read()
        enc = (resp.headers.get("Content-Encoding") or "").lower()
        if "gzip" in enc:
            try:
                raw = gzip.decompress(raw)
            except Exception:
                pass
        code = getattr(resp, "status", 200) or 200
        if code != 200:
            raise RuntimeError("HTTP %s" % code)
        text = raw.decode("utf-8", "ignore")
        cookies = {c.name: c.value for c in cj}
        return code, text, cookies

    def _load_saved_cookies(self, url):
        try:
            path = self._cf_cookie_path(url)
            if os.path.exists(path):
                with open(path, 'r', encoding='utf-8') as f:
                    return json.load(f)
        except Exception:
            pass
        return {}

    def _cf_cookie_path(self, url):
        name = self._root(url).rstrip('/').split('//')[-1] or 'site'
        return os.path.join(CF_COOKIE_DIR, name + '.json')

    def _on_verified(self, url, new_cookies):
        self._log('验证完成，已获取 cookie: ' + ', '.join(new_cookies.keys()))
        if new_cookies:
            try:
                if not os.path.isdir(CF_COOKIE_DIR):
                    os.makedirs(CF_COOKIE_DIR, exist_ok=True)
                path = self._cf_cookie_path(url)
                with open(path, 'w', encoding='utf-8') as f:
                    json.dump(new_cookies, f, ensure_ascii=False, indent=2)
                self._log('已保存 cookie 到: ' + path)
            except Exception as e:
                self._log('保存 cookie 失败: ' + str(e))

    def _refetch_solved(self, url, wv_ua, cookies):
        root = self._root(url)
        headers = self._build_jable_headers(url)
        headers['User-Agent'] = wv_ua or UA_MOBILE
        if cookies:
            headers['Cookie'] = '; '.join('%s=%s' % (k, v) for k, v in cookies.items())
        try:
            status, text, _ = self._fetch_urllib_jable(url, headers)
            self._log('[验证后重抓] status=%s len=%d' % (status, len(text)))
            html = text
        except Exception as e:
            self._log('[验证后重抓] 失败: ' + str(e))
            status, html = None, ''
        snippet = (html or '')[:2000]
        self._show_result(url, status, html, tag='refetch')

    def _show_result(self, url, status, text, tag=''):
        snippet = (text or '')[:2000]
        self._log_block('结果', ['URL: ' + url, '来源: ' + tag, 'status: ' + str(status),
                                 '源码(前2000):', snippet])
        if self.dlg is not None:
            def _save_clicked():
                self.dlg.run_bg(self._save_html, url, text)
            self.dlg.run_on_ui(lambda act: self.dlg.show_log(
                '页面源码',
                text='来源=%s\nstatus=%s\n\n%s' % (tag, status, snippet),
                height_ratio=0.7,
                extra_button=('保存网页', _save_clicked)))

    # 保存完整网页源码: /storage/emulated/0/tmp/123/<域名>.html
    def _save_html(self, url, text):
        try:
            domain = urllib.parse.urlparse(url).netloc or 'site'
            save_dir = '/storage/emulated/0/tmp/123'
            if not os.path.isdir(save_dir):
                os.makedirs(save_dir, exist_ok=True)
            path = os.path.join(save_dir, domain + '.html')
            with open(path, 'w', encoding='utf-8') as f:
                f.write(text or '')
            self._log('已保存完整网页: ' + path + ' (' + str(len(text or '')) + ' 字符)')
            self._toast(None, '已保存: ' + path)
        except Exception as e:
            self._log('保存网页失败: ' + str(e))
            self._toast(None, '保存失败: ' + str(e))

    def _root(self, url):
        p = urllib.parse.urlparse(url)
        return '%s://%s/' % (p.scheme, p.netloc)

    def _solve_cf(self, url, base_headers, on_done):
        if self.dlg is None or jclass is None:
            on_done({})
            return

        def on_ui(act):
            WebView = jclass('android.webkit.WebView')
            WebViewClient = jclass('android.webkit.WebViewClient')
            LinearLayout = jclass('android.widget.LinearLayout')
            TextView = jclass('android.widget.TextView')
            Builder = jclass('android.app.AlertDialog$Builder')
            CookieManager = jclass('android.webkit.CookieManager')
            TypedValue = jclass('android.util.TypedValue')
            pad = int(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8.0,
                                                act.getResources().getDisplayMetrics()))

            container = LinearLayout(act)
            container.setOrientation(LinearLayout.VERTICAL)
            tip = TextView(act)
            tip.setText('请在下方手动完成验证，成功后点「完成」')
            tip.setPadding(pad, pad, pad, pad)
            wv = WebView(act)
            wv.getSettings().setJavaScriptEnabled(True)
            wv.getSettings().setDomStorageEnabled(True)
            wv.setWebViewClient(WebViewClient())
            wv.loadUrl(url, self._hdr_map(base_headers))
            container.addView(tip)
            container.addView(wv)

            try:
                wv_ua = str(wv.getSettings().getUserAgentString())
            except Exception:
                wv_ua = ''

            result = {'cookies': {}}

            def _collect():
                raw = CookieManager.getInstance().getCookie(url) or ''
                out = {}
                for part in raw.split(';'):
                    part = part.strip()
                    if '=' in part:
                        k, v = part.split('=', 1)
                        out[k.strip()] = v
                result['cookies'] = out

            def _finish(d, w):
                _collect()
                try:
                    if dialog is not None:
                        dialog.dismiss()
                except Exception:
                    pass
                on_done(result['cookies'])
                self.dlg.run_bg(self._refetch_solved, url, wv_ua, result['cookies'])

            builder = Builder(act)
            builder.setTitle('手动过验证')
            builder.setView(container)
            builder.setPositiveButton('完成', self.dlg._make_click(_finish))
            builder.setNegativeButton('取消', self.dlg._make_click(
                lambda d, w: on_done({})))
            dialog = builder.create()
            dialog.show()

        self.dlg.run_on_ui(on_ui)

    def _hdr_map(self, headers):
        HashMap = jclass('java.util.HashMap')
        m = HashMap()
        for k, v in (headers or {}).items():
            m.put(str(k), str(v))
        return m

    @staticmethod
    def _is_cf(text, status):
        if not text:
            return False
        low = (text or '').lower()
        if any(m in low for m in _CHALLENGE):
            return True
        if status in (403, 503) and 'cloudflare' in low:
            return True
        return False

    def detailContent(self, ids):
        url = (ids[0] if ids else '') or ''
        if not url.startswith('http'):
            return {'list': []}
        return {'list': [{
            'vod_id': url,
            'vod_name': '嗅探 ' + self._host(url),
            'vod_pic': '',
            'vod_content': 'WebView 嗅探页面视频流，播放带防盗链',
            'vod_play_from': '嗅探',
            'vod_play_url': '嗅探$' + url,
        }]}

    def playerContent(self, flag, id, vipFlags):
        url = id or ''
        if not url.startswith('http'):
            return {'parse': 0, 'playUrl': '', 'url': 'about:blank', 'header': {}}
        # 防盗链: UA + Referer + 已保存 Cookie
        headers = self._build_jable_headers(url)
        saved = self._load_saved_cookies(url)
        if saved:
            headers['Cookie'] = '; '.join('%s=%s' % (k, v) for k, v in saved.items())
        self._log('嗅探播放: ' + url + ' (防盗链: UA/Referer' + ('/Cookie' if saved else '') + ')')
        return {'parse': 1, 'playUrl': '', 'url': url, 'header': headers}

    def _host(self, url):
        return urllib.parse.urlparse(url).netloc or url
