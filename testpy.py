# -*- coding: utf-8 -*-
import os
import sys
import json
import time
import hashlib
import threading
import traceback
import importlib.util
import inspect

from base.spider import Spider as _BaseSpider  # 蜂蜜壳内置基类

# chaquopy java 互操作（设备上可用；本地测试环境自动降级）
try:
    from java import jclass, dynamic_proxy
    from java.lang import Runnable
    _HAS_JAVA = True
except Exception:
    _HAS_JAVA = False

DEFAULT_DIR = "/storage/emulated/0/TV/py"
CMD_RUN = "pycheck::run"   # action 指令标识（参考 pop.py 的 :: 前缀风格）
DEFAULT_TIMEOUT = 15   # 单文件整体超时（含模块加载 + init + homeContent + categoryContent 两次网络请求）
LOG_MAX_LINES = 800

STATUS_ICON = {"ok": "✅", "warn": "⚠️", "fail": "❌"}


def _errstr(e):
    try:
        return "%s: %s" % (type(e).__name__, str(e)[:150])
    except Exception:
        return type(e).__name__


def _short(msg, n=48):
    s = str(msg or "").replace("\n", " ").replace("\r", " ").strip()
    return s if len(s) <= n else s[:n - 1] + "…"


class _NoDialog:
    """无 java 环境时的弹窗替身（打印降级）"""

    def update(self, text):
        pass

    def finish(self, title, text):
        print("──[ %s ]──\n%s" % (title, text))


class _UiDialog:
    """已弹出 AlertDialog 的控制器：update/finish 均经 decorView.post 切 UI 线程"""

    def __init__(self, act, holder):
        self._act = act
        self._holder = holder

    def _post(self, fn):
        try:
            from java import dynamic_proxy
            from java.lang import Runnable

            class Run(dynamic_proxy(Runnable)):
                def run(self):
                    try:
                        fn()
                    except Exception as e:
                        # UI 线程内异常外抛会崩壳，必须拦截
                        print("[py测活] UI更新异常(已拦截):", _errstr(e))

            self._act.getWindow().getDecorView().post(Run())
        except Exception as e:
            print("[py测活] post失败:", _errstr(e))

    def update(self, text):
        tv = self._holder.get("tv")
        if tv is not None:
            self._post(lambda: tv.setText(text))

    def finish(self, title, text):
        self.update(text)


