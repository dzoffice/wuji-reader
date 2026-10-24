package com.wuji.reader.news;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 榜单注册表：type -> 中文标签，顺序即 UI 顺序。
 *
 * 顺序铁律（wuji 用踩坑换来的，不要"优化"）：
 *   沿用上游顺序，知乎必须放第一位。上游顺序是有道理的——知乎稳定返回 ~30 条带标题数据。
 *   已实测：weibo 返回 HTTP 500；baidu 只回 1 条 title 为 undefined 的空记录。
 *   绝不要按"哪个更热门 / 更快"重排：一旦坏榜落到默认位，
 *   表现就是「有页签、列表全白」，极难排查。
 *
 * 注：weibo / baidu 现已改为自研抓取（见 WeiboHotFetcher / BaiduHotFetcher），
 * 不再走聚合 API；但 type 列表与顺序保持不变，避免影响默认选中与用户习惯。
 * 实际拉取的榜单列表以 SourceManager.newsTypes()（启用过滤 + 排序）为准。
 */
public final class NewsRegistry {

    /** 热榜服务基地址 */
    public static final String BASE_URL = "http://47.103.115.255:6688/";

    private static final List<String> TYPES = Collections.unmodifiableList(Arrays.asList(
            "zhihu", "juejin", "36kr", "qq-news", "thepaper", "netease-news",
            "toutiao", "ithome", "bilibili", "douyin", "weibo", "baidu",
            "sspai", "tieba", "zhihu-daily", "hellogithub", "jianshu",
            "weread", "ngabbs", "genshin", "starrail", "lol"));

    private static final Map<String, String> LABELS = buildLabels();

    private NewsRegistry() {
    }

    /** 榜单类型列表，顺序即 UI 顺序 */
    public static List<String> types() {
        return TYPES;
    }

    public static String labelOf(String type) {
        String l = LABELS.get(type);
        return (l == null) ? type : l;
    }

    private static Map<String, String> buildLabels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("zhihu", "知乎");
        m.put("juejin", "掘金");
        m.put("36kr", "36氪");
        m.put("qq-news", "腾讯新闻");
        m.put("thepaper", "澎湃新闻");
        m.put("netease-news", "网易新闻");
        m.put("toutiao", "今日头条");
        m.put("ithome", "IT之家");
        m.put("bilibili", "哔哩哔哩");
        m.put("douyin", "抖音");
        m.put("weibo", "微博");
        m.put("baidu", "百度");
        m.put("sspai", "少数派");
        m.put("tieba", "贴吧");
        m.put("zhihu-daily", "知乎日报");
        m.put("hellogithub", "GitHub热榜");
        m.put("jianshu", "简书");
        m.put("weread", "微信读书");
        m.put("ngabbs", "NGA");
        m.put("genshin", "原神");
        m.put("starrail", "星穹铁道");
        m.put("lol", "英雄联盟");
        return Collections.unmodifiableMap(m);
    }
}
