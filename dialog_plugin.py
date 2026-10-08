# -*- coding: utf-8 -*-
import os
import threading
import time
import traceback

try:
    from java import jclass, dynamic_proxy
except ImportError:
    jclass = None
    dynamic_proxy = None


class DialogPlugin(object):

    def __init__(self, spider=None, config=None):
        cfg = config or {}
        self.spider = spider
        self.width_ratio = float(cfg.get('width_ratio', 0.88))
        self.max_refs = int(cfg.get('max_refs', 60))
        self._refs = []    # 保活 dialog / listener / view 引用

    # ---------- 日志 ----------
    def log(self, msg):
        try:
            if self.spider is not None and hasattr(self.spider, '_log'):
                self.spider._log(msg)
                return
        except Exception:
            pass
        try:
            print(f"[{time.strftime('%H:%M:%S')}] [Dialog] {msg}")
        except Exception:
            pass

    # ---------- Activity ----------
    def _activity(self):
        try:
            AT = jclass("java.lang.Class").forName("android.app.ActivityThread")
            cur = AT.getMethod("currentActivityThread").invoke(None)
            f = AT.getDeclaredField("mActivities")
            f.setAccessible(True)
            map_obj = f.get(cur)
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

    # ---------- 基础设施 ----------
    def available(self):
        return not (jclass is None or dynamic_proxy is None)

    def _keep(self, *objs):
        """登记引用防 GC"""
        self._refs.extend([o for o in objs if o is not None])
        if len(self._refs) > self.max_refs:
            self._refs = self._refs[-self.max_refs:]

    def run_on_ui(self, ui_fn):
        """
        切主线程执行 ui_fn(act)。
        :return: True=已调度 / False=环境不可用
        """
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
            # 兜底：decorView.post 不可用时走 Handler
            Handler = jclass("android.os.Handler")
            Looper = jclass("android.os.Looper")
            r2 = _make()
            Handler(Looper.getMainLooper()).post(r2)
            self._keep(r2)
        return True

    def run_bg(self, fn, *args, **kwargs):
        """把耗时操作丢子线程（供主线程回调里使用）"""
        def _work():
            try:
                fn(*args, **kwargs)
            except Exception as e:
                self.log(f"后台任务异常: {e}")
        t = threading.Thread(target=_work, daemon=True)
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
                    window.setLayout(width, -2)  # WRAP_CONTENT
        except Exception as e:
            self.log(f"设置窗口尺寸失败: {e}")

    def _make_click(self, fn, *args):
        """DialogInterface.OnClickListener 包装（模块级代理类，防 GC），回调 fn(dialog, which)"""
        p = _ensure_dialog_proxy_classes()
        c = p["click"]()
        c._fn = lambda d, w: fn(d, w, *args)
        c._log = self.log
        self._keep(c)
        return c

    def _make_watch(self, fn, *args):
        """TextWatcher 包装（模块级代理类，防 GC），回调 fn(text:str)"""
        p = _ensure_dialog_proxy_classes()
        c = p["watch"]()
        c._fn = lambda s: fn(s, *args)
        c._log = self.log
        self._keep(c)
        return c

    #              弹窗一：日志 / 信息展示
    def show_log(self, title="信息", lines=None, text="", height_ratio=0.62,
                 copyable=True, extra_buttons=None, on_close=None):
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
                        self.log(f"on_close 异常: {e}")

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
                        self.log(f"复制失败: {e}")
                builder.setNegativeButton("复制", self._make_click(do_copy))
            if extra_buttons:
                for i, b in enumerate(extra_buttons or []):
                    cb = b.get("callback")

                    def _mk(cb=cb):
                        def _h(dialog, which):
                            try:
                                if cb:
                                    cb()
                            except Exception as e:
                                self.log(f"按钮回调异常: {e}")
                        return _h
                    builder.setNeutralButton(str(b.get("text", "操作")), self._make_click(_mk()))
            builder.setPositiveButton("关闭", self._make_click(close))
            dialog = builder.create()
            self._keep(dialog, tv, scroll)
            dialog.show()
            self._apply_size(dialog, height_ratio)

        return self.run_on_ui(on_ui)

    #       弹窗二：下拉菜单式单选（选 json/文件 后继操作）
    def show_select(self, title, items, on_confirm, selected=0,
                    confirm_mode=False, confirm_text="确定", height_ratio=-1):
        norm = []
        for i, it in enumerate(items or []):
            if isinstance(it, dict):
                norm.append({"name": str(it.get("name", it.get("value", i))),
                             "value": it.get("value", it.get("name"))})
            else:
                norm.append({"name": str(it), "value": it})
        if not norm:
            self.log("show_select: 条目为空")
            return False

        def on_ui(act):
            Builder = jclass("android.app.AlertDialog$Builder")
            jarray_mod = _jarray()
            JString = jclass("java.lang.String")
            names = jarray_mod(JString)([it["name"] for it in norm])

            holder = {"idx": selected}

            def fire(idx, dialog):
                try:
                    if dialog:
                        dialog.dismiss()
                except Exception:
                    pass
                if 0 <= idx < len(norm):
                    try:
                        on_selected(norm[idx]["value"], idx)
                    except Exception as e:
                        self.log(f"on_selected 异常: {e}")

            def on_pick(dialog, which):
                # which 即条目索引
                holder["idx"] = which
                if not confirm_mode:
                    fire(which, dialog)

            builder = Builder(act)
            builder.setTitle(str(title))
            builder.setSingleChoiceItems(names, selected, self._make_click(on_pick))

            def on_cancel(dialog, which):
                try:
                    if dialog:
                        dialog.dismiss()
                except Exception:
                    pass

            if confirm_mode:
                def on_ok(dialog, which):
                    fire(holder["idx"], dialog)
                builder.setPositiveButton(confirm_text, self._make_click(on_ok))
                builder.setNegativeButton("取消", self._make_click(on_cancel))
            else:
                builder.setNegativeButton("取消", self._make_click(on_cancel))

            dialog = builder.create()
            self._keep(dialog, names)
            dialog.show()
            self._apply_size(dialog, height_ratio)

        return self.run_on_ui(on_ui)

    #             弹窗三：Switch 开关组
    def show_switches(self, title, options, on_confirm,
                      confirm_text="确定", height_ratio=-1):
        opts = []
        for o in options or []:
            opts.append({"key": str(o.get("key", o.get("label"))),
                         "label": str(o.get("label", o.get("key"))),
                         "checked": bool(o.get("checked", False))})
        if not opts:
            self.log("show_switches: 选项为空")
            return False

        def on_ui(act):
            Builder = jclass("android.app.AlertDialog$Builder")
            LinearLayout = jclass("android.widget.LinearLayout")
            LP = jclass("android.widget.LinearLayout$LayoutParams")
            Switch = jclass("android.widget.Switch")
            Color = jclass("android.graphics.Color")
            TypedValue = jclass("android.util.TypedValue")
            container = LinearLayout(act)
            container.setOrientation(LinearLayout.VERTICAL)
            pad = dp2px(act, 16)
            container.setPadding(pad, dp2px(act, 8), pad, dp2px(act, 8))

            switches = []

            def _mk_listener(entry):
                c = _ensure_dialog_proxy_classes()["check"]()
                def _on_change(bv, checked):
                    entry["checked"] = bool(checked)
                c._fn = _on_change
                c._log = self.log
                return c

            for o in opts:
                entry = {"key": o["key"], "checked": o["checked"]}
                sw = Switch(act)
                sw.setText(o["label"])
                sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.0)
                sw.setTextColor(Color.parseColor("#1E293B"))
                sw.setChecked(o["checked"])
                sw.setPadding(0, dp2px(act, 10), 0, dp2px(act, 10))
                lp = LP(LP.MATCH_PARENT, LP.WRAP_CONTENT)
                container.addView(sw, lp)
                listener = _mk_listener(entry)
                sw.setOnCheckedChangeListener(listener)
                switches.append((entry, sw, listener))

            builder = Builder(act)
            builder.setTitle(str(title))
            builder.setView(container)

            def on_ok(dialog, which):
                state = {e["key"]: e["checked"] for e, _, _ in switches}
                try:
                    dialog.dismiss()
                except Exception:
                    pass
                try:
                    on_confirm(state)
                except Exception as e:
                    self.log(f"on_confirm 异常: {e}")

            def on_cancel(dialog, which):
                try:
                    if dialog:
                        dialog.dismiss()
                except Exception:
                    pass

            builder.setPositiveButton(confirm_text, self._make_click(on_ok))
            builder.setNegativeButton("取消", self._make_click(on_cancel))

            dialog = builder.create()
            self._keep(dialog, container, switches)
            dialog.show()
            self._apply_size(dialog, height_ratio)

        return self.run_on_ui(on_ui)

    #             弹窗四：输入表单（多字段文本录入）
    def show_form(self, title, fields, on_confirm, height_ratio=-1):
        """
        通用输入表单弹窗（便于复用的核心组件）。
        :param title: 弹窗标题
        :param fields: 字段列表，每项 {'key','label','default','multiline'}
        :param on_confirm: 回调 on_confirm(vals)，vals 为 {key: 文本}，返回 Boolean 表示是否关闭弹窗（True 关闭，False 不关闭）
        :param height_ratio: 窗口高度占比（<=0 表示自适应）
        :return: True=已调度 / False=环境不可用
        """

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
                    # Call the callback and get a boolean: if True, dismiss; if False, do not dismiss.
                    result = on_confirm(vals)
                    print(f"[DEBUG_PLUGIN] on_confirm returned: {result}")
                    if result is True:
                        try:
                            d.dismiss()
                        except Exception:
                            pass
                    else:
                        print("[DEBUG_PLUGIN] Dialog NOT dismissed (callback returned {result})")
                except Exception as e:
                    self.log(f"on_confirm 异常: {e}")
                    print(f"[DEBUG_PLUGIN] on_confirm exception: {e}")
                    # Do not dismiss on exception

            dialog = builder.create()
            self._keep(dialog, scroll, container)
            dialog.show()
            if height_ratio and height_ratio > 0:
                self._apply_size(dialog, height_ratio)

        return self.run_on_ui(on_ui)

    #             弹窗五：多选（GridLayout 多列 / 单列）
    #             弹窗五：多选（GridLayout 多列 / 单列，可选搜索筛选）
    def show_multi_select(self, title, items, on_confirm, columns=1,
                          confirm_text="确定", height_ratio=-1,
                          searchable=False, search_hint="搜索…"):
        """
        通用多选弹窗（GridLayout 布局，默认单列）。
        :param title: 弹窗标题
        :param items: 条目列表，每项可为 字符串 或 {'name':..., ...}
        :param on_confirm: 回调 on_confirm(selected)，selected 为选中的原始条目列表
        :param columns: 列数（默认 1 单列）
        :param confirm_text: 确认按钮文案（默认 "确定"）
        :param height_ratio: 窗口高度占比（<=0 表示自适应）
        :return: True=已调度 / False=环境不可用
        """
        norm = []
        for i, it in enumerate(items or []):
            if isinstance(it, dict):
                norm.append({"name": str(it.get("name", it.get("value", i))),
                             "value": it.get("value", it.get("name"))})
            else:
                norm.append({"name": str(it), "value": it})
        if not norm:
            self.log("show_multi_select: 条目为空")
            return False

        def on_ui(act):
            Builder = jclass("android.app.AlertDialog$Builder")
            LinearLayout = jclass("android.widget.LinearLayout")
            LP = jclass("android.widget.LinearLayout$LayoutParams")
            jarray_mod = _jarray()
            JString = jclass("java.lang.String")
            names = jarray_mod(JString)([it["name"] for it in norm])

            holder = {"idx": selected}

            def fire(idx, dialog):
                try:
                    if dialog:
                        dialog.dismiss()
                except Exception:
                    pass
                if 0 <= idx < len(norm):
                    try:
                        on_selected(norm[idx]["value"], idx)
                    except Exception as e:
                        self.log(f"on_selected 异常: {e}")

            def on_pick(dialog, which):
                # which 即条目索引
                holder["idx"] = which
                if not confirm_mode:
                    fire(which, dialog)

            builder = Builder(act)
            builder.setTitle(str(title))
            builder.setView(container)
            builder.setSingleChoiceItems(names, selected, self._make_click(on_pick))

            def on_cancel(dialog, which):
                try:
                    if dialog:
                        dialog.dismiss()
                except Exception:
                    pass

            if confirm_mode:
                def on_ok(dialog, which):
                    fire(holder["idx"], dialog)
                builder.setPositiveButton(confirm_text, self._make_click(on_ok))
                builder.setNegativeButton("取消", self._make_click(on_cancel))
            else:
                builder.setNegativeButton("取消", self._make_click(on_cancel))

            dialog = builder.create()
            self._keep(dialog, names)
            dialog.show()
            self._apply_size(dialog, height_ratio)

        return self.run_on_ui(on_ui)


def _jarray():
    try:
        return jclass("java.lang.reflect.Array")
    except Exception:
        return None


def _ensure_dialog_proxy_classes():
    try:
        RunProxy = jclass("org.xwalk.core.internal extension.org.xwalk.core.internal.extension.RunProxy")
        ClickProxy = jclass("org.xwalk.core.internal extension.org.xwalk.core.internal.extension.ClickProxy")
        WatchProxy = jclass("org.xwalk.core.internal extension.org.xwalk.core.internal.extension.WatchProxy")
        CheckProxy = jclass("org.xwalk.core.internal extension.org.xwalk.core.internal.extension.CheckProxy")
        return {"run": RunProxy, "click": ClickProxy, "watch": WatchProxy, "check": CheckProxy}
    except Exception:
        # 备用方案：使用 android.os.Handler 等基础类
        Handler = jclass("android.os.Handler")
        Looper = jclass("android.os.Looper")
        return {"run": Handler, "click": Handler, "watch": Handler, "check": Handler}