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
                 copyable=True, on_close=None):
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
            self._entry('cf::log', '日志', '查看最近一次测试日志'),
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
        elif action_str == 'cf::log':
            self._show_log_dialog()
        return {}

    def _show_log_dialog(self):
        if self.dlg is None:
            self._toast(None, '弹窗插件不可用')
            return
        self.dlg.show_log('最近日志', lines=list(_LOG_BUF) or ['（暂无日志）'], height_ratio=0.7)

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
            self.dlg.run_on_ui(lambda act: self.dlg.show_log(
                '页面源码',
                text='来源=%s\nstatus=%s\n\n%s' % (tag, status, snippet),
                height_ratio=0.7))

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
        return {'list': []}

    def playerContent(self, flag, id, vipFlags):
        return {}
