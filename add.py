# -*- coding: utf-8 -*-
import sys, os, json, time, urllib.request

_extra_paths = ['/storage/emulated/0/本地包', '/sdcard/本地包']
for _p in _extra_paths:
    if _p not in sys.path and os.path.isdir(_p):
        sys.path.insert(0, _p)

from base.spider import Spider
from local_json_plugin import _decode_text
from dialog_plugin import DialogPlugin

CONFIG = {}

# ---------------- file:// 路径支持(模块级工具) ----------------
# Android 主存储根；编辑本地接口时，file:// 简写路径统一修正到这里
_PRIMARY_STORAGE = '/storage/emulated/0'


def _is_file_url(url):
    """是否 file:// 或 file:/// 形式的本地接口"""
    return isinstance(url, str) and url.strip().lower().startswith('file://')


def _fix_local_path(p):
    if not p:
        return p
    p = p.strip()
    for sep in ('?', '#'):  # 去掉误带的 query / fragment
        i = p.find(sep)
        if i >= 0:
            p = p[:i]
    while p.startswith('./'):
        p = p[2:]
    if p.startswith('localhost/'):  # file://localhost/... 形式的主机名
        p = p[len('localhost'):]
    if not p.startswith('/'):
        # 省略了斜杠的简写: file://abc/.. 、file://sdcard/.. 、file://storage/..
        if p == 'sdcard':
            p = _PRIMARY_STORAGE
        elif p.startswith('sdcard/'):
            p = _PRIMARY_STORAGE + '/' + p[7:]
        elif p == 'storage' or p.startswith('storage/'):
            p = '/' + p
        else:
            p = _PRIMARY_STORAGE + '/' + p
    # 绝对路径别名修正: /sdcard、/storage/self/primary -> /storage/emulated/0
    for alias in ('/sdcard', '/storage/self/primary'):
        if p == alias:
            p = _PRIMARY_STORAGE
        elif p.startswith(alias + '/'):
            p = _PRIMARY_STORAGE + '/' + p[len(alias) + 1:]
    # 压缩重复斜杠并消除 ./ ../
    segs = []
    for seg in p.split('/'):
        if not seg or seg == '.':
            continue
        if seg == '..':
            if segs:
                segs.pop()
            continue
        segs.append(seg)
    return '/' + '/'.join(segs) if segs else '/'


def _file_url_local_paths(url):
    if not _is_file_url(url):
        return []
    rest = url.strip()[7:]
    try:
        from urllib.parse import unquote
        variants = (rest, unquote(rest))
    except Exception:
        variants = (rest,)
    out, seen = [], set()

    def _add(p):
        if p and p not in seen:
            seen.add(p)
            out.append(p)

    for base in variants:
        # 1) 修正后的路径(简写拼到主存储、sdcard 等别名转正)
        _add(_fix_local_path(base))
        # 2) 绝对路径字面量(如 /data/... 应用私有路径)及其拼到主存储的兜底
        b = base.strip()
        while b.startswith('./'):
            b = b[2:]
        if b.startswith('/'):
            _add(b)
            if not b.startswith('/storage/emulated/'):
                _add(_PRIMARY_STORAGE + b)
    return out


