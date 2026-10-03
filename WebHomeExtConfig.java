package com.fongmi.web;

import android.text.TextUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 ext 字段 (分类配置).
 *
 * <p>ext 两种形式:
 * <ul>
 *   <li>字符串: 指向 json 文件路径 (相对 csp jar 同目录). SDK 读这个文件.
 *       支持 http(s):// URL 和本地 file:// 路径.
 *   <li>对象: 直接作为分类数据使用.
 * </ul>
 *
 * <p>数据结构:
 * <pre>
 * {
 *   "分类名1": { "条目名1": "./html/file.html", "条目名2": "https://..." },
 *   "分类名2": { "条目名3": "..." }
 * }
 * </pre>
 */
public class WebHomeExtConfig {

    /** 加载后的 ext: 分类名 -> (条目名 -> URL) */
    private final Map<String, Map<String, String>> categories = new LinkedHashMap<>();

    public static WebHomeExtConfig load(String ext) {
        if (ext == null || ext.isEmpty()) {
            return new WebHomeExtConfig();  // 空配置
        }
        String json;
        // 字符串: 加载文件
        if (ext.startsWith("{") || ext.startsWith("[")) {
            // 直接是 JSON 字符串
            json = ext;
        } else {
            json = readExternal(ext);
            if (json == null) return new WebHomeExtConfig();
        }
        return parse(json);
    }

    private static String readExternal(String path) {
        try {
            // 本地文件 (相对当前工作目录)
            File f = new File(path);
            if (f.exists() && f.isFile()) {
                try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                    return readAll(r);
                }
            }
            // http(s) URL
            if (path.startsWith("http://") || path.startsWith("https://")) {
                HttpURLConnection conn = (HttpURLConnection) new URL(path).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                try (InputStream is = conn.getInputStream();
                     Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    return readAll(r);
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readAll(Reader r) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        return sb.toString();
    }

    private static WebHomeExtConfig parse(String json) {
        WebHomeExtConfig cfg = new WebHomeExtConfig();
        try {
            JsonElement el = JsonParser.parseString(json);
            if (el == null || !el.isJsonObject()) return cfg;
            JsonObject root = el.getAsJsonObject();
            for (Map.Entry<String, JsonElement> cat : root.entrySet()) {
                String catName = cat.getKey();
                Map<String, String> items = new LinkedHashMap<>();
                if (cat.getValue() != null && cat.getValue().isJsonObject()) {
                    JsonObject obj = cat.getValue().getAsJsonObject();
                    for (Map.Entry<String, JsonElement> item : obj.entrySet()) {
                        String val = item.getValue() == null || item.getValue().isJsonNull() ? "" :
                                (item.getValue().isJsonPrimitive() ? item.getValue().getAsString() : item.getValue().toString());
                        items.put(item.getKey(), val);
                    }
                }
                cfg.categories.put(catName, items);
            }
        } catch (Throwable t) {
            // 解析失败, 返回空配置
        }
        return cfg;
    }

    /**
     * 排序后的分类列表 (按加载顺序).
     */
    public List<String> getCategoryNames() {
        return new ArrayList<>(categories.keySet());
    }

    /**
     * 排序后的条目列表 (按加载顺序).
     */
    public List<String> getEntryNames(String category) {
        Map<String, String> items = categories.get(category);
        if (items == null) return new ArrayList<>();
        return new ArrayList<>(items.keySet());
    }

    public String getUrl(String category, String entry) {
        Map<String, String> items = categories.get(category);
        if (items == null) return null;
        return items.get(entry);
    }

    /**
     * 生成唯一 id, 用于 蜂蜜壳的 Vod.vod_id.
     * 格式: "wh_" + 分类索引 + "_" + 条目索引 (md5 不需要, 索引够用)
     */
    public String getId(String category, String entry) {
        int catIdx = -1, entryIdx = -1;
        int i = 0;
        for (String c : categories.keySet()) {
            if (c.equals(category)) {
                catIdx = i;
                int j = 0;
                for (String e : categories.get(c).keySet()) {
                    if (e.equals(entry)) { entryIdx = j; break; }
                    j++;
                }
                break;
            }
            i++;
        }
        if (catIdx < 0 || entryIdx < 0) return null;
        return "wh_" + catIdx + "_" + entryIdx;
    }

    /**
     * 从 id 反查 category / entry.
     */
    public String[] getFromId(String id) {
        if (id == null || !id.startsWith("wh_")) return null;
        try {
            String[] parts = id.substring(3).split("_");
            int catIdx = Integer.parseInt(parts[0]);
            int entryIdx = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int i = 0;
            for (Map.Entry<String, Map<String, String>> e : categories.entrySet()) {
                if (i++ == catIdx) {
                    int j = 0;
                    for (Map.Entry<String, String> ee : e.getValue().entrySet()) {
                        if (j++ == entryIdx) {
                            return new String[] { e.getKey(), ee.getKey() };
                        }
                    }
                }
            }
        } catch (Throwable t) {}
        return null;
    }
}