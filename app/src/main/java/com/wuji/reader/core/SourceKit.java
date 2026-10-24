package com.wuji.reader.core;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.wuji.reader.model.Chapter;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 复刻 JS 源运行时里由宿主 App 提供的那套辅助方法。
 *
 * JS 源里出现的 this.xxx() 里，属于宿主的共 9 个：
 *   urlJoin / fetch / fetchDom / getContentText /
 *   queryChapters / queryBookElements / queryComicElements /
 *   maxPageNoFromElements / pLimit
 *
 * 这里全部用 OkHttp + Jsoup 实现，逐个源移植时直接调用。
 */
public final class SourceKit {

    public static final String UA =
            "Mozilla/5.0 (Linux; Android 6.0; E-Ink) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/91.0.0.0 Mobile Safari/537.36";

    private static volatile OkHttpClient http;

    private SourceKit() {
    }

    public static OkHttpClient http() {
        if (http == null) {
            synchronized (SourceKit.class) {
                if (http == null) {
                    http = new OkHttpClient.Builder()
                            .connectTimeout(20, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(20, TimeUnit.SECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .build();
                }
            }
        }
        return http;
    }

    public static Map<String, String> baseHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Accept-Language", "zh-CN,zh;q=0.9");
        return h;
    }

    // ------------------------------------------------------------------
    // 基础网络
    // ------------------------------------------------------------------

    /** 对应 JS 的 this.fetch(url).text()，适用于 JSON 接口 */
    public static String fetch(String url) throws Exception {
        return fetch(url, null);
    }

    public static String fetch(String url, Map<String, String> headers) throws Exception {
        ResponseBody body = execute(url, headers);
        if (body == null) {
            throw new Exception("empty response: " + url);
        }
        return body.string();
    }

    /** JSON 接口直取 */
    public static JSONObject fetchJson(String url, Map<String, String> headers) throws Exception {
        return new JSONObject(fetch(url, headers));
    }

    /** 原始字节：给需要自行嗅探编码的中文站用 */
    public static byte[] fetchBytes(String url, Map<String, String> headers) throws Exception {
        ResponseBody body = execute(url, headers);
        if (body == null) {
            throw new Exception("empty response: " + url);
        }
        return body.bytes();
    }

    private static ResponseBody execute(String url, Map<String, String> headers) throws Exception {
        Request.Builder b = new Request.Builder().url(url);
        Map<String, String> h = (headers != null && !headers.isEmpty()) ? headers : baseHeaders();
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                b.header(e.getKey(), e.getValue());
            }
        }
        Response resp = http().newCall(b.build()).execute();
        if (!resp.isSuccessful()) {
            resp.close();
            throw new Exception("HTTP " + resp.code() + " <- " + url);
        }
        return resp.body();
    }

    // ------------------------------------------------------------------
    // DOM
    // ------------------------------------------------------------------

    /**
     * 对应 JS 的 this.fetchDom(url)。
     *
     * 关键：传 null 给 Jsoup，让它自己在 HTML 的 meta / http-equiv 里嗅探编码，
     * 这样 GBK 的中文站不会乱码。直接拿 OkHttp 的字符串会按 UTF-8 硬解。
     */
    public static Document fetchDom(String url) throws Exception {
        return fetchDom(url, null);
    }

    public static Document fetchDom(String url, Map<String, String> headers) throws Exception {
        byte[] bytes = fetchBytes(url, headers);
        return Jsoup.parse(new ByteArrayInputStream(bytes), null, url);
    }

    // ------------------------------------------------------------------
    // URL
    // ------------------------------------------------------------------

    /** 对应 JS 的 this.urlJoin(base, url) */
    public static String urlJoin(String base, String rel) {
        if (rel == null) {
            return base;
        }
        String r = rel.trim();
        if (r.isEmpty()) {
            return base;
        }
        if (r.startsWith("http://") || r.startsWith("https://")) {
            return r;
        }
        if (r.startsWith("//")) {          // 协议相对地址
            String scheme = (base != null && base.startsWith("https")) ? "https:" : "http:";
            return scheme + r;
        }
        if (base == null) {
            return r;
        }
        try {
            URI b = URI.create(base);
            String scheme = b.getScheme();
            String host = b.getHost();
            if (scheme == null || host == null) {
                return r;
            }
            if (r.startsWith("/")) {
                return scheme + "://" + host + r;
            }
            String path = b.getPath();
            if (path == null) {
                path = "/";
            }
            int i = path.lastIndexOf('/');
            String dir = (i >= 0) ? path.substring(0, i + 1) : "/";
            return scheme + "://" + host + dir + r;
        } catch (Exception e) {
            return r;
        }
    }

    // ------------------------------------------------------------------
    // 文本 / 选择器
    // ------------------------------------------------------------------

    public static String text(Element e) {
        return e == null ? "" : e.text().trim();
    }

    /** 对应 JS 的 this.getContentText：把容器内的正文洗成纯文本段落 */
    public static String getContentText(Document doc, String selector) {
        if (doc == null || selector == null || selector.isEmpty()) {
            return "";
        }
        Element root = doc.selectFirst(selector);
        return contentTextOf(root);
    }

    public static String contentTextOf(Element root) {
        if (root == null) {
            return "";
        }
        Elements ps = root.select("p, br");
        if (!ps.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Element p : root.children()) {
                String t = p.tagName().equals("br") ? "" : p.text().trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t);
            }
            String s = sb.toString().trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return root.text().trim();
    }

    /** 对应 JS 的 this.queryChapters(doc, listSelector, titleSelector) */
    public static List<Chapter> queryChapters(Document doc, String listSelector,
                                              String titleSelector, String baseUrl) {
        List<Chapter> out = new ArrayList<>();
        if (doc == null) {
            return out;
        }
        Elements as = doc.select(listSelector);
        for (Element a : as) {
            String href = a.attr("href");
            String title = !titleSelector.isEmpty() ? text(a.selectFirst(titleSelector)) : a.text().trim();
            if (title.isEmpty()) {
                title = a.text().trim();
            }
            out.add(new Chapter("", title, urlJoin(baseUrl, href)));
        }
        return out;
    }

    /** 对应 JS 的 this.maxPageNoFromElements：从分页链接里取最大页码 */
    public static int maxPageNoFromElements(Document doc, String selector) {
        int max = 1;
        if (doc == null) {
            return max;
        }
        for (Element e : doc.select(selector)) {
            String t = e.text().trim();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(t);
            if (m.find()) {
                try {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return max;
    }

    public static String attrAbs(Element e, String attr, String base) {
        if (e == null) {
            return "";
        }
        return urlJoin(base, e.attr(attr));
    }

    public static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }
}
