# -*- coding: utf-8 -*-
import sys
import re
import json
import gzip
import base64
import time
import threading
import traceback
import urllib.request
import urllib.parse

sys.path.append('..')
from base.spider import Spider

try:
    from java import jclass, dynamic_proxy
except ImportError:
    jclass = None
    dynamic_proxy = None

_WV_PROXY = {}
_LANGS = ("cn", "ja", "ko", "ms", "th", "de", "fr", "vi", "id", "fil", "pt")
_CHALLENGE = ("just a moment", "attention required", "checking your browser",
              "请稍候", "cf-mitigated", "challenges.cloudflare.com")


def _ensure_wv_proxy():
    if _WV_PROXY:
        return _WV_PROXY
    Runnable = jclass("java.lang.Runnable")
    ValueCallback = jclass("android.webkit.ValueCallback")

    class _Run(dynamic_proxy(Runnable)):
        def run(self):
            fn = getattr(self, "_fn", None)
            if fn:
                try:
                    fn()
                except Exception:
                    print("[missav] UI任务异常:\n" + traceback.format_exc())

    class _Cb(dynamic_proxy(ValueCallback)):
        def onReceiveValue(self, v):
            fn = getattr(self, "_fn", None)
            if fn:
                try:
                    fn(v)
                except Exception:
                    print("[missav] 回调异常:\n" + traceback.format_exc())

    _WV_PROXY["run"] = _Run
    _WV_PROXY["cb"] = _Cb
    return _WV_PROXY


