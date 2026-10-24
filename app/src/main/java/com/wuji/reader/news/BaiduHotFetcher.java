package com.wuji.reader.news;

import com.wuji.reader.core.SourceKit;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 百度热搜自研抓取（静态解析 top.baidu.com/board?tab=realtime）。
 *
 * 线上实测结论（curl + python 核实当前真实 DOM，类名带 hash 后缀、
 * 可能变，所以选择器一律用前缀/子串匹配，别写死全类名）：
 *   - 必须桌面 UA：本项目默认移动 UA 只会拿到约 50KB 的残缺页，
 *     0 个条目（百度按 UA 分流）；
 *   - 页面 UTF-8；SourceKit.fetchDom 用 Jsoup 嗅探编码，不会乱码；
 *   - 条目容器 class="category-wrap_iQLoo horizontal_1eKyQ"，
 *     实测 51 个（第 1 个是皇冠图标特色位、无排名数字，
 *     其余 index 文本为 1..50，热度严格递减，顺序即榜单排名）；
 *   - 排名 div.index_1Ew5p（皇冠位无数字 → 用兜底序号）；
 *   - 标题 div.c-single-text-ellipsis，其父级 a[href] 即跳转链接
 *     （绝对地址 https://www.baidu.com/s?wd=...）；
 *   - 热度 div.hot-index_1Bl1a，实测为纯数字（如 7904436）。
 *   类名失效时 parseFallback 按结构自适应：标题块含 a[href] 且文本非空、
 *   热度取附近文本含数字的块。仍解析不出就抛异常，
 *   由 HotApiClient 写 HotBoard.failReason（不伪造数据）。
 *   页面无单条发布时间，timestamp 统一取抓取时刻。
 */
public final class BaiduHotFetcher {

    private static final String URL = "https://top.baidu.com/board?tab=realtime";

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 热搜指数文本：纯数字（如 7904436）或带万（如 123万）；至少 4 位，避开排名数字 */
    private static final Pattern HOT_TEXT = Pattern.compile("^(\\d{4,}万?|\\d{1,4}(\\.\\d+)?万)$");

    private BaiduHotFetcher() {
    }

    /** 抓百度热搜；返回顺序即榜单排名顺序 */
    public static List<HotItem> fetch() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", DESKTOP_UA);
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        // 非 2xx 会抛 "HTTP xxx <- url"（SourceKit 统一行为）
        Document doc = SourceKit.fetchDom(URL, headers);
        Elements items = doc.select("div[class^=category-wrap]");

        final long now = System.currentTimeMillis();
        List<HotItem> out = new ArrayList<>(items.size());
        if (!items.isEmpty()) {
            int seq = 0;
            for (Element item : items) {
                HotItem it = parseItem(item, ++seq, now);
                if (it != null) {
                    out.add(it);
                }
            }
        } else {
            out = parseFallback(doc, now);
        }
        if (out.isEmpty()) {
            throw new Exception("baidu board 解析出 0 条（页面结构可能已变）");
        }
        return out;
    }

    /** 主路径：按线上实测结构解析单个条目；无标题 / 无链接返回 null（跳过） */
    private static HotItem parseItem(Element item, int seq, long now) {
        Element titleBox = item.selectFirst("div[class*=c-single-text]");
        if (titleBox == null) {
            return null;
        }
        String title = titleBox.text().trim();
        if (title.isEmpty()) {
            return null;
        }
        Element a = titleBox.closest("a");
        if (a == null) {
            return null;
        }
        String link = a.attr("abs:href").trim();
        if (link.isEmpty()) {
            return null;
        }

        HotItem it = new HotItem();
        Element rankBox = item.selectFirst("div[class^=index]");
        String rank = (rankBox == null) ? "" : rankBox.text().trim();
        it.id = rank.matches("\\d{1,3}") ? rank : String.valueOf(seq);
        it.title = title;
        Element hotBox = item.selectFirst("div[class*=hot-index]");
        it.hot = (hotBox == null) ? "" : hotBox.text().trim();
        it.url = link;
        it.mobileUrl = link;
        it.timestamp = String.valueOf(now);
        return it;
    }

    /** 兜底：类名 hash 后缀失效时，按「标题块含 a[href] 且文本非空」自适应 */
    private static List<HotItem> parseFallback(Document doc, long now) {
        List<HotItem> out = new ArrayList<>();
        int seq = 0;
        for (Element t : doc.select("div[class*=c-single-text]")) {
            String title = t.text().trim();
            if (title.isEmpty()) {
                continue;
            }
            Element a = t.closest("a");
            if (a == null) {
                continue;
            }
            String link = a.attr("abs:href").trim();
            if (link.isEmpty()) {
                continue;
            }
            HotItem it = new HotItem();
            it.id = String.valueOf(++seq);
            it.title = title;
            it.hot = hotNear(a);
            it.url = link;
            it.mobileUrl = link;
            it.timestamp = String.valueOf(now);
            out.add(it);
        }
        return out;
    }

    /** 兜底用：从标题锚点向上找最近一个「热搜指数」文本块 */
    private static String hotNear(Element a) {
        Element p = a;
        for (int i = 0; i < 8 && p != null; i++) {
            for (Element d : p.select("div")) {
                String txt = d.text().trim();
                if (HOT_TEXT.matcher(txt).matches()) {
                    return txt;
                }
            }
            p = p.parent();
        }
        return "";
    }
}
