# -*- coding: utf-8 -*-
import os
import json
import re
import time
import threading
from urllib.parse import urlparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from requests import Session
from base.spider import Spider

_HTML_ESCAPE_TABLE = str.maketrans({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#x27;'
})
_ATTR_ESCAPE_TABLE = str.maketrans({'"': '&quot;', "'": '&#x27;'})

def html_escape(s: str, attr_only: bool = False) -> str:
    table = _ATTR_ESCAPE_TABLE if attr_only else _HTML_ESCAPE_TABLE
    return str(s).translate(table)

def is_valid_http_url(url: str) -> bool:
    try:
        parsed = urlparse(url.strip())
        return parsed.scheme in ('http', 'https') and bool(parsed.netloc)
    except Exception:
        return False

COMMON_CSS = """
* { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
body { background: rgba(0, 0, 0, 0); display: flex; justify-content: center; align-items: center; min-height: 100vh; padding: 16px; }
.card { background: #ffffff; border-radius: 16px; padding: 24px; width: 90%; max-width: 480px; box-shadow: 0 10px 30px rgba(0,0,0,0.3); max-height: 85vh; display: flex; flex-direction: column; }
.title { font-size: 18px; font-weight: bold; color: #1a73e8; margin-bottom: 6px; }
.subtitle { font-size: 12px; color: #666; margin-bottom: 16px; line-height: 1.4; }
.label { font-size: 12px; font-weight: bold; color: #333; margin-bottom: 6px; display: block; }
input[type="text"], textarea { width: 100%; padding: 12px; border: 1.5px solid #e0e0e0; border-radius: 10px; font-size: 13px; outline: none; background: #f8f9fa; }
textarea { height: 120px; resize: none; }
input:focus, textarea:focus { border-color: #1a73e8; background: #fff; }
.btn-group { display: flex; gap: 12px; margin-top: 18px; }
.btn { flex: 1; padding: 12px; border: none; border-radius: 8px; font-weight: bold; cursor: pointer; font-size: 14px; }
.btn-primary { background: #1a73e8; color: white; }
.btn-secondary { background: #f1f3f4; color: #5f6368; }
.btn-danger { background: #ff4d4f; color: white; }
"""

