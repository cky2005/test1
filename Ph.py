# -*- coding: utf-8 -*-
import json
import re
import sys
from base64 import b64decode, b64encode
from urllib.parse import urlparse
import time  # 确保导入了 time 库
import requests
from pyquery import PyQuery as pq
from requests import Session
sys.path.append('..')
from base.spider import Spider

class Spider(Spider):

    # 缩略图候选属性，按“越靠前越可能是真实地址”排序；data: 开头的懒加载占位图一律丢弃
    IMG_ATTRS = ('data-thumb_url', 'data-mediumthumb', 'data-smallthumb', 'src', 'data-path', 'data-poster', 'data-original', 'data-lazy')

    # 取图失败时返回的 1x1 透明图，避免壳里图片加载抛异常
    BLANK = b'GIF89a\x01\x00\x01\x00\x80\x00\x00\x00\x00\x00\xff\xff\xff!\xf9\x04\x01\x00\x00\x00\x00,\x00\x00\x00\x00\x01\x00\x01\x00\x00\x02\x02D\x01\x00;'

    def init(self, extend=""):
        self.headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.9',
            'Connection': 'keep-alive',
            'Upgrade-Insecure-Requests': '1',
        }
        self.host = self.gethost()
        self.headers.update({'referer': f'{self.host}/', 'origin': self.host})
        self.session = Session()
        self.session.headers.update(self.headers)
        
        # 播放专用头 (m3u8 与分片都在 phncdn 上,必须带 UA + Referer,缺一个就是 403 / 黑屏)
        self.PLAY_HEADERS = {
            "User-Agent": self.headers['User-Agent'],
            "Referer": self.host + "/",
            "Origin": self.host,
        }
        self.token = ''
        pass

    def getName(self):pass

    def homeContent(self, filter):
        result = {}
        cateManual = {
            "视频": "/video",
            "片单": "/playlists",
            "频道": "/channels",
            "分类": "/categories",
            "明星": "/pornstars"
        }
        classes = []
        filters = {}
        for k in cateManual:
            classes.append({
                'type_name': k,
                'type_id': cateManual[k]
            })
        result['class'] = classes
        result['filters'] = filters
        return result

    def homeVideoContent(self):
        data = self.getpq('/recommended')
        vhtml = data("#recommendedListings .pcVideoListItem .phimage")
        return {'list': self.getlist(vhtml)}

    def categoryContent(self, tid, pg, filter, extend):
        vdata = []
        result = {}
        result['page'] = pg
        result['pagecount'] = 9999
        result['limit'] = 90
        result['total'] = 999999
        if tid == '/video' or '_this_video' in tid:
            pagestr = f'&' if '?' in tid else f'?'
            tid = tid.split('_this_video')[0]
            data = self.getpq(f'{tid}{pagestr}page={pg}')
            vdata = self.getlist(data('#videoCategory .pcVideoListItem'))
        elif tid == '/playlists':
            data = self.getpq(f'{tid}?page={pg}')
            vhtml = data('#playListSection li')
            vdata = []
            for i in range(len(vhtml)):
                i = vhtml.eq(i)
                href = i('.thumbnail-info-wrapper .display-block a').attr('href') or i('.viewPlaylistLink').attr('href')
                if not href: continue
                vdata.append({
                    'vod_id': 'playlists_click_' + href,
                    'vod_name': i('.thumbnail-info-wrapper .display-block a').attr('title') or i('.title a').text(),
                    'vod_pic': self.proxy(self.pickpic(i)),
                    'vod_tag': 'folder',
                    'vod_remarks': i('.playlist-videos .number').text(),
                    'style': {"type": "rect", "ratio": 1.33}
                })
        elif tid == '/channels':
            data = self.getpq(f'{tid}?o=rk&page={pg}')
            vhtml = data('#filterChannelsSection li .description')
            vdata = []
            for i in range(len(vhtml)):
                i = vhtml.eq(i)
                vdata.append({
                    'vod_id': 'director_click_' + i('.avatar a').attr('href'),
                    'vod_name': i('.avatar img').attr('alt'),
                    'vod_pic': self.proxy(self.pickpic(i)),
                    'vod_tag': 'folder',
                    'vod_remarks': i('.descriptionContainer ul li').eq(-1).text(),
                    'style': {"type": "rect", "ratio": 1.33}
                })
        elif tid == '/categories' and pg == '1':
            result['pagecount'] = 1
            data = self.getpq(f'{tid}')
            vhtml = data('.categoriesListSection li .relativeWrapper')
            vdata = []
            for i in range(len(vhtml)):
                i = vhtml.eq(i)
                vdata.append({
                    'vod_id': i('a').attr('href') + '_this_video',
                    'vod_name': i('a').attr('alt'),
                    'vod_pic': self.proxy(self.pickpic(i)),
                    'vod_tag': 'folder',
                    'style': {"type": "rect", "ratio": 1.33}
                })
        elif tid == '/pornstars':
            data = self.getpq(f'{tid}?o=t&page={pg}')
            vhtml = data('#popularPornstars .performerCard .wrap')
            vdata = []
            for i in range(len(vhtml)):
                i = vhtml.eq(i)
                vdata.append({
                    'vod_id': 'pornstars_click_' + i('a').attr('href'),
                    'vod_name': i('.performerCardName').text(),
                    'vod_pic': self.proxy(self.pickpic(i)),
                    'vod_tag': 'folder',
                    'vod_year': i('.performerVideosViewsCount span').eq(0).text(),
                    'vod_remarks': i('.performerVideosViewsCount span').eq(-1).text(),
                    'style': {"type": "rect", "ratio": 1.33}
                })
        elif 'playlists_click' in tid:
            tid = tid.split('click_')[-1]
            if pg == '1':
                hdata = self.getpq(tid)
                self.token = hdata('#searchInput').attr('data-token') or ''
                vdata = self.getlist(hdata('#videoPlaylist .pcVideoListItem .phimage'))
            else:
                pid = tid.split('playlist/')[-1]
                data = self.getpq(f'/playlist/viewChunked?id={pid}&token={self.token}&page={pg}')
                vdata = self.getlist(data('.pcVideoListItem .phimage'))
        elif 'director_click' in tid:
            tid = tid.split('click_')[-1]
            data = self.getpq(f'{tid}/videos?page={pg}')
            vdata = self.getlist(data('#showAllChanelVideos .pcVideoListItem .phimage'))
        elif 'pornstars_click' in tid:
            tid = tid.split('click_')[-1]
            data = self.getpq(f'{tid}/videos?page={pg}')
            vdata = self.getlist(data('#mostRecentVideosSection .pcVideoListItem .phimage'))
        result['list'] = vdata
        return result

    def detailContent(self, ids):
        url = f"{self.host}{ids[0]}"
        data = self.getpq(ids[0])
        vn = data('meta[property="og:title"]').attr('content') or ''
        dtext = data('.userInfo .usernameWrap a')
        pdtitle = ''
        if len(dtext) and dtext.attr('href'):
            pdtitle = '[a=cr:' + json.dumps(
                {'id': 'director_click_' + dtext.attr('href'), 'name': dtext.text()}) + '/]' + dtext.text() + '[/a]'
        vod = {
            'vod_name': vn,
            'vod_director': pdtitle,
            'vod_remarks': (data('.userInfo').text() + ' / ' + data('.ratingInfo').text()).replace('\n', ' / '),
            'vod_play_from': '老僧酿酒',
            'vod_play_url': ''
        }
        js_content = data("#player script").eq(0).text()
        plist = [f"{vn}${self.e64(f'1@@@@{url}@@@@@@@@{url}')}"]
        try:
            pattern = r'"mediaDefinitions":\s*(\[.*?\]),\s*"isVertical"'
            match = re.search(pattern, js_content, re.DOTALL)
            if match:
                udata = json.loads(match.group(1))
                got = []
                for m in udata:
                    vurl = m.get('videoUrl')
                    if not vurl: continue
                    hh = str(m.get('height') or m.get('quality') or '')
                    got.append((hh, vurl))
                if got:
                    # 将视频页 url 作为第 4 个参数拼在后面传递
                    plist = [f"{hh}${self.e64('0@@@@' + vurl + '@@@@' + hh + '@@@@' + url)}" for hh, vurl in got]
        except Exception as e:
            print(f"提取mediaDefinitions失败: {str(e)}")
        vod['vod_play_url'] = '#'.join(plist)
        return {'list': [vod]}

    def searchContent(self, key, quick, pg="1"):
        data = self.getpq(f'/video/search?search={key}&page={pg}')
        return {'list': self.getlist(data('#videoSearchResult .pcVideoListItem .phimage'))}

    # ---- pornhub2.py 核心辅助函数 ----
    def _brace_json(self, source, start):
        """从 start 处的 '{' 起做括号配平取 JSON,跳过字符串里的花括号。"""
        depth = 0
        in_str = False
        esc = False
        for i in range(start, len(source)):
            ch = source[i]
            if in_str:
                if esc:
                    esc = False
                elif ch == "\\":
                    esc = True
                elif ch == '"':
                    in_str = False
                continue
            if ch == '"':
                in_str = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return source[start:i + 1]
        return ""

    def _flashvars(self, html):
        m = re.search(r"var\s+flashvars_\d+\s*=\s*", html)
        if not m: return {}
        start = html.find("{", m.end())
        if start < 0: return {}
        raw = self._brace_json(html, start)
        try: return json.loads(raw)
        except: return {}

    def _streams(self, fv):
        """从 mediaDefinitions 里取 HLS 线路,按清晰度从高到低排。"""
        out = []
        if not isinstance(fv, dict): return out
        for md in (fv.get("mediaDefinitions") or []):
            if not isinstance(md, dict): continue
            if str(md.get("format", "")).lower() != "hls": continue
            u = md.get("videoUrl") or ""
            if not u or not u.startswith("http"): continue
            q = md.get("quality") or md.get("height") or ""
            try: qn = int(str(q).strip())
            except: qn = 0
            out.append({"q": qn, "url": u, "default": bool(md.get("defaultQuality"))})
        out.sort(key=lambda x: x["q"], reverse=True)
        return out

    def _pick_stream(self, streams, want=None):
        if not streams: return None
        if want:
            try: wn = int(str(want).strip())
            except: wn = 0
            if wn:
                exact = [s for s in streams if s["q"] == wn]
                if exact: return exact[0]
                lower = [s for s in streams if s["q"] and s["q"] <= wn]
                if lower: return lower[0]
        for s in streams:
            if s["default"]: return s
        for s in streams:
            if s["q"] and s["q"] <= 1080: return s
        return streams[0]

    def _probe(self, url, timeout=8):
        """直链真拉一小段验证,避免把 410/403 的死链甩给播放器(黑屏)。"""
        if not url: return False
        headers = dict(self.PLAY_HEADERS)
        headers["Range"] = "bytes=0-2047"
        try:
            r = self.session.get(url, headers=headers, timeout=timeout, stream=True)
            if r.status_code not in (200, 206):
                r.close()
                return False
            head = next(r.iter_content(1024), b"")
            r.close()
            return b"#EXTM3U" in (head or b"")
        except:
            return False

    # ---- 核心播放逻辑 ----
    def playerContent(self, flag, id, vipFlags):
        raw = self.d64(id).strip()
        
        if raw.startswith("http"):
            return {"parse": 0, "playUrl": "", "url": raw, "header": self.PLAY_HEADERS}

        vkey = ""
        want = ""
        
        # 解析 ID 格式 (兼容 pornhub1: e64('parse@@@@url@@@@hh@@@@page_url') 和 pornhub2: 'ph:vkey@quality')
        if "ph:" in raw:
            vkey_part = raw.split("ph:")[-1]
            if "@" in vkey_part:
                vkey, want = vkey_part.split("@", 1)
            else:
                vkey = vkey_part
        else:
            ids = raw.split('@@@@')
            if len(ids) >= 4:
                vkey = re.sub(r"^.*viewkey=", "", ids[3]).strip()
                want = ids[2]

        if not vkey:
            return {"parse": 0, "playUrl": "", "url": "", "header": self.PLAY_HEADERS}

        # 命中缓存直接给 (直链有效期约 2 小时,缓存只留 20 分钟)
        if not hasattr(self, '_url_cache'): self._url_cache = {}
        hit = self._url_cache.get(vkey + "@" + str(want))
        if hit and time.time() - hit[0] < 1200:
            return {"parse": 0, "playUrl": "", "url": hit[1], "header": self.PLAY_HEADERS, "format": "application/x-mpegURL"}

        # 站点对同一部片会随机下发两种 CDN 直链,其中一种整批 410 —— 探测 + 重取
        for _ in range(4):
            try:
                url_page = f"{self.host}/view_video.php?viewkey={vkey}"
                html = self.session.get(url_page, headers={'User-Agent': self.PLAY_HEADERS['User-Agent']}, timeout=20).text
                fv = self._flashvars(html)
                streams = self._streams(fv)
                if streams:
                    ordered = []
                    first = self._pick_stream(streams, want)
                    if first:
                        ordered.append(first)
                    for s in streams:
                        if s not in ordered:
                            ordered.append(s)
                    for s in ordered[:3]:
                        if self._probe(s["url"]):
                            self._url_cache[vkey + "@" + str(want)] = (time.time(), s["url"])
                            return {"parse": 0, "playUrl": "", "url": s["url"], "header": self.PLAY_HEADERS, "format": "application/x-mpegURL"}
            except Exception as e:
                print(f"取流重试: {e}")
            time.sleep(1)
        return {"parse": 0, "playUrl": "", "url": "", "header": self.PLAY_HEADERS}

    # getmedia 方法已废弃 (保留兼容但不再使用)
    def getmedia(self, url, hh, page_url=""):
        return None

    def localProxy(self, param):
        url = self.d64(param.get('url'))
        if param.get('type') == 'm3u8':
            return self.m3Proxy(url)
        elif param.get('type') == 'img':
            return self.imgProxy(url)
        else:
            return self.tsProxy(url)

    def m3Proxy(self, url):
        try:
            ydata = self.session.get(url, headers=self.headers, allow_redirects=False, timeout=30)
            data = ydata.content.decode('utf-8')
        except Exception as e:
            print(f"取播放列表失败: {str(e)}")
            return [200, "application/vnd.apple.mpegur", "#EXTM3U\n"]
        if ydata.headers.get('Location'):
            url = ydata.headers['Location']
            try:
                data = self.session.get(url, headers=self.headers, timeout=30).content.decode('utf-8')
            except Exception as e:
                print(f"取播放列表失败: {str(e)}")
                return [200, "application/vnd.apple.mpegur", "#EXTM3U\n"]
        lines = data.strip().split('\n')
        parsed_url = urlparse(url)
        durl = parsed_url.scheme + "://" + parsed_url.netloc
        last_r = durl + parsed_url.path[:parsed_url.path.rfind('/') + 1]
        for index, string in enumerate(lines):
            if '#EXT' not in string and string.strip():
                if 'http' not in string:
                    domain = (last_r if string.count('/') < 2 else durl).rstrip('/')
                    string = domain + ('' if string.startswith('/') else '/') + string
                ext = urlparse(string).path.rsplit('.', 1)[-1].lower() if '.' in urlparse(string).path else 'ts'
                lines[index] = self.proxy(string, ext)
        data = '\n'.join(lines)
        return [200, "application/vnd.apple.mpegur", data]

    def tsProxy(self, url):
        try:
            data = self.session.get(url, headers=self.headers, timeout=60)
        except Exception as e:
            print(f"取流失败: {str(e)}")
            return [200, 'video/mp2t', b'']
        ctype = data.headers.get('Content-Type', 'video/mp2t').split(';')[0]
        if data.status_code != 200 or ctype == 'text/html':
            print(f"取流失败({data.status_code}/{ctype}): {url[:110]}")
            return [200, 'video/mp2t', b'']
        return [200, ctype, data.content]

    def imgProxy(self, url):
        for _ in range(2):
            try:
                data = self.session.get(url, headers=self.headers, timeout=30)
            except Exception as e:
                print(f"取图失败: {str(e)}")
                break
            ctype = data.headers.get('Content-Type', '').split(';')[0]
            if data.status_code == 200 and ctype.startswith('image'):
                return [200, ctype, data.content]
            print(f"取图失败({data.status_code}/{ctype}): {url[:110]}")
        return [200, 'image/gif', self.BLANK]

    def gethost(self):
        try:
            response = requests.get('https://www.pornhub.com', headers=self.headers, allow_redirects=False, timeout=15)
            loc = response.headers.get('Location', 'https://www.pornhub.com/')
            return loc.rstrip('/')
        except Exception as e:
            print(f"获取主页失败: {str(e)}")
            return "https://www.pornhub.com"

    def e64(self, text):
        try:
            text_bytes = text.encode('utf-8')
            encoded_bytes = b64encode(text_bytes)
            return encoded_bytes.decode('utf-8')
        except Exception as e:
            print(f"Base64编码错误: {str(e)}")
            return ""

    def d64(self, encoded_text):
        try:
            encoded_bytes = encoded_text.encode('utf-8')
            decoded_bytes = b64decode(encoded_bytes)
            return decoded_bytes.decode('utf-8')
        except Exception as e:
            print(f"Base64解码错误: {str(e)}")
            return ""

    def getlist(self, data):
        vlist = []
        for idx in range(len(data)):
            node = data.eq(idx)
            anc = node.parents('li')
            tag = str(getattr(node[0] if len(node) else None, 'tag', '')).lower()
            card = node if tag == 'li' else (anc.eq(0) if len(anc) else node)
            href = card('a[href*="viewkey="]').attr('href') or card('a[href]').attr('href')
            if not href: continue
            pic = self.pickpic(card)
            if not pic: continue
            vlist.append({
                'vod_id': href,
                'vod_name': self.pickname(card, href),
                'vod_pic': self.proxy(pic),
                'vod_remarks': self.pickremark(card),
                'style': {'ratio': 1.33, 'type': 'rect'}
            })
        return vlist

    def pickpic(self, card):
        cands = []
        for el in card('img'):
            at = getattr(el, 'attrib', {}) or {}
            for a in self.IMG_ATTRS:
                v = at.get(a)
                if v and v.startswith('http'):
                    cands.append(v)
        poster = card('a').attr('data-poster')
        if poster:
            cands.append(poster)
        if not cands:
            return None
        for u in cands:
            if 'hdnea=' not in u:
                return u
        return cands[0]

    def pickname(self, card, fb):
        n = card('a[title]').attr('title')
        if not n:
            t = card('.title a, .title, .vidTitleWrapper a')
            n = (t.text() or '').strip() if len(t) else ''
        if not n:
            n = card('img').attr('alt')
        if not n:
            n = (card('a').text() or '').strip()
        return n or fb

    def pickremark(self, card):
        for sel in ('.bgShadeEffect', '.duration', '.views', '.shortiesBox__card__views', '.playTime'):
            t = card(sel).text()
            if t and t.strip():
                return t
        return ''

    def getpq(self, path):
        for n in range(2):
            try:
                response = self.session.get(f'{self.host}{path}', timeout=30).text
                return pq(response.encode('utf-8'))
            except Exception as e:
                print(f"请求失败(第{n + 1}次): {path} , {str(e)}")
        return pq(b'<html></html>')

    def proxy(self, data, type='img'):
        if data and data.startswith('http'):
            return f"{self.getProxyUrl()}&url={self.e64(data)}&type={type}"
        else:
            return data
