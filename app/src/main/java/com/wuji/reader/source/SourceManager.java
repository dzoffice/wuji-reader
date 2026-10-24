package com.wuji.reader.source;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.wuji.reader.legado.LegadoBookSource;
import com.wuji.reader.legado.LegadoComicSource;
import com.wuji.reader.news.NewsRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 源管理器：在 SourceRegistry（内置源）之上叠加
 *   启用 / 停用、排序、自定义 Legado 源（增删）；
 *   新闻榜同理（启用过滤 + 排序，无自定义）。
 *
 * 持久化用独立的 SharedPreferences("source_mgr")，
 * 不占用 store/Prefs 的 "reader"（阅读进度/字号）。
 *
 * 读写都发生在主线程（App.onCreate / 设置页 / 列表页），
 * 内部统一加锁防御误用；SharedPreferences 首次访问后由系统缓存，
 * 无 Context 参数的静态方法直接读内存态。
 */
public final class SourceManager {

    private static final String TAG = "SourceManager";
    private static final String SP = "source_mgr";

    private static final String K_BOOK_ORDER = "book_order";
    private static final String K_BOOK_OFF = "book_off";
    private static final String K_CUSTOM_BOOKS = "custom_books";
    private static final String K_COMIC_ORDER = "comic_order";
    private static final String K_COMIC_OFF = "comic_off";
    private static final String K_CUSTOM_COMICS = "custom_comics";
    private static final String K_NEWS_ORDER = "news_order";
    private static final String K_NEWS_OFF = "news_off";

    private static final Object LOCK = new Object();

    private static SharedPreferences sp;
    private static boolean inited = false;

    /** 小说全量（内置 + 自定义），顺序即用户排序 */
    private static final List<BookSource> BOOK_ALL = new ArrayList<>();
    /** 漫画全量（内置 + 自定义） */
    private static final List<ComicSource> COMIC_ALL = new ArrayList<>();
    /** 新闻榜全量顺序（含停用） */
    private static final List<String> NEWS_ALL = new ArrayList<>();

    private static final LinkedHashSet<String> BOOK_OFF = new LinkedHashSet<>();
    private static final LinkedHashSet<String> COMIC_OFF = new LinkedHashSet<>();
    private static final LinkedHashSet<String> NEWS_OFF = new LinkedHashSet<>();

    /** 自定义源对象与原始 JSON 平行存放，删除/持久化都用它 */
    private static final List<BookSource> CUSTOM_BOOKS = new ArrayList<>();
    private static final List<JSONObject> CUSTOM_BOOK_JSON = new ArrayList<>();
    private static final List<ComicSource> CUSTOM_COMICS = new ArrayList<>();
    private static final List<JSONObject> CUSTOM_COMIC_JSON = new ArrayList<>();