class Spider(Spider):
    
    IS_WEB_SHOWING = False
    WEB_LOCK_TIMEOUT = 60
    _web_lock_time = 0
    
    DEFAULT_CONFIG_PATH = "/storage/emulated/0/tvbox/downloadurl.json"
    DEFAULT_SAVE_DIR = "/storage/emulated/0/123/html/"
    
    DEFAULT_BUILTIN_LINKS = {
        "宝宝_瑟": "https://av-9du.pages.dev/",
        "宝宝_油管": "https://yt-cas.pages.dev/",
        "宝宝_哔哩": "https://bili-1k4.pages.dev",
        "宝宝_盘链": "https://pl-d8z.pages.dev/",
        "宝宝_Eclipse": "https://vvee.ccwu.cc/",
        "宝宝_Nostr": "https://nostr.aws.dpdns.org/",
        "宝宝_NostrTV": "https://nostrtv.aws.dpdns.org/",
        "宝宝_玩偶聚合26": "https://wo26.vivas.cc.cd",
        "宝宝_玩偶聚合": " https://wo.vivas.cc.cd",
        "宝宝_Pomo": "https://pomo.920410.xyz/",
        "宝宝_Pomo赛博": "https://cyber.920410.xyz/",
        "宝宝_导航": "https://wh.920410.xyz/",
    }
    
    CMD_INPUT_LINKS = "download::input_links"
    CMD_CUSTOM_DOWNLOAD = "download::custom_download"
    CMD_BUILTIN_LINKS = "download::builtin_links"
    CMD_SET_PATH = "download::set_path"
    CMD_TOGGLE_SITES = "download::toggle_sites"
    CMD_MANAGE_SITES = "download::manage_sites"
    
    @classmethod
    def _acquire_web_lock(cls):
        now = time.time()
        if cls.IS_WEB_SHOWING and (now - cls._web_lock_time < cls.WEB_LOCK_TIMEOUT):
            return False
        cls.IS_WEB_SHOWING = True
        cls._web_lock_time = now
        return True

    @classmethod
    def _release_web_lock(cls):
        cls.IS_WEB_SHOWING = False
        cls._web_lock_time = 0

    def getName(self):
        return "网页源码下载工具 (修复版)"

    def init(self, extend=""):
        self.cached_activity = self._activity()
        self.session = Session()
        self.session.headers.update({
            "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        })
        self._load_config()

    def _safe_log_error(self, prefix, e):
        msg = f"{prefix}: {e}"
        print(msg)
        self._show_toast(msg)

    def _reload_ui(self):
        """调用 Android 原生 Activity recreate() 重绘刷新界面"""
        try:
            from java import jclass, dynamic_proxy
            from java.lang import Runnable

            act = self.cached_activity or self._activity()
            if act:
                class ReloadRunnable(dynamic_proxy(Runnable)):
                    def run(self):
                        try:
                            act.recreate()
                        except Exception as e:
                            print(f"Recreate activity failed: {e}")

                act.runOnUiThread(ReloadRunnable())
        except Exception as e:
            print(f"Reload UI failed: {e}")

    # ====================== 配置读写逻辑 ======================
    def _load_config(self):
        self.master_switch = True
        self.save_dir = self.DEFAULT_SAVE_DIR
        self.sites = dict(self.DEFAULT_BUILTIN_LINKS)
        self.builtin_switches = {name: True for name in self.sites}

        try:
            if os.path.exists(self.DEFAULT_CONFIG_PATH):
                with open(self.DEFAULT_CONFIG_PATH, "r", encoding="utf-8") as f:
                    data = json.load(f)

                if "download_dir" in data:
                    self.save_dir = data["download_dir"]

                if "master_switch" in data:
                    self.master_switch = bool(data["master_switch"])

                if isinstance(data.get("sites"), dict) and data.get("sites"):
                    loaded_sites = {str(k).strip(): str(v).strip() for k, v in data["sites"].items() if str(k).strip() and str(v).strip()}
                    if loaded_sites:
                        self.sites = loaded_sites

                if isinstance(data.get("custom_sites"), dict):
                    for k, v in data["custom_sites"].items():
                        name = str(k).strip()
                        url = str(v).strip()
                        if name and url:
                            self.sites[name] = url

                self.builtin_switches = {name: True for name in self.sites}
                if isinstance(data.get("builtin_switches"), dict):
                    for k, v in data["builtin_switches"].items():
                        if k in self.sites:
                            self.builtin_switches[k] = bool(v)
        except Exception as e:
            self._safe_log_error("加载设置失败", e)

        try:
            if not os.path.exists(self.save_dir):
                os.makedirs(self.save_dir)
        except Exception:
            pass

    def _save_config(self, new_dir=None, new_master_switch=None, new_switches=None, new_sites=None):
        data = {}
        try:
            if os.path.exists(self.DEFAULT_CONFIG_PATH):
                with open(self.DEFAULT_CONFIG_PATH, "r", encoding="utf-8") as f:
                    data = json.load(f)
        except Exception:
            pass

        if new_dir is not None:
            self.save_dir = new_dir
        if new_master_switch is not None:
            self.master_switch = new_master_switch
        if new_sites is not None:
            self.sites = {str(k).strip(): str(v).strip() for k, v in new_sites.items() if str(k).strip() and str(v).strip()}
            self.builtin_switches = {name: bool(self.builtin_switches.get(name, True)) for name in self.sites}
        if new_switches is not None:
            self.builtin_switches = {name: bool(new_switches.get(name, self.builtin_switches.get(name, True))) for name in self.sites}

        data["download_dir"] = self.save_dir
        data["master_switch"] = self.master_switch
        data["sites"] = self.sites
        data["builtin_switches"] = self.builtin_switches
        data.pop("custom_sites", None)

        try:
            config_dir = os.path.dirname(self.DEFAULT_CONFIG_PATH)
            if not os.path.exists(config_dir):
                os.makedirs(config_dir)

            with open(self.DEFAULT_CONFIG_PATH, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
            return True, "设置保存成功"
        except Exception as e:
            return False, f"保存设置失败: {str(e)}"

    def _get_active_builtin_links(self):
        self._load_config()
        if not self.master_switch:
            return dict(self.sites)
        return {name: url for name, url in self.sites.items() if self.builtin_switches.get(name, True)}

    def homeContent(self, filter):
        return {"class": [{"type_id": "download_tools", "type_name": "下载工具"}], "filters": {}}

    def categoryContent(self, tid, pg, filter, extend):
        if tid == "download_tools":
            self._load_config()
            active_links = self._get_active_builtin_links()
            return {
                "list": [
                    {
                        "vod_id": self.CMD_INPUT_LINKS, "vod_name": "✨ 输入链接下载源码",
                        "vod_remarks": "HTML 多行输入(默认路径)", "action": self.CMD_INPUT_LINKS, "style": {"type": "list", "ratio": 1.1}
                    },
                    {
                        "vod_id": self.CMD_CUSTOM_DOWNLOAD, "vod_name": "✨ 自定义下载(设置路径)",
                        "vod_remarks": "HTML 界面：链接 & 临时保存路径", "action": self.CMD_CUSTOM_DOWNLOAD, "style": {"type": "list", "ratio": 1.1}
                    },
                    {
                        "vod_id": self.CMD_BUILTIN_LINKS, "vod_name": "↓ 下载内置链接源码",
                        "vod_remarks": f"当前有效: {len(active_links)} / 共 {len(self.sites)} 个", "action": self.CMD_BUILTIN_LINKS, "style": {"type": "list", "ratio": 1.1}
                    },
                    {
                        "vod_id": self.CMD_SET_PATH, "vod_name": "⚙ 设置全局下载路径",
                        "vod_remarks": f"当前: {self.save_dir}", "action": self.CMD_SET_PATH, "style": {"type": "list", "ratio": 1.1}
                    },
                    {
                        "vod_id": self.CMD_TOGGLE_SITES, "vod_name": "☑ 内置下载站点开关管理",
                        "vod_remarks": f"总开关: {'开启' if self.master_switch else '关闭'} | 共 {len(self.sites)} 个", "action": self.CMD_TOGGLE_SITES, "style": {"type": "list", "ratio": 1.1}
                    },
                    {
                        "vod_id": self.CMD_MANAGE_SITES, "vod_name": "➕ 增删下载站点",
                        "vod_remarks": f"增加/删除 JSON 配置（当前 {len(self.sites)} 个）", "action": self.CMD_MANAGE_SITES, "style": {"type": "list", "ratio": 1.1}
                    }
                ],
                "page": 1, "pagecount": 1, "limit": 6, "total": 6
            }
        return {"list": [], "page": 1, "pagecount": 1, "limit": 0, "total": 0}

    def action(self, action_str):
        if not self._acquire_web_lock():
            return json.dumps({"msg": "busy - 有界面正在显示或被强制锁定"}, ensure_ascii=False)

        if action_str == self.CMD_INPUT_LINKS:
            self._input_links_dialog()
        elif action_str == self.CMD_CUSTOM_DOWNLOAD:
            self._custom_download_dialog()
        elif action_str == self.CMD_BUILTIN_LINKS:
            self._release_web_lock()
            self._download_builtin_links()
        elif action_str == self.CMD_SET_PATH:
            self._open_path_setting_dialog()
        elif action_str == self.CMD_TOGGLE_SITES:
            self._open_sites_toggle_dialog()
        elif action_str == self.CMD_MANAGE_SITES:
            self._open_manage_sites_dialog()
        else:
            self._release_web_lock()
        return {}

    def detailContent(self, ids): return {"list": []}
    def playerContent(self, flag, id, vipFlags): return {"parse": 0, "playUrl": "", "url": "about:blank", "header": {}}

    # ====================== 通用 HTML 容器渲染 (修复Chaquo崩溃) ======================
    def _render_html_overlay(self, html_content, on_submit_callback):
        try:
            from java import jclass, dynamic_proxy
            from java.lang import Runnable

            act = self.cached_activity or self._activity()
            if not act:
                self._release_web_lock()
                self._show_toast("无法获取 Activity，操作已取消。")
                return

            WebView = jclass("android.webkit.WebView")
            FrameLayout = jclass("android.widget.FrameLayout")
            Color = jclass("android.graphics.Color")
            ValueCallback = jclass("android.webkit.ValueCallback")
            spider_self = self

            class CreateRootWebView(dynamic_proxy(Runnable)):
                def run(self):
                    try:
                        decor_view = act.getWindow().getDecorView()
                        overlay_wrapper = FrameLayout(act)
                        web = WebView(act)
                        web.setBackgroundColor(Color.TRANSPARENT)

                        settings = web.getSettings()
                        settings.setJavaScriptEnabled(True)
                        settings.setDomStorageEnabled(True)

                        web.loadDataWithBaseURL(None, html_content, "text/html", "utf-8", None)

                        match_parent = FrameLayout.LayoutParams.MATCH_PARENT
                        web_params = FrameLayout.LayoutParams(match_parent, match_parent)
                        overlay_wrapper.addView(web, web_params)

                        wrapper_params = FrameLayout.LayoutParams(match_parent, match_parent)
                        decor_view.addView(overlay_wrapper, wrapper_params)

                        runnable_ref = [None]
                        
                        class JSCallback(dynamic_proxy(ValueCallback)):
                            def onReceiveValue(self, value):
                                try:
                                    val_str = str(value) if value is not None else ""
                                    if val_str.startswith('"') and val_str.endswith('"'):
                                        val_str = json.loads(val_str)
                                    if val_str == "__CLOSE__":
                                        decor_view.removeView(overlay_wrapper)
                                        spider_self._release_web_lock()
                                    elif val_str and val_str != "null" and val_str != '""':
                                        decor_view.removeView(overlay_wrapper)
                                        spider_self._release_web_lock()
                                        on_submit_callback(val_str)
                                    else:
                                        if spider_self.IS_WEB_SHOWING:
                                            act.getWindow().getDecorView().postDelayed(runnable_ref[0], 100)
                                except Exception:
                                    spider_self._release_web_lock()

                        js_callback_instance = JSCallback()

                        class CheckStatus(dynamic_proxy(Runnable)):
                            def run(self):
                                try: web.evaluateJavascript("window.RESULT_DATA", js_callback_instance)
                                except: pass
                                
                        runnable_ref[0] = CheckStatus()
                        act.getWindow().getDecorView().postDelayed(runnable_ref[0], 100)

                    except Exception as e:
                        spider_self._release_web_lock()
                        spider_self._safe_log_error("加载 HTML UI 异常", e)

            act.runOnUiThread(CreateRootWebView())
        except Exception as e:
            self._release_web_lock()
            self._safe_log_error("唤起失败", e)

    # ====================== 各条目 HTML UI 实现 ======================

    def _input_links_dialog(self):
        html = f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>{COMMON_CSS}</style></head>
<body onclick="closeUI()">
    <div class="card" onclick="event.stopPropagation()">
        <div class="title">🔗 输入链接下载源码</div>
        <div class="subtitle">每行输入一个 URL，支持批量下载。<br>下载的源码将默认存入全局保存路径中。</div>
        <textarea id="linksInput" placeholder="https://example.com&#10;https://test.com"></textarea>
        <div class="btn-group">
            <button class="btn btn-secondary" onclick="closeUI()">取消</button>
            <button class="btn btn-primary" onclick="submitData()">开始下载</button>
        </div>
    </div>
    <script>
        window.RESULT_DATA = null;
        function submitData() {{
            var val = document.getElementById('linksInput').value.trim();
            if(!val) {{ alert('请输入至少一个链接！'); return; }}
            window.RESULT_DATA = val;
        }}
        function closeUI() {{ window.RESULT_DATA = "__CLOSE__"; }}
    </script>
</body></html>"""

        def on_submit(raw_text):
            links = [l.strip() for l in raw_text.split("\n") if l.strip() and is_valid_http_url(l)]
            if links:
                self._download_with_live_log(links, "input_links", target_dir=self.save_dir)
            else:
                self._show_toast("未检测到合法的 URL")

        self._render_html_overlay(html, on_submit)

    def _custom_download_dialog(self):
        html = f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>{COMMON_CSS}</style></head>
<body onclick="closeUI()">
    <div class="card" onclick="event.stopPropagation()">
        <div class="title">🛠️ 自定义下载与路径</div>
        <div class="subtitle">可多行输入链接，并临时指定保存路径。</div>
        <span class="label">保存路径 (仅本次有效，需绝对路径):</span>
        <input type="text" id="pathInput" value="{self.save_dir}">
        <span class="label" style="margin-top:12px;">下载链接 (每行一个):</span>
        <textarea id="linksInput" placeholder="https://example.com"></textarea>
        <div class="btn-group">
            <button class="btn btn-secondary" onclick="closeUI()">取消</button>
            <button class="btn btn-primary" onclick="submitData()">开始下载</button>
        </div>
    </div>
    <script>
        window.RESULT_DATA = null;
        function submitData() {{
            var p = document.getElementById('pathInput').value.trim();
            var l = document.getElementById('linksInput').value.trim();
            if(!p) {{ alert('保存路径不能为空！'); return; }}
            if(!l) {{ alert('请输入至少一个下载链接！'); return; }}
            window.RESULT_DATA = JSON.stringify({{ path: p, links: l }});
        }}
        function closeUI() {{ window.RESULT_DATA = "__CLOSE__"; }}
    </script>
</body></html>"""

        def on_submit(json_raw):
            try:
                data = json.loads(json_raw)
                target_dir = data.get("path")
                raw_links = data.get("links", "")
                
                if not os.path.isabs(target_dir):
                    self._show_toast("请使用绝对路径")
                    return
                
                links = [l.strip() for l in raw_links.split("\n") if l.strip() and is_valid_http_url(l)]
                if links and target_dir:
                    self._download_with_live_log(links, "input_links", target_dir=target_dir)
                else:
                    self._show_toast("链接格式错误，请检查")
            except Exception as e:
                self._safe_log_error("解析参数异常", e)

        self._render_html_overlay(html, on_submit)

    def _open_path_setting_dialog(self):
        html = f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>{COMMON_CSS}</style></head>
<body onclick="closeUI()">
    <div class="card" onclick="event.stopPropagation()">
        <div class="title">📁 全局下载路径设置</div>
        <div class="subtitle">配置文件: downloadurl.json<br>注意：请务必填写绝对路径。</div>
        <input type="text" id="pathInput" value="{self.save_dir}">
        <div class="btn-group">
            <button class="btn btn-secondary" onclick="closeUI()">取消</button>
            <button class="btn btn-primary" onclick="submitData()">保存配置</button>
        </div>
    </div>
    <script>
        window.RESULT_DATA = null;
        function submitData() {{
            var p = document.getElementById('pathInput').value.trim();
            if(!p) {{ alert('路径不能为空！'); return; }}
            window.RESULT_DATA = p;
        }}
        function closeUI() {{ window.RESULT_DATA = "__CLOSE__"; }}
    </script>
</body></html>"""

        def on_submit(input_dir):
            if input_dir == self.save_dir:
                self._show_toast("路径未修改")
                return
            if not os.path.isabs(input_dir):
                self._show_toast("错误：请使用绝对路径")
                return
            try:
                os.makedirs(input_dir, exist_ok=True)
                test_file = os.path.join(input_dir, ".perm_test")
                with open(test_file, "w") as f:
                    f.write("ok")
                os.remove(test_file)
            except Exception as err:
                self._safe_log_error("路径无写权限", err)
                return

            ok, msg = self._save_config(new_dir=input_dir)
            self._show_toast(msg)
            if ok:
                self._reload_ui()

        self._render_html_overlay(html, on_submit)

    def _open_sites_toggle_dialog(self):
        switches_html_list = []
        for name in self.sites:
            checked = "checked" if self.builtin_switches.get(name, True) else ""
            switches_html_list.append(f"""
            <div class="switch-item" style="display:flex; justify-content:space-between; align-items:center; padding:10px 0; border-bottom:1px solid #f0f0f0; font-size:14px; color:#333;">
                <span>{name}</span>
                <label class="switch">
                    <input type="checkbox" class="site-chk" data-name="{name}" {checked}>
                    <span class="slider"></span>
                </label>
            </div>
            """)
        switches_html = "".join(switches_html_list)
        master_checked = "checked" if self.master_switch else ""

        html = f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>{COMMON_CSS}
.switch {{ position: relative; display: inline-block; width: 44px; height: 24px; }}
.switch input {{ opacity: 0; width: 0; height: 0; }}
.slider {{ position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0; background-color: #ccc; transition: .3s; border-radius: 24px; }}
.slider:before {{ position: absolute; content: ""; height: 18px; width: 18px; left: 3px; bottom: 3px; background-color: white; transition: .3s; border-radius: 50%; }}
input:checked + .slider {{ background-color: #1a73e8; }}
input:checked + .slider:before {{ transform: translateX(20px); }}
</style></head>
<body onclick="closeUI()">
    <div class="card" onclick="event.stopPropagation()">
        <div class="title">🎛️ 站点下载开关配置</div>
        <div class="subtitle">关闭总开关: 强制全量下载内置站点<br>开启总开关: 仅下载下方选中的自定义站点</div>
        
        <div class="switch-item master-box" style="background:#e8f0fe; padding:10px; border-radius:8px; margin-bottom:10px; font-weight:bold; color:#1a73e8; display:flex; justify-content:space-between;">
            <span>⚡ 使用外置/自定义站点开关</span>
            <label class="switch">
                <input type="checkbox" id="masterSwitch" {master_checked}>
                <span class="slider"></span>
            </label>
        </div>
        <div class="scroll-list" style="overflow-y:auto; flex:1; padding-right:4px; margin-bottom:14px;">
            {switches_html}
        </div>
        <div class="btn-group">
            <button class="btn btn-secondary" onclick="closeUI()">取消</button>
            <button class="btn btn-primary" onclick="submitData()">保存设置</button>
        </div>
    </div>
    <script>
        window.RESULT_DATA = null;
        function submitData() {{
            var master = document.getElementById('masterSwitch').checked;
            var siteNodes = document.querySelectorAll('.site-chk');
            var switches = {{}};
            siteNodes.forEach(function(node) {{ switches[node.getAttribute('data-name')] = node.checked; }});
            window.RESULT_DATA = JSON.stringify({{ master: master, switches: switches }});
        }}
        function closeUI() {{ window.RESULT_DATA = "__CLOSE__"; }}
    </script>
</body></html>"""

        def on_submit(json_raw):
            try:
                data = json.loads(json_raw)
                ok, msg = self._save_config(new_master_switch=data.get("master", True), new_switches=data.get("switches", {}))
                self._show_toast(msg)
                if ok:
                    self._reload_ui()
            except Exception as e:
                self._safe_log_error("保存设置失败", e)

        self._render_html_overlay(html, on_submit)

    def _open_manage_sites_dialog(self):
        self._load_config()
        site_items = []
        for name, url in self.sites.items():
            site_items.append(
                f'<div class="site-item" style="padding:8px 4px; border-bottom:1px solid #f5f5f5;">'
                f'<label class="left" style="display:flex; align-items:center; gap:8px;">'
                f'<input type="checkbox" class="del-chk" data-name="{html_escape(name, attr_only=True)}">'
                f'<span class="name" style="font-size:14px; font-weight:600;">{html_escape(name)}</span></label>'
                f'<div class="url" style="font-size:11px; color:#888; margin:4px 0 0 24px; word-break:break-all;">{html_escape(url)}</div></div>'
            )
        sites_html = "".join(site_items) if site_items else '<div style="color:#999;font-size:13px;padding:12px;text-align:center;">当前没有站点，请先添加</div>'

        html = f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>{COMMON_CSS}
.scroll-list {{ overflow-y:auto; flex:1; margin:8px 0 12px; border:1px solid #f0f0f0; border-radius:10px; padding:8px; }}
#status {{ margin-top:10px; padding:8px; font-size:12px; color:#666; background:#f8f9fa; border-radius:6px; min-height:20px; }}
</style></head>
<body onclick="closeUI()">
    <div class="card" onclick="event.stopPropagation()">
        <div class="title">➕ 增删下载站点</div>
        <div class="subtitle">配置: {html_escape(self.DEFAULT_CONFIG_PATH)}<br>当前共 {len(self.sites)} 个站点</div>
        
        <span class="label">新增站点</span>
        <input type="text" id="nameInput" placeholder="站点名称，如 宝宝_新站" style="margin-bottom:8px;">
        <input type="text" id="urlInput" placeholder="https://example.com/" style="margin-bottom:4px;">
        <div style="font-size:11px; color:#888; margin-bottom: 12px;">填写名称和链接后点"添加站点"</div>
        
        <span class="label">现有站点（勾选后可删除）</span>
        <div class="scroll-list">{sites_html}</div>
        
        <div class="btn-group">
            <button class="btn btn-danger" onclick="deleteSelected()">删除选中</button>
            <button class="btn btn-primary" onclick="addSite()">添加站点</button>
            <button class="btn btn-secondary" onclick="closeUI()">取消</button>
        </div>
        <div id="status"></div>
    </div>
    <script>
        var resultData = null;
        var timerId = null;
        var pollCount = 0; 
        
        function addSite() {{
            var name = document.getElementById("nameInput").value.trim();
            var url = document.getElementById("urlInput").value.trim();
            if (!name || !url) {{ document.getElementById("status").textContent = "名称和链接不能为空"; return; }}
            if (!(url.indexOf("http://") === 0 || url.indexOf("https://") === 0)) {{ document.getElementById("status").textContent = "链接需以 http/https 开头"; return; }}
            resultData = JSON.stringify({{action: "add", name: name, url: url}});
        }}
        function deleteSelected() {{
            var nodes = document.querySelectorAll(".del-chk");
            var names = [];
            nodes.forEach(n => {{ if(n.checked) names.push(n.getAttribute("data-name")); }});
            if (names.length === 0) {{ document.getElementById("status").textContent = "请先勾选要删除的站点"; return; }}
            document.getElementById("status").textContent = "正在删除...";
            resultData = JSON.stringify({{action: "delete", names: names}});
        }}
        function closeUI() {{ resultData = "__CLOSE__"; }}
        function checkResult() {{
            if (resultData) {{ window.RESULT_DATA = resultData; if(timerId) clearTimeout(timerId); }}
            else {{
                if(pollCount++ > 6000) return; 
                timerId = setTimeout(checkResult, 100);
            }}
        }}
        checkResult();
    </script>
</body></html>"""

        def on_submit(json_raw):
            try:
                data = json.loads(json_raw)
                action = data.get("action")
                self._load_config()
                sites = dict(self.sites)

                if action == "add":
                    name, url = str(data.get("name") or "").strip(), str(data.get("url") or "").strip()
                    if not name or not url or not is_valid_http_url(url):
                        self._show_toast("站点名称和合法链接不能为空")
                        return
                    existed = name in sites
                    sites[name] = url
                    ok, msg = self._save_config(new_sites=sites)
                    self._show_toast((f"已更新站点: {name}" if existed else f"已添加站点: {name}") if ok else msg)
                    if ok:
                        self._reload_ui()

                elif action == "delete":
                    names = data.get("names") or []
                    if not names:
                        self._show_toast("未选择要删除的站点")
                        return
                        
                    removed_count = 0
                    for n in names:
                        if n in sites:
                            del sites[n]
                            removed_count += 1
                            
                    if removed_count == 0:
                        self._show_toast("没有可删除的站点")
                        return
                        
                    ok, msg = self._save_config(new_sites=sites)
                    self._show_toast(f"已删除 {removed_count} 个站点" if ok else msg)
                    if ok:
                        self._reload_ui()
            except Exception as e:
                self._safe_log_error("处理失败", e)

        self._render_html_overlay(html, on_submit)

    def _detect_encoding(self, resp):
        ct = resp.headers.get('Content-Type', '')
        m = re.search(r'charset=([a-zA-Z0-9_-]+)', ct)
        if m:
            enc = m.group(1).lower()
            try:
                "test".encode(enc)
                return enc
            except LookupError:
                pass
        if resp.encoding and resp.encoding.upper() != 'ISO-8859-1':
            return resp.encoding
        m = re.search(r'<meta[^>]+charset=["\']?([\w-]+)', resp.text[:1000], re.IGNORECASE)
        if m:
            enc = m.group(1).lower()
            try:
                "test".encode(enc)
                return enc
            except LookupError:
                pass
        return 'utf-8'

    def _download_single(self, url, save_path):
        url = url.strip()
        if not url: return False, "链接为空", ""
        
        temp_session = Session()
        temp_session.headers.update(self.session.headers)
        
        try:
            resp = temp_session.get(url, timeout=30, allow_redirects=True)
            resp.raise_for_status()
            
            encoding = self._detect_encoding(resp)
            content = resp.content.decode(encoding, errors='replace')
            
            d = os.path.dirname(save_path)
            if d and not os.path.exists(d):
                os.makedirs(d)
            
            with open(save_path, "w", encoding="utf-8") as f:
                f.write(content)
            
            return True, f"成功 ({len(content)} 字节)", save_path
        except Exception as e:
            if os.path.exists(save_path):
                try: os.remove(save_path)
                except: pass
            return False, f"失败: {str(e)}", ""

    def _download_builtin_links(self):
        active_map = self._get_active_builtin_links()
        links = list(active_map.items())
        if not links:
            self._show_toast("当前无开启的站点可供下载！")
            return
        self._acquire_web_lock()
        self._download_with_live_log(links, "builtin", target_dir=self.save_dir)

    def _build_html_log(self, log_entries, is_complete=False):
        close_btn_html = '<button class="close-btn" onclick="closeUI()">✕</button>' if is_complete else ''
        html_parts = [
            '<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">',
            '<style>* { box-sizing: border-box; margin:0; padding:0; }',
            'body { font-family: monospace; font-size: 13px; background: #fafafa; color: #333; }',
            '.log-wrap { position: relative; width:100%; height:100vh; padding:16px; display:flex; flex-direction:column; }',
            '.header { border-bottom: 2px solid #1976D2; padding-bottom: 8px; margin-bottom: 12px; flex-shrink:0; }',
            '.title { font-weight: bold; font-size: 15px; color: #1976D2; }',
            '.log-content { flex:1; overflow-y:auto; }',
            '.close-btn { position:absolute; right:16px; bottom:16px; background: #ff4d4f; color: white; border: none; border-radius: 50%; width: 32px; height: 32px; font-weight: bold; cursor: pointer; box-shadow: 0 2px 6px rgba(0,0,0,0.3); z-index:20; }',
            '.info { color: #1976D2; margin:4px 0; }',
            '.success { color: #2E7D32; font-weight: bold; margin:4px 0; }',
            '.fail { color: #C62828; font-weight: bold; margin:4px 0; }',
            '.path { color: #666; font-size: 12px; padding-left: 12px; margin:4px 0; }',
            '.separator { border-top: 1px solid #ddd; margin: 8px 0; padding-top: 8px; }',
            '.progress { background: #E3F2FD; padding: 6px 10px; border-radius: 4px; margin: 4px 0; color: #1565C0; font-weight:bold; }',
            '</style>',
            '<script>function closeUI(){ window.RESULT_DATA = "__CLOSE__"; }</script>',
            '</head><body><div class="log-wrap"><div class="header"><div class="title">📋 下载进度日志</div></div><div class="log-content">'
        ]
        for entry in log_entries:
            safe_entry = html_escape(entry)
            if safe_entry.startswith("✅"): html_parts.append(f'<div class="success">{safe_entry}</div>')
            elif safe_entry.startswith("❌"): html_parts.append(f'<div class="fail">{safe_entry}</div>')
            elif safe_entry.startswith("["): html_parts.append(f'<div class="info">{safe_entry}</div>')
            elif safe_entry.startswith("进度:") or safe_entry.startswith("总计:") or safe_entry.startswith("下载完成"): html_parts.append(f'<div class="progress">{safe_entry}</div>')
            elif safe_entry.startswith("保存至:") or safe_entry.startswith("  保存至:"): html_parts.append(f'<div class="path">{safe_entry}</div>')
            elif "====" in safe_entry or "----" in safe_entry: html_parts.append(f'<div class="separator"></div>')
            else: html_parts.append(f'<div>{safe_entry}</div>')
    
        html_parts.extend(['</div>', close_btn_html, '</div></body></html>'])
        return "\n".join(html_parts)

    def _download_with_live_log(self, links, mode, target_dir=None, max_workers=4):
        try:
            from java import jclass, dynamic_proxy
            from java.lang import Runnable

            act = self.cached_activity or self._activity()
            if not act:
                self._release_web_lock()
                return

            spider_self = self
            total = len(links)
            save_dir = target_dir if target_dir else self.save_dir

            log_entries = [f"下载任务启动 (目标路径: {save_dir})", "-" * 20]
            is_complete_ref = [False]
            log_lock = threading.Lock()

            WebView = jclass("android.webkit.WebView")
            FrameLayout = jclass("android.widget.FrameLayout")
            Color = jclass("android.graphics.Color")
            ValueCallback = jclass("android.webkit.ValueCallback")

            class CreateLogView(dynamic_proxy(Runnable)):
                def run(self):
                    decor_view = act.getWindow().getDecorView()
                    wrapper = FrameLayout(act)
                    web = WebView(act)
                    web.setBackgroundColor(Color.TRANSPARENT)
                    
                    settings = web.getSettings()
                    settings.setJavaScriptEnabled(True)
                    settings.setDomStorageEnabled(True)

                    params = FrameLayout.LayoutParams(
                        int(act.getResources().getDisplayMetrics().widthPixels * 0.90),
                        int(act.getResources().getDisplayMetrics().heightPixels * 0.85)
                    )
                    params.gravity = 17

                    wrapper.addView(web, params)
                    decor_view.addView(wrapper)

                    class ScrollBottom(dynamic_proxy(Runnable)):
                        def run(self):
                            try: web.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", None)
                            except: pass
                    scroll_bottom_runnable = ScrollBottom()

                    class RefreshRun(dynamic_proxy(Runnable)):
                        def run(self):
                            try:
                                with log_lock:
                                    html = spider_self._build_html_log(log_entries, is_complete_ref[0])
                                web.loadDataWithBaseURL(None, html, "text/html", "utf-8", None)
                                web.postDelayed(scroll_bottom_runnable, 100)
                            except Exception: pass
                    refresh_runnable = RefreshRun()

                    def refresh_webview():
                        act.runOnUiThread(refresh_runnable)

                    close_runnable_ref = [None]
                    class CloseJSCallback(dynamic_proxy(ValueCallback)):
                        def onReceiveValue(self, value):
                            try:
                                val_str = str(value) if value is not None else ""
                                if val_str.startswith('"') and val_str.endswith('"'): val_str = json.loads(val_str)
                                if val_str == "__CLOSE__":
                                    decor_view.removeView(wrapper)
                                    spider_self._release_web_lock()
                                else:
                                    act.getWindow().getDecorView().postDelayed(close_runnable_ref[0], 150)
                            except Exception: pass
                    close_callback_instance = CloseJSCallback()

                    class CheckCloseSignal(dynamic_proxy(Runnable)):
                        def run(self):
                            try: web.evaluateJavascript("window.RESULT_DATA", close_callback_instance)
                            except: pass
                    close_runnable_ref[0] = CheckCloseSignal()

                    def worker():
                        time.sleep(0.3)
                        refresh_webview()
                        
                        success_cnt, fail_cnt, done_cnt = 0, 0, 0

                        with ThreadPoolExecutor(max_workers=max_workers) as pool:
                            futures = {}
                            for i, item in enumerate(links, 1):
                                if mode == "input_links":
                                    url = item
                                    name = url[:35] + ("..." if len(url) > 35 else "")
                                    filename = f"html_{i}.html"
                                else:
                                    name, url = item
                                    safe_name = re.sub(r'[/\\:*?"<>|]', '_', name)
                                    filename = f"{safe_name}.html"
                                
                                save_path = os.path.join(save_dir, filename)
                                futures[pool.submit(spider_self._download_single, url, save_path)] = (i, name)

                            for future in as_completed(futures):
                                i, name = futures[future]
                                done_cnt += 1
                                try:
                                    success, msg, path = future.result()
                                except Exception as err:
                                    success, msg, path = False, str(err), ""
                                
                                with log_lock:
                                    log_entries.append(f"[{done_cnt}/{total}] 下载完毕: {name}")
                                    if success:
                                        log_entries.append(f"✅ {name}: 成功 ({msg})")
                                        log_entries.append(f"   保存至: {path}")
                                        success_cnt += 1
                                    else:
                                        log_entries.append(f"❌ {name}: {msg}")
                                        fail_cnt += 1
                                    
                                    log_entries.append(f"进度: {done_cnt}/{total} | 成功: {success_cnt} | 失败: {fail_cnt}")
                                    log_entries.append("-" * 20)
                                refresh_webview()
                        
                        is_complete_ref[0] = True
                        with log_lock:
                            log_entries.extend(["", "=" * 40, "🎉 下载任务完毕，点击右下角 ✕ 退出", f"总计: {total} | 成功: {success_cnt} | 失败: {fail_cnt}"])
                        refresh_webview()
                        act.getWindow().getDecorView().postDelayed(close_runnable_ref[0], 200)

                    threading.Thread(target=worker, daemon=True).start()
            act.runOnUiThread(CreateLogView())
        except Exception as e:
            self._release_web_lock()
            print(f"Download Task Error: {e}")

    # ====================== 基础工具 ======================
    def _show_toast(self, msg):
        try:
            from java import jclass, dynamic_proxy
            from java.lang import Runnable
            act = self.cached_activity or self._activity()
            if act:
                Toast = jclass("android.widget.Toast")
                class ShowT(dynamic_proxy(Runnable)):
                    run = lambda s: Toast.makeText(act, msg, 0).show()
                act.runOnUiThread(ShowT())
        except: pass

    def _activity(self):
        try:
            from java import jclass
            JClass = jclass("java.lang.Class")
            AT = JClass.forName("android.app.ActivityThread")
            cur = AT.getMethod("currentActivityThread").invoke(None)
            f = AT.getDeclaredField("mActivities")
            f.setAccessible(True)
            for r in f.get(cur).values().toArray():
                rc = r.getClass()
                pf = rc.getDeclaredField("paused")
                pf.setAccessible(True)
                if not pf.getBoolean(r):
                    af = rc.getDeclaredField("activity")
                    af.setAccessible(True)
                    return af.get(r)
        except Exception: pass
        return None

    def destroy(self):
        try: self.session.close()
        except Exception: pass
