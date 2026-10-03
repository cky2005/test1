# -*- coding: utf-8 -*-
"""
缓存 py 管理工具 v2（蜂蜜壳 / FongMi TV + Chaquopy）
================================================
用途：操作壳内部缓存目录 cache/py（壳把站点 py 另存到这里的副本）。
功能：一个分类「缓存py管理」→ 一个条目 → 点击 action 拦截 →
     jclass 原生弹窗显示缓存目录内 py 文件列表（多选），按钮：
     [全选/取消全选] [反选] | [复制] [删除] [返回]
     · 复制：选中的 py 复制到 /sdcard/123/hd/
     · 删除：删除选中的文件（二次确认，删除后复核）
     · 返回：关闭弹窗
注意：cache/py 是壳的下载缓存，删除的副本在对应站点再次使用时
     会被壳重新下载（这是壳的机制）；要彻底移除请改接口 json。

删除完成：关闭所有弹窗 → toast 提示成功/失败数 → act.recreate()
静默刷新（分类自动重绘）；异常仅记 logcat，不写日志文件。

站点配置示例：
  {"key": "cacheop", "name": "缓存py管理", "type": 3,
   "api": "http://127.0.0.1:9978/file/TV/cacheop.py",
   "ext": "",
   "searchable": 2, "quickSearch": 0, "changeable": 0}
ext 可选：覆盖复制目标目录（默认 /sdcard/123/hd）。
"""
import os
import re
import shutil
import json
import time
import traceback

from base.spider import Spider as _BaseSpider

CMD_OPEN = "cacheop::open"
TARGET_DIR = "/sdcard/123/hd"   # 复制目标目录


def _errstr(e):
    try:
        return "%s: %s" % (type(e).__name__, str(e)[:120])
    except Exception:
        return type(e).__name__


def _short(msg, n=48):
    s = str(msg or "").replace("\n", " ").strip()
    return s if len(s) <= n else s[:n - 1] + "…"


