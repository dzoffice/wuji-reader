package com.wuji.reader.legado;

import com.wuji.reader.core.SourceKit;
import com.wuji.reader.model.Chapter;
import com.wuji.reader.model.Item;
import com.wuji.reader.model.Result;
import com.wuji.reader.source.BookSource;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 用 Legado 规则驱动的文字源。所有解析都交给 RuleEngine，本类只负责
 * 把 ruleSearch / ruleExplore / ruleBookInfo / ruleToc / ruleContent
 * 串成 BookSource 的四个方法。
 */
public class LegadoBookSource implements BookSource {

    protected final JSONObject src;
    protected final RuleEngine engine;
    protected final String sourceUrl;
    protected final String sourceName;
    protected final String id;

    private List<String[]> explore;

    public LegadoBookSource(JSONObject source) {
        this.src = source;
        this.sourceUrl = RuleEngine.cleanBase(source.optString("bookSourceUrl"));
        this.sourceName = source.optString("bookSourceName", "未命名书源");
        String key = source.optString("bookSourceUrl", sourceName);
        this.id = "legado-" + Integer.toHexString(key.hashCode());
        this.engine = new RuleEngine(sourceUrl, null);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return sourceName;
    }

    @Override
    public List<String> types() {
        List<String> out = new ArrayList<>();
        for (String[] e : exploreEntries()) {
            if (!out.contains(e[0])) {
                out.add(e[0]);
            }
        }
        if (out.isEmpty()) {
            out.add("搜索找书");
        }
        return out;
    }

    @Override
    public Result<Item> getRecommendBooks(int pageNo, String type) throws Exception {
        if (pageNo < 1) {
            pageNo = 1;
        }
        List<String[]> entries = exploreEntries();
        if (entries.isEmpty()) {
            return new Result<>(new ArrayList<>(), pageNo, pageNo);
        }
        String[] target = entries.get(0);
        for (String[] e : entries) {
            if (e[0].equals(type)) {
                target = e;
                break;
            }
        }
        String url = target[1].replace("{{page}}", String.valueOf(pageNo));
        if (!url.startsWith("http")) {
            url = SourceKit.urlJoin(sourceUrl, url);
        }
        RuleEngine.HttpRequest req = new RuleEngine.HttpRequest(url);
        Object page = fetch(req);
        JSONObject rule = optObject("ruleExplore");
        if (rule == null || rule.length() == 0) {
            rule = optObject("ruleSearch");
        }
        return parseList(page, rule, pageNo, url);
    }

    @Override
    public Result<Item> search(String keyword, int pageNo) throws Exception {
        if (pageNo < 1) {
            pageNo = 1;
        }
        RuleEngine.HttpRequest req = engine.parseUrl(src.optString("searchUrl"), keyword, pageNo);
        if (req == null || req.url == null || req.url.isEmpty()) {
            return new Result<>(new ArrayList<>(), pageNo, pageNo);
        }
        Object page = fetch(req);
        Result<Item> r = parseList(page, optObject("ruleSearch"), pageNo, req.url);
        return r;
    }

    @Override
    public Item getBookDetail(Item item, int pageNo) throws Exception {
        if (item == null) {
            throw new Exception("item 不能为空");
        }
        String detailUrl = firstNonEmpty(item.url, sourceUrl);
        if (detailUrl.isEmpty()) {
            throw new Exception("缺少详情页地址");
        }
        Object detail = fetch(new RuleEngine.HttpRequest(detailUrl));
        JSONObject info = optObject("ruleBookInfo");
        String name = "";
        String author = "";
        String cover = "";
        String intro = "";
        String kind = "";
        String lastChapter = "";
        String tocRaw = "";
        if (info != null) {
            name = safe(engine.string(detail, info.optString("name")));
            author = safe(engine.string(detail, info.optString("author")));
            cover = safe(engine.string(detail, info.optString("coverUrl")));
            intro = safe(engine.string(detail, info.optString("intro")));
            kind = safe(engine.string(detail, info.optString("kind")));
            lastChapter = safe(engine.string(detail, info.optString("lastChapter")));
            tocRaw = safe(engine.string(detail, info.optString("tocUrl")));
        }
        if (!name.isEmpty()) {
            item.title = name;
        }
        if (!author.isEmpty()) {
            item.author = author;
        }
        if (!cover.isEmpty()) {
            item.cover = abs(detailUrl, cover);
        }
        if (!intro.isEmpty()) {
            item.desc = intro;
        } else if (item.desc == null || item.desc.isEmpty()) {
            item.desc = (kind + " " + lastChapter).trim();
        }
        if (item.desc == null) {
            item.desc = "";
        }

        String tocUrl = urlLike(tocRaw) ? abs(detailUrl, tocRaw) : "";
        if (tocUrl.isEmpty()) {
            tocUrl = detailUrl;
        }
        Object tocRoot = tocUrl.equals(detailUrl)
                ? detail : fetch(new RuleEngine.HttpRequest(tocUrl));
        Set<String> visited = new LinkedHashSet<>();
        visited.add(tocUrl);
        List<Chapter> chapters = parseToc(tocRoot, tocUrl, visited);
        item.chapters = chapters;
        return item;
    }