class Spider(_BaseSpider):

    def init(self, extend=""):
        cfg = self._parse_ext(extend)
        self.log_lines = []
        self.results = {}
        self.scan_dir = cfg.get("dir") or DEFAULT_DIR
        self.timeout = max(5.0, float(cfg.get("timeout", DEFAULT_TIMEOUT) or DEFAULT_TIMEOUT))
        self.recursive = bool(cfg.get("recursive", False))
        self._running = False
        self._done = 0
        self._log("初始化完成：目录=%s 超时=%ss 待测=%d 个" % (
            self.scan_dir, int(self.timeout), len(self._list_files())))

    def getName(self):
        return "Py爬虫测活"

    # ------------------------------------------------------------------ 首页：一个分类

    def homeContent(self, filter):
        return {"class": [{"type_id": "test", "type_name": "测活"}], "filters": {}}

    def homeVideoContent(self):
        return {}

    # ------------------------------------------------------------------ 分类：一个 action 条目

    def categoryContent(self, tid, pg, filter, extend):
        """点击条目在 action 字段层被拦截 -> action() -> 弹窗测活日志"""
        try:
            files = self._list_files()
            ok, warn, fail = self._counts()
            if self._running:
                remark = "⏳ 测活进行中… %d/%d" % (self._done, len(files))
            elif self.results:
                remark = "共%d个" % len(files)
            else:
                remark = "目录共 %d 个py，点击开始测活" % len(files)
            return {"page": 1, "pagecount": 1, "limit": 1, "total": 1,
                    "list": [{"vod_id": CMD_RUN, "vod_name": "测活（点击弹窗显示测活日志）",
                              "vod_pic": "", "vod_remarks": remark,
                              "style": {"type": "list", "ratio": 1.1}, "action": CMD_RUN}]}
        except Exception as e:
            print("[py测活] categoryContent错误:", _errstr(e))
            return {"page": 1, "pagecount": 1, "limit": 0, "total": 0, "list": []}

    # ------------------------------------------------------------------ action：测活 + jclass 弹窗

    def action(self, action_str):
        """action 字段拦截入口（壳限时30s，测活放守护线程，立即返回）"""
        try:
            if str(action_str or "") == CMD_RUN:
                return self._start_batch()
            return self._toast("未知 action: %s" % action_str)
        except Exception as e:
            print("[py测活] action错误:", _errstr(e))
            return self._toast("action异常: %s" % _short(_errstr(e), 60))

    def _start_batch(self):
        if self._running:
            return self._toast("测活进行中，进度见弹窗")
        files = self._list_files()
        if not files:
            return self._toast("目录无 py 文件：%s" % self.scan_dir)

        def worker():
            try:
                self._run_batch(files)
            finally:
                self._running = False

        self._running = True
        self._done = 0
        threading.Thread(target=worker, daemon=True, name="pycheck-batch").start()
        return self._toast("测活已开始（%d个爬虫），进度见弹窗" % len(files))

    def _run_batch(self, files):
        total = len(files)
        # 每次新测活重置，避免与上次混在一起
        self.results = {}
        self.log_lines = []
        self._log("── 测活开始：%s（%d 个）──" % (self.scan_dir, total))
        dlg = self._live_dialog("📋 测活日志", "开始测活：%d 个爬虫…\n（目录：%s）" % (total, self.scan_dir))
        for i, f in enumerate(files):
            self._log("(%d/%d) 检测 %s" % (i + 1, total, os.path.basename(f)))
            dlg.update(self._progress_text(i, total))
            self.test_file(f)
            self._done = i + 1
        ok, warn, fail = self._counts()
        self._log("── 测活完成：✅%d ⚠️%d ❌%d ──" % (ok, warn, fail))
        dlg.finish("📋 测活日志（✅%d ⚠️%d ❌%d）" % (ok, warn, fail), self._log_text())

    def _toast(self, msg):
        return json.dumps({"code": 0, "msg": msg}, ensure_ascii=False)

    def _progress_text(self, done, total):
        tail = "\n".join(self.log_lines[-12:])
        return "检测进度：%d/%d（超时%d秒/个）\n\n%s" % (done, total, int(self.timeout), tail)

    # ------------------------------------------------------------------ jclass 原生弹窗（严格照抄 pop.py 可用实现）

    def _live_dialog(self, title, text):
        """jclass 原生 AlertDialog：ActivityThread 反射取 Activity，
        decorView.post 切 UI 线程，dynamic_proxy 子类 super().__init__()。
        布局同 pop.py：LinearLayout -> ScrollView -> TextView。"""
        try:
            from java import jclass, dynamic_proxy
            from java.lang import Runnable

            act = self._activity()
            if not act:
                print("[py测活] 未取到前台Activity，降级打印")
                return _NoDialog()

            # 类在 post 之前解析（pop.py 同款）
            Builder = jclass("android.app.AlertDialog$Builder")
            TextView = jclass("android.widget.TextView")
            ScrollView = jclass("android.widget.ScrollView")
            LinearLayout = jclass("android.widget.LinearLayout")
            LP = jclass("android.widget.LinearLayout$LayoutParams")
            DialogClick = jclass("android.content.DialogInterface$OnClickListener")
            Toast = jclass("android.widget.Toast")
            ClipData = jclass("android.content.ClipData")
            holder = {}

            class Click(dynamic_proxy(DialogClick)):
                def __init__(self, fn):
                    super().__init__()  # pop.py 同款：必须调用
                    self.fn = fn

                def onClick(self, dialog, which):
                    if self.fn:
                        try:
                            self.fn()
                        except Exception as e:
                            print("[py测活] 按钮异常(已拦截):", _errstr(e))

            def do_copy():
                try:
                    cm = act.getSystemService("clipboard")
                    cm.setPrimaryClip(ClipData.newPlainText("pylog", str(holder["tv"].getText())))
                    Toast.makeText(act, "日志已复制到剪贴板", 0).show()
                except Exception as e:
                    print("[py测活] 复制失败:", _errstr(e))

            def on_ui():
                try:
                    root = LinearLayout(act)
                    root.setOrientation(LinearLayout.VERTICAL)
                    root.setPadding(36, 20, 36, 20)
                    sv = ScrollView(act)
                    tv = TextView(act)
                    tv.setText(text)
                    tv.setTextSize(13.0)
                    sv.addView(tv)
                    root.addView(sv, LP(-1, -2))
                    Builder(act).setTitle(title).setView(root) \
                        .setNeutralButton("复制全文", Click(do_copy)) \
                        .setNegativeButton("关闭", None).show()
                    holder["tv"] = tv
                except Exception as e:
                    print("[py测活] 弹窗构建失败(已拦截):", _errstr(e))
                    traceback.print_exc()

            class Run(dynamic_proxy(Runnable)):
                def run(self):
                    on_ui()  # on_ui 内部已全量 try 包裹

            act.getWindow().getDecorView().post(Run())
            return _UiDialog(act, holder)
        except Exception as e:
            print("[py测活] 弹窗失败:", _errstr(e))
            return _NoDialog()

    def _activity(self):
        """ActivityThread 反射取当前前台 Activity（pop.py 原样）"""
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
            print("[py测活] 取Activity失败:", _errstr(e))
        return None

    # ------------------------------------------------------------------ 搜索 / 详情（不拦截的条目默认走这里）

    def searchContent(self, key, quick, pg="1"):
        try:
            files = [f for f in self._list_files() if key in os.path.basename(f)]
            items = [self._to_item(f, self.results.get(f, {})) for f in files]
            return {"list": items, "limit": len(items), "total": len(items)}
        except Exception as e:
            print("[py测活] searchContent错误:", _errstr(e))
            return {"list": []}

    def detailContent(self, ids):
        """非 action 条目默认走这里：重新测活并显示完整报告（阶段/错误/堆栈）"""
        try:
            f = str(ids[0])
            r = self.test_file(f)
            d = r.get("detail", {})
            lines = [
                "文件：%s" % f,
                "状态：%s %s" % (STATUS_ICON.get(r.get("status"), "🐍"), self._status_text(r)),
                "检测阶段：%s" % d.get("stage", "-"),
                "耗时：%s 秒" % r.get("cost", "-"),
                "分类tid：%s" % d.get("tid", "-"),
                "返回数量：%s" % d.get("cnt", "-"),
                "分类名：%s" % _short(d.get("home_class", ""), 60),
                "结果：%s" % _short(r.get("msg", ""), 100),
                "时间：%s" % r.get("time", "-"),
                "",
                "—— 错误堆栈（如有）——",
                d.get("trace", "无") or "无",
            ]
            vod = {
                "vod_id": f,
                "vod_name": os.path.basename(f),
                "vod_pic": "",
                "vod_remarks": _short(r.get("msg", ""), 60),
                "vod_content": "\n".join(lines),
            }
            return {"list": [vod]}
        except Exception as e:
            print("[py测活] detailContent错误:", _errstr(e))
            return {"list": []}

    def playerContent(self, flag, id, vipFlags):
        return {}

    def liveContent(self, url):
        return ""

    # ------------------------------------------------------------------ 日志

    def _log(self, line):
        stamp = time.strftime("%H:%M:%S")
        self.log_lines.append("[%s] %s" % (stamp, line))
        if len(self.log_lines) > LOG_MAX_LINES:
            del self.log_lines[:len(self.log_lines) - LOG_MAX_LINES]
        print("[py测活] %s" % line)

    def _log_text(self):
        if not self.log_lines:
            return "暂无日志。请点击「测活」条目开始测活。"
        head = "目录：%s ｜ 超时：%ds ｜ 时间：%s\n%s\n" % (
            self.scan_dir, int(self.timeout), time.strftime("%Y-%m-%d %H:%M:%S"), "─" * 46)
        return head + "\n".join(self.log_lines)

    def _counts(self):
        ok = sum(1 for v in self.results.values() if v.get("status") == "ok")
        warn = sum(1 for v in self.results.values() if v.get("status") == "warn")
        fail = sum(1 for v in self.results.values() if v.get("status") == "fail")
        return ok, warn, fail

    # ------------------------------------------------------------------ 核心测活

    def test_file(self, path):
        """整体在子线程中执行，超时自动放弃（线程守护，不影响后续检测）。
        优化：如果首次 categoryContent 返回空/失败，尝试重试一次以过滤偶发网络问题。"""
        t0 = time.time()
        box = {}
        done = threading.Event()

        def worker():
            try:
                self._pipeline(path, box)
            except BaseException as e:
                box["error"] = _errstr(e)
                box["trace"] = traceback.format_exc()[-1500:]
            finally:
                done.set()

        th = threading.Thread(target=worker, name="pytest-" + os.path.basename(path)[:16], daemon=True)
        th.start()
        th.join(self.timeout)
        cost = round(time.time() - t0, 1)

        if not done.is_set():
            # 超时：线程无法强杀，主动摘除其模块注册，避免污染后续检测
            modkey = "pyscan_" + hashlib.md5(path.encode("utf-8")).hexdigest()[:10]
            sys.modules.pop(modkey, None)
            res = {"status": "fail", "cost": cost, "path": path,
                   "msg": "超时(>%ss)，可能网络阻塞或死循环" % self.timeout,
                   "detail": {"stage": "整体", "trace": "检测超时，无法获取堆栈"}}
        else:
            stage = box.get("stage", "未知")
            if "error" in box:
                res = {"status": "fail", "cost": cost, "path": path,
                       "msg": "[%s] %s" % (stage, box["error"]),
                       "detail": {"stage": stage, "trace": box.get("trace", "")}}
            elif box.get("warn"):
                res = {"status": "warn", "cost": cost, "path": path,
                       "msg": box.get("msg", "结构正常但无数据"), "detail": box}
            else:
                res = {"status": "ok", "cost": cost, "path": path,
                       "msg": box.get("msg", ""), "detail": box}
        res["time"] = time.strftime("%Y-%m-%d %H:%M:%S")
        self.results[path] = res
        self._log("%s %s -> %s %s" % (STATUS_ICON.get(res["status"], "·"), os.path.basename(path),
                                      res["status"], res["msg"]))
        return res

    def _pipeline(self, path, out):
        """两阶段检测，等价于壳加载 + 用户点击流程：
        - 结构阶段：import → 找Spider类 → 实例化 → init。失败 = 真 fail（代码坏）。
        - 功能阶段：homeContent → categoryContent（含网络请求）。失败/异常 = warn（环境抖动），
                    因为壳内同一网络也可能会遇到。
        """
        out["stage"] = "加载"
        out["tid"] = "-"
        out["cnt"] = "-"
        modkey = "pyscan_" + hashlib.md5(path.encode("utf-8")).hexdigest()[:10]
        saved_path = list(sys.path)
        sp = None
        if not os.path.isfile(path):
            raise RuntimeError("文件不存在: %s" % path)

        # ── 结构阶段（等价壳加载）────────────────────────────────
        try:
            spec = importlib.util.spec_from_file_location(modkey, path)
            if spec is None or spec.loader is None:
                raise RuntimeError("无法创建模块加载器（可能是语法错误）")
            mod = importlib.util.module_from_spec(spec)
            sys.modules[modkey] = mod
            spec.loader.exec_module(mod)         # 顶层代码抛异常 = 代码结构性损坏 → fail

            cls = self._find_spider_class(mod)
            if cls is None:
                raise RuntimeError("未找到爬虫类（需定义 Spider 类，且含 categoryContent）")
            sp = cls()
            try:
                sp.siteKey = modkey
            except Exception:
                pass

            extend = self._file_extend(path)
            self._call(sp.init, extend)          # init 通常无网络
        except Exception:
            out["stage"] = "结构"
            out["trace"] = traceback.format_exc()[-1500:]
            raise                                # 直接上抛 → test_file 判 fail

        # ── 功能阶段（壳内用户点分类后走的流程，含网络）─────────────
        try:
            home = self._call(sp.homeContent, True)
            out["stage"] = "首页"
            tid = self._first_tid(home) if isinstance(home, dict) else None
            out["tid"] = tid
            out["home_class"] = self._first_class_name(home) if isinstance(home, dict) else ""

            result = self._call(sp.categoryContent, tid, "1", False, {})
            if self._is_empty_result(result):
                result = self._call(sp.categoryContent, tid, "1", False, {})

            ok, warn, msg, cnt = self._check_result(result)
            out["stage"] = "分类"
            out["cnt"] = cnt
            if ok and not warn:
                out["msg"] = msg
            else:
                out["warn"] = True
                out["msg"] = msg or "分类数据异常"
        except Exception as e:
            out["warn"] = True
            out["msg"] = "功能阶段异常(%s)，不推翻结构 ok" % _errstr(e)
            out["trace"] = traceback.format_exc()[-800:]
        finally:
            try:
                if sp is not None and hasattr(sp, "destroy"):
                    self._call(sp.destroy)
            except Exception:
                pass
            sys.modules.pop(modkey, None)
            try:
                if list(sys.path) != saved_path:
                    sys.path[:] = saved_path
            except Exception:
                pass

    def _is_empty_result(self, result):
        """判断 categoryContent 返回是否完全无数据（用于决定是否重试）"""
        if result is None:
            return True
        if not isinstance(result, dict):
            return True
        lst = result.get("list")
        if lst is None or (isinstance(lst, list) and len(lst) == 0):
            # list 为空但 pagecount/total 有值不算"完全空"
            pc = result.get("pagecount")
            total = result.get("total")
            if pc and int(pc) > 1:
                return False
            if total and int(total) > 0:
                return False
            return True
        return False

    def _looks_like_network_error(self, msg):
        """判断错误消息是否更像网络/环境异常而非代码结构性损坏"""
        keywords = ["网络", "timeout", "timed out", "connection", "dns", "resolve",
                    "refused", "reset", "unreachable", "urlopen", "requests",
                    "HTTPError", "ConnectionError", "ConnectTimeout", "SocketTimeout"]
        ml = msg.lower()
        return any(kw.lower() in ml for kw in keywords)

    # ------------------------------------------------------------------ 工具

    def _find_spider_class(self, mod):
        """优先名为 Spider 且继承 base.spider.Spider 的类；其次任意继承类；再次鸭子类型。
        优化：支持基类名称不严格等于 _BaseSpider（某些壳版本 base.spider.Spider 导入后仍可通过 MRO 识别）。"""
        subclasses = []
        ducks = []
        try:
            items = list(vars(mod).items())
        except Exception:
            items = []
        for name, obj in items:
            if not isinstance(obj, type) or obj is _BaseSpider or name.startswith("_"):
                continue
            try:
                if issubclass(obj, _BaseSpider):
                    subclasses.append((name, obj))
                elif callable(getattr(obj, "init", None)) and callable(getattr(obj, "categoryContent", None)):
                    ducks.append((name, obj))
            except Exception:
                continue
        # 1) 基类子类，name=Spider 优先
        for name, cls in subclasses:
            if name == "Spider":
                return cls
        # 2) 基类子类，categoryContent 在自己类里定义的优先（覆盖进去的更可能是业务逻辑）
        if subclasses:
            for name, cls in subclasses:
                if "categoryContent" in cls.__dict__:
                    return cls
            return subclasses[0][1]
        # 3) 鸭子类型
        for name, cls in ducks:
            if name == "Spider":
                return cls
        return ducks[0][1] if ducks else None

    def _call(self, fn, *args):
        """按函数签名裁剪参数后调用（兼容 init() 无参等变体）。
        优化：对 *args/**kwargs 函数不再过度截断。"""
        try:
            sig = inspect.signature(fn)
        except (TypeError, ValueError):
            return fn(*args)
        params = list(sig.parameters.values())
        # 过滤掉 self（绑定方法 self 已绑定，不会出现在 sig 中；但类方法可能还在）
        pos_count = 0
        has_var = False
        has_kw = False
        for p in params:
            if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD):
                pos_count += 1
            elif p.kind == p.VAR_POSITIONAL:
                has_var = True
            elif p.kind == p.VAR_KEYWORD:
                has_kw = True
        if not has_var and not has_kw and pos_count < len(args):
            args = args[:pos_count]
        return fn(*args)

    def _first_tid(self, home):
        """取首页第一个有效 type_id；无分类时返回 None（而非硬编码 "1"）"""
        try:
            if isinstance(home, dict):
                cls = home.get("class")
                if isinstance(cls, list):
                    for item in cls:
                        if isinstance(item, dict):
                            tid = item.get("type_id")
                            if tid is not None and str(tid).strip() != "":
                                return str(tid)
        except Exception:
            pass
        return None

    def _first_class_name(self, home):
        try:
            if isinstance(home, dict):
                cls = home.get("class")
                if isinstance(cls, list) and cls and isinstance(cls[0], dict):
                    return str(cls[0].get("type_name", ""))
        except Exception:
            pass
        return ""

    def _check_result(self, result):
        """校验 categoryContent 返回：(ok, warn, 消息, 条数)
        简化判定：能返回多个条目即视为成功，不逐条校验字段格式。"""
        if not isinstance(result, dict):
            return False, False, "返回类型异常: %s（应为dict）" % type(result).__name__, 0
        lst = result.get("list", None)
        if lst is None:
            return False, False, "返回缺少 list 字段", 0
        if not isinstance(lst, list):
            return False, False, "list 类型异常: %s" % type(lst).__name__, 0
        cnt = len(lst)
        if cnt == 0:
            return False, True, "list 为空", 0
        try:
            pc = result.get("pagecount", 1)
        except Exception:
            pc = 1
        try:
            total = result.get("total", "")
        except Exception:
            total = ""
        suffix = "%d条 · 共%s页" % (cnt, pc) if not total else "%d条 · 共%s页 · total=%s" % (cnt, pc, total)
        return True, False, suffix, cnt

    def _status_text(self, r):
        return {"ok": "有效", "warn": "可疑(无数据)", "fail": "无效"}.get(r.get("status"), "未知")

    def _to_item(self, f, r):
        icon = STATUS_ICON.get(r.get("status"), "🐍")
        return {"vod_id": f, "vod_name": icon + " " + os.path.basename(f), "vod_pic": "",
                "vod_remarks": _short(r.get("msg", "未测"), 40),
                "style": {"type": "list"}}

    def _parse_ext(self, extend):
        extend = (extend or "").strip()
        if not extend:
            return {}
        if extend.startswith("{"):
            try:
                v = json.loads(extend)
                return v if isinstance(v, dict) else {}
            except Exception:
                return {}
        # 纯路径
        return {"dir": extend}

    def _file_extend(self, path):
        """被测爬虫 init 需要的 extend：同名 json 或 ext_config.json"""
        try:
            p = path[:-3] + ".json"
            if os.path.isfile(p):
                with open(p, "r", encoding="utf-8") as f:
                    return f.read()
            cfg = os.path.join(os.path.dirname(path), "ext_config.json")
            if os.path.isfile(cfg):
                with open(cfg, "r", encoding="utf-8") as f:
                    data = json.load(f)
                v = data.get(os.path.basename(path))
                if v is None:
                    return ""
                if isinstance(v, dict):
                    return json.dumps(v, ensure_ascii=False)
                return str(v)
        except Exception:
            pass
        return ""

    def _list_files(self):
        out = []
        d = self.scan_dir
        try:
            if self.recursive:
                for root, dirs, names in os.walk(d):
                    dirs[:] = [x for x in dirs if not x.startswith(".") and x != "__pycache__"]
                    for n in sorted(names):
                        if n.endswith(".py") and not n.startswith(("_", ".")):
                            out.append(os.path.join(root, n))
            else:
                for n in sorted(os.listdir(d)):
                    p = os.path.join(d, n)
                    if os.path.isfile(p) and n.endswith(".py") and not n.startswith(("_", ".")):
                        out.append(p)
        except Exception as e:
            print("[py测活] 扫描目录失败:", _errstr(e))
        try:
            me = os.path.abspath(__file__)
            out = [p for p in out if os.path.abspath(p) != me]  # 排除自身
        except Exception:
            pass
        return out

    def destroy(self):
        pass
