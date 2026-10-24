package com.wuji.reader.legado;

import com.wuji.reader.model.Chapter;
import com.wuji.reader.model.Item;
import com.wuji.reader.model.Result;
import com.wuji.reader.source.ComicSource;

import org.json.JSONObject;
import org.jsoup.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用 Legado 规则驱动的漫画源。
 *
 * 漫画正文规则最终都会得到一段包含 &lt;img&gt; 的 HTML（imageStyle=FULL/TEXT），
 * 这里只负责把 img 的 src 抽出来，并剥掉 Legado 的图片级 header 后缀：
 *   https://x/1.jpg,{"headers":{"referer":"..."}}
 */
public class LegadoComicSource implements ComicSource {

    private static final Pattern IMG = Pattern.compile(
            "<img[^>]*?\\s(?:src|data-src|data-original)\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    private final LegadoBookSource core;

    public LegadoComicSource(JSONObject source) {
        this.core = new LegadoBookSource(source);
    }

    @Override
    public String getId() {
        return core.getId();
    }

    @Override
    public String getName() {
        return core.getName();
    }

    @Override
    public List<String> types() {
        return core.types();
    }

    @Override
    public Result<Item> getRecommendComics(int pageNo, String type) throws Exception {
        return core.getRecommendBooks(pageNo, type);
    }

    @Override
    public Result<Item> search(String keyword, int pageNo) throws Exception {
        return core.search(keyword, pageNo);
    }

    @Override
    public Item getComicDetail(Item item, int pageNo) throws Exception {
        return core.getBookDetail(item, pageNo);
    }

    @Override
    public List<String> getContent(Item item, Chapter chapter) throws Exception {
        if (chapter == null) {
            throw new Exception("chapter 不能为空");
        }
        String url = LegadoBookSource.firstNonEmpty(chapter.url, chapter.id);
        if (url.isEmpty()) {
            throw new Exception("缺少章节地址");
        }
        Object root = core.fetch(new RuleEngine.HttpRequest(url));
        JSONObject rc = core.optObject("ruleContent");
        String rule = rc.optString("content");
        List<String> parts = core.engine.strings(root, rule);
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(p);
            }
        }
        String html = sb.toString();
        String replace = rc.optString("replaceRegex");
        if (!replace.isEmpty() && html.contains("<")) {
            html = applyReplace(html, replace);
        }

        List<String> images = new ArrayList<>();
        Matcher m = IMG.matcher(html);
        while (m.find()) {
            String u = cleanImage(m.group(1));
            if (!u.isEmpty()) {
                images.add(core.abs(url, u));
            }
        }
        if (images.isEmpty()) {
            // 少数源直接返回一行一个图片地址
            for (String line : html.split("[\\r\\n]+")) {
                String t = line.trim();
                if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("//")) {
                    images.add(core.abs(url, cleanImage(t)));
                }
            }
        }
        return images;
    }

    /** 剥掉 Legado 的 ,{"headers":...} 后缀，并还原 &amp; */
    public static String cleanImage(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        int i = s.indexOf(",{");
        if (i < 0) {
            i = s.indexOf(",\"headers\"");
        }
        if (i > 0) {
            s = s.substring(0, i);
        }
        s = Parser.unescapeEntities(s, false);
        return s.trim();
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
            return Pattern.compile(regex).matcher(text).replaceAll(repl);
        } catch (Exception e) {
            return text;
        }
    }
}