    @Override
    public String getContent(Item item, Chapter chapter) throws Exception {
        if (chapter == null) {
            throw new Exception("chapter 不能为空");
        }
        String url = firstNonEmpty(chapter.url, chapter.id);
        if (url.isEmpty()) {
            throw new Exception("缺少章节地址");
        }
        Object root = fetch(new RuleEngine.HttpRequest(url));
        JSONObject rc = optObject("ruleContent");
        String rule = rc == null ? "" : rc.optString("content");
        if (rule.contains("@js:") || rule.contains("<js>")) {
            return "";
        }
        List<String> parts = engine.strings(root, rule);
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(p);
        }
        String content = sb.toString();
        if (content.isEmpty()) {
            return "";
        }
        if (content.indexOf('<') >= 0) {
            content = Html.toText(content);
        }
        String replace = rc == null ? "" : rc.optString("replaceRegex");
        if (!replace.isEmpty()) {
            content = applyReplace(content, replace);
        }
        // 下一页正文（最多 5 页）
        if (rc != null) {
            String nextRule = rc.optString("nextContentUrl");
            String cursor = url;
            Object curRoot = root;
            int guard = 0;
            while (nextRule != null && !nextRule.isEmpty() && guard++ < 5) {
                String next = engine.string(curRoot, nextRule);
                if (next.isEmpty()) {
                    break;
                }
                next = abs(cursor, next);
                if (next.isEmpty() || next.equals(cursor)) {
                    break;
                }
                Object nr = fetch(new RuleEngine.HttpRequest(next));
                List<String> np = engine.strings(nr, rule);
                StringBuilder nb = new StringBuilder(content);
                for (String p : np) {
                    if (p != null && !p.isEmpty()) {
                        nb.append('\n').append(p.indexOf('<') >= 0 ? Html.toText(p) : p);
                    }
                }
                content = nb.toString();
                cursor = next;
                curRoot = nr;
            }
        }
        return content.trim();
    }

    // ------------------------------------------------------------------
    // 列表 / 目录
    // ------------------------------------------------------------------

    protected Result<Item> parseList(Object page, JSONObject rule, int pageNo, String pageUrl) {
        List<Item> list = new ArrayList<>();
        if (page != null && rule != null) {
            List<Object> nodes = engine.nodes(page, rule.optString("bookList"));
            for (Object node : nodes) {
                String name = safe(engine.string(node, rule.optString("name")));
                String bookUrl = fieldUrl(node, rule.optString("bookUrl"), pageUrl);
                if (name.isEmpty() && bookUrl.isEmpty()) {
                    continue;
                }
                Item it = new Item();
                it.sourceId = id;
                it.title = name;
                it.author = safe(engine.string(node, rule.optString("author")));
                it.cover = abs(pageUrl, engine.string(node, rule.optString("coverUrl")));
                it.desc = safe(engine.string(node, rule.optString("intro")));
                it.url = bookUrl;
                it.id = bookUrl.isEmpty() ? name : bookUrl;
                if (it.desc.isEmpty()) {
                    String kind = safe(engine.string(node, rule.optString("kind")));
                    if (!kind.isEmpty()) {
                        it.desc = kind;
                    }
                }
                list.add(it);
            }
        }
        int total = pageNo;
        if (!list.isEmpty()) {
            total = pageNo + 1;
        }
        return new Result<>(list, pageNo, total);
    }

    private List<Chapter> parseToc(Object root, String tocUrl, Set<String> visited) {
        List<Chapter> out = new ArrayList<>();
        if (visited.size() > 30) {
            return out;
        }
        JSONObject rt = optObject("ruleToc");
        if (rt == null) {
            return out;
        }
        List<Object> nodes = engine.nodes(root, rt.optString("chapterList"));
        String nameRule = rt.optString("chapterName");
        String urlRule = rt.optString("chapterUrl");
        for (Object node : nodes) {
            String title = safe(engine.string(node, nameRule));
            String href = safe(engine.string(node, urlRule));
            if (href.isEmpty() && node instanceof Element) {
                href = ((Element) node).attr("href");
            }
            if (title.isEmpty() && node instanceof Element) {
                title = ((Element) node).text().trim();
            }
            if (title.isEmpty() || href.isEmpty()) {
                continue;
            }
            if (href.startsWith("javascript:") || href.startsWith("#")) {
                continue;
            }
            String abs = abs(tocUrl, href);
            out.add(new Chapter(abs, title, abs));
        }

        String nextRule = rt.optString("nextTocUrl");
        if (!nextRule.isEmpty()) {
            for (String raw : engine.strings(root, nextRule)) {
                if (visited.size() > 30) {
                    break;
                }
                String next = abs(tocUrl, raw);
                if (next.isEmpty() || next.equals(tocUrl) || visited.contains(next)) {
                    continue;
                }
                visited.add(next);
                try {
                    Object nr = fetch(new RuleEngine.HttpRequest(next));
                    out.addAll(parseToc(nr, next, visited));
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    protected Object fetch(RuleEngine.HttpRequest req) throws Exception {
        return engine.fetch(req).page();
    }

    protected JSONObject optObject(String key) {
        JSONObject o = src.optJSONObject(key);
        return o == null ? new JSONObject() : o;
    }

    private String fieldUrl(Object node, String rule, String pageUrl) {
        if (rule == null || rule.isEmpty()) {
            return "";
        }
        String raw;
        if (rule.startsWith("http://") || rule.startsWith("https://")
                || (rule.startsWith("/") && !rule.contains("@"))) {
            raw = engine.interpolatePublic(rule, node);
        } else {
            raw = engine.string(node, rule);
        }
        return abs(pageUrl, raw);
    }

    private static String applyReplace(String text, String rule) {
        String r = rule.trim();
        while (r.startsWith("#")) {
            r = r.substring(1);
        }
        List<String> parts = RuleEngine.splitTop(r, "##");
        String regex = parts.isEmpty() ? "" : parts.get(0);
        String repl = parts.size() > 1 ? parts.get(1) : "";
        if (regex.isEmpty()) {
            return text;
        }
        try {
            return java.util.regex.Pattern.compile(regex).matcher(text).replaceAll(repl);
        } catch (Exception e) {
            return text;
        }
    }

    protected String abs(String base, String raw) {
        if (raw == null) {
            return "";
        }
        String r = raw.trim();
        if (r.isEmpty()) {
            return "";
        }
        if (r.startsWith("http://") || r.startsWith("https://")) {
            return r;
        }
        return SourceKit.urlJoin(base, r);
    }

    private List<String[]> exploreEntries() {
        if (explore != null) {
            return explore;
        }
        explore = new ArrayList<>();
        String raw = src.optString("exploreUrl");
        if (raw == null || raw.trim().isEmpty()) {
            return explore;
        }
        String s = raw.trim();
        if (s.startsWith("[")) {
            try {
                JSONArray arr = new JSONArray(s);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    String title = o.optString("title").trim();
                    String url = o.optString("url").trim();
                    if (title.isEmpty() || url.isEmpty()) {
                        continue;
                    }
                    explore.add(new String[]{title, url});
                }
                return explore;
            } catch (Exception ignored) {
            }
        }
        for (String line : s.split("\\n")) {
            for (String piece : line.split("&&")) {
                String l = piece.trim();
                if (l.isEmpty()) {
                    continue;
                }
                int p = l.indexOf("::");
                if (p <= 0) {
                    continue;
                }
                String title = l.substring(0, p).trim();
                String url = l.substring(p + 2).trim().replace("<", "").replace(">", "");
                if (title.isEmpty() || url.isEmpty()) {
                    continue;
                }
                explore.add(new String[]{title, url});
            }
        }
        return explore;
    }

    private static boolean urlLike(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return false;
        }
        if (t.startsWith("http://") || t.startsWith("https://")
                || t.startsWith("/") || t.startsWith("?") || t.startsWith("./")) {
            return true;
        }
        return t.contains(".html") || t.contains(".htm") || t.contains(".php");
    }

    protected static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        return b == null ? "" : b.trim();
    }

    protected static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
