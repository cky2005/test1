package com.fongmi.web;

import android.text.TextUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebHome 内联 VOD: html 只交剧集占位, 壳走本站 detailContent / playerContent.
 * {@code vod_play_url} 里 {@code $} 后面是 {@code whi:<vodId>:<index>}, 不是直链.
 */
public class WebHomeInlineVodStore {

    public static final String KEY = "webhome_inline";
    public static final String TOKEN_PREFIX = "whi:";

    private static final Map<String, Item> ITEMS = new ConcurrentHashMap<>();
    private static final Map<String, String> RESOLVED = new ConcurrentHashMap<>();

    public static String put(JsonObject payload) {
        if (payload == null) payload = new JsonObject();
        String src = getStr(payload, "vod_id", "");
        String id;
        if (TextUtils.isEmpty(src) || src.startsWith("webhome_inline_")) {
            id = "webhome_inline_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 10000);
        } else {
            id = "webhome_inline_" + stableId(src);
        }
        ITEMS.put(id, new Item(payload.toString()));
        return id;
    }

    /** 同一部片多次点播放用同一个 vodId, 壳历史才能续上; 第一次没有历史则落在第 1 集. */
    private static String stableId(String src) {
        StringBuilder sb = new StringBuilder(src.length());
        for (int i = 0; i < src.length() && sb.length() < 80; i++) {
            char c = src.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) sb.append(c);
            else if (c == '-' || c == '_' || c == '.') sb.append(c);
            else sb.append('_');
        }
        int h = src.hashCode();
        sb.append('_').append(Integer.toHexString(h));
        return sb.toString();
    }

    public static boolean has(String id) {
        return id != null && ITEMS.containsKey(id);
    }

    public static boolean isToken(String id) {
        return id != null && id.startsWith(TOKEN_PREFIX);
    }

    public static String token(String vodId, int index) {
        return TOKEN_PREFIX + vodId + ":" + index;
    }

    public static String[] parseToken(String id) {
        if (!isToken(id)) return null;
        String rest = id.substring(TOKEN_PREFIX.length());
        int last = rest.lastIndexOf(':');
        if (last <= 0 || last >= rest.length() - 1) return null;
        return new String[]{rest.substring(0, last), rest.substring(last + 1)};
    }

    public static String detailJson(String id) {
        Item item = ITEMS.get(id);
        if (item == null) return "{\"list\":[]}";
        JsonObject payload = parseObj(item.vodJson);
        JsonArray episodes = episodesOf(payload);

        StringBuilder playUrl = new StringBuilder();
        for (int i = 0; i < episodes.size(); i++) {
            JsonObject ep = episodes.get(i).getAsJsonObject();
            String name = getName(ep, i);
            if (playUrl.length() > 0) playUrl.append("#");
            playUrl.append(name).append("$").append(token(id, i));
        }

        JsonObject vod = new JsonObject();
        vod.addProperty("vod_id", id);
        vod.addProperty("vod_name", getStr(payload, "vod_name", getStr(payload, "title", "WebHome")));
        vod.addProperty("vod_pic", getStr(payload, "vod_pic", getStr(payload, "pic", "")));
        vod.addProperty("vod_play_url", playUrl.toString());
        vod.addProperty("vod_play_from", getStr(payload, "vod_play_from", getStr(payload, "playFrom", "WebHome")));
        vod.addProperty("vod_content", getStr(payload, "vod_content", ""));

        JsonArray list = new JsonArray();
        list.add(vod);
        JsonObject result = new JsonObject();
        result.add("list", list);
        return result.toString();
    }

    public static JsonObject getPayload(String vodId) {
        Item item = ITEMS.get(vodId);
        return item == null ? null : parseObj(item.vodJson);
    }

    public static JsonObject getEpisode(String vodId, int index) {
        JsonObject payload = getPayload(vodId);
        if (payload == null) return null;
        JsonArray episodes = episodesOf(payload);
        if (index < 0 || index >= episodes.size()) return null;
        return episodes.get(index).getAsJsonObject();
    }

    public static String getResolved(String vodId, int index) {
        return RESOLVED.get(vodId + ":" + index);
    }

    public static void putResolved(String vodId, int index, String url) {
        if (!TextUtils.isEmpty(url)) RESOLVED.put(vodId + ":" + index, url);
    }

    public static Map<String, String> headersOf(JsonObject ep) {
        Map<String, String> headers = new HashMap<>();
        if (ep != null && ep.has("headers") && ep.get("headers").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : ep.getAsJsonObject("headers").entrySet()) {
                try { headers.put(e.getKey(), e.getValue().getAsString()); } catch (Throwable ignored) {}
            }
        }
        return headers;
    }

    public static String episodeMediaHint(JsonObject ep) {
        if (ep == null) return "";
        String mediaUrl = getStr(ep, "mediaUrl");
        if (!TextUtils.isEmpty(mediaUrl)) return mediaUrl;
        return getStr(ep, "url");
    }

    public static String playResult(String url, String format, Map<String, String> headers, String flag) {
        return playResult(url, format, headers, flag, 0);
    }

    public static String playResult(String url, String format, Map<String, String> headers, String flag, int parse) {
        JsonObject result = new JsonObject();
        result.addProperty("url", url == null ? "" : url);
        result.addProperty("parse", parse);
        result.addProperty("jx", 0);
        result.addProperty("flag", flag == null ? "" : flag);
        if (!TextUtils.isEmpty(format)) result.addProperty("format", format);
        JsonObject headerObj = new JsonObject();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) headerObj.addProperty(e.getKey(), e.getValue());
            }
        }
        result.add("header", headerObj);
        return result.toString();
    }

    public static String episodePageUrl(JsonObject ep) {
        if (ep == null) return "";
        String page = getStr(ep, "pageUrl");
        if (!TextUtils.isEmpty(page)) return page;
        return getStr(ep, "url");
    }

    public static String getName(JsonObject ep, int index) {
        String n = getStr(ep, "name");
        if (TextUtils.isEmpty(n)) n = getStr(ep, "label");
        if (TextUtils.isEmpty(n)) n = getStr(ep, "title");
        if (TextUtils.isEmpty(n)) n = String.format("%02d", index + 1);
        return n.replace("$", " ").replace("#", " ");
    }

    public static String getStr(JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        try { return obj.get(key).getAsString(); } catch (Exception e) { return def; }
    }

    public static String getStr(JsonObject obj, String key) {
        return getStr(obj, key, "");
    }

    private static JsonArray episodesOf(JsonObject payload) {
        if (payload != null && payload.has("episodes") && payload.get("episodes").isJsonArray()) {
            return payload.getAsJsonArray("episodes");
        }
        return new JsonArray();
    }

    private static JsonObject parseObj(String json) {
        try { return JsonParser.parseString(json).getAsJsonObject(); } catch (Throwable t) { return new JsonObject(); }
    }

    private record Item(String vodJson) {}
}