    private SourceManager() {
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    /** 幂等；由 App.onCreate 调用。内部兜底触发 SourceRegistry.init。 */
    public static void init(Context ctx) {
        if (ctx == null) {
            return;
        }
        Context app = ctx.getApplicationContext();
        synchronized (LOCK) {
            if (inited) {
                return;
            }
            // 保证 Legado 内置源已装进 SourceRegistry（重复调用幂等）
            SourceRegistry.init(app);
            sp = app.getSharedPreferences(SP, Context.MODE_PRIVATE);
            loadState();
            inited = true;
        }
    }

    /** 从 prefs 恢复排序/停用/自定义源；order 为空时按内置顺序 + 自定义追加 */
    private static void loadState() {
        if (sp == null) {
            return;
        }
        BOOK_ALL.clear();
        COMIC_ALL.clear();
        NEWS_ALL.clear();
        BOOK_OFF.clear();
        COMIC_OFF.clear();
        NEWS_OFF.clear();
        CUSTOM_BOOKS.clear();
        CUSTOM_BOOK_JSON.clear();
        CUSTOM_COMICS.clear();
        CUSTOM_COMIC_JSON.clear();

        // 自定义源（坏条目直接丢弃，下次 persist 时清理掉）
        for (JSONObject o : decodeJsonArray(sp.getString(K_CUSTOM_BOOKS, "[]"))) {
            try {
                CUSTOM_BOOKS.add(new LegadoBookSource(o));
                CUSTOM_BOOK_JSON.add(o);
            } catch (Throwable t) {
                Log.w(TAG, "custom book source skipped", t);
            }
        }
        for (JSONObject o : decodeJsonArray(sp.getString(K_CUSTOM_COMICS, "[]"))) {
            try {
                CUSTOM_COMICS.add(new LegadoComicSource(o));
                CUSTOM_COMIC_JSON.add(o);
            } catch (Throwable t) {
                Log.w(TAG, "custom comic source skipped", t);
            }
        }

        // 自然顺序 = 内置 + 自定义追加
        List<BookSource> books = new ArrayList<>(SourceRegistry.BOOKS);
        books.addAll(CUSTOM_BOOKS);
        List<ComicSource> comics = new ArrayList<>(SourceRegistry.COMICS);
        comics.addAll(CUSTOM_COMICS);

        BOOK_ALL.addAll(orderBy(decodeList(sp.getString(K_BOOK_ORDER, "")), books, BookSource::getId));
        COMIC_ALL.addAll(orderBy(decodeList(sp.getString(K_COMIC_ORDER, "")), comics, ComicSource::getId));

        List<String> news = new ArrayList<>(NewsRegistry.types());
        NEWS_ALL.addAll(orderBy(decodeList(sp.getString(K_NEWS_ORDER, "")), news, t -> t));

        BOOK_OFF.addAll(decodeList(sp.getString(K_BOOK_OFF, "")));
        COMIC_OFF.addAll(decodeList(sp.getString(K_COMIC_OFF, "")));
        NEWS_OFF.addAll(decodeList(sp.getString(K_NEWS_OFF, "")));

        // 规范化落盘（首启写入默认值；也清掉失效 id）
        persist();
    }

    /**
     * 取 id 的取值器。
     * 刻意不用 java.util.function.Function —— 它是 API 24+，本工程 minSdk 23，
     * 用它会让 orderBy 在 Android 6 上抛 NoClassDefFoundError（App.onCreate 启动即调用）。
     */
    private interface IdFn<T> {
        String apply(T t);
    }

    /** 按 saved 排序自然集合：saved 中存在的在前，新出现的追加末尾 */
    private static <T> List<T> orderBy(List<String> saved, List<T> natural,
                                       IdFn<T> idFn) {
        List<T> out = new ArrayList<>();
        if (saved != null && !saved.isEmpty()) {
            for (String id : saved) {
                for (T s : natural) {
                    if (id.equals(idFn.apply(s)) && !out.contains(s)) {
                        out.add(s);
                        break;
                    }
                }
            }
        }
        for (T s : natural) {
            if (!out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static int indexOfBook(List<BookSource> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfComic(List<ComicSource> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 生效集合
    // ------------------------------------------------------------------

    /** 生效小说源（启用 + 排序 + 自定义） */
    public static List<BookSource> books() {
        synchronized (LOCK) {
            List<BookSource> out = new ArrayList<>();
            for (BookSource s : BOOK_ALL) {
                if (!BOOK_OFF.contains(s.getId())) {
                    out.add(s);
                }
            }
            return out;
        }
    }

    /** 生效漫画源（启用 + 排序 + 自定义） */
    public static List<ComicSource> comics() {
        synchronized (LOCK) {
            List<ComicSource> out = new ArrayList<>();
            for (ComicSource s : COMIC_ALL) {
                if (!COMIC_OFF.contains(s.getId())) {
                    out.add(s);
                }
            }
            return out;
        }
    }

    /** 小说全量（含停用），管理页用 */
    public static List<BookSource> allBooks() {
        synchronized (LOCK) {
            return new ArrayList<>(BOOK_ALL);
        }
    }

    /** 漫画全量（含停用），管理页用 */
    public static List<ComicSource> allComics() {
        synchronized (LOCK) {
            return new ArrayList<>(COMIC_ALL);
        }
    }

    /** 新闻榜全量顺序（含停用），管理页用 */
    public static List<String> allNewsTypes() {
        synchronized (LOCK) {
            return new ArrayList<>(NEWS_ALL);
        }
    }

    /** 生效集合内按 id 找小说源；停用/不存在返回 null */
    public static BookSource bookById(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        synchronized (LOCK) {
            for (BookSource s : BOOK_ALL) {
                if (id.equals(s.getId()) && !BOOK_OFF.contains(id)) {
                    return s;
                }
            }
            return null;
        }
    }

    /** 生效集合内按 id 找漫画源；停用/不存在返回 null */
    public static ComicSource comicById(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        synchronized (LOCK) {
            for (ComicSource s : COMIC_ALL) {
                if (id.equals(s.getId()) && !COMIC_OFF.contains(id)) {
                    return s;
                }
            }
            return null;
        }
    }

    /** 默认 true（未设置即启用） */
    public static boolean isEnabled(String id) {
        if (id == null) {
            return true;
        }
        synchronized (LOCK) {
            return !BOOK_OFF.contains(id) && !COMIC_OFF.contains(id);
        }
    }

    /** 该小说 id 是否为用户手动添加的自定义源 */
    public static boolean isCustomBook(String id) {
        synchronized (LOCK) {
            for (BookSource s : CUSTOM_BOOKS) {
                if (id != null && id.equals(s.getId())) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 该漫画 id 是否为用户手动添加的自定义源 */
    public static boolean isCustomComic(String id) {
        synchronized (LOCK) {
            for (ComicSource s : CUSTOM_COMICS) {
                if (id != null && id.equals(s.getId())) {
                    return true;
                }
            }
            return false;
        }
    }

    public static void setEnabled(String id, boolean on) {
        if (id == null || id.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            if (on) {
                BOOK_OFF.remove(id);
                COMIC_OFF.remove(id);
            } else {
                if (indexOfBook(BOOK_ALL, id) >= 0) {
                    BOOK_OFF.add(id);
                }
                if (indexOfComic(COMIC_ALL, id) >= 0) {
                    COMIC_OFF.add(id);
                }
            }
            persist();
        }
    }

    /** 上移(-1)/下移(+1)；越界或找不到时不动 */
    public static void move(String id, int delta) {
        if (id == null || delta == 0) {
            return;
        }
        synchronized (LOCK) {
            int i = indexOfBook(BOOK_ALL, id);
            if (i >= 0) {
                int to = i + delta;
                if (to >= 0 && to < BOOK_ALL.size()) {
                    Collections.swap(BOOK_ALL, i, to);
                    persist();
                }
                return;
            }
            i = indexOfComic(COMIC_ALL, id);
            if (i >= 0) {
                int to = i + delta;
                if (to >= 0 && to < COMIC_ALL.size()) {
                    Collections.swap(COMIC_ALL, i, to);
                    persist();
                }
            }
        }
    }

    /** 一键重置：清掉排序/停用/自定义，回到内置默认 */
    public static void reset() {
        synchronized (LOCK) {
            if (sp != null) {
                sp.edit().clear().apply();
            }
            loadState();
        }
    }

    // ------------------------------------------------------------------
    // 自定义源
    // ------------------------------------------------------------------

    /**
     * 添加 Legado 书源 JSON（单对象或数组），返回可读结果：
     *   "成功导入 N 个源" / "失败：JSON 格式不正确" / "失败：与现有源 id 重复" / "失败：内容为空"
     * 解析失败、或 id 与已有源重复时不做任何修改（整批都不加）。
     */
    public static String addCustomBookReport(String json) {
        List<JSONObject> objs = decodeJsonArrayOrObject(json);
        if (objs.isEmpty()) {
            return "失败：JSON 格式不正确";
        }
        List<BookSource> adding = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JSONObject o : objs) {
            BookSource s;
            try {
                s = new LegadoBookSource(o);
            } catch (Throwable t) {
                Log.w(TAG, "addCustomBook parse failed", t);
                return "失败：JSON 格式不正确";
            }
            String id = s.getId();
            if (id == null || id.isEmpty() || seen.contains(id)
                    || indexOfBook(BOOK_ALL, id) >= 0) {
                return "失败：与现有源 id 重复";
            }
            seen.add(id);
            adding.add(s);
        }
        synchronized (LOCK) {
            for (int i = 0; i < adding.size(); i++) {
                CUSTOM_BOOKS.add(adding.get(i));
                CUSTOM_BOOK_JSON.add(objs.get(i));
                BOOK_ALL.add(adding.get(i));
            }
            persist();
        }
        return "成功导入 " + adding.size() + " 个源";
    }

    /** 兼容旧调用：等价于 addCustomBookReport(json).startsWith("成功") */
    public static boolean addCustomBook(String json) {
        return addCustomBookReport(json).startsWith("成功");
    }

    /**
     * 添加 Legado 漫画源 JSON（单对象或数组），返回格式同 addCustomBookReport。
     */
    public static String addCustomComicReport(String json) {
        List<JSONObject> objs = decodeJsonArrayOrObject(json);
        if (objs.isEmpty()) {
            return "失败：JSON 格式不正确";
        }
        List<ComicSource> adding = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JSONObject o : objs) {
            ComicSource s;
            try {
                s = new LegadoComicSource(o);
            } catch (Throwable t) {
                Log.w(TAG, "addCustomComic parse failed", t);
                return "失败：JSON 格式不正确";
            }
            String id = s.getId();
            if (id == null || id.isEmpty() || seen.contains(id)
                    || indexOfComic(COMIC_ALL, id) >= 0) {
                return "失败：与现有源 id 重复";
            }
            seen.add(id);
            adding.add(s);
        }
        synchronized (LOCK) {
            for (int i = 0; i < adding.size(); i++) {
                CUSTOM_COMICS.add(adding.get(i));
                CUSTOM_COMIC_JSON.add(objs.get(i));
                COMIC_ALL.add(adding.get(i));
            }
            persist();
        }
        return "成功导入 " + adding.size() + " 个源";
    }

    /** 兼容旧调用：等价于 addCustomComicReport(json).startsWith("成功") */
    public static boolean addCustomComic(String json) {
        return addCustomComicReport(json).startsWith("成功");
    }

    /**
     * 导出全部自定义源为 {"books":[...],"comics":[...]} 合辑 JSON。
     * 发布版无内置源，导出的就是用户导入过的全部源。
     * 没有任何自定义源时返回 null（调用方提示「还没有导入任何源」）。
     */
    public static String exportCustomSourcesJson() {
        synchronized (LOCK) {
            if (CUSTOM_BOOK_JSON.isEmpty() && CUSTOM_COMIC_JSON.isEmpty()) {
                return null;
            }
            try {
                JSONObject root = new JSONObject();
                JSONArray books = new JSONArray();
                for (JSONObject o : CUSTOM_BOOK_JSON) {
                    books.put(o);
                }
                JSONArray comics = new JSONArray();
                for (JSONObject o : CUSTOM_COMIC_JSON) {
                    comics.put(o);
                }
                root.put("books", books);
                root.put("comics", comics);
                return root.toString();
            } catch (Throwable t) {
                Log.w(TAG, "export sources json failed", t);
                return null;
            }
        }
    }

    /** 仅自定义源可删；内置源或找不到返回 false */
    public static boolean removeCustom(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            for (int i = 0; i < CUSTOM_BOOKS.size(); i++) {
                if (id.equals(CUSTOM_BOOKS.get(i).getId())) {
                    CUSTOM_BOOKS.remove(i);
                    CUSTOM_BOOK_JSON.remove(i);
                    int j = indexOfBook(BOOK_ALL, id);
                    if (j >= 0) {
                        BOOK_ALL.remove(j);
                    }
                    BOOK_OFF.remove(id);
                    persist();
                    return true;
                }
            }
            for (int i = 0; i < CUSTOM_COMICS.size(); i++) {
                if (id.equals(CUSTOM_COMICS.get(i).getId())) {
                    CUSTOM_COMICS.remove(i);
                    CUSTOM_COMIC_JSON.remove(i);
                    int j = indexOfComic(COMIC_ALL, id);
                    if (j >= 0) {
                        COMIC_ALL.remove(j);
                    }
                    COMIC_OFF.remove(id);
                    persist();
                    return true;
                }
            }
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 新闻榜
    // ------------------------------------------------------------------

    /** 生效新闻榜（启用 + 排序，默认 NewsRegistry.types() 顺序） */
    public static List<String> newsTypes() {
        synchronized (LOCK) {
            List<String> out = new ArrayList<>();
            for (String t : NEWS_ALL) {
                if (!NEWS_OFF.contains(t)) {
                    out.add(t);
                }
            }
            return out;
        }
    }

    public static boolean isNewsEnabled(String type) {
        if (type == null) {
            return true;
        }
        synchronized (LOCK) {
            return !NEWS_OFF.contains(type);
        }
    }

    public static void setNewsEnabled(String type, boolean on) {
        if (type == null) {
            return;
        }
        synchronized (LOCK) {
            if (on) {
                NEWS_OFF.remove(type);
            } else {
                NEWS_OFF.add(type);
            }
            persist();
        }
    }

    /** 新闻榜上移(-1)/下移(+1)；越界不动 */
    public static void moveNews(String type, int delta) {
        if (type == null || delta == 0) {
            return;
        }
        synchronized (LOCK) {
            int i = NEWS_ALL.indexOf(type);
            if (i < 0) {
                return;
            }
            int to = i + delta;
            if (to < 0 || to >= NEWS_ALL.size()) {
                return;
            }
            Collections.swap(NEWS_ALL, i, to);
            persist();
        }
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    private static void persist() {
        if (sp == null) {
            return;
        }
        SharedPreferences.Editor e = sp.edit();
        e.putString(K_BOOK_ORDER, encodeList(idsOfBooks(BOOK_ALL)));
        e.putString(K_BOOK_OFF, encodeList(new ArrayList<>(BOOK_OFF)));
        e.putString(K_CUSTOM_BOOKS, encodeJsonArray(CUSTOM_BOOK_JSON));
        e.putString(K_COMIC_ORDER, encodeList(idsOfComics(COMIC_ALL)));
        e.putString(K_COMIC_OFF, encodeList(new ArrayList<>(COMIC_OFF)));
        e.putString(K_CUSTOM_COMICS, encodeJsonArray(CUSTOM_COMIC_JSON));
        e.putString(K_NEWS_ORDER, encodeList(NEWS_ALL));
        e.putString(K_NEWS_OFF, encodeList(new ArrayList<>(NEWS_OFF)));
        e.apply();
    }

    private static List<String> idsOfBooks(List<BookSource> list) {
        List<String> out = new ArrayList<>();
        for (BookSource s : list) {
            out.add(s.getId());
        }
        return out;
    }

    private static List<String> idsOfComics(List<ComicSource> list) {
        List<String> out = new ArrayList<>();
        for (ComicSource s : list) {
            out.add(s.getId());
        }
        return out;
    }

    private static String encodeList(List<String> v) {
        JSONArray a = new JSONArray();
        if (v != null) {
            for (String s : v) {
                a.put(s);
            }
        }
        return a.toString();
    }

    private static String encodeJsonArray(List<JSONObject> v) {
        JSONArray a = new JSONArray();
        if (v != null) {
            for (JSONObject o : v) {
                a.put(o);
            }
        }
        return a.toString();
    }

    private static List<String> decodeList(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            return out;
        }
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                String t = a.optString(i);
                if (t != null && !t.isEmpty()) {
                    out.add(t);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static List<JSONObject> decodeJsonArray(String s) {
        List<JSONObject> out = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            return out;
        }
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) {
                    out.add(o);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 单对象或数组都能吃；都不是返回空列表 */
    private static List<JSONObject> decodeJsonArrayOrObject(String s) {
        List<JSONObject> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return out;
        }
        try {
            JSONArray a = new JSONArray(t);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) {
                    out.add(o);
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        } catch (Throwable notArray) {
            // 落到单对象分支
        }
        try {
            out.add(new JSONObject(t));
        } catch (Throwable e) {
            Log.w(TAG, "json not an object or array", e);
        }
        return out;
    }
}
