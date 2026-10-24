package com.wuji.reader.news;

import com.wuji.reader.core.SourceKit;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 新闻正文抓取器。
 *
 * 本项目没有 WebView，所以"点开新闻读正文"只能把 HTML 抓下来自己抽正文。
 *
 * 两层策略：
 *  1. **站点专用选择器**：对热榜里那 22 个主流站点，用它们真实正文容器的 CSS 选择器，
 *     命中就优先用（比通用算法准得多）。
 *  2. **通用密度算法兜底**：没有命中时，挑选"直接 <p> 子节点多、文本长、链接密度低"
 *     的容器作为正文。老阅读器同款思路。
 *
 * 另外会过滤版权声明、扫码关注这类版面垃圾行，并统一成"一行一段"的纯文本。
 */
public final class ArticleExtractor {

    public static class Article {
        /**
         * 三个文本字段必须有默认值。
         * BoardContent 的几条（zhihu 摘要 / 贴吧帖子 / 搜索结果）只填部分字段，
         * 2026-10-21 线上实测：如果 author 保持 null，ArticleActivity 拼
         * "作者：" 那一步直接 NPE，整个 App 进程崩回列表页。
         */
        public String title = "";
        public String author = "";
        public String url = "";
        public String text = "";
        /** 有效段落数，<2 基本可以判定抓空了 */
        public int paragraphs;
        /** 走的是专用选择器(1) 还是通用算法(0)，便于排查 */
        public int strategy;
        /**
         * 相关报道（抓不到正文时检索出来的结果），可在正文页点开继续读。
         * 默认空列表：绝大多数正文没有相关报道，调用方统一判 isEmpty() 即可，
         * 不用到处判 null。
         */
        public java.util.List<Link> links = new java.util.ArrayList<>();
    }

    /** 一条可点开的相关报道（标题 + 摘要 + 链接） */
    public static class Link {
        public String title = "";
        public String summary = "";
        public String url = "";

        public Link(String t, String s, String u) {
            title = (t == null) ? "" : t;
            summary = (s == null) ? "" : s;
            url = (u == null) ? "" : u;
        }
    }

    /** 站点(按 host 关键字) → 候选正文容器选择器，按优先级排列 */
    private static final Map<String, String[]> SITE_SELECTORS = new LinkedHashMap<>();

    static {
        SITE_SELECTORS.put("zhihu.com", new String[]{
                "div.RichContent-inner", "div.QuestionAnswer-content",
                "div.RichText.ztext", "div.Post-RichTextContainer"});
        SITE_SELECTORS.put("juejin.cn", new String[]{
                "article.article-content", "div.article-content", "main.article-content"});
        SITE_SELECTORS.put("36kr.com", new String[]{
                "div.article-content", "div.common-width", "article"});
        SITE_SELECTORS.put("sspai.com", new String[]{
                "div.article-detail", "div.content", "article"});
        SITE_SELECTORS.put("thepaper.cn", new String[]{
                "div.news_txt", "div.news_txt_cont", "div.news_txt_cont_1"});
        SITE_SELECTORS.put("ithome.com", new String[]{
                "div#content", "div.content", "div article"});
        SITE_SELECTORS.put("163.com", new String[]{
                "div.post_body", "div#content", "div.post_content"});
        SITE_SELECTORS.put("toutiao.com", new String[]{
                "div.article-content", "article.article-content", "article"});
        SITE_SELECTORS.put("bilibili.com", new String[]{
                "div.article-content", "div.bili-dyn-item", "div.op-today"});
        SITE_SELECTORS.put("jianshu.com", new String[]{
                "div.article", "article", "div#content"});
        SITE_SELECTORS.put("tieba.baidu.com", new String[]{
                "div.d_post_content", "div.d_post_content_first", "div.p_content"});
        SITE_SELECTORS.put("ngabbs", new String[]{"div#postcontent0", "div.postcontent"});
        SITE_SELECTORS.put("qq.com", new String[]{
                "div.content-article", "div.article-content", "div#ArticleContent"});
        SITE_SELECTORS.put("douyin.com", new String[]{"div.detail-desc", "div[data-e2e=desc]"});
    }

    /** 版面垃圾行：命中任一即丢弃 */
    private static final Pattern[] JUNK = new Pattern[]{
            Pattern.compile("版权|免责声明|转载|未经.*授权|不得.*转载"),
            Pattern.compile("扫码|二维码|长按|关注.*公众号|微信公众号"),
            Pattern.compile("责任编辑|美术编辑|文字编辑|出品人"),
            Pattern.compile("点击.*展开|展开阅读全文|阅读原文$"),
            Pattern.compile("^\\s*来源[：:]\\s*$"),
    };

    private ArticleExtractor() {
    }

    public static Article extract(String url) throws Exception {
        return extract(url, SourceKit.baseHeaders());
    }

