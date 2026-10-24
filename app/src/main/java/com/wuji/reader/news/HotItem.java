package com.wuji.reader.news;

import org.json.JSONObject;

import java.math.BigDecimal;

/**
 * 热榜条目。字段与 wuji（packages/hot-api）契约一致：
 * { id, title, desc, author, hot, url, mobileUrl, timestamp, cover }
 *
 * 注意：上游 hot / timestamp / id 有时是数字（知乎实测 hot 为 int），
 * org.json 对小数会给出科学计数法（如 1.0E7），这里统一归一成普通十进制字符串。
 */
public class HotItem {

    public String id = "";
    public String title = "";
    public String desc = "";
    public String author = "";
    public String hot = "";
    public String url = "";
    public String mobileUrl = "";
    public String timestamp = "";
    /** 契约里有 cover，但墨水屏 + 防盗链考虑，新闻列表暂不展示封面 */
    public String cover = "";

    public HotItem() {
    }

    /** 从上游 data[] 的一项解析 */
    public static HotItem fromJson(JSONObject o) {
        HotItem it = new HotItem();
        if (o == null) {
            return it;
        }
        it.id = readString(o, "id");
        it.title = readString(o, "title");
        it.desc = readString(o, "desc");
        it.author = readString(o, "author");
        it.hot = readString(o, "hot");
        it.url = readString(o, "url");
        it.mobileUrl = readString(o, "mobileUrl");
        it.timestamp = readString(o, "timestamp");
        it.cover = readString(o, "cover");
        return it;
    }

    /** 首选可跳转链接：url 优先，退回 mobileUrl */
    public String bestLink() {
        String raw = (url != null && !url.isEmpty())
                ? url : ((mobileUrl == null) ? "" : mobileUrl);
        return unescapeHtml(raw);
    }

    /**
     * 上游 JSON 的 url 字段偶尔带 HTML 实体（贴吧热议链接实测形如
     * "...topic_id=28367809&amp;topic_name=..."），原样发出去 query 会被
     * 实体里的分号截断、topic_name 丢失。这里统一解掉常见实体。
     */
    private static String unescapeHtml(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s == null ? "" : s;
        }
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static String readString(JSONObject o, String key) {
        if (o.isNull(key)) {
            return "";
        }
        Object v = o.opt(key);
        if (v == null) {
            return "";
        }
        if (v instanceof Number) {
            Number num = (Number) v;
            if (v instanceof Double || v instanceof Float) {
                double d = num.doubleValue();
                if (!Double.isNaN(d) && !Double.isInfinite(d) && d == Math.floor(d)) {
                    return String.valueOf((long) d);
                }
                return new BigDecimal(v.toString()).toPlainString();
            }
            return num.toString();
        }
        return v.toString().trim();
    }
}