class Spider(Spider):
    def getName(self):return '站源管理(编辑当前点播接口)'

    def init(self, extend=''):
        if extend:
            try:
                ext = json.loads(extend) if isinstance(extend, str) else extend
                if isinstance(ext, dict):
                    CONFIG.update(ext)
            except Exception:
                pass
        self.dlg = DialogPlugin(self)

        # 自动获取当前接口（OKTV）；若为本地 file:// 且文件存在，直接作为编辑目标
        self.current_url = self.get_current_url()
        self.box_path = self._resolve_box_path()

    # ---------------- home / category ----------------
    def homeContent(self, filter):
        return {'class': [{'type_id': 'site', 'type_name': '站源'}], 'list': []}

    def categoryContent(self, tid, pg, filter, extend):
        if tid == 'site':
            return self._site_category()
        return {'page': 1, 'pagecount': 1, 'limit': 10, 'total': 0, 'list': []}

    def _site_category(self):
        items = [
            self._entry('site::add_api', '1. 添加站源(API)', '录入 name/api/type/key'),
            self._entry('site::add_json', '2. 添加站源(JSON片段)', '粘贴站源json自动合并'),
            self._entry('site::del', '3. 删除站源', '当前接口: ' + self._current_interface_label()),
        ]
        return {'page': 1, 'pagecount': 1, 'limit': len(items), 'total': len(items), 'list': items}

    @staticmethod
    def _entry(action_str, name, remark):
        return {'vod_id': action_str,'vod_name': name,'vod_pic': '','vod_remarks': remark,'style': {'type': 'list'},'action': action_str,}

    # ---------------- action 拦截（必须发生在 categoryContent 产生的 action 字符串层） ----------------
    def action(self, action_str):
        if action_str == 'site::add_api':
            self.dlg.run_on_ui(lambda a: self._add_api_ui(a))
        elif action_str == 'site::add_json':
            self.dlg.run_on_ui(lambda a: self._add_json_ui(a))
        elif action_str == 'site::del':
            self.dlg.run_on_ui(lambda a: self._del_site_ui(a))
        return {}

    # ---------------- 当前接口(box)管理 ----------------
    def _resolve_box_path(self):
        for lp in _file_url_local_paths(self.current_url):
            try:
                if os.path.isfile(lp):
                    return lp
            except Exception:
                pass
        return None

    def _current_interface_label(self):
        """当前接口展示用短标签（优先显示修正后的本地路径，过长截断）"""
        cur = self.box_path or (self.current_url or '').strip()
        if _is_file_url(cur):
            cur = cur.strip()[7:]
        if len(cur) > 60:
            cur = cur[:57] + '...'
        return cur or '未获取'

    def _require_box(self, act):
        """返回可编辑的本地 box 路径；非本地 file:// 接口(或文件不存在)则提示并返回 None。"""
        if self.box_path and os.path.isfile(self.box_path):
            return self.box_path
        self._toast(act, '当前接口不是本地 file:// 接口(或文件不存在)，无法编辑')
        return None

    def _load_box(self, path):
        try:
            return json.loads(_decode_text(path))
        except Exception as e:
            self._toast(None, '读取接口失败: ' + str(e))
            return None

    def _save_box(self, path, data):
        d = os.path.dirname(path) or '.'
        os.makedirs(d, exist_ok=True)
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

    def _apply_box(self, box_path, name):
        """Python 内部重新读取 + 界面重绘（极简版）"""
        def work():
            try:
                self._load_box(box_path)
                self.dlg.run_on_ui(lambda act: act.recreate())
            except Exception as e:
                self._toast(None, f"❌ 重载失败: {e}")
        self.dlg.run_bg(work)

    # ---------------- 添加站源(API) ----------------
    def _add_api_ui(self, act):
        bp = self._require_box(act)
        if not bp:
            return
        self._show_add_api_form(act, bp)

    def _show_add_api_form(self, act, box_path):
        ts = str(int(time.time()))
        fields = [
            {'key': 'name', 'label': 'name (名字)', 'default': ts},
            {'key': 'api', 'label': 'api (文件)', 'default': './py/'},
            {'key': 'type', 'label': 'type', 'default': '3'},
            {'key': 'key', 'label': 'key', 'default': ts},
        ]
        self.dlg.show_form('添加站源(API)', fields,
                           lambda vals: self._on_add_api(vals, box_path))

    def _on_add_api(self, vals, box_path):
        name = (vals.get('name') or '').strip() or str(int(time.time()))
        key = (vals.get('key') or '').strip() or str(int(time.time()))
        try:
            t = int((vals.get('type') or '3').strip() or 3)
        except Exception:
            t = 3
        api = (vals.get('api') or '').strip()
        if api in ('', './py/'):
            api = './py/' + name + '.py'
        site = {'key': key, 'name': name, 'type': t, 'api': api}
        data = self._load_box(box_path)
        if data is None:
            return False  # Don't close dialog
        if not isinstance(data.get('sites'), list):
            data['sites'] = []
        data['sites'].append(site)
        self._save_box(box_path, data)
        self._toast(None, '已添加: ' + name)
        self._apply_box(box_path, '本地[' + name + ']')
        return True  # Close dialog

    # ---------------- 添加站源(JSON片段) ----------------
    def _add_json_ui(self, act):
        bp = self._require_box(act)
        if not bp:
            return
        self._show_add_json_form(act, bp)

    def _show_add_json_form(self, act, box_path):
        self.dlg.show_form('添加站源(JSON片段)',
                           [{'key': 'json',
                             'label': '粘贴站源 JSON (单对象 / 数组 / {"sites":[...]})',
                             'default': '', 'multiline': True}],
                           lambda vals: self._on_add_json(vals, box_path))

    def _on_add_json(self, vals, box_path):
        raw = (vals.get('json') or '').strip()
        if not raw:
            self._toast(None, '内容为空')
            return False  # Don't close dialog
        
        # Filter out invisible characters like nbsp (non-breaking space) and other special whitespace
        # Replace common invisible characters with regular space or remove them
        import re
        # Replace non-breaking space (\u00a0) and other Unicode whitespace with regular space
        raw = re.sub(r'[\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]', ' ', raw)
        # Remove zero-width characters
        raw = re.sub(r'[\u200b-\u200d\u2060]', '', raw)
        # Remove control characters except common whitespace (\n, \t, \r, space)
        raw = re.sub(r'[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]', '', raw)
        
        if not raw.strip():
            self._toast(None, '内容为空（过滤不可见字符后）')
            return False  # Don't close dialog
        
        # Try to parse JSON
        try:
            obj = json.loads(raw)
        except json.JSONDecodeError as e:
            # If error is about extra data, try to strip trailing commas and try again
            if "Extra data" in str(e):
                # Remove trailing commas and whitespace
                trimmed = raw.rstrip()
                # Remove trailing commas
                while trimmed.endswith(','):
                    trimmed = trimmed[:-1].rstrip()
                # Try parsing again
                try:
                    obj = json.loads(trimmed)
                except json.JSONDecodeError:
                    # If still fails, show original error
                    self._toast(None, 'JSON 格式错误: ' + str(e))
                    return False
            else:
                self._toast(None, 'JSON 格式错误: ' + str(e))
                return False
        except Exception as e:
            self._toast(None, 'JSON 解析失败: ' + str(e))
            return False
        
        if isinstance(obj, list):
            sites = obj
        elif isinstance(obj, dict):
            if 'sites' in obj and isinstance(obj['sites'], list):
                sites = obj['sites']
            else:
                sites = [obj]
        else:
            self._toast(None, '无法识别的 JSON 结构')
            return False
        
        sites = [s for s in sites if isinstance(s, dict)]
        if not sites:
            self._toast(None, '未解析到站源对象')
            return False  # Don't close dialog
        
        data = self._load_box(box_path)
        if data is None:
            return False  # Don't close dialog
        
        if not isinstance(data.get('sites'), list):
            data['sites'] = []
        data['sites'].extend(sites)
        self._save_box(box_path, data)
        self._toast(None, '已添加 %d 个站源' % len(sites))
        self._apply_box(box_path, '本地接口')
        return True  # Close dialog on success

    # ---------------- 删除站源 ----------------
    def _del_site_ui(self, act):
        bp = self._require_box(act)
        if not bp:
            return
        self._show_del_dialog(act, bp)

    def _show_del_dialog(self, act, box_path):
        data = self._load_box(box_path)
        if data is None:
            return
        sites = data.get('sites') or []
        if not sites:
            self._toast(act, '当前接口没有站源')
            return
        self.dlg.show_multi_select('删除站源 (单列多选)', sites,
                                   lambda selected: self._on_del_sites(selected, box_path, data),
                                   confirm_text='删除选中', searchable=True,
                                   search_hint='搜索站源…')

    def _on_del_sites(self, selected, box_path, data):
        if not selected:
            self._toast(None, '未选择任何站源')
            return
        sites = data.get('sites') or []
        remaining = [s for s in sites if s not in selected]
        removed = len(sites) - len(remaining)
        data['sites'] = remaining
        self._save_box(box_path, data)
        self._toast(None, '已删除 %d 个站源' % removed)
        self._apply_box(box_path, '本地接口')

    # ---------------- 工具 ----------------
    def get_current_url(self):
        try:
            from java import jclass
            config_class = jclass("com.fongmi.android.tv.bean.Config")
            return str(config_class.vod().getUrl() or "")
        except Exception:
            pass
        try:
            from java import jclass
            proxy = jclass("com.github.catvod.Proxy")
            port = int(proxy.getPort())
            if port <= 0:
                return None
            req = urllib.request.Request(
                f"http://127.0.0.1:{port}/action?do=getConfig&type=vod")
            with urllib.request.urlopen(req, timeout=2) as resp:
                data = json.loads(resp.read().decode('utf-8'))
                return data.get("url")
        except Exception:
            return None

    def _activity(self):
        try:
            from java import jclass
            AT = jclass('java.lang.Class').forName('android.app.ActivityThread')
            cur = AT.getMethod('currentActivityThread').invoke(None)
            f = AT.getDeclaredField('mActivities')
            f.setAccessible(True)
            map_obj = f.get(cur)
            values = map_obj.values().toArray() if hasattr(map_obj, 'values') else map_obj.toArray()
            for r in values:
                rc = r.getClass()
                pf = rc.getDeclaredField('paused')
                pf.setAccessible(True)
                if not pf.getBoolean(r):
                    af = rc.getDeclaredField('activity')
                    af.setAccessible(True)
                    return af.get(r)
        except Exception:
            pass
        return None

    def _toast(self, act, text):
        text = str(text)
        a = act or self._activity()
        if not a:
            print('[Toast] ' + text)
            return

        def _show(act):
            try:
                from java import jclass
                jclass('android.widget.Toast').makeText(act, text, 1).show()
            except Exception as e:
                print('[Toast Err] ' + text + ' | ' + str(e))

        # 走 DialogPlugin 的模块级代理投递，避免闭包代理被 GC 导致的崩溃
        if not self.dlg.run_on_ui(_show):
            print('[Toast] ' + text)

    def detailContent(self, ids):
        return {'list': []}

    def playerContent(self, flag, id, vipFlags):
        return {}