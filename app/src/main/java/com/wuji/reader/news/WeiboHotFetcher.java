package com.wuji.reader.news;

import com.wuji.reader.core.SourceKit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 微博热搜自研抓取。
 *
 * 为什么不解析 s.weibo.com/top/summary 的 HTML（原方案）：
 *   实测该页对无 Cookie 的纯 HTTP 客户端一律 302 跳
 *   “Sina Visitor System” JS 反爬验证页（约 9.6KB，无榜单数据），
 *   带上 weibo.com 首页 Cookie 重试同样 302；m.weibo.cn 容器 API 也 302。
 *   本项目没有 WebView，过不去 JS 挑战，故改走微博官网自身在用的
 *   JSON 接口（同一份热搜数据源）：
 *     GET https://weibo.com/ajax/side/hotSearch
 *   以下全部为线上实测结论：
 *     - 必须带 Referer: https://weibo.com/ ，否则 403 {"error":"Forbidden"}；
 *     - 桌面 UA 稳（本项目默认移动 UA 也可过，但桌面更稳）；
 *     - 返回 {ok:1, data:{realtime:[...]}}，realtime 即热搜榜（实测 52 条）；
 *     - 广告条目带 is_ad / topic_ad 标记（实测 2+1 条），跳过；
 *     - 置顶政务 hotgov 不在 realtime 里，不会混入榜单。
 *   页面无单条发布时间，timestamp 统一取抓取时刻（毫秒 epoch 字符串），
 *   保证列表能显示「刚刚抓到的时间」。热度取 num（整数），缺失留空。
 *   任何失败（HTTP 非 2xx / ok!=1 / 结构不含 realtime）抛异常，
 *   由 HotApiClient 翻译成 HotBoard.failReason。
 */
public final class WeiboHotFetcher {

    private static final String URL = "https://weibo.com/ajax/side/hotSearch";

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private WeiboHotFetcher() {
    }

    /** 抓微博热搜；返回顺序即榜单顺序（第 1 条最热） */
    public static List<HotItem> fetch() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", DESKTOP_UA);
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Referer", "https://weibo.com/");
        // 非 2xx 会抛 "HTTP xxx <- url"（SourceKit 统一行为）
        String body = SourceKit.fetch(URL, headers);
        JSONObject json = new JSONObject(body);
        int ok = json.optInt("ok", 0);
        if (ok != 1) {
            throw new Exception("weibo hotSearch ok=" + ok);
        }
        JSONArray arr = (json.optJSONObject("data") == null)
                ? null : json.getJSONObject("data").optJSONArray("realtime");
        if (arr == null || arr.length() == 0) {
            throw new Exception("weibo hotSearch 响应不含 data.realtime");
        }

        final long now = System.currentTimeMillis();
        List<HotItem> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || isAd(o)) {
                continue;
            }
            String word = o.optString("word", "").trim();
            if (word.isEmpty() || "null".equals(word)) {
                continue;
            }
            HotItem it = new HotItem();
            String pos = o.optString("realpos", "").trim();
            it.id = (pos.matches("\\d{1,3}") && !"null".equals(pos)) ? pos : String.valueOf(i + 1);
            it.title = word;
            it.hot = hotOf(o);
            String link = searchUrl(word);
            it.url = link;
            it.mobileUrl = link;
            it.timestamp = String.valueOf(now);
            out.add(it);
        }
        if (out.isEmpty()) {
            throw new Exception("weibo realtime 解析出 0 条");
        }
        return out;
    }

    /** 广告条目跳过：is_ad / topic_ad 任一非 0（字段可能是数字或字符串） */
    private static boolean isAd(JSONObject o) {
        return o.optInt("is_ad", 0) != 0 || o.optInt("topic_ad", 0) != 0;
    }

    /** num 是整数热度值；取不到或为 0 留空 */
    private static String hotOf(JSONObject o) {
        Object v = o.opt("num");
        if (v instanceof Number) {
            long n = ((Number) v).longValue();
            return (n > 0) ? String.valueOf(n) : "";
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.matches("\\d{1,12}") && Long.parseLong(s) > 0) {
                return s;
            }
        }
        return "";
    }

    /** 话题搜索落地页，与 s.weibo.com 热搜页点击跳转一致 */
    private static String searchUrl(String word) throws Exception {
        try {
            return "https://s.weibo.com/weibo?q=" + URLEncoder.encode("#" + word + "#", "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new Exception("URLEncoder UTF-8 失败: " + e);
        }
    }
}
