# -*- coding: utf-8 -*-
import os
import sys
import json
from requests import Session
from base.spider import Spider

sys.path.append("..")

class Spider(Spider):
    CACHE_FILE = "/storage/emulated/0/TV/demo_input.txt"
    
    # 定义动作指令标识
    CMD_INPUT = "demo::input"
    CMD_INFO = "demo::info"
    CMD_INPUT_SHOW = "demo::input_show"
    CMD_HTML_LOCAL = "demo::html_local"
    CMD_HTML_WEB = "demo::html_web"
    CMD_HTML_FULL = "demo::html_full"  # 新增：全屏 HTML
    
    input_cache = ""

    def getName(self):
        return "Action弹窗多示例演示(含全屏HTML)"

    def init(self, extend=""):
        self.cfg = self._load_ext(extend)
        self.session = Session()
        self._load_cache()

    def _load_ext(self, extend):
        try:
            if isinstance(extend, dict):
                return extend
            s = str(extend or "").strip()
            if s.startswith("{"):
                return json.loads(s)
        except Exception:
            pass
        return {}

    def _load_cache(self):
        if os.path.exists(self.CACHE_FILE):
            try:
                with open(self.CACHE_FILE, "r", encoding="utf-8") as f:
                    self.input_cache = f.read().strip()
            except Exception:
                self.input_cache = ""

    def _save_cache(self, text):
        self.input_cache = text
        try:
            d = os.path.dirname(self.CACHE_FILE)
            if d and not os.path.exists(d):
                os.makedirs(d)
            with open(self.CACHE_FILE, "w", encoding="utf-8") as f:
                f.write(text or "")
        except Exception:
            pass

    # 首页分类
    def homeContent(self, filter):
        return {
            "class": [
                {"type_id": "popup_demo", "type_name": "弹窗示例大全"}
            ],
            "filters": {}
        }

    # 分类列表 - 新增全屏 HTML 选项
    def categoryContent(self, tid, pg, filter, extend):
        if tid == "popup_demo":
            return {
                "list": [
                    {
                        "vod_id": self.CMD_INPUT,
                        "vod_name": "示例 1：输入框并写入文件",
                        "vod_pic": "",
                        "vod_remarks": f"当前文件内容：{self.input_cache if self.input_cache else '空'}",
                        "action": self.CMD_INPUT,
                        "style": {"type": "list", "ratio": 1.1}
                    },
                    {
                        "vod_id": self.CMD_INFO,
                        "vod_name": "示例 2：信息提示弹窗",
                        "vod_pic": "",
                        "vod_remarks": "纯文本确认对话框",
                        "action": self.CMD_INFO,
                        "style": {"type": "list", "ratio": 1.1}
                    },
                    {
                        "vod_id": self.CMD_INPUT_SHOW,
                        "vod_name": "示例 3：输入后连弹第二个弹窗",
                        "vod_pic": "",
                        "vod_remarks": "内存传递展示",
                        "action": self.CMD_INPUT_SHOW,
                        "style": {"type": "list", "ratio": 1.1}
                    },
                    {
                        "vod_id": self.CMD_HTML_LOCAL,
                        "vod_name": "示例 4：弹窗加载本地自定义 HTML 富文本",
                        "vod_pic": "",
                        "vod_remarks": "普通窗口模式",
                        "action": self.CMD_HTML_LOCAL,
                        "style": {"type": "list", "ratio": 1.1}
                    },
                    {
                        "vod_id": self.CMD_HTML_WEB,
                        "vod_name": "示例 5：弹窗直接加载远程网页 URL",
                        "vod_pic": "",
                        "vod_remarks": "普通窗口模式",
                        "action": self.CMD_HTML_WEB,
                        "style": {"type": "list", "ratio": 1.1}
                    },
                    {
                        "vod_id": self.CMD_HTML_FULL,
                        "vod_name": "示例 6：全屏展示 HTML 页面 (WebView)",
                        "vod_pic": "",
                        "vod_remarks": "按返回键即可退出全屏",
                        "action": self.CMD_HTML_FULL,
                        "style": {"type": "list", "ratio": 1.1}
                    }
                ],
                "page": 1,
                "pagecount": 1
            }
        return {"list": [], "page": 1, "pagecount": 1}

    # ====================== Action 动作路由 ======================
    def action(self, action_str):
        if action_str == self.CMD_INPUT:
            self._input_ck()
        elif action_str == self.CMD_INFO:
            self._show_info_dialog("系统提示", "这是一条简单的信息提示内容。\n点击确定关闭弹窗。")
        elif action_str == self.CMD_INPUT_SHOW:
            self._input_then_show()
        elif action_str == self.CMD_HTML_LOCAL:
            html_code = "<html><body style='background:#f5f5f5;padding:20px;'><h1 style='color:#e91e63;'>弹窗富文本</h1><p>这是一个局部的 HTML 弹窗！</p></body></html>"
            self._show_html_dialog("富文本公告", html_content=html_code)
        elif action_str == self.CMD_HTML_WEB:
            self._show_html_dialog("在线网页", web_url="https://m.baidu.com")
        elif action_str == self.CMD_HTML_FULL:
            # 示例 6：全屏 HTML 渲染（也可以传 web_url="https://..."）
            full_html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body { 
                        background: linear-gradient(135deg, #1e3c72 0%, #2a5298 100%); 
                        color: #ffffff; 
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                        height: 100vh;
                        display: flex;
                        flex-direction: column;
                        justify-content: center;
                        align-items: center;
                        text-align: center;
                        padding: 20px;
                    }
                    h1 { font-size: 32px; margin-bottom: 20px; text-shadow: 0 2px 4px rgba(0,0,0,0.3); }
                    p { font-size: 18px; line-height: 1.6; color: #e0e0e0; max-width: 600px; }
                    .btn-box { margin-top: 30px; }
                    .tag { background: rgba(255,255,255,0.2); padding: 8px 16px; border-radius: 20px; font-size: 14px; }
                </style>
            </head>
            <body>
                <h1>🚀 全屏 WebView 渲染演示</h1>
                <p>当前页面已充满整个电视屏幕，没有标题栏和边框。</p>
                <br>
                <p class="tag">提示：按遥控器或手机的【返回键】可立即退出全屏模式</p>
            </body>
            </html>
            """
            self._show_full_webview(html_content=full_html)
            
        return json.dumps({"msg": "ok"}, ensure_ascii=False)

    def detailContent(self, ids):
        return {"list": []}

    def playerContent(self, flag, id, vipFlags):
        header = {"User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"}
        return {"parse": 0, "playUrl": "", "url": "about:blank", "header": header}

    # ====================== 核心实现：全屏 WebView ======================
    def _show_full_webview(self, html_content=None, web_url=None):
        """完全覆盖全屏的 WebView 渲染方法"""
        try:
            from java import jclass, dynamic_proxy
            from java.lang import Runnable

            act = self._activity()
            if not act:
                return

            Dialog = jclass("android.app.Dialog")
            WebView = jclass("android.webkit.WebView")
            LP = jclass("android.view.ViewGroup$LayoutParams")

            class Run(dynamic_proxy(Runnable)):
                def run(self):
                    # 1. 使用 Android 内置的全屏无标题栏主题 (0x0103000a = android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                    dialog = Dialog(act, 0x0103000a)
                    
                    # 2. 创建并配置 WebView
                    web_view = WebView(act)
                    settings = web_view.getSettings()
                    settings.setJavaScriptEnabled(True)
                    settings.setDomStorageEnabled(True)
                    settings.setUseWideViewPort(True)
                    settings.setLoadWithOverviewMode(True)

                    # 3. 加载内容
                    if web_url:
                        web_view.loadUrl(web_url)
                    elif html_content:
                        web_view.loadDataWithBaseURL(None, html_content, "text/html", "utf-8", None)

                    # 4. 设置 Dialog 内容并将尺寸拉满（-1 表示 MATCH_PARENT）
                    dialog.setContentView(web_view, LP(-1, -1))
                    dialog.setCancelable(True)  # 允许按返回键关闭
                    dialog.show()

            act.getWindow().getDecorView().post(Run())
        except Exception:
            pass

    # 普通弹窗使用的组件
    def _show_html_dialog(self, title, html_content=None, web_url=None):
        def on_ui(act, Builder, WebView, LinearLayout, LP, Click):
            root = LinearLayout(act)
            root.setOrientation(LinearLayout.VERTICAL)
            web_view = WebView(act)
            settings = web_view.getSettings()
            settings.setJavaScriptEnabled(True)
            settings.setDomStorageEnabled(True)

            if web_url:
                web_view.loadUrl(web_url)
            elif html_content:
                web_view.loadDataWithBaseURL(None, html_content, "text/html", "utf-8", None)

            root.addView(web_view, LP(-1, 800)) 
            Builder(act).setTitle(title).setView(root).setPositiveButton("关闭", None).show()

        self._run_html_ui(on_ui)

    def _run_on_ui(self, ui_builder_fn):
        try:
            from java import jclass, dynamic_proxy
            from java.lang import Runnable

            act = self._activity()
            if not act:
                return

            Builder = jclass("android.app.AlertDialog$Builder")
            EditText = jclass("android.widget.EditText")
            TextView = jclass("android.widget.TextView")
            LinearLayout = jclass("android.widget.LinearLayout")
            LP = jclass("android.widget.LinearLayout$LayoutParams")
            InputType = jclass("android.text.InputType")
            DialogClick = jclass("android.content.DialogInterface$OnClickListener")
            Toast = jclass("android.widget.Toast")

            class Run(dynamic_proxy(Runnable)):
                def run(self):
                    ui_builder_fn(act, Builder, EditText, TextView, LinearLayout, LP, InputType, Click, Toast)

            class Click(dynamic_proxy(DialogClick)):
                def __init__(self, fn):
                    super().__init__()
                    self.fn = fn

                def onClick(self, dialog, which):
                    if self.fn:
                        self.fn()

            act.getWindow().getDecorView().post(Run())
        except Exception:
            pass

    def _run_html_ui(self, ui_builder_fn):
        try:
            from java import jclass, dynamic_proxy
            from java.lang import Runnable

            act = self._activity()
            if not act:
                return

            Builder = jclass("android.app.AlertDialog$Builder")
            WebView = jclass("android.webkit.WebView")
            LinearLayout = jclass("android.widget.LinearLayout")
            LP = jclass("android.widget.LinearLayout$LayoutParams")
            DialogClick = jclass("android.content.DialogInterface$OnClickListener")

            class Run(dynamic_proxy(Runnable)):
                def run(self):
                    ui_builder_fn(act, Builder, WebView, LinearLayout, LP, Click)

            class Click(dynamic_proxy(DialogClick)):
                def __init__(self, fn):
                    super().__init__()
                    self.fn = fn

                def onClick(self, dialog, which):
                    if self.fn:
                        self.fn()

            act.getWindow().getDecorView().post(Run())
        except Exception:
            pass

    def _input_ck(self):
        def on_ui(act, Builder, EditText, TextView, LinearLayout, LP, InputType, Click, Toast):
            root = LinearLayout(act)
            root.setOrientation(LinearLayout.VERTICAL)
            root.setPadding(36, 8, 36, 0)
            tip = TextView(act)
            tip.setText("粘贴输入内容\n保存自动写入本地")
            root.addView(tip, LP(-1, -2))
            edit = EditText(act)
            edit.setSingleLine(False)
            edit.setMinLines(4)
            edit.setText(self.input_cache or "")
            root.addView(edit, LP(-1, -2))

            def save():
                ck = str(edit.getText().toString()).strip()
                if ck:
                    self._save_cache(ck)
                    Toast.makeText(act, "内容已保存", 0).show()
                else:
                    Toast.makeText(act, "输入内容不能为空", 1).show()

            Builder(act).setTitle("输入弹窗 (示例1)").setView(root).setNegativeButton("取消", None).setPositiveButton("保存", Click(save)).show()
        self._run_on_ui(on_ui)

    def _show_info_dialog(self, title, message):
        def on_ui(act, Builder, EditText, TextView, LinearLayout, LP, InputType, Click, Toast):
            root = LinearLayout(act)
            root.setOrientation(LinearLayout.VERTICAL)
            root.setPadding(36, 20, 36, 20)
            msg_view = TextView(act)
            msg_view.setText(message)
            msg_view.setTextSize(16.0)
            root.addView(msg_view, LP(-1, -2))
            def on_confirm():
                Toast.makeText(act, "知道了", 0).show()
            Builder(act).setTitle(title).setView(root).setPositiveButton("确定", Click(on_confirm)).show()
        self._run_on_ui(on_ui)

    def _input_then_show(self):
        def on_ui(act, Builder, EditText, TextView, LinearLayout, LP, InputType, Click, Toast):
            root = LinearLayout(act)
            root.setOrientation(LinearLayout.VERTICAL)
            root.setPadding(36, 8, 36, 0)
            tip = TextView(act)
            tip.setText("请输入任意文本（确定后将直接弹出新对话框显示该文本）：")
            root.addView(tip, LP(-1, -2))
            edit = EditText(act)
            edit.setSingleLine(False)
            edit.setMinLines(3)
            edit.setHint("在这里输入一些内容...")
            root.addView(edit, LP(-1, -2))

            def next_step():
                user_text = str(edit.getText().toString()).strip()
                if not user_text:
                    Toast.makeText(act, "内容为空，取消下一步操作", 0).show()
                    return
                self._show_info_dialog("第 2 步：展示结果", f"您刚才输入的内容是：\n\n【 {user_text} 】")

            Builder(act).setTitle("第 1 步：请输入内容").setView(root).setNegativeButton("取消", None).setPositiveButton("下一步", Click(next_step)).show()
        self._run_on_ui(on_ui)

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
        except Exception:
            pass
        return None

    def destroy(self):
        try:
            self.session.close()
        except Exception:
            pass
