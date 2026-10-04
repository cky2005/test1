package com.github.catvod.spider;

import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 给壳 ExoPlayer 用的本地 HTTP.
 * 9978 /webResource 只被 WebView shouldInterceptRequest 吃, ExoPlayer 真 GET 会 404.
 * 这里绑定 127.0.0.1, 吐 MPD, 并带 B 站 Referer 代理分片.
 */
public final class LocalMediaServer {

    public static final String TAG = "LocalMedia";
    private static final String REFERRER = "https://www.bilibili.com/";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final int[] PORTS = {18080, 18081, 18082, 18083, 18084, 18765, 18766, 18767};

    private static final LocalMediaServer INSTANCE = new LocalMediaServer();

    public static LocalMediaServer get() {
        return INSTANCE;
    }

    private final Object lock = new Object();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Map<String, Item> store = new LinkedHashMap<String, Item>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Item> eldest) {
            return size() > 64;
        }
    };

    private volatile int port;
    private volatile ServerSocket server;

    private LocalMediaServer() {}

    public String origin() {
        ensureStarted();
        return port > 0 ? ("http://127.0.0.1:" + port) : "";
    }

    public String proxyUrl(String url) {
        if (TextUtils.isEmpty(url)) return url;
        if (url.startsWith("http://127.0.0.1:") && url.contains("/proxy?")) return url;
        String o = origin();
        if (o.isEmpty()) return url;
        try {
            return o + "/proxy?url=" + URLEncoder.encode(url, "UTF-8");
        } catch (Throwable t) {
            return url;
        }
    }

    /** data:application/dash+xml;base64,... → 本地 .mpd, 并把 BaseURL 改到 /proxy */
    public String putDataUri(String dataUri) {
        if (TextUtils.isEmpty(dataUri) || !dataUri.startsWith("data:")) return "";
        try {
            String rest = dataUri.substring(5);
            int comma = rest.indexOf(',');
            if (comma < 0) return "";
            String meta = rest.substring(0, comma);
            String payload = rest.substring(comma + 1);
            String type = "application/dash+xml";
            int semi = meta.indexOf(';');
            if (semi > 0) type = meta.substring(0, semi).trim();
            else if (!meta.isEmpty() && !meta.contains("base64")) type = meta.trim();
            byte[] bytes;
            if (meta.toLowerCase(Locale.ROOT).contains("base64")) {
                bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT);
            } else {
                bytes = URLDecoder.decode(payload, "UTF-8").getBytes(StandardCharsets.UTF_8);
            }
            String body = new String(bytes, StandardCharsets.UTF_8);
            if (type.contains("dash") || body.contains("<MPD") || body.contains("<mpd")) {
                body = rewriteMpdBaseUrls(body);
                return put(body, "application/dash+xml");
            }
            return put(body, type);
        } catch (Throwable t) {
            Log.w(TAG, "putDataUri: " + t.getMessage());
            return "";
        }
    }

    /**
     * 只处理「交给播放器」的地址: data URI → 本地 .mpd;
     * html 用 fm.res 拼的 9978/webResource → 剥出 CDN 再走 18080/proxy.
     * 不要从 resolveResourceUrl / fm.res 调用, 封面仍走 9978.
     */
    public String hostIfNeeded(String url) {
        if (TextUtils.isEmpty(url)) return url;
        String u = unwrapWebResource(url);
        if (u.startsWith("data:")) {
            String hosted = putDataUri(u);
            return TextUtils.isEmpty(hosted) ? u : hosted;
        }
        if (isLocalMedia(u)) return u;
        if (u.contains("/webResource?")) {
            String inner = unwrapWebResource(u);
            if (!inner.equals(u)) u = inner;
        }
        if (u.startsWith("http://") || u.startsWith("https://")) {
            if (u.contains("/webResource?")) {
                String inner = extractWebResourceTarget(u);
                if (!TextUtils.isEmpty(inner)) return proxyUrl(inner);
            }
        }
        return u;
    }

    static boolean isLocalMedia(String url) {
        if (url == null) return false;
        return url.startsWith("http://127.0.0.1:")
                && (url.contains("/proxy?") || url.contains("/mpd/") || url.contains("/hls/"));
    }

    /** 9978/webResource?url=ENC[&headers=] → 还原 CDN. 多层包裹剥最多 3 次. */
    public static String unwrapWebResource(String url) {
        if (url == null) return "";
        String u = url.trim();
        for (int n = 0; n < 3; n++) {
            String inner = extractWebResourceTarget(u);
            if (TextUtils.isEmpty(inner) || inner.equals(u)) break;
            u = inner;
        }
        return u;
    }

    static String extractWebResourceTarget(String url) {
        if (url == null) return "";
        int wr = url.indexOf("/webResource?");
        if (wr < 0) return "";
        String q = url.substring(wr + 13);
        int hash = q.indexOf('#');
        if (hash >= 0) q = q.substring(0, hash);
        String enc = queryParam(q, "url");
        if (TextUtils.isEmpty(enc)) return "";
        try {
            return URLDecoder.decode(enc, "UTF-8");
        } catch (Throwable t) {
            return enc;
        }
    }

    String rewriteMpdBaseUrls(String xml) {
        if (TextUtils.isEmpty(xml) || !xml.contains("BaseURL")) return xml;
        StringBuilder out = new StringBuilder(xml.length() + 64);
        int i = 0;
        String lower = xml.toLowerCase(Locale.ROOT);
        while (i < xml.length()) {
            int start = lower.indexOf("<baseurl>", i);
            if (start < 0) {
                out.append(xml, i, xml.length());
                break;
            }
            int inner = start + 9;
            int end = lower.indexOf("</baseurl>", inner);
            if (end < 0) {
                out.append(xml, i, xml.length());
                break;
            }
            String innerUrl = xml.substring(inner, end).trim();
            String rewritten = rewriteOneBaseUrl(innerUrl);
            out.append(xml, i, inner);
            out.append(rewritten);
            i = end;
        }
        return out.toString();
    }

    private String rewriteOneBaseUrl(String inner) {
        if (TextUtils.isEmpty(inner)) return inner;
        String u = inner;
        try {
            if (u.contains("&amp;")) u = u.replace("&amp;", "&");
            int wr = u.indexOf("/webResource?");
            if (wr >= 0) {
                String q = u.substring(wr + 13);
                String enc = queryParam(q, "url");
                if (!TextUtils.isEmpty(enc)) {
                    String real = URLDecoder.decode(enc, "UTF-8");
                    return proxyUrl(real);
                }
            }
            if (u.startsWith("http://") || u.startsWith("https://")) {
                if (u.contains("/proxy?") && u.contains("127.0.0.1")) return inner;
                if (u.contains("/mpd/") && u.contains("127.0.0.1")) return inner;
                return proxyUrl(u);
            }
        } catch (Throwable ignored) {}
        return inner;
    }

    public String put(String body, String type) {
        if (TextUtils.isEmpty(body)) return "";
        String o = origin();
        if (o.isEmpty()) return "";
        String id = Long.toHexString(System.currentTimeMillis())
                + Integer.toHexString((int) (Math.random() * 0xffff));
        String ct = TextUtils.isEmpty(type) ? "application/dash+xml" : type;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        synchronized (store) {
            store.put(id, new Item(bytes, ct, System.currentTimeMillis()));
        }
        String path = ct.contains("mpegURL") || ct.contains("m3u8")
                ? "/hls/" + id + ".m3u8"
                : "/mpd/" + id + ".mpd";
        Log.d(TAG, "put " + path + " " + bytes.length + " bytes");
        return o + path;
    }

    public void ensureStarted() {
        if (server != null && port > 0 && !server.isClosed()) return;
        synchronized (lock) {
            if (server != null && port > 0 && !server.isClosed()) return;
            for (int p : PORTS) {
                try {
                    ServerSocket ss = new ServerSocket(p, 32, InetAddress.getByName("127.0.0.1"));
                    ss.setReuseAddress(true);
                    server = ss;
                    port = p;
                    Thread t = new Thread(this::acceptLoop, "local-media-" + p);
                    t.setDaemon(true);
                    t.start();
                    Log.i(TAG, "listening on 127.0.0.1:" + p);
                    return;
                } catch (Throwable ignored) {
                }
            }
            Log.e(TAG, "no free port in 18080-18084 / 18765-18767");
        }
    }

    private void acceptLoop() {
        ServerSocket ss = server;
        while (ss != null && !ss.isClosed()) {
            try {
                Socket sock = ss.accept();
                pool.execute(() -> handle(sock));
            } catch (Throwable t) {
                if (ss.isClosed()) return;
                Log.w(TAG, "accept: " + t.getMessage());
            }
        }
    }

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(25000);
            sock.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = new BufferedOutputStream(sock.getOutputStream());
            String reqLine = readLine(in);
            if (reqLine == null || reqLine.isEmpty()) {
                sock.close();
                return;
            }
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) {
                    headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT),
                            line.substring(c + 1).trim());
                }
            }
            String[] parts = reqLine.split(" ");
            String method = parts.length > 0 ? parts[0].toUpperCase(Locale.ROOT) : "GET";
            String rawPath = parts.length > 1 ? parts[1] : "/";
            if ("OPTIONS".equals(method)) {
                writeHeaders(out, 200, "OK", "text/plain", 0, null);
                out.flush();
                return;
            }
            String path = rawPath;
            String query = "";
            int q = rawPath.indexOf('?');
            if (q >= 0) {
                path = rawPath.substring(0, q);
                query = rawPath.substring(q + 1);
            }
            if (path.startsWith("/mpd/") || path.startsWith("/hls/")) {
                serveStored(out, method, path);
            } else if (path.equals("/proxy")) {
                serveProxy(out, method, query, headers);
            } else {
                byte[] msg = "not found".getBytes(StandardCharsets.UTF_8);
                writeHeaders(out, 404, "Not Found", "text/plain", msg.length, null);
                out.write(msg);
                out.flush();
            }
        } catch (Throwable t) {
            Log.w(TAG, "handle: " + t.getMessage());
        } finally {
            try { sock.close(); } catch (Throwable ignored) {}
        }
    }

    private void serveStored(OutputStream out, String method, String path) throws Exception {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String id = dot > 0 ? name.substring(0, dot) : name;
        Item item;
        synchronized (store) {
            item = store.get(id);
        }
        if (item == null) {
            byte[] msg = "expired".getBytes(StandardCharsets.UTF_8);
            writeHeaders(out, 404, "Not Found", "text/plain", msg.length, null);
            out.write(msg);
            out.flush();
            return;
        }
        writeHeaders(out, 200, "OK", item.type, item.bytes.length, null);
        if (!"HEAD".equals(method)) out.write(item.bytes);
        out.flush();
    }

    private void serveProxy(OutputStream out, String method, String query, Map<String, String> reqHeaders) throws Exception {
        String target = queryParam(query, "url");
        if (TextUtils.isEmpty(target)) {
            byte[] msg = "missing url".getBytes(StandardCharsets.UTF_8);
            writeHeaders(out, 400, "Bad Request", "text/plain", msg.length, null);
            out.write(msg);
            out.flush();
            return;
        }
        try {
            target = URLDecoder.decode(target, "UTF-8");
        } catch (Throwable ignored) {}
        if (!isSafeTarget(target)) {
            byte[] msg = "forbidden".getBytes(StandardCharsets.UTF_8);
            writeHeaders(out, 403, "Forbidden", "text/plain", msg.length, null);
            out.write(msg);
            out.flush();
            return;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("HEAD".equals(method) ? "GET" : method);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", REFERRER);
            conn.setRequestProperty("Origin", "https://www.bilibili.com");
            String range = reqHeaders.get("range");
            if (!TextUtils.isEmpty(range)) conn.setRequestProperty("Range", range);
            conn.connect();
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String ct = conn.getContentType();
            if (TextUtils.isEmpty(ct)) ct = "application/octet-stream";
            long len = conn.getContentLength();
            String extra = "";
            String cr = conn.getHeaderField("Content-Range");
            if (!TextUtils.isEmpty(cr)) extra += "Content-Range: " + cr + "\r\n";
            String ar = conn.getHeaderField("Accept-Ranges");
            extra += "Accept-Ranges: " + (TextUtils.isEmpty(ar) ? "bytes" : ar) + "\r\n";
            if (len >= 0) extra += "Content-Length: " + len + "\r\n";
            String status = code + " " + (TextUtils.isEmpty(conn.getResponseMessage()) ? "OK" : conn.getResponseMessage());
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(status).append("\r\n");
            sb.append("Content-Type: ").append(ct).append("\r\n");
            sb.append(extra);
            sb.append("Access-Control-Allow-Origin: *\r\n");
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            if (!"HEAD".equals(method) && is != null) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                }
            }
            out.flush();
        } catch (Throwable t) {
            Log.w(TAG, "proxy fail: " + t.getMessage());
            try {
                byte[] msg = ("proxy error: " + t.getMessage()).getBytes(StandardCharsets.UTF_8);
                writeHeaders(out, 502, "Bad Gateway", "text/plain", msg.length, null);
                out.write(msg);
                out.flush();
            } catch (Throwable ignored) {}
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static boolean isSafeTarget(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.ROOT);
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false;
        try {
            URL parsed = new URL(url);
            String host = parsed.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            if ("localhost".equals(host) || host.endsWith(".localhost")) return false;
            if (host.equals("127.0.0.1") || host.equals("0.0.0.0") || host.equals("::1")) return false;
            if (host.equals("169.254.169.254") || host.equals("metadata.google.internal")) return false;
            if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("172.")) {
                // 172.16-31.x.x 是内网; 粗过滤 172.* 以免误伤公网 172.x CDN 的极少情况
                if (host.startsWith("10.") || host.startsWith("192.168.")) return false;
                String[] segs = host.split("\\.");
                if (segs.length > 1) {
                    int second = Integer.parseInt(segs[1]);
                    if (second >= 16 && second <= 31) return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String queryParam(String query, String key) {
        if (query == null) return "";
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            if (key.equals(k)) return eq < 0 ? "" : part.substring(eq + 1);
        }
        return "";
    }

    private static void writeHeaders(OutputStream out, int code, String reason, String type, long length, String extra) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("Connection: close\r\n");
        if (!TextUtils.isEmpty(extra)) sb.append(extra);
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int prev = -1, c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (prev == '\r' && c != '\n') bos.write(prev);
            if (c != '\r') bos.write(c);
            prev = c;
            if (bos.size() > 8192) break;
        }
        if (c < 0 && bos.size() == 0) return null;
        return bos.toString("UTF-8");
    }

    private static final class Item {
        final byte[] bytes;
        final String type;
        final long at;

        Item(byte[] bytes, String type, long at) {
            this.bytes = bytes;
            this.type = type;
            this.at = at;
        }
    }
}