    /** 带自定义请求头的重载：某些站（如 news.qq.com）桌面 UA 才给完整正文 */
    public static Article extract(String url, Map<String, String> headers) throws Exception {
        Article out = new Article();
        out.url = url;
        out.strategy = -1;

        Map<String, String> h = (headers == null || headers.isEmpty())
                ? SourceKit.baseHeaders() : headers;
        h.put("Referer", originOf(url));

        byte[] bytes = SourceKit.fetchBytes(url, h);
        Document doc = Jsoup.parse(new ByteArrayInputStream(bytes), null, url);

        // 版面噪音整体剪掉，减少正文污染
        doc.select("script, style, noscript, iframe, form, nav, footer, aside, " +
                "svg, canvas, button, input, figure figcaption").remove();
        doc.select("[class*=ad-], [id^=ad-], [class*=share], [class*=comment]").remove();

        out.title = pickTitle(doc);
        out.author = pickAuthor(doc);

        String text = "";
        for (Map.Entry<String, String[]> e : SITE_SELECTORS.entrySet()) {
            if (!url.contains(e.getKey())) {
                continue;
            }
            for (String sel : e.getValue()) {
                Element el = doc.selectFirst(sel);
                if (el == null) {
                    continue;
                }
                String t = paragraphsFrom(el);
                out.strategy = 1;
                if (t.length() > text.length()) {
                    text = t;
                }
                if (text.length() > 120) {
                    break;
                }
            }
            break;
        }

        if (text.length() < 120) {
            String generic = genericExtract(doc);
            out.strategy = 0;
            if (generic.length() > text.length()) {
                text = generic;
            }
        }

        List<String> clean = filterJunk(splitLines(text));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clean.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(clean.get(i));
        }
        out.text = sb.toString();
        out.paragraphs = clean.size();
        return out;
    }

    /** 正文密度算法：直接 <p> 子节点多、文本长、链接密度低的容器胜出 */
    private static String genericExtract(Document doc) {
        Element best = null;
        long bestScore = -1;

        for (Element el : doc.select("article, main, div, section")) {
            Elements ps = el.select("> p");
            if (ps.size() < 2) {
                continue;
            }
            long score = 0;
            for (Element p : ps) {
                String t = p.text();
                // 单段超过 200 字的让它在打分里饱和，避免一个超长段落独霸
                score += Math.min(t.length(), 200);
            }
            String allText = el.text();
            if (allText.length() > 0) {
                int linkLen = 0;
                for (Element a : el.select("a")) {
                    linkLen += a.text().length();
                }
                long linkRatio = (long) linkLen * 100 / allText.length();
                score = score * (100 - linkRatio) / 100;
            }
            if (score > bestScore) {
                bestScore = score;
                best = el;
            }
        }
        return best == null ? "" : paragraphsFrom(best);
    }

    private static String paragraphsFrom(Element root) {
        StringBuilder sb = new StringBuilder();
        for (Element child : root.children()) {
            String tag = child.tagName();
            String t;
            if (tag.equals("br")) {
                t = "";
            } else if (tag.equals("p") || tag.equals("div")) {
                t = child.text().trim();
            } else {
                t = child.ownText().trim();
            }
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t);
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? root.text().trim() : s;
    }

    private static String pickTitle(Document doc) {
        String t = SourceKit.firstNonEmpty(doc.title());
        if (!t.isEmpty()) {
            t = t.replaceAll("[-_|].{2,}$", "").trim();
        }
        Element h1 = doc.selectFirst("h1");
        if (h1 != null) {
            String h = h1.text().trim();
            if (h.length() > t.length()) {
                t = h;
            }
        }
        return t;
    }

    private static String pickAuthor(Document doc) {
        Element el = doc.selectFirst("[class*=author], [rel=author], meta[name=author]");
        if (el != null) {
            String v = el.hasAttr("content") ? el.attr("content") : el.text();
            if (!v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<String> filterJunk(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (line.length() < 2) {
                continue;
            }
            boolean junk = false;
            for (Pattern p : JUNK) {
                if (p.matcher(line).find()) {
                    junk = true;
                    break;
                }
            }
            if (!junk) {
                out.add(line);
            }
        }
        return out;
    }

    private static String originOf(String url) {
        try {
            URI u = URI.create(url);
            return u.getScheme() + "://" + u.getHost() + "/";
        } catch (Exception e) {
            return url;
        }
    }

    /** 兼容旧签名：只返回正文文本 */
    public static String extractText(String url) throws Exception {
        return extract(url).text;
    }

    /** 未使用但保留：调试用，返回容器 JSON */
    public static JSONObject debugJson(String url) throws Exception {
        Article a = extract(url);
        JSONObject o = new JSONObject();
        o.put("url", a.url);
        o.put("title", a.title);
        o.put("strategy", a.strategy);
        o.put("paragraphs", a.paragraphs);
        o.put("text", a.text);
        return o;
    }

    @SuppressWarnings("unused")
    private static List<String> allSelectorKeys() {
        return new ArrayList<>(Arrays.asList(SITE_SELECTORS.keySet().toArray(new String[0])));
    }
}
