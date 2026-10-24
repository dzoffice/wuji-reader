package com.wuji.reader.store;

import android.content.Context;
import android.content.SharedPreferences;

import com.wuji.reader.model.Item;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 书架：SharedPreferences（文件名 "reader"，键 "favorites_v1"）+ org.json 数组持久化。
 *
 * 与 Prefs 共用同一 SharedPreferences 文件，互不干扰。
 * 每条记录序列化 Item 的 id/title/author/cover/url/sourceId，
 * 另存一份完整 key（含 book:/comic: 前缀）用于 isFav/remove 与书架页判断小说/漫画。
 */
public final class Favorites {

    private static final String KEY = "favorites_v1";

    private Favorites() {
    }

    /** 收藏键：kind 前缀 + sourceId + 站内 id */
    public static String key(boolean isBook, String sourceId, String id) {
        return (isBook ? "book:" : "comic:")
                + safe(sourceId) + ":" + safe(id);
    }

    /** key 是否小说（"book:" 前缀）；非法前缀按漫画处理 */
    public static boolean isBookKey(String key) {
        return key != null && key.startsWith("book:");
    }

    public static List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        JSONArray arr = readArray(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Item it = new Item();
            it.id = o.optString("id");
            it.title = o.optString("title");
            it.author = o.optString("author");
            it.cover = o.optString("cover");
            it.url = o.optString("url");
            it.sourceId = o.optString("sourceId");
            out.add(it);
        }
        return out;
    }

    /** 与 {@link #list(Context)} 同序返回每条收藏的完整 key */
    public static List<String> keys(Context c) {
        List<String> out = new ArrayList<>();
        JSONArray arr = readArray(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            out.add(o.optString("key"));
        }
        return out;
    }

    public static boolean isFav(Context c, String key) {
        if (key == null) {
            return false;
        }
        JSONArray arr = readArray(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && key.equals(o.optString("key"))) {
                return true;
            }
        }
        return false;
    }

    /** 加入书架；同 key 已存在则去重后重新插入（相当于置顶更新） */
    public static void add(Context c, Item it, boolean isBook) {
        if (c == null || it == null) {
            return;
        }
        String k = key(isBook, it.sourceId, it.id);
        JSONArray arr = readArray(c);
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || k.equals(o.optString("key"))) {
                continue;
            }
            out.put(o);
        }
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", safe(it.id));
            obj.put("title", safe(it.title));
            obj.put("author", safe(it.author));
            obj.put("cover", safe(it.cover));
            obj.put("url", safe(it.url));
            obj.put("sourceId", safe(it.sourceId));
            obj.put("key", k);
        } catch (JSONException ignored) {
        }
        out.put(obj);
        sp(c).edit().putString(KEY, out.toString()).apply();
    }

    public static void remove(Context c, String key) {
        if (c == null || key == null) {
            return;
        }
        JSONArray arr = readArray(c);
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || key.equals(o.optString("key"))) {
                continue;
            }
            out.put(o);
        }
        sp(c).edit().putString(KEY, out.toString()).apply();
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("reader", Context.MODE_PRIVATE);
    }

    private static JSONArray readArray(Context c) {
        if (c == null) {
            return new JSONArray();
        }
        String raw = sp(c).getString(KEY, "[]");
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
