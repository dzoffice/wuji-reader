package com.wuji.reader.news;

import com.wuji.reader.core.SourceKit;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 热榜正文抓取策略（按榜单分治）。
 *
 * 背景：本项目没有 WebView，"读取正文"只能自己抓 HTML 抽文本。实测五个榜
 * 的直连通道全部走不通（2026-10-20 curl + 线上页面核实）：
 *   - 知乎  https://www.zhihu.com/question/{id}  → 403（桌面/移动 UA 均拒）
 *   - 微博  s.weibo.com/weibo?q=...              → 302 跳 Sina Visitor System
 *   - 抖音  https://www.douyin.com/hot/{id}      → 200 但是 JS 挑战壳（body 为空）
 *   - 百度  https://www.baidu.com/s?wd=...       → 302 跳百度安全验证（验证码）
 *   - 贴吧  热议页是 SSR，能抓，但 URL 里带 &amp; 转义、选择器要单独写
 *
 * 因此按榜分流（都在线上实测通过）：
 *   tieba   → 直抓热议 SSR 页：话题简介 + 前 N 条帖子全文
 *   baidu   → 百度热搜条目：cn.bing.com 检索该热词的相关报道
 *             （m.baidu.com 于 2026-10-21 实测无论真实网络/沙箱 IP 均
 *               返回「百度安全验证」壳页，已废弃该通道）
 *   zhihu   → desc 字段自带被转新闻的原文链接（如 news.qq.com），抓原文；
 *              抓不到就退回展示 desc 摘要（zhihu 热榜条目自带的正文文本）
 *   weibo   → 站内全反爬（m 站 API/话题页均 302），改用 Bing 检索相关报道
 *   douyin  → 热点页是 JS 壳，同样走 Bing 检索相关报道
 *   others  → ArticleExtractor 通用通道（专用选择器 + 密度算法）
 *
 * 任何一路失败都抛异常，由 ArticleActivity 翻译成用户可读的提示，
 * 绝不静默返回空文本。
 */
public final class BoardContent {

