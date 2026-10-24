package com.wuji.reader.legado;

import android.content.res.AssetManager;
import android.util.Log;

import com.wuji.reader.source.BookSource;
import com.wuji.reader.source.ComicSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动时读取 assets/sources 下的 Legado 纯规则书源。
 * 任何单个源或整个 JSON 损坏都只记录日志，绝不把 App 带崩。
 */
public final class LegadoSourceLoader {

    private static final String TAG = "LegadoLoader";
    private static final String BOOKS_ASSET = "sources/novel_20.json";
    private static final String COMICS_ASSET = "sources/comic_pure.json";

    private LegadoSourceLoader() {
    }

    public static List<BookSource> loadBooks(AssetManager am) {
        String json = read(am, BOOKS_ASSET);
        if (json == null) {
            return new ArrayList<>();
        }
        return parseBooks(json);
    }

    public static List<ComicSource> loadComics(AssetManager am) {
        String json = read(am, COMICS_ASSET);
        if (json == null) {
            return new ArrayList<>();
        }
        return parseComics(json);
    }

    public static List<BookSource> parseBooks(String json) {
        List<BookSource> out = new ArrayList<>();
        JSONArray arr = parseArray(json);
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            try {
                out.add(new LegadoBookSource(o));
            } catch (Throwable e) {
                Log.w(TAG, "book source skipped: " + o.optString("bookSourceName"), e);
            }
        }
        return out;
    }

    public static List<ComicSource> parseComics(String json) {
        List<ComicSource> out = new ArrayList<>();
        JSONArray arr = parseArray(json);
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            try {
                out.add(new LegadoComicSource(o));
            } catch (Throwable e) {
                Log.w(TAG, "comic source skipped: " + o.optString("bookSourceName"), e);
            }
        }
        return out;
    }

    private static JSONArray parseArray(String json) {
        if (json == null) {
            return null;
        }
        try {
            return new JSONArray(json);
        } catch (Throwable e) {
            Log.w(TAG, "sources json broken", e);
            return null;
        }
    }

    private static String read(AssetManager am, String path) {
        if (am == null) {
            return null;
        }
        InputStream in = null;
        try {
            in = am.open(path);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            // 发布版 assets/sources 目录已不存在，启动时必然走到这里：
            // 属于正常状态，静默返回 null（调用方转空列表），不打日志不抛异常。
            return null;
        } catch (Throwable e) {
            Log.w(TAG, "read asset failed: " + path, e);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