class Spider(_BaseSpider):

    def init(self, extend=""):
        s = (extend or "").strip()
        self.target_dir = TARGET_DIR
        if s.startswith("{"):
            try:
                cfg = json.loads(s)
                if isinstance(cfg, dict) and cfg.get("dir"):
                    self.target_dir = str(cfg["dir"]).rstrip("/")
            except Exception:
                pass
        elif s:
            self.target_dir = s.rstrip("/")

    def getName(self):
        return "缓存py管理"

    def homeContent(self, filter):
        return {"class": [{"type_id": "op", "type_name": "缓存py管理"}], "filters": {}}

    def homeVideoContent(self):
        return {}

    def categoryContent(self, tid, pg, filter, extend):
        try:
            n = len(self._list_cache_files())
            remark = "缓存 %d 个py，点击管理（复制/删除）" % n
        except Exception:
            remark = "点击管理"
        return {"page": 1, "pagecount": 1, "limit": 1, "total": 1,
                "list": [{"vod_id": CMD_OPEN, "vod_name": "打开缓存py管理", "vod_pic": "",
                          "vod_remarks": remark, "style": {"type": "list", "ratio": 1.1},
                          "action": CMD_OPEN}]}

    def action(self, action_str):
        try:
            if str(action_str or "") == CMD_OPEN:
                return self._open_manager()
            return json.dumps({"code": 0, "msg": "未知 action: %s" % action_str}, ensure_ascii=False)
        except Exception as e:
            self._dbg("action异常 " + traceback.format_exc()[-600:])
            return json.dumps({"code": 0, "msg": "action异常: %s" % _short(_errstr(e))}, ensure_ascii=False)

    # ------------------------------------------------------------------ 文件操作

    def _cache_dir(self):
        f = globals().get("__file__", "")
        return os.path.dirname(f) if f else ""

    def _list_cache_files(self):
        """cache/py 下的 .py 文件（忽略子目录/__pycache__），按名称排序"""
        d = self._cache_dir()
        out = []
        try:
            for n in sorted(os.listdir(d)):
                p = os.path.join(d, n)
                if os.path.isfile(p) and n.endswith(".py"):
                    try:
                        size = os.path.getsize(p)
                    except Exception:
                        size = 0
                    out.append({"path": p, "name": n, "size": size})
        except Exception as e:
            self._dbg("列目录失败 %s: %s" % (d, _errstr(e)))
        return out

    def _do_copy(self, paths):
        ok, fail = 0, []
        try:
            os.makedirs(self.target_dir, exist_ok=True)
        except Exception as e:
            return 0, ["创建目录失败 %s: %s" % (self.target_dir, _errstr(e))]
        for p in paths:
            try:
                shutil.copy2(p, os.path.join(self.target_dir, os.path.basename(p)))
                ok += 1
            except Exception as e:
                fail.append("%s: %s" % (os.path.basename(p), _short(_errstr(e), 40)))
        self._dbg("复制 完成%d 失败%d" % (ok, len(fail)))
        return ok, fail

    def _do_delete(self, paths):
        """删除选中文件。逐个删除并复核 exists；失败尝试 chmod / rm 兜底。"""
        ok, fail = 0, []
        for p in paths:
            name = os.path.basename(p)
            try:
                if not os.path.isfile(p):
                    fail.append(name + ": 文件不存在")
                    self._dbg("SKIP  %s 文件不存在" % name)
                    continue
                err = None
                try:
                    os.remove(p)
                except Exception as e1:
                    err = e1
                    try:
                        os.chmod(p, 0o644)
                        os.remove(p)
                        err = None
                    except Exception as e2:
                        err = e2
                if err is not None:
                    try:
                        rc = os.system("rm -f '%s'" % p.replace("'", "'\\''"))
                        if rc == 0 and not os.path.exists(p):
                            err = None
                    except Exception as e3:
                        err = e3
                if err is not None:
                    fail.append("%s: %s" % (name, _short(_errstr(err), 50)))
                    self._dbg("FAIL  %s %s\n%s" % (name, _errstr(err), traceback.format_exc()[-500:]))
                    continue
                if os.path.exists(p):
                    fail.append(name + ": remove未报错但文件仍存在")
                    self._dbg("FAIL  %s remove未报错但文件仍存在" % name)
                else:
                    ok += 1
                    self._dbg("OK    %s" % name)
            except Exception as e:
                fail.append("%s: %s" % (name, _errstr(e)))
                self._dbg("FAIL  %s %s" % (name, _errstr(e)))
        self._dbg("删除 完成%d 失败%d" % (ok, len(fail)))
        return ok, fail

    # ------------------------------------------------------------------ 诊断日志（异常不静默）

    def _dbg(self, msg):
        """仅 logcat（不落盘，用户要求去掉日志文件）"""
        print("[缓存py] " + time.strftime("[%H:%M:%S] ") + msg)

    # ------------------------------------------------------------------ action：jclass 弹窗（pop.py 同款骨架）

    def _open_manager(self):
        files = self._list_cache_files()
        if not files:
            return json.dumps({"code": 0, "msg": "缓存目录无 py 文件: %s" % _short(self._cache_dir(), 40)},
                              ensure_ascii=False)
        try:
            from java import jclass, dynamic_proxy
            from java.lang import Runnable

            act = self._activity()
            if not act:
                self._dbg("无前台Activity，无法弹窗（共%d个文件）" % len(files))
                return json.dumps({"code": 0, "msg": "无前台Activity，无法弹窗（详见日志）"}, ensure_ascii=False)

            Builder = jclass("android.app.AlertDialog$Builder")
            LinearLayout = jclass("android.widget.LinearLayout")
            LPL = jclass("android.widget.LinearLayout$LayoutParams")
            Button = jclass("android.widget.Button")
            TextView = jclass("android.widget.TextView")
            ListView = jclass("android.widget.ListView")
            ArrayAdapter = jclass("android.widget.ArrayAdapter")
            Rlayout = jclass("android.R$layout")
            Toast = jclass("android.widget.Toast")
            ViewClick = jclass("android.view.View$OnClickListener")
            DlgClick = jclass("android.content.DialogInterface$OnClickListener")
            MATCH = -1
            WRAP = -2

            labels = ["%s  (%.1fKB)" % (f["name"], f["size"] / 1024.0) for f in files]
            paths = [f["path"] for f in files]

            def warn(e):
                """UI 回调异常：写日志 + toast 可见化（绝不静默）"""
                self._dbg("UI异常 " + traceback.format_exc()[-800:])
                try:
                    Toast.makeText(act, "操作异常: %s（详见日志）" % _short(_errstr(e), 30), 1).show()
                except Exception:
                    pass

            class Click(dynamic_proxy(ViewClick)):
                """控件按钮专用（android.view.View$OnClickListener）"""
                def __init__(self, fn):
                    super().__init__()
                    self.fn = fn

                def onClick(self, v):
                    try:
                        self.fn()
                    except Exception as e:
                        warn(e)

            class DlgBtn(dynamic_proxy(DlgClick)):
                """AlertDialog 按钮专用（android.content.DialogInterface$OnClickListener）
                注意：setPositiveButton/NegativeButton/NeutralButton 只认这个接口，
                传 View 版代理会 TypeError（v1 删除无效的根因）"""
                def __init__(self, fn):
                    super().__init__()
                    self.fn = fn

                def onClick(self, dialog, which):
                    try:
                        self.fn()
                    except Exception as e:
                        warn(e)

            class Run(dynamic_proxy(Runnable)):
                def run(self):
                    try:
                        on_ui()
                    except Exception as e:
                        warn(e)

            def on_ui():
                root = LinearLayout(act)
                root.setOrientation(LinearLayout.VERTICAL)
                root.setPadding(24, 16, 24, 8)

                tip = TextView(act)
                tip.setText("目录：%s\n共 %d 个py → 复制目标：%s" %
                            (_short(self._cache_dir(), 40), len(files), self.target_dir))
                tip.setTextSize(11.0)
                root.addView(tip, LPL(MATCH, WRAP))          # 无 weight，避免撑出空白

                row1 = LinearLayout(act)
                row1.setOrientation(LinearLayout.HORIZONTAL)
                b_all = Button(act)
                b_all.setText("全选")
                b_all.setTextSize(11.0)
                row1.addView(b_all, LPL(0, WRAP, 1.0))
                b_inv = Button(act)
                b_inv.setText("反选")
                b_inv.setTextSize(11.0)
                row1.addView(b_inv, LPL(0, WRAP, 1.0))
                root.addView(row1, LPL(MATCH, WRAP))

                row2 = LinearLayout(act)
                row2.setOrientation(LinearLayout.HORIZONTAL)
                b_cp = Button(act)
                b_cp.setText("复制")
                b_cp.setTextSize(11.0)
                row2.addView(b_cp, LPL(0, WRAP, 1.0))
                b_del = Button(act)
                b_del.setText("删除")
                b_del.setTextSize(11.0)
                row2.addView(b_del, LPL(0, WRAP, 1.0))
                b_back = Button(act)
                b_back.setText("返回")
                b_back.setTextSize(11.0)
                row2.addView(b_back, LPL(0, WRAP, 1.0))
                root.addView(row2, LPL(MATCH, WRAP))

                lv = ListView(act)
                lv.setChoiceMode(2)  # CHOICE_MODE_MULTIPLE
                lv.setAdapter(ArrayAdapter(act, Rlayout.simple_list_item_multiple_choice, labels))
                root.addView(lv, LPL(MATCH, 0, 1.0))         # weight 占满剩余高度

                holder = {"dlg": None}

                def checked_indices():
                    sba = lv.getCheckedItemPositions()
                    sel = []
                    for i in range(sba.size()):
                        if sba.valueAt(i):
                            sel.append(sba.keyAt(i))
                    return sel

                def selected_paths():
                    return [paths[i] for i in checked_indices()]

                def refresh():
                    try:
                        lv.getAdapter().notifyDataSetChanged()
                    except Exception:
                        pass

                def toggle_all():
                    sel = checked_indices()
                    target = len(sel) < len(paths)
                    for i in range(len(paths)):
                        lv.setItemChecked(i, target)
                    refresh()

                def invert():
                    sel = set(checked_indices())
                    for i in range(len(paths)):
                        lv.setItemChecked(i, i not in sel)
                    refresh()

                def do_copy():
                    sp = selected_paths()
                    if not sp:
                        Toast.makeText(act, "未勾选任何文件", 0).show()
                        return
                    ok, fail = self._do_copy(sp)
                    if fail:
                        Builder(act).setTitle("复制完成（%d成功 %d失败）" % (ok, len(fail))) \
                            .setMessage("\n".join(fail[:20])).setNegativeButton("关闭", None).show()
                    else:
                        Toast.makeText(act, "已复制 %d 个 → %s" % (ok, self.target_dir), 1).show()

                def do_delete():
                    sp = selected_paths()
                    if not sp:
                        Toast.makeText(act, "未勾选任何文件", 0).show()
                        return
                    names = "\n".join(os.path.basename(x) for x in sp[:15]) + \
                            ("…" if len(sp) > 15 else "")

                    def finish():
                        ok, fail = self._do_delete(sp)
                        # 关闭所有弹窗（管理弹窗；确认框随按钮点击自动关闭）
                        try:
                            dlg = holder.get("dlg")
                            if dlg is not None:
                                dlg.dismiss()
                        except Exception:
                            pass
                        # toast 结果 + recreate 刷新（静默，无其它提示）
                        msg = "已删除 %d 个" % ok + ("，失败 %d 个" % len(fail) if fail else "")
                        Toast.makeText(act, msg, 1).show()
                        try:
                            act.recreate()
                        except Exception as e:
                            warn(e)

                    Builder(act).setTitle("确认删除") \
                        .setMessage("将删除 %d 个缓存py（不可恢复）：\n%s" % (len(sp), names)) \
                        .setNegativeButton("取消", None) \
                        .setPositiveButton("确认删除", DlgBtn(finish)).show()

                b_all.setOnClickListener(Click(toggle_all))
                b_inv.setOnClickListener(Click(invert))
                b_cp.setOnClickListener(Click(do_copy))
                b_del.setOnClickListener(Click(do_delete))
                b_back.setOnClickListener(Click(lambda: dlg_dismiss()))

                def dlg_dismiss():
                    try:
                        holder["dlg"].dismiss()
                    except Exception as e:
                        warn(e)

                d = Builder(act).setTitle("📦 缓存py管理（勾选后操作）").setView(root).create()
                d.show()
                holder["dlg"] = d
                # 限定弹窗高度（屏幕72%），ListView weight 才能正确占满剩余空间
                try:
                    h = act.getResources().getDisplayMetrics().heightPixels
                    d.getWindow().setLayout(MATCH, int(h * 0.72))
                except Exception as e:
                    self._dbg("窗口高度设置失败(忽略): " + _errstr(e))

            act.getWindow().getDecorView().post(Run())
            return json.dumps({"code": 0, "msg": "已打开缓存py管理（%d个）" % len(files)}, ensure_ascii=False)
        except Exception as e:
            self._dbg("弹窗失败 " + traceback.format_exc()[-600:])
            return json.dumps({"code": 0, "msg": "弹窗失败: %s" % _short(_errstr(e))}, ensure_ascii=False)

    # ------------------------------------------------------------------ Activity（pop.py 同款反射）

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
        except Exception as e:
            print("[缓存py] 取Activity失败:", _errstr(e))
        return None

    def searchContent(self, key, quick, pg="1"):
        return {"list": []}

    def detailContent(self, ids):
        return {"list": []}

    def playerContent(self, flag, id, vipFlags):
        return {}

    def liveContent(self, url):
        return ""

    def destroy(self):
        pass