    /** 移动搜索实测可用的 UA（百度按 UA 分流，这个稳） */
    private static final String MBAIDU_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) " +
                    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1";

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 每个页面最多抽取的结果/帖子条数（墨水屏一屏放不下，够读就行） */
    private static final int MAX_BLOCKS = 10;

    /** desc 开头的原文链接（聚合 API 的 zhihu 条目形如 "https://news.qq.com/... 正文..."） */
    private static final Pattern LEAD_URL = Pattern.compile("^\\s*(https?://\\S+)");

    private BoardContent() {
    }

    /**
     * 按榜单抓正文。
     *
     * @param item  热榜条目（title/url/desc 已由上游填好）
     * @param board 榜单 type（NewsRegistry.TYPES 里的值，如 zhihu/weibo/tieba）
     */
    public static ArticleExtractor.Article fetch(HotItem item, String board) throws Exception {
        if (item == null) {
            throw new Exception("条目为空");
        }
        String link = item.bestLink();
        String type = (board == null) ? "" : board;

        if ("tieba".equals(type)) {
            return tieba(link);
        }
        if ("baidu".equals(type)) {
            return baidu(link);
        }
        if ("weibo".equals(type) || "douyin".equals(type)) {
            return related(item.title, type);
        }
        if ("zhihu".equals(type)) {
            return zhihu(item);
        }
        if (link.isEmpty()) {
            throw new Exception("没有可读取的链接");
        }
        return ArticleExtractor.extract(link);
    }

    // ------------------------------------------------------------------
    // 知乎：desc 里的原文链接优先，退回 desc 摘要
    // ------------------------------------------------------------------

    private static ArticleExtractor.Article zhihu(HotItem item) throws Exception {
        String desc = (item.desc == null) ? "" : item.desc.trim();
        if (!desc.isEmpty()) {
            Matcher m = LEAD_URL.matcher(desc);
            if (m.find()) {
                String src = m.group(1);
                try {
                    ArticleExtractor.Article a =
                            ArticleExtractor.extract(src, desktopHeaders());
                    if (a != null && a.text.length() >= 60) {
                        if (a.title == null || a.title.isEmpty()) {
                            a.title = item.title;
                        }
                        a.url = src;
                        return a;
                    }
                } catch (Exception ignored) {
                    // 原文也抓不到就落到 desc 摘要，不中断
                }
            }
        }
        if (desc.isEmpty()) {
            throw new Exception("知乎问题页需登录访问，该条也没有摘要");
        }
        ArticleExtractor.Article a = new ArticleExtractor.Article();
        a.url = item.bestLink();
        a.title = item.title;
        // 去掉开头那段链接，只留正文文本
        a.text = desc.replaceFirst("^\\s*https?://\\S+\\s*", "").trim();
        a.paragraphs = a.text.isEmpty() ? 0 : 1;
        a.strategy = 3; // 3 = 上游摘要
        return a;
    }

    // ------------------------------------------------------------------
    // 贴吧：热议 SSR 页直抓（话题简介 + 帖子）
    // ------------------------------------------------------------------

    private static ArticleExtractor.Article tieba(String link) throws Exception {
        if (link.isEmpty()) {
            throw new Exception("没有可读取的链接");
        }
        // 必须按 UTF-8 显式解析：热议页的 meta keywords/description 里塞了
        // 大段 URL 编码中文，把 <meta charset> 挤出 Jsoup 的 1024 字节嗅探
        // 窗口，Jsoup 统计猜编码会猜成 GBK，整页变乱码（线上实测复现）。
        byte[] bytes = SourceKit.fetchBytes(link, desktopHeaders());
        Document doc = Jsoup.parse(new ByteArrayInputStream(bytes), "UTF-8", link);

        ArticleExtractor.Article out = new ArticleExtractor.Article();
        out.url = link;
        out.strategy = 1;

        Element titleEl = doc.selectFirst("h2.topic-title, div.topic-title");
        out.title = (titleEl != null) ? titleEl.text().trim() : "";

        StringBuilder sb = new StringBuilder();
        Element desc = doc.selectFirst("p.topic-desc");
        if (desc != null && !desc.text().trim().isEmpty()) {
            sb.append("【话题】").append(desc.text().trim()).append('\n');
        }

        Elements posts = doc.select("li.thread-item");
        int shown = 0;
        for (Element p : posts) {
            if (shown >= MAX_BLOCKS) {
                break;
            }
            Element a = p.selectFirst("a.title");
            Element c = p.selectFirst("p.content");
            String title = (a != null) ? a.text().trim() : "";
            String content = (c != null) ? c.text().trim() : "";
            if (title.isEmpty() && content.isEmpty()) {
                continue;
            }
            shown++;
            sb.append('\n').append(shown).append(". ").append(title).append('\n');
            if (!content.isEmpty()) {
                sb.append(content).append('\n');
            }
        }
        String text = sb.toString().trim();
        if (text.length() < 40) {
            throw new Exception("贴吧热议页没有抓到帖子（页面结构可能已变）");
        }
        out.text = text;
        out.paragraphs = shown;
        return out;
    }

    // ------------------------------------------------------------------
    // 百度热搜：条目直连必跳百度安全验证 → 用 Bing 检索该热词
    // ------------------------------------------------------------------

    private static ArticleExtractor.Article baidu(String link) throws Exception {
        if (link.isEmpty()) {
            throw new Exception("没有可读取的链接");
        }
        if (link.contains("baidu.com/s")) {
            String word = queryParam(link, "wd");
            if (word.isEmpty()) {
                word = queryParam(link, "word");
            }
            if (word.isEmpty()) {
                throw new Exception("百度链接缺少搜索词");
            }
            return mobileSearch(word, "百度热搜");
        }
        // 少数条目直达真实新闻页，走通用通道
        return ArticleExtractor.extract(link);
    }

    // ------------------------------------------------------------------
    // 微博 / 抖音：站内反爬 → 移动搜索相关报道
    // ------------------------------------------------------------------

    private static ArticleExtractor.Article related(String title, String board) throws Exception {
        String q = (title == null) ? "" : title.trim();
        if (q.isEmpty()) {
            throw new Exception("条目没有标题，无法检索相关内容");
        }
        ArticleExtractor.Article a = mobileSearch(q, label(board));
        a.title = q;
        return a;
    }

    private static String label(String board) {
        if ("weibo".equals(board)) {
            return "微博热搜";
        }
        if ("douyin".equals(board)) {
            return "抖音热点";
        }
        return board;
    }

    // ------------------------------------------------------------------
    // 相关报道检索通道：cn.bing.com
    //
    // 曾经用 m.baidu.com/s?word=，2026-10-21 线上实测（模拟器真实网络 +
    // 用户现场网络）两处均返回 1488 字节的「百度安全验证」mkdjump 壳页，
    // 判定为对非浏览器请求全面反爬，通道废弃。
    // cn.bing.com/search 对 OkHttp 直连友好（curl 200 / 160KB），
    // 中文新闻收录质量也足够，作为默认检索通道。
    // ------------------------------------------------------------------

    private static ArticleExtractor.Article mobileSearch(String word, String boardLabel)
            throws Exception {
        String url;
        try {
            url = "https://cn.bing.com/search?q=" + URLEncoder.encode(word, "UTF-8")
                    + "&ensearch=0";
        } catch (UnsupportedEncodingException e) {
            throw new Exception("URLEncoder UTF-8 失败: " + e);
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", MBAIDU_UA);
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");

        byte[] html = SourceKit.fetchBytes(url, headers);
        Document doc = Jsoup.parse(new ByteArrayInputStream(html), "UTF-8", url);
        Elements blocks = doc.select("li.b_algo");

        // 抓不到正文时的说明行。下面能解析出链接时，正文只放这一行，
        // 具体列表交给 UI 渲染成"可点开的条目"，避免同一份内容重复两遍。
        String header = "（" + boardLabel + "正文需在原平台查看，"
                + "以下为「" + word + "」的相关报道）";
        StringBuilder sb = new StringBuilder();
        sb.append(header).append('\n');
        List<ArticleExtractor.Link> links = new ArrayList<>();
        int shown = 0;
        for (Element b : blocks) {
            if (shown >= MAX_BLOCKS) {
                break;
            }
            // Bing 2026-10-24 实测改版：结果块是 <a href="真实文章URL"><h2>标题</h2></a>，
            // a 是 h2 的**父节点**。旧选择器 "h2 a" 选的是 h2 内部的 a，一个都选不到，
            // 导致 title/href 全空 → links 为空 → 正文页退化成纯文本编号列表
            // （用户线上复现：条目不带下划线、点不开）。新老结构都兼容。
            Element t = b.selectFirst("a:has(h2)");
            if (t == null) {
                t = b.selectFirst("h2 a");
            }
            String title = (t != null) ? t.text().trim() : "";
            Element s = b.selectFirst("div.b_caption p, div.b_caption");
            String summary = (s != null) ? s.text().trim() : "";
            if (title.isEmpty() && summary.isEmpty()) {
                continue;
            }
            // 记下这条结果的真实链接，正文页可以点开继续读
            String href = (t != null) ? t.attr("abs:href") : "";
            if (!href.isEmpty()) {
                links.add(new ArticleExtractor.Link(
                        title.isEmpty() ? summary : title, summary, href));
            }
            shown++;
            sb.append('\n').append(shown).append(". ");
            if (!title.isEmpty()) {
                sb.append(title).append('\n');
            }
            if (!summary.isEmpty()) {
                sb.append(summary).append('\n');
            }
        }
        if (shown == 0) {
            throw new Exception("检索没有解析出结果（页面结构可能已变）");
        }
        ArticleExtractor.Article a = new ArticleExtractor.Article();
        a.url = url;
        a.title = word;
        a.text = links.isEmpty() ? sb.toString().trim() : header;
        a.paragraphs = shown;
        a.strategy = 2; // 2 = 搜索引擎结果
        a.links = links; // 供正文页点开逐个报道
        return a;
    }

    // ------------------------------------------------------------------
    // 多源摘要聚合：「摘要+」按钮手动触发的内容加长
    //
    // 两条铁律（v18 踩过坑，2026-10-24 用户实测）：
    //  1. 检索词必须用**最初的热搜词**，不能用报道标题——标题带【】、数字、
    //     站点后缀，Bing 分词后会跑偏（实测「说个骚的这把只有369见过HLE高地
    //     【抗压背锅吧】_百度贴吧」被解析成「说」，返回一堆汉语字典释义）。
    //  2. 必须做**相关性过滤**：结果标题/摘要与检索词至少 2 个相邻双字重合
    //     才收，否则宁可一条都不给，也不能显示不相干内容。
    // ------------------------------------------------------------------

    public static String searchSummaries(String query, int max) throws Exception {
        String clean = cleanQuery(query);
        if (clean.isEmpty()) {
            throw new Exception("没有检索词");
        }
        if (max < 1) {
            max = 1;
        }
        String url;
        try {
            url = "https://cn.bing.com/search?q=" + URLEncoder.encode(clean, "UTF-8")
                    + "&ensearch=0";
        } catch (UnsupportedEncodingException e) {
            throw new Exception("URLEncoder UTF-8 失败: " + e);
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", MBAIDU_UA);
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");

        byte[] html = SourceKit.fetchBytes(url, headers);
        Document doc = Jsoup.parse(new ByteArrayInputStream(html), "UTF-8", url);
        Elements blocks = doc.select("li.b_algo");

        StringBuilder sb = new StringBuilder();
        sb.append("（以下内容按「").append(clean).append("」检索，仅收录相关的 ")
                .append(max).append(" 条摘要；点「摘要+」可继续加长）\n");
        int shown = 0;
        // 多取候选：相关性过滤会筛掉一部分，候选上限放宽到 3 倍
        int scanLimit = Math.min(blocks.size(), max * 3);
        for (int i = 0; i < scanLimit && shown < max; i++) {
            Element b = blocks.get(i);
            // 与 mobileSearch 同款结构：Bing 改版后 a 包着 h2
            Element t = b.selectFirst("a:has(h2)");
            if (t == null) {
                t = b.selectFirst("h2 a");
            }
            String title = (t != null) ? t.text().trim() : "";
            Element s = b.selectFirst("div.b_caption p, div.b_caption");
            String summary = (s != null) ? s.text().trim() : "";
            if (title.isEmpty() && summary.isEmpty()) {
                continue;
            }
            // 相关性闸门：不相关的直接丢弃，绝不显示
            if (!isRelevant(clean, title + " " + summary)) {
                continue;
            }
            shown++;
            sb.append('\n').append(shown).append(". ");
            if (!title.isEmpty()) {
                sb.append(title).append('\n');
            }
            if (!summary.isEmpty()) {
                sb.append(summary);
            }
        }
        if (shown == 0) {
            throw new Exception("没有找到与「" + clean + "」相关的内容");
        }
        return sb.toString().trim();
    }

    /**
     * 清洗检索词：去掉标点符号，以及「_百度贴吧」「- 今日头条」这类站点后缀。
     * 保留中文、字母、数字——它们才是检索的有效信息。
     */
    private static String cleanQuery(String q) {
        if (q == null) {
            return "";
        }
        String s = q.replaceAll("[\\p{P}\\p{S}]", " ");
        // 尾部常见站点名（前面分隔符已被上一步换成空格）
        s = s.replaceAll("\\s*(百度贴吧|今日头条|新京报|澎湃新闻|新浪|网易|搜狐|腾讯网|MSN)\\s*$", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    /**
     * 相关性判断：中文按相邻两字切片（bigram），至少 2 片重合才算相关。
     *
     * 例：检索词「只有369摸到过高地」vs 结果「说个骚的这把只有369见过HLE高地」
     * 重合 只有/有3/36/69/高地 ≥2 → 相关；
     * 结果「说（汉语文字）_百度百科」重合 0 → 丢弃。
     */
    private static boolean isRelevant(String query, String text) {
        String q = query.replaceAll("[^\\u4e00-\\u9fa5A-Za-z0-9]", "");
        String t = (text == null ? "" : text).replaceAll("[^\\u4e00-\\u9fa5A-Za-z0-9]", "");
        if (q.length() < 2) {
            return true;
        }
        int hit = 0;
        for (int i = 0; i + 1 < q.length(); i++) {
            if (t.contains(q.substring(i, i + 2))) {
                hit++;
                if (hit >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static Map<String, String> desktopHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", DESKTOP_UA);
        h.put("Accept-Language", "zh-CN,zh;q=0.9");
        return h;
    }

    /** 取 URL query 里指定参数的值（已 URL 解码）；没有返回空串 */
    private static String queryParam(String url, String key) {
        try {
            String q = url;
            int i = q.indexOf('?');
            if (i < 0) {
                return "";
            }
            q = q.substring(i + 1);
            for (String kv : q.split("&")) {
                int eq = kv.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                if (key.equals(kv.substring(0, eq))) {
                    return java.net.URLDecoder.decode(
                            kv.substring(eq + 1), "UTF-8");
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 未使用但保留：调试用，返回当前策略说明 */
    @SuppressWarnings("unused")
    private static List<String> strategyNames() {
        List<String> s = new ArrayList<>();
        s.add("0=通用密度算法");
        s.add("1=站点专用选择器");
        s.add("2=搜索引擎结果");
        s.add("3=上游摘要");
        return s;
    }
}