class Spider(Spider):
    host = "https://missav.ws"
    UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
    STREAM_HOST = "https://surrit.com"
    CATS = [
        ("dm539/new", "最近更新"),
        ("dm635/release", "新发行"),
        ("dm817/uncensored-leak", "无码流出"),
        ("dm301/today-hot", "今日热门"),
        ("dm170/weekly-hot", "本周热门"),
        ("dm273/monthly-hot", "本月热门"),
        ("dm23/english-subtitle", "中文字幕"),
        ("dm36/siro", "SIRO"),
        ("dm34/luxu", "LUXU"),
        ("dm34/gana", "GANA"),
        ("dm1004/maan", "PRESTIGE PREMIUM"),
        ("dm38/scute", "S-CUTE"),
        ("dm34/ara", "ARA"),
        ("dm597/fc2", "FC2"),
        ("dm42/tokyohot", "Tokyo Hot"),
        ("dm150/gachinco", "Gachinco"),
        ("dm42/xxxav", "XXX-AV"),
        ("dm37/marriedslash", "Married Slash"),
        ("dm33/naughty4610", "Naughty 4610"),
        ("dm37/naughty0930", "Naughty 0930"),
        ("dm63/madou", "Madou"),
        ("dm31/twav", "TWAV"),
        ("dm15/furuke", "Furuke"),
        ("dm2208642/heyzo", "HEYZO"),
        ("dm5199603/1pondo", "1pondo"),
        ("dm7704788/caribbeancom", "Caribbeancom"),
        ("dm91887/caribbeancompr", "Caribbeancom PR"),
        ("dm7208981/10musume", "10musume"),
        ("dm3600557/pacopacomama", "pacopacomama"),
    ]
    SORTS = [("", "最新"), ("released_at", "发行时间"), ("published_at", "加入时间"),
             ("saved", "收藏数"), ("today_views", "今日观看"),
             ("weekly_views", "本周观看"), ("monthly_views", "本月观看"),
             ("views", "总观看")]
    _NAV = {"vip", "klive", "clive", "saved", "playlists", "history", "new", "release",
            "uncensored-leak", "english-subtitle", "today-hot", "weekly-hot", "monthly-hot",
            "actresses", "genres", "makers", "search", "dm265", "categories", "tags",
            "popular", "rankings", "logout", "login", "signup", "settings", "about"}

    def init(self, extend=""):
        self._wv = None
        self._wv_lay = None
        self._wv_keep = []
        self._uuid_cache = {}
        self._st_cache = {}
        self._lastpg_cache = {}

    def getName(self):
        return "MISSAV"

    def getHeader(self):
        return {"User-Agent": self.UA, "Referer": self.host + "/"}

    def homeContent(self, filter):
        cls = [{"type_id": t, "type_name": n} for t, n in self.CATS]
        fl = {t: [{"key": "sort", "name": "排序",
                   "value": [{"n": n, "v": v} for v, n in self.SORTS]}]
              for t, n in self.CATS}
        return {"class": cls, "filters": fl, "list": []}

    def categoryContent(self, tid, pg, filter, extend):
        if isinstance(extend, str):
            try:
                extend = json.loads(extend)
            except Exception:
                extend = {}
        sort = extend.get("sort", "") if isinstance(extend, dict) else ""
        try:
            pg = int(pg)
        except Exception:
            pg = 1
        url = "%s/%s/en/%s?page=%d" % (self.host, tid.split("/")[0],
                                       tid.split("/")[1] if "/" in tid else "release", pg)
        if sort:
            url += "&sort=" + sort
        html = self._page(url)
        vods = self._vods(html)
        return {"list": vods, "page": pg, "pagecount": self._pagecount(html, pg, url),
                "limit": 12, "total": 0}

    def searchContent(self, key, pg, filter):
        try:
            pg = int(pg)
        except Exception:
            pg = 1
        kw = urllib.parse.quote((key or "").strip())
        url = "%s/en/search/%s" % (self.host, kw)
        if pg > 1:
            url += "?page=%d" % pg
        html = self._page(url)
        vods = self._vods(html)
        return {"list": vods, "page": pg, "pagecount": self._pagecount(html, pg, url),
                "limit": 12, "total": 0}

    def detailContent(self, ids):
        vid = ids[0] if isinstance(ids, list) and ids else (ids or "")
        if not vid:
            return {"list": []}
        if not vid.startswith("http"):
            vid = "%s/en/%s" % (self.host, vid)
        html = self._page(vid)
        if not html:
            return {"list": []}
        name = self._detail_name(html, vid)
        pic = ""
        m = re.search(r'data-poster="(https?://[^"]+)"', html)
        if m:
            pic = m.group(1)
        if not pic:
            m = re.search(r'https://fourhoi\.com/[a-z0-9\-]+/cover-[tn]\.jpg', html)
            pic = m.group(0) if m else ""
        lines = self._streams(html, vid)
        froms = list(lines.keys())
        urls = ["%s$%s|%s" % (q, vid, q) for q in froms]
        return {"list": [{"vod_id": vid, "vod_name": name, "vod_pic": pic,
                           "vod_play_from": "$$$".join(froms),
                           "vod_play_url": "$$$".join(urls)}]}

    def playerContent(self, flag, id, vipFlags):
        parts = id.split("|")
        page = parts[0]
        q = parts[1] if len(parts) > 1 else (flag or "")
        if not page.startswith("http"):
            page = "%s/en/%s" % (self.host, page)
        sources = getattr(self, "_st_cache", {}).get(page)
        if sources is None:
            html = self._page(page)
            sources = self._streams(html, page)
        if not sources:
            return {"parse": 0, "jx": 0, "playUrl": "", "url": "",
                    "header": self.getHeader(), "format": "application/x-mpegURL"}
        url = sources.get(q) or list(sources.values())[0]
        proxy = "%s&type=media&url=%s" % (self.getProxyUrl(), urllib.parse.quote(url, safe=""))
        return {"parse": 0, "jx": 0, "playUrl": "", "url": proxy,
                "header": self.getHeader(), "format": "application/x-mpegURL"}

    def localProxy(self, param):
        url = param.get("url", "") if isinstance(param, dict) else ""
        if not url and isinstance(param, str):
            url = param
        if url and not url.startswith(("http://", "https://")):
            try:
                url = base64.b64decode(url + "=" * (-len(url) % 4)).decode("utf-8", "ignore")
            except Exception:
                url = ""
        if not url or not url.startswith(("http://", "https://")):
            return [404, "text/plain", b"not found", {}]
        try:
            data = self._fetch_bytes(url)
            if not data:
                return [404, "text/plain", b"not found", {}]
            if ".m3u8" in url.lower():
                text = data.decode("utf-8", "ignore")
                text = self._proxy_playlist(text, url)
                ct = "application/vnd.apple.mpegurl"
                return [200, ct, text.encode("utf-8"),
                        {"Content-Type": ct, "Access-Control-Allow-Origin": "*"}]
            return [200, "video/mp2t", data,
                    {"Content-Type": "video/mp2t", "Access-Control-Allow-Origin": "*"}]
        except Exception as e:
            print("[missav] localProxy失败(%s): %s" % (url, e))
            return [404, "text/plain", b"not found", {}]

    def _proxy_playlist(self, text, base):
        out = []
        for ln in text.split("\n"):
            s = ln.strip()
            if not s or s.startswith("#"):
                out.append(ln)
                continue
            if s.startswith("http://") or s.startswith("https://"):
                full = s
            else:
                p = base.rsplit("/", 1)[0] + "/" + s
                full = self._abs_url(p)
            out.append("%s&type=media&url=%s" % (self.getProxyUrl(), urllib.parse.quote(full, safe="")))
        return "\n".join(out)

    def _abs_url(self, url):
        if url.startswith("http://") or url.startswith("https://"):
            return url
        if url.startswith("//"):
            return "https:" + url
        return self.host + url

    def _fetch_bytes(self, url, referer=None):
        h = {"User-Agent": self.UA, "Referer": referer or (self.host + "/")}
        req = urllib.request.Request(url, headers=h)
        resp = urllib.request.urlopen(req, timeout=25)
        raw = resp.read()
        if "gzip" in (resp.headers.get("Content-Encoding") or "").lower():
            raw = gzip.decompress(raw)
        return raw

    def _unpack(self, html):
        out = {}
        pos = 0
        while True:
            i = html.find("eval(function(p,a,c,k,e,d)", pos)
            if i < 0:
                break
            pos = i + 1
            j = self._match_paren(html, i + 4)
            if j < 0:
                continue
            code = html[i:j + 1]
            res = self._unpack_packer(code)
            if not res:
                continue
            for m in re.finditer(r"(\w+)\s*=\s*'([^']*)'", res):
                out[m.group(1)] = m.group(2)
        return out

    def _unpack_packer(self, code):
        m = re.search(r"\}\s*\(\s*'(.*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'(.*?)'\.split\('\|'\)",
                      code, re.S)
        if not m:
            return ""
        payload, radix, count, kdict = m.group(1), int(m.group(2)), int(m.group(3)), m.group(4)
        words = kdict.split("|")
        base = {}
        for idx, word in enumerate(words):
            if idx < count:
                base[idx] = word
        for idx in range(count, len(words)):
            base[idx] = words[idx] if words[idx] else str(idx)
        try:
            head = payload.replace("\\'", "'").replace("\\\\", "\\")
        except Exception:
            head = payload
        out = head
        for idx in range(count - 1, -1, -1):
            word = base.get(idx)
            if not word:
                continue
            try:
                key = self._b36(idx, radix)
            except Exception:
                continue
            if not key:
                continue
            out = re.sub(r"\b" + re.escape(key) + r"\b", lambda m: word.replace("\\", "\\\\"), out)
        return out

    def _b36(self, n, radix):
        digits = "0123456789abcdefghijklmnopqrstuvwxyz"
        if n == 0:
            return "0"
        res = ""
        while n > 0:
            res = digits[n % radix] + res
            n //= radix
        return res

    def _match_paren(self, s, start):
        depth = 0
        i = start
        quote = ""
        while i < len(s):
            c = s[i]
            if quote:
                if c == "\\":
                    i += 2
                    continue
                if c == quote:
                    quote = ""
            elif c in "'\"":
                quote = c
            elif c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    return i
            i += 1
        return -1

    def _streams(self, html, page):
        if not html:
            return {}
        cache = getattr(self, "_st_cache", None)
        if cache is None:
            cache = self._st_cache = {}
        if page in cache:
            return cache[page]
        pack = self._unpack(html)
        if not pack:
            return {}
        base = ""
        for k, v in pack.items():
            if "playlist.m3u8" in v and v.startswith("http"):
                base = v
                break
        if not base:
            return {}
        root = base.rsplit("/", 1)[0]
        out = {}
        for key in ("source1280", "source842", "source"):
            u = pack.get(key)
            if not u:
                continue
            lb = self._label_of(u)
            if lb and lb not in out:
                out[lb] = u
        for q, u in self._master_variants(base, root).items():
            if q not in out:
                out[q] = u
        if not out:
            out["360P"] = "%s/360p/video.m3u8" % root
        out = dict(sorted(out.items(), key=lambda kv: -self._qnum(kv[0])))
        self._st_cache[page] = out
        return out

    @staticmethod
    def _label_of(url):
        m = re.search(r"/(\d{3,4})p/video\.m3u8", url or "")
        return m.group(1) + "P" if m else ""

    @staticmethod
    def _qnum(label):
        m = re.search(r"(\d{3,4})", label or "")
        return int(m.group(1)) if m else 0

    def _master_variants(self, base, root):
        out = {}
        try:
            txt = self._fetch_bytes(base).decode("utf-8", "ignore")
        except Exception as e:
            print("[missav] master清单取失败: %s" % e)
            return out
        lines = txt.split("\n")
        for i, ln in enumerate(lines):
            if "RESOLUTION=" not in ln:
                continue
            rm = re.search(r"RESOLUTION=(\d+)x(\d+)", ln)
            if not rm:
                continue
            h = int(rm.group(2))
            nm = None
            for j in range(i + 1, min(i + 4, len(lines))):
                s = lines[j].strip()
                if s and not s.startswith("#"):
                    nm = s
                    break
            if not nm:
                continue
            lb = "%dP" % h
            if lb in out:
                continue
            out[lb] = (nm if nm.startswith("http") else "%s/%s" % (root, nm))
        return out

    def _vods(self, html):
        out = []
        seen = set()
        if not html:
            return out
        blocks = re.split(r'<div[^>]*class="[^"]*\bthumbnail\b[^"]*"[^>]*>', html)[1:]
        for blk in blocks:
            code = self._code_of(blk)
            if not code:
                continue
            if code.lower() in self._NAV or code in seen:
                continue
            seen.add(code)
            url = "%s/en/%s" % (self.host, code)
            pic = ""
            pm = re.search(r'(https://fourhoi\.com/[^"\']+?/cover-[tn]\.jpg)', blk)
            if pm:
                pic = pm.group(1)
            if not pic:
                pic = "https://fourhoi.com/%s/cover-t.jpg" % code
            name = ""
            tm = re.search(r'<a[^>]*class="text-secondary[^"]*"[^>]*>\s*([^<]+?)\s*</a>', blk, re.S)
            if tm:
                name = tm.group(1).strip()
            if not name:
                am = re.search(r'<img[^>]*alt="([^"]*)"', blk)
                name = am.group(1).strip() if am else ""
            if not name:
                name = code.upper()
            out.append({"vod_id": url, "vod_name": name, "vod_pic": pic})
        if not out:
            for m in re.finditer(r'href="[^"]*?([a-z0-9\-]{2,40})(?:[#?][^"]*)?"[^>]*alt="([^"]*)"', html):
                code = m.group(1)
                if code.lower() in self._NAV or code in seen:
                    continue
                seen.add(code)
                out.append({"vod_id": "%s/en/%s" % (self.host, code),
                            "vod_name": m.group(2) or code.upper(),
                            "vod_pic": "https://fourhoi.com/%s/cover-t.jpg" % code})
        return out

    def _code_of(self, blk):
        m = re.search(r'href="(?:https?://missav\.ws)?/(?:[a-z0-9]+/)?(?:en/)?([a-z0-9\-]{2,40})(?:[#?][^"]*)?"', blk)
        if not m:
            return ""
        code = m.group(1)
        if not re.match(r"^[a-z0-9]+-[a-z0-9\-]*$|^[a-z0-9]{4,}$", code):
            return ""
        return code

    def _pagecount(self, html, cur, base_url=None):
        pages = []
        for m in re.finditer(r'href="([^"]*[?&]page=(\d+)[^"]*)"', html):
            href = m.group(1)
            if not self._is_real_pager(href):
                continue
            pages.append(int(m.group(2)))
        if not pages:
            if cur > 1 and base_url:
                last = self._last_page_by_probe(base_url)
                if last:
                    return min(last, cur)
            return cur + 1
        mx = max(pages)
        if mx > cur:
            return mx
        if cur > mx + 1:
            return mx
        return max(cur, mx)

    @staticmethod
    def _is_real_pager(href):
        if not href:
            return False
        if "missav.ws" in href:
            return True
        seg = href.split("?")[0].split("#")[0].strip("/").split("/")
        for s in seg:
            if s in _LANGS or s == "en":
                return False
        return True

    def _last_page_by_probe(self, base_url):
        key = base_url.split("?")[0]
        cache = getattr(self, "_lastpg_cache", None)
        if cache is None:
            cache = self._lastpg_cache = {}
        if key in cache:
            return cache[key]
        last = 0
        try:
            h1 = self._page(base_url.split("?")[0])
            for m in re.finditer(r'href="([^"]*[?&]page=(\d+)[^"]*)"', h1 or ""):
                if not self._is_real_pager(m.group(1)):
                    continue
                n = int(m.group(2))
                if n > last:
                    last = n
        except Exception as e:
            print("[missav] 末页探测失败: %s" % e)
        cache[key] = last
        return last

    def _detail_name(self, html, url):
        m = re.search(r"<h1[^>]*>(.*?)</h1>", html, re.S)
        if m:
            t = re.sub(r"<[^>]+>", "", m.group(1)).strip()
            if t:
                return t
        m = re.search(r'<meta\s+property="og:title"\s+content="([^"]*)"', html)
        if m:
            return m.group(1).strip()
        return url.rsplit("/", 1)[-1].upper()

    def _page(self, url):
        if jclass is None:
            return self._plain_page(url)
        try:
            Looper = jclass("android.os.Looper")
            if Looper.myLooper() == Looper.getMainLooper():
                print("[missav] 主线程禁止同步等待WebView, 降级urllib: " + url)
                return self._plain_page(url)
        except Exception:
            return self._plain_page(url)
        box, evt = {}, threading.Event()

        def done(h):
            box["h"] = h
            evt.set()

        if not self._run_ui(lambda act: self._wv_load(act, url, done)):
            return self._plain_page(url)
        evt.wait(60)
        return box.get("h", "")

    def _plain_page(self, url):
        try:
            return self._fetch_bytes(url).decode("utf-8", "ignore")
        except Exception as e:
            print("[missav] urllib直连失败(CF拦截属正常, 壳内走WebView) %s: %s" % (url, e))
            return ""

    def _run_ui(self, fn):
        try:
            p = _ensure_wv_proxy()
            Handler = jclass("android.os.Handler")
            Looper = jclass("android.os.Looper")
            r = p["run"]()
            r._fn = lambda: fn(self._activity())
            Handler(Looper.getMainLooper()).post(r)
            return True
        except Exception:
            print("[missav] UI投递失败:\n" + traceback.format_exc())
            return False

    @staticmethod
    def _activity():
        try:
            AT = jclass("java.lang.Class").forName("android.app.ActivityThread")
            cur = AT.getMethod("currentActivityThread").invoke(None)
            f = AT.getDeclaredField("mActivities")
            f.setAccessible(True)
            vals = f.get(cur).values().toArray()
            for r in vals:
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
            print("[missav] 取Activity失败:\n" + traceback.format_exc())
        return None

    def _wv_load(self, act, url, done):
        try:
            if act is None:
                done("")
                return
            wv = self._wv
            if wv is None:
                wv = self._wv_create(act)
            st = {"n": 0, "done": False, "url": url}

            def finish(h):
                if not st["done"]:
                    st["done"] = True
                    done(h)

            def grab():
                if st["done"]:
                    return
                try:
                    if not st.get("cfok") and self._cf_cleared(url):
                        st["cfok"] = True
                        st["rl"] = st.get("rl", 0) + 1
                        print("[missav] WebView捕获cf_clearance, 主动reload进真页")
                        try:
                            wv.reload()
                        except Exception:
                            pass
                        p0 = _ensure_wv_proxy()
                        H0 = jclass("android.os.Handler")
                        L0 = jclass("android.os.Looper")
                        r0 = p0["run"]()
                        r0._fn = grab
                        self._keep(r0)
                        H0(L0.getMainLooper()).postDelayed(r0, 800)
                        return
                    p = _ensure_wv_proxy()
                    cb = p["cb"]()
                    cb._fn = lambda v: self._wv_check(v, st, finish, sched)
                    self._keep(cb)
                    wv.evaluateJavascript(
                        "(function(){var h=document.documentElement.outerHTML||'';"
                        "return JSON.stringify({rs:document.readyState,"
                        "t:document.title||'',h:h});})()", cb)
                except Exception:
                    print("[missav] evaluateJavascript异常:\n" + traceback.format_exc())
                    finish("")

            def sched():
                try:
                    p = _ensure_wv_proxy()
                    Handler = jclass("android.os.Handler")
                    Looper = jclass("android.os.Looper")
                    r = p["run"]()
                    r._fn = grab
                    self._keep(r)
                    Handler(Looper.getMainLooper()).postDelayed(r, 500)
                except Exception:
                    finish("")

            wv.loadUrl(url)
            sched()
        except Exception:
            print("[missav] WebView加载异常:\n" + traceback.format_exc())
            done("")

    def _cf_cleared(self, url):
        try:
            CookieManager = jclass("android.webkit.CookieManager")
            ck = CookieManager.getInstance().getCookie(url) or ""
            if "cf_clearance" in ck:
                return True
        except Exception:
            pass
        return False

    def _wv_check(self, v, st, finish, sched):
        if st["done"]:
            return
        d = None
        try:
            if v and v != "null":
                d = json.loads(v)
                if isinstance(d, str):
                    d = json.loads(d)
        except Exception:
            d = None
        html = d.get("h", "") if isinstance(d, dict) else ""
        rs = str(d.get("rs", "")) if isinstance(d, dict) else ""
        title = str(d.get("t", "")).lower() if isinstance(d, dict) else ""
        low = html.lower()
        chal = (any(k in title for k in _CHALLENGE)
                or "cf-mitigated" in low or "challenges.cloudflare.com" in low
                or "cf-challenge" in low)
        if (rs in ("interactive", "complete") and len(html) >= 400 and not chal):
            print("[missav] WebView取到正文 len=%d %s" % (len(html), st["url"]))
            finish(html)
            return
        st["n"] += 1
        if st["n"] >= 45:
            print("[missav] WebView %d次探测未取到正文: %s" % (st["n"], st["url"]))
            finish("")
            return
        sched()

    def _wv_create(self, act):
        WebView = jclass("android.webkit.WebView")
        WebViewClient = jclass("android.webkit.WebViewClient")
        FrameLayout = jclass("android.widget.FrameLayout")
        CookieManager = jclass("android.webkit.CookieManager")
        wv = WebView(act)
        wv.getSettings().setJavaScriptEnabled(True)
        wv.getSettings().setDomStorageEnabled(True)
        wv.getSettings().setLoadWithOverviewMode(True)
        wv.getSettings().setUseWideViewPort(True)
        wv.getSettings().setBlockNetworkImage(True)
        CookieManager.getInstance().setAcceptCookie(True)
        wv.setWebViewClient(WebViewClient())
        lay = FrameLayout(act)
        lay.addView(wv, FrameLayout.LayoutParams(1, 1))
        act.getWindow().getDecorView().addView(lay)
        self._wv = wv
        self._wv_lay = lay
        self._keep(wv, lay)
        return wv

    def _keep(self, *objs):
        self._wv_keep.extend([o for o in objs if o is not None])
        if len(self._wv_keep) > 40:
            del self._wv_keep[:20]
