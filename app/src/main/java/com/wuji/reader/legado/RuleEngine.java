package com.wuji.reader.legado;

import com.wuji.reader.core.SourceKit;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Legado 书源规则解释器（纯 Java，无 JS 引擎）。
 *
 * 支持范围见项目规格：
 *   - 选择步骤 class./id./tag./text./children/CSS 透传
 *   - 索引 .0 / !0 / [a:b] / 取反
 *   - 取值 text / ownText / textNodes / html / 任意属性
 *   - 修饰符 ## / || / && / 前缀 - / 模板 {{...}} / @get
 *   - JSON 规则 $.a.b / $..list[*] / $.data.[*]
 *
 * 明确不支持 @js: / <js> / java.*，遇到时该步骤返回空并记录 unsupported:js，绝不抛异常。
 */
public final class RuleEngine {

    private static final Set<String> TAGS = new HashSet<>(Arrays.asList(
            "a", "li", "p", "img", "dd", "dl", "ul", "ol", "div", "span",
            "h1", "h2", "h3", "h4", "h5", "h6", "td", "tr", "table", "tbody",
            "option", "select", "article", "section", "em", "strong", "b", "i",
            "body", "html", "head", "form", "input", "button", "label", "br",
            "dt", "th", "thead", "nav", "header", "footer", "main", "figure",
            "figcaption", "blockquote", "pre", "code", "small", "time", "u", "s"));

    private static final Set<String> GETTERS = new HashSet<>(Arrays.asList(
            "text", "ownText", "textNodes", "html"));

    private static final Pattern TEMPLATE = Pattern.compile(
            "\\{\\{([^{}]+)\\}\\}|\\{(\\$[^{}]*|@@?[^{}]*)\\}");

    private final String baseUrl;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final Map<String, String> vars = new LinkedHashMap<>();

    /** 最近一次不支持语法的记录，便于日志排查 */
    private String lastUnsupported = "";

    public RuleEngine(String baseUrl, Map<String, String> extraHeaders) {
        this.baseUrl = cleanBase(baseUrl);
        headers.putAll(SourceKit.baseHeaders());
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    headers.put(e.getKey(), e.getValue());
                }
            }
        }
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String lastUnsupported() {
        return lastUnsupported;
    }

    public void putVariable(String key, String value) {
        if (key != null) {
            vars.put(key, value == null ? "" : value);
        }
    }

    public String getVariable(String key) {
        String v = vars.get(key);
        return v == null ? "" : v;
    }

    public static String cleanBase(String url) {
        if (url == null) {
            return "";
        }
        int i = url.indexOf('#');
        return (i >= 0 ? url.substring(0, i) : url).trim();
    }

    // ==================================================================
    // 网络
    // ==================================================================

    public static final class HttpRequest {
        public String url = "";
        public String method = "GET";
        public String body = "";
        public String charset = null;
        public final Map<String, String> headers = new LinkedHashMap<>();

        public HttpRequest(String url) {
            this.url = url;
        }
    }

    /** 解析 searchUrl：纯 URL / url,{options} / {json 对象} */
    public HttpRequest parseUrl(String raw, String keyword, int page) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        // 含有 {{cookie...}} 的源无法执行 cookie 脚本，直接切掉该行保留后面的 URL
        int cookie = s.indexOf("{{cookie.");
        if (cookie == 0) {
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(nl + 1).trim();
            }
        }

        String urlPart = s;
        String optPart = "";
        int comma = topLevelIndex(s, ',');
        if (comma > 0) {
            String rest = s.substring(comma + 1).trim();
            if (rest.startsWith("{")) {
                urlPart = s.substring(0, comma).trim();
                optPart = rest;
            }
        }
        if (urlPart.startsWith("{")) {
            optPart = urlPart;
            urlPart = "";
        }

        HttpRequest req = new HttpRequest("");
        Map<String, String> opts = parseOptions(optPart);
        req.url = opts.get("url") == null ? urlPart : opts.get("url");
        req.method = opts.get("method") == null ? "GET" : opts.get("method");
        req.body = opts.get("body") == null ? "" : opts.get("body");
        req.charset = opts.get("charset");
        String hs = opts.get("headers");
        if (hs != null && !hs.isEmpty()) {
            Map<String, String> hm = parseHeaderObject(hs);
            req.headers.putAll(hm);
        }

        String k = keyword == null ? "" : keyword;
        try {
            k = URLEncoder.encode(k, "UTF-8");
        } catch (Exception ignored) {
        }
        req.url = req.url.replace("{{key}}", k).replace("{{page}}", String.valueOf(page));
        req.body = req.body.replace("{{key}}", k).replace("{{page}}", String.valueOf(page));
        if ("POST".equalsIgnoreCase(req.method)) {
            req.body = encodeNonAscii(req.body, req.charset);
        }
        if (req.url.startsWith("/") || (baseUrl != null && !baseUrl.isEmpty()
                && !req.url.startsWith("http"))) {
            req.url = SourceKit.urlJoin(baseUrl, req.url);
        }
        return req;
    }

    /** 执行请求，返回原始字节与响应 charset */
    public Fetched fetch(HttpRequest req) throws Exception {
        if (req == null || req.url == null || req.url.isEmpty()) {
            throw new Exception("empty url");
        }
        Request.Builder b = new Request.Builder();
        Map<String, String> hs = new LinkedHashMap<>(headers);
        hs.putAll(req.headers);
        for (Map.Entry<String, String> e : hs.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                b.header(e.getKey(), e.getValue());
            }
        }
        b.url(req.url);
        if ("POST".equalsIgnoreCase(req.method)) {
            Charset cs = charsetOf(req.charset);
            RequestBody body = RequestBody.create(req.body.getBytes(cs),
                    MediaType.parse("application/x-www-form-urlencoded; charset=" + cs.name()));
            b.post(body);
        }
        Response resp = SourceKit.http().newCall(b.build()).execute();
        try {
            if (!resp.isSuccessful()) {
                throw new Exception("HTTP " + resp.code() + " <- " + req.url);
            }
            ResponseBody rb = resp.body();
            if (rb == null) {
                throw new Exception("empty response: " + req.url);
            }
            byte[] bytes = rb.bytes();
            String cs = req.charset;
            if (cs == null || cs.isEmpty()) {
                cs = charsetFromHeader(resp.header("Content-Type"));
            }
            return new Fetched(bytes, cs, req.url);
        } finally {
            resp.close();
        }
    }

    public static final class Fetched {
        public final byte[] bytes;
        public final String charset;
        public final String url;

        Fetched(byte[] bytes, String charset, String url) {
            this.bytes = bytes;
            this.charset = charset;
            this.url = url;
        }

        public Document dom() {
            try {
                return Jsoup.parse(new ByteArrayInputStream(bytes), charset, url);
            } catch (IOException e) {
                return Jsoup.parse("<html></html>", url);
            }
        }

        public String text() {
            return decode(bytes, charset);
        }

        /** 自动识别 JSON / HTML；JSON 失败时退回 DOM */
        public Object page() {
            String t = text().trim();
            if (t.startsWith("\uFEFF")) {
                t = t.substring(1).trim();
            }
            if (t.startsWith("{")) {
                try {
                    return new JSONObject(t);
                } catch (Exception ignored) {
                }
            } else if (t.startsWith("[")) {
                try {
                    return new JSONArray(t);
                } catch (Exception ignored) {
                }
            }
            return dom();
        }
    }

    public static String decode(byte[] bytes, String charset) {
        Charset cs = null;
        if (charset != null && !charset.trim().isEmpty()) {
            try {
                cs = Charset.forName(charset.trim());
            } catch (Exception ignored) {
            }
        }
        if (cs == null) {
            cs = sniffCharset(bytes);
        }
        try {
            return new String(bytes, cs == null ? StandardCharsets.UTF_8 : cs);
        } catch (Exception e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** POST 表单里写死的中文（如 submit=搜索）要按目标 charset 做百分号编码 */
    private static String encodeNonAscii(String body, String charset) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        Charset cs = charsetOf(charset);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < body.length(); ) {
            int cp = body.codePointAt(i);
            int n = Character.charCount(cp);
            if (cp < 0x80) {
                sb.append((char) cp);
            } else {
                try {
                    byte[] bytes = new String(Character.toChars(cp)).getBytes(cs.name());
                    for (byte b : bytes) {
                        sb.append('%').append(String.format(Locale.US, "%02X", b & 0xFF));
                    }
                } catch (Exception ignored) {
                }
            }
            i += n;
        }
        return sb.toString();
    }

    private static Charset charsetOf(String name) {        if (name != null && !name.trim().isEmpty()) {
            try {
                return Charset.forName(name.trim());
            } catch (Exception ignored) {
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Charset sniffCharset(byte[] bytes) {
        int n = Math.min(bytes.length, 4096);
        String head = new String(bytes, 0, n, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        int i = head.indexOf("charset=");
        if (i >= 0) {
            int j = i + 8;
            while (j < head.length() && (head.charAt(j) == '"' || head.charAt(j) == '\'')) {
                j++;
            }
            int k = j;
            while (k < head.length() && k - j < 20
                    && (Character.isLetterOrDigit(head.charAt(k)) || head.charAt(k) == '-')) {
                k++;
            }
            String name = head.substring(j, k);
            if (!name.isEmpty()) {
                try {
                    return Charset.forName(name);
                } catch (Exception ignored) {
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String charsetFromHeader(String ct) {
        if (ct == null) {
            return null;
        }
        int i = ct.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (i < 0) {
            return null;
        }
        String s = ct.substring(i + 8).trim();
        int semi = s.indexOf(';');
        if (semi >= 0) {
            s = s.substring(0, semi);
        }
        return s.replace("\"", "").replace("'", "").trim();
    }

    // ==================================================================
    // 规则求值入口
    // ==================================================================

    public List<Object> nodes(Object root, String rule) {
        List<Object> raw = eval(root, rule, false);
        List<Object> out = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof JSONArray) {
                JSONArray a = (JSONArray) o;
                for (int i = 0; i < a.length(); i++) {
                    Object v = a.opt(i);
                    if (v != null && v != JSONObject.NULL) {
                        out.add(v);
                    }
                }
            } else if (o != null && o != JSONObject.NULL) {
                out.add(o);
            }
        }
        return out;
    }

    public List<Element> elements(Object root, String rule) {
        List<Element> out = new ArrayList<>();
        for (Object o : nodes(root, rule)) {
            if (o instanceof Element) {
                out.add((Element) o);
            }
        }
        return out;
    }

    public List<String> strings(Object root, String rule) {
        List<Object> ns = eval(root, rule, true);
        List<String> out = new ArrayList<>();
        for (Object o : ns) {
            String s = valueOf(o);
            if (s != null && !s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    public String string(Object root, String rule) {
        List<String> ss = strings(root, rule);
        return ss.isEmpty() ? "" : ss.get(0);
    }

    // ==================================================================
    // 求值主流程
    // ==================================================================

    private List<Object> eval(Object root, String rule, boolean forString) {
        if (rule == null) {
            return new ArrayList<>();
        }
        String r = rule.trim();
        if (r.isEmpty()) {
            return new ArrayList<>();
        }
        for (String alt : splitTop(r, "||")) {
            List<Object> got = evalAnd(root, alt, forString);
            if (!got.isEmpty()) {
                return got;
            }
        }
        return new ArrayList<>();
    }

    private List<Object> evalAnd(Object root, String expr, boolean forString) {
        List<String> parts = splitTop(expr, "&&");
        if (parts.size() <= 1) {
            return evalOne(root, expr, forString);
        }
        if (forString) {
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                String s = firstString(evalOne(root, p, true));
                if (!s.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(s);
                }
            }
            List<Object> out = new ArrayList<>();
            if (sb.length() > 0) {
                out.add(sb.toString());
            }
            return out;
        }
        List<Object> out = new ArrayList<>();
        for (String p : parts) {
            out.addAll(evalOne(root, p, false));
        }
        return out;
    }

    private List<Object> evalOne(Object root, String expr, boolean forString) {
        String e = expr.trim();
        if (e.isEmpty()) {
            return new ArrayList<>();
        }
        if (e.startsWith("@get:{")) {
            int end = e.indexOf('}');
            if (end > 6) {
                String k = e.substring(6, end).trim();
                String v = getVariable(k);
                List<Object> out = new ArrayList<>();
                if (!v.isEmpty()) {
                    out.add(v);
                }
                return out;
            }
            return new ArrayList<>();
        }
        if (e.startsWith("@put:") || e.startsWith("@js:") || e.startsWith("<js>")
                || e.contains("java.")) {
            lastUnsupported = "unsupported:js";
            return new ArrayList<>();
        }

        boolean reverse = false;
        if (e.startsWith("-") && !e.startsWith("--")) {
            reverse = true;
            e = e.substring(1).trim();
        }

        List<String> mods = splitTop(e, "##");
        String base = mods.get(0).trim();
        List<String> replRegex = new ArrayList<>();
        List<String> replValue = new ArrayList<>();
        for (int i = 1; i + 1 <= mods.size(); i += 2) {
            String rx = mods.get(i);
            if (rx == null || rx.isEmpty()) {
                continue;
            }
            replRegex.add(rx);
            replValue.add(i + 1 < mods.size() ? mods.get(i + 1) : "");
        }

        if (base.startsWith("@js:") || base.startsWith("<js>")) {
            lastUnsupported = "unsupported:js";
            return new ArrayList<>();
        }

        String interp = interpolate(base, root);
        List<Object> out = new ArrayList<>();
        if (isLiteral(interp)) {
            out.add(interp);
        } else if (root instanceof Element) {
            out.addAll(evalHtml(root, interp));
        } else if (root instanceof JSONObject || root instanceof JSONArray) {
            out.addAll(evalJson(root, interp));
        } else if (root instanceof String) {
            String s = ((String) root).trim();
            if (s.startsWith("{") || s.startsWith("[")) {
                try {
                    Object j = s.startsWith("{") ? new JSONObject(s) : new JSONArray(s);
                    out.addAll(evalJson(j, interp));
                } catch (Exception ignored) {
                }
            }
        }

        if (!replRegex.isEmpty()) {
            List<String> ss = new ArrayList<>();
            for (Object o : out) {
                String s = valueOf(o);
                for (int i = 0; i < replRegex.size(); i++) {
                    s = applyRegex(s, replRegex.get(i), replValue.get(i));
                }
                if (!s.isEmpty()) {
                    ss.add(s);
                }
            }
            out.clear();
            out.addAll(ss);
        }

        if (reverse && out.size() > 1) {
            java.util.Collections.reverse(out);
        }
        return out;
    }

    private static boolean isLiteral(String s) {
        String t = s.trim();
        if (t.startsWith("http://") || t.startsWith("https://")) {
            return true;
        }
        if (t.startsWith("//")) {
            // //meta[@property=...]/@content 是 XPath，不是协议相对地址
            return !t.contains("[") && !t.contains("@");
        }
        if (t.startsWith("/") && !t.contains("@") && !t.contains("[")) {
            return true;
        }
        return false;
    }

    private String applyRegex(String s, String regex, String replacement) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        try {
            return Pattern.compile(regex).matcher(s)
                    .replaceAll(replacement == null ? "" : replacement);
        } catch (Exception e) {
            return s;
        }
    }

    private static String firstString(List<Object> list) {
        for (Object o : list) {
            String s = valueOf(o);
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "";
    }

    private static String valueOf(Object o) {
        if (o == null || o == JSONObject.NULL) {
            return "";
        }
        if (o instanceof String) {
            return ((String) o).trim();
        }
        if (o instanceof Element) {
            return ((Element) o).text().trim();
        }
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                String s = valueOf(a.opt(i));
                if (!s.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(s);
                }
            }
            return sb.toString();
        }
        return String.valueOf(o).trim();
    }

    // ==================================================================
    // 模板插值
    // ==================================================================

    private String interpolate(String s, Object root) {
        if (s.indexOf('{') < 0) {
            return s;
        }
        Matcher m = TEMPLATE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String inner = m.group(1) != null ? m.group(1) : m.group(2);
            String val = "";
            if (inner != null) {
                inner = inner.trim();
                while (inner.startsWith("@") && !inner.startsWith("@get:")) {
                    inner = inner.substring(1);
                }
                if (!inner.isEmpty()) {
                    val = string(root, inner);
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 供适配器在拼接 URL 时复用模板逻辑 */
    public String interpolatePublic(String s, Object root) {
        return interpolate(s, root);
    }

    // ==================================================================
    // HTML 选择
    // ==================================================================

    private List<Object> evalHtml(Object rootObj, String base) {
        if (!(rootObj instanceof Element)) {
            return new ArrayList<>();
        }
        Element root = (Element) rootObj;
        Document doc = root.ownerDocument() == null ? (Document) root : root.ownerDocument();

        String expr = base.trim();
        if (expr.startsWith("@")) {
            expr = expr.substring(1);
        }

        // 裸取值：text / href 等，直接作用于当前节点
        if (GETTERS.contains(expr) || isBareAttr(expr)) {
            List<Object> out = new ArrayList<>();
            String v = readValue(root, expr);
            if (v != null && !v.isEmpty()) {
                out.add(v);
            }
            return out;
        }

        if (expr.startsWith("//")) {
            return evalXpath(doc, expr);
        }

        List<String> steps = splitTop(expr, "@");
        List<String> cleaned = new ArrayList<>();
        for (String st : steps) {
            if (st != null && !st.trim().isEmpty()) {
                cleaned.add(st.trim());
            }
        }
        if (cleaned.isEmpty()) {
            return new ArrayList<>();
        }

        boolean hasGetter = false;
        String getter = null;
        int last = cleaned.size() - 1;
        if (cleaned.size() >= 1 && isGetterToken(cleaned.get(last))) {
            hasGetter = true;
            getter = cleaned.remove(last);
        }

        List<Element> current = new ArrayList<>();
        current.add(root);
        for (String step : cleaned) {
            current = applyStep(current, step, doc);
            if (current.isEmpty()) {
                return new ArrayList<>();
            }
        }

        List<Object> out = new ArrayList<>();
        if (hasGetter) {
            for (Element el : current) {
                String v = readValue(el, getter);
                if (v != null && !v.isEmpty()) {
                    out.add(v);
                }
            }
        } else {
            out.addAll(dedupe(current));
        }
        return out;
    }

    private static List<Element> dedupe(List<Element> list) {
        LinkedHashSet<Element> set = new LinkedHashSet<>(list);
        return new ArrayList<>(set);
    }

    private boolean isGetterToken(String token) {
        String t = token.trim();
        if (t.startsWith("@")) {
            t = t.substring(1);
        }
        if (GETTERS.contains(t)) {
            return true;
        }
        return isBareAttr(t);
    }

    private static boolean isBareAttr(String t) {
        if (t == null || t.isEmpty()) {
            return false;
        }
        // 属性名不含 '.'；否则 tag.a.0 / class.foo 会被误判成取值步骤
        if (t.indexOf('.') >= 0) {
            return false;
        }
        if (!t.matches("[A-Za-z_][A-Za-z0-9_:-]*")) {
            return false;
        }
        return !TAGS.contains(t.toLowerCase(Locale.ROOT));
    }

    private List<Element> applyStep(List<Element> in, String rawStep, Document doc) {
        String step = rawStep.trim();
        if (step.isEmpty()) {
            return in;
        }
        IndexSpec idx = IndexSpec.parse(step);
        String bare = idx.stripped.trim();
        if (bare.isEmpty()) {
            return in;
        }

        List<Element> matched = new ArrayList<>();
        if ("children".equals(bare)) {
            for (Element e : in) {
                List<Element> kids = new ArrayList<>(e.children());
                matched.addAll(idx.apply(kids));
            }
        } else if (bare.startsWith("//")) {
            List<Element> all = new ArrayList<>();
            for (Object o : evalXpath(doc, bare)) {
                if (o instanceof Element) {
                    all.add((Element) o);
                }
            }
            matched.addAll(idx.apply(all));
        } else if (bare.startsWith("text.")) {
            String needle = bare.substring(5).trim();
            Elements all = doc.select("*");
            List<Element> leaf = new ArrayList<>();
            for (Element e : all) {
                if (e.text().contains(needle)) {
                    boolean childHit = false;
                    for (Element c : e.getAllElements()) {
                        if (c != e && c.text().contains(needle)) {
                            childHit = true;
                            break;
                        }
                    }
                    if (!childHit) {
                        leaf.add(e);
                    }
                }
            }
            matched.addAll(idx.apply(leaf));
        } else {
            String css = toCss(bare);
            if (css.isEmpty()) {
                return new ArrayList<>();
            }
            for (Element e : in) {
                try {
                    Elements got;
                    if (e instanceof Document && in.size() == 1) {
                        got = ((Document) e).select(css);
                    } else {
                        got = e.select(css);
                    }
                    matched.addAll(idx.apply(new ArrayList<>(got)));
                } catch (Exception ignored) {
                }
            }
        }
        return dedupe(matched);
    }

    private static String toCss(String step) {
        String s = step.trim();
        if (s.startsWith("class.")) {
            String rest = s.substring(6).trim();
            StringBuilder sb = new StringBuilder();
            for (String t : rest.split("\\s+")) {
                if (!t.isEmpty()) {
                    sb.append('.').append(t);
                }
            }
            return sb.toString();
        }
        if (s.startsWith("id.")) {
            return "#" + s.substring(3).trim();
        }
        if (s.startsWith("tag.")) {
            return s.substring(4).trim();
        }
        return s;
    }

    private String readValue(Element e, String getter) {
        String g = getter == null ? "text" : getter.trim();
        if (g.startsWith("@")) {
            g = g.substring(1);
        }
        if ("text".equals(g)) {
            return e.text().trim();
        }
        if ("ownText".equals(g)) {
            return e.ownText().trim();
        }
        if ("textNodes".equals(g)) {
            StringBuilder sb = new StringBuilder();
            for (Element el : e.getAllElements()) {
                for (TextNode tn : el.textNodes()) {
                    String s = tn.text().trim();
                    if (!s.isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(s);
                    }
                }
            }
            return sb.toString();
        }
        if ("html".equals(g)) {
            return e.html();
        }
        String v = e.attr(g);
        if (("href".equals(g) || "src".equals(g)) && !v.isEmpty()) {
            String abs = e.absUrl(g);
            if (abs != null && !abs.isEmpty()) {
                v = abs;
            }
        }
        return v == null ? "" : v.trim();
    }

    // ==================================================================
    // JSON 规则
    // ==================================================================

    private List<Object> evalJson(Object root, String rule) {
        String path = rule.trim();
        if (path.startsWith("@json:")) {
            path = path.substring(6).trim();
        }
        if (path.startsWith("$")) {
            path = path.substring(1);
        }
        boolean recursive = path.startsWith("..");
        if (recursive) {
            path = path.substring(2);
        } else {
            while (path.startsWith(".")) {
                path = path.substring(1);
            }
        }
        if (path.isEmpty()) {
            List<Object> self = new ArrayList<>();
            self.add(root);
            return self;
        }
        boolean expandEnd = path.endsWith("[*]") || path.endsWith(".*");
        if (path.endsWith("[*]")) {
            path = path.substring(0, path.length() - 3);
        } else if (path.endsWith(".*")) {
            path = path.substring(0, path.length() - 2);
        }
        if (recursive) {
            String key = path;
            if (key.endsWith("[*]")) {
                key = key.substring(0, key.length() - 3);
            } else if (key.endsWith(".*")) {
                key = key.substring(0, key.length() - 2);
            }
            List<Object> values = recursiveFind(root, key);
            return expand(values);
        }

        String[] segs = path.split("\\.");
        List<Object> cur = new ArrayList<>();
        cur.add(root);
        for (String seg : segs) {
            if (seg.isEmpty()) {
                continue;
            }
            if ("[*]".equals(seg)) {
                cur = expand(cur);
                if (cur.isEmpty()) {
                    return new ArrayList<>();
                }
                continue;
            }
            boolean expand = seg.endsWith("[*]") || "*".equals(seg);
            String key = seg;
            if (key.endsWith("[*]")) {
                key = key.substring(0, key.length() - 3);
            }
            if (key.isEmpty()) {
                cur = expand(cur);
                continue;
            }
            List<Object> next = new ArrayList<>();
            for (Object o : cur) {
                if ("*".equals(seg)) {
                    next.addAll(valuesOf(o));
                    continue;
                }
                Object v = child(o, key);
                if (v != null && v != JSONObject.NULL) {
                    next.add(v);
                }
            }
            cur = expand ? expand(next) : next;
            if (cur.isEmpty()) {
                return new ArrayList<>();
            }
        }
        return expandEnd ? expand(cur) : cur;
    }

    private static Object child(Object o, String key) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            if (j.has(key)) {
                return j.opt(key);
            }
            return null;
        }
        if (o instanceof JSONArray) {
            JSONArray arr = (JSONArray) o;
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                Object v = child(arr.opt(i), key);
                if (v != null && v != JSONObject.NULL) {
                    out.put(v);
                }
            }
            return out.length() == 0 ? null : out;
        }
        return null;
    }

    private static List<Object> valuesOf(Object o) {
        List<Object> out = new ArrayList<>();
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                out.add(a.opt(i));
            }
        } else if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                out.add(j.opt(it.next()));
            }
        } else if (o != null) {
            out.add(o);
        }
        return out;
    }

    private static List<Object> expand(List<Object> in) {
        List<Object> out = new ArrayList<>();
        for (Object o : in) {
            if (o instanceof JSONArray) {
                JSONArray a = (JSONArray) o;
                for (int i = 0; i < a.length(); i++) {
                    Object v = a.opt(i);
                    if (v != null && v != JSONObject.NULL) {
                        out.add(v);
                    }
                }
            } else if (o != null && o != JSONObject.NULL) {
                out.add(o);
            }
        }
        return out;
    }

    private static List<Object> recursiveFind(Object root, String key) {
        List<Object> out = new ArrayList<>();
        walk(root, key, out);
        return out;
    }

    private static void walk(Object o, String key, List<Object> out) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            if (j.has(key)) {
                Object v = j.opt(key);
                if (v != null && v != JSONObject.NULL) {
                    out.add(v);
                }
            }
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                walk(j.opt(it.next()), key, out);
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                walk(a.opt(i), key, out);
            }
        }
    }

    // ==================================================================
    // 简易 XPath（只覆盖 //tag[@attr="v"]/@attr 这种 meta 写法）
    // ==================================================================

    private List<Object> evalXpath(Document doc, String expr) {
        List<Object> out = new ArrayList<>();
        Pattern p = Pattern.compile(
                "^//([A-Za-z0-9]+)\\[@([\\w:.-]+)([~^$*]?)=[\"']?([^\"'\\]]*)[\"']?\\](?:/@([\\w:.-]+))?$");
        Matcher m = p.matcher(expr.trim());
        if (!m.find()) {
            return out;
        }
        String tag = m.group(1);
        String attr = m.group(2);
        String op = m.group(3);
        String val = m.group(4);
        String getter = m.group(5);
        Elements els;
        try {
            els = doc.select(tag);
        } catch (Exception e) {
            return out;
        }
        for (Element e : els) {
            String av = e.attr(attr);
            boolean hit;
            if ("~".equals(op)) {
                try {
                    hit = Pattern.compile(val).matcher(av).find();
                } catch (Exception ex) {
                    hit = av.contains(val);
                }
            } else if ("$".equals(op)) {
                hit = av.endsWith(val);
            } else if ("^".equals(op)) {
                hit = av.startsWith(val);
            } else if ("*".equals(op)) {
                hit = av.contains(val);
            } else {
                hit = av.equals(val);
            }
            if (!hit) {
                continue;
            }
            if (getter == null || getter.isEmpty()) {
                out.add(e);
            } else {
                String v = readValue(e, getter);
                if (!v.isEmpty()) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    // ==================================================================
    // 索引 / 切片
    // ==================================================================

    private static final class IndexSpec {
        String stripped;
        List<Integer> pick;
        List<Integer> skip;
        boolean slice;
        int start;
        int end;

        static IndexSpec parse(String step) {
            IndexSpec s = new IndexSpec();
            String t = step;
            Matcher m = Pattern.compile("\\[([^\\]]*)\\]$").matcher(t);
            if (m.find()) {
                String inside = m.group(1).trim();
                String before = t.substring(0, m.start());
                if (inside.contains(":")) {
                    int c = inside.indexOf(':');
                    s.slice = true;
                    s.start = parseInt(inside.substring(0, c), 0);
                    String r = inside.substring(c + 1).trim();
                    s.end = r.isEmpty() ? Integer.MAX_VALUE : parseInt(r, Integer.MAX_VALUE);
                    s.stripped = before;
                    return s;
                }
                if (inside.startsWith("!") || inside.contains(",")) {
                    s.skip = parseInts(inside.replace("!", ""));
                    s.stripped = before;
                    return s;
                }
                s.pick = parseInts(inside);
                s.stripped = before;
                return s;
            }
            m = Pattern.compile("!(-?\\d+)$").matcher(t);
            if (m.find()) {
                s.skip = new ArrayList<>();
                s.skip.add(Integer.parseInt(m.group(1)));
                s.stripped = t.substring(0, m.start());
                return s;
            }
            m = Pattern.compile("\\.(-?\\d+(?::-?\\d+)*)$").matcher(t);
            if (m.find()) {
                s.pick = parseInts(m.group(1));
                s.stripped = t.substring(0, m.start());
                return s;
            }
            s.stripped = t;
            return s;
        }

        private static int parseInt(String s, int def) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Exception e) {
                return def;
            }
        }

        private static List<Integer> parseInts(String s) {
            List<Integer> out = new ArrayList<>();
            for (String p : s.split("[,\\s]+")) {
                if (!p.isEmpty()) {
                    try {
                        out.add(Integer.parseInt(p));
                    } catch (Exception ignored) {
                    }
                }
            }
            return out;
        }

        List<Element> apply(List<Element> in) {
            int n = in.size();
            if (pick != null && !pick.isEmpty()) {
                List<Element> out = new ArrayList<>();
                for (int i : pick) {
                    int k = i < 0 ? n + i : i;
                    if (k >= 0 && k < n) {
                        out.add(in.get(k));
                    }
                }
                return out;
            }
            if (skip != null && !skip.isEmpty()) {
                Set<Integer> drop = new HashSet<>();
                for (int i : skip) {
                    int k = i < 0 ? n + i : i;
                    drop.add(k);
                }
                List<Element> out = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    if (!drop.contains(i)) {
                        out.add(in.get(i));
                    }
                }
                return out;
            }
            if (slice) {
                int a = start;
                int b = end;
                if (a < 0) {
                    a = n + a;
                }
                if (b < 0) {
                    b = n + b;
                }
                if (b == Integer.MAX_VALUE) {
                    b = n - 1;
                }
                a = Math.max(0, Math.min(n - 1, a));
                b = Math.max(0, Math.min(n - 1, b));
                List<Element> out = new ArrayList<>();
                if (a <= b) {
                    for (int i = a; i <= b; i++) {
                        out.add(in.get(i));
                    }
                } else {
                    for (int i = a; i >= b; i--) {
                        out.add(in.get(i));
                    }
                }
                return out;
            }
            return in;
        }
    }

    // ==================================================================
    // 顶层分隔（忽略 [] {} () 内部与转义字符）
    // ==================================================================

    public static List<String> splitTop(String s, String sep) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == '[' || c == '{' || c == '(') {
                depth++;
                continue;
            }
            if (c == ']' || c == '}' || c == ')') {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (depth == 0 && s.startsWith(sep, i)) {
                out.add(s.substring(start, i));
                i += sep.length() - 1;
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    private static int topLevelIndex(String s, char sep) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == '[' || c == '{' || c == '(') {
                depth++;
            } else if (c == ']' || c == '}' || c == ')') {
                if (depth > 0) {
                    depth--;
                }
            } else if (depth == 0 && c == sep) {
                return i;
            }
        }
        return -1;
    }

    private static final Pattern OPT = Pattern.compile(
            "[\\\"']?([A-Za-z_][A-Za-z0-9_]*)[\\\"']?\\s*:\\s*(?:\\\"((?:[^\\\"\\\\]|\\\\.)*)\\\"|'((?:[^'\\\\]|\\\\.)*)'|([^,}\\s]+))");

    private static Map<String, String> parseOptions(String opt) {
        Map<String, String> map = new LinkedHashMap<>();
        if (opt == null || opt.trim().isEmpty()) {
            return map;
        }
        Matcher m = OPT.matcher(opt);
        while (m.find()) {
            String k = m.group(1);
            String v = m.group(2) != null ? m.group(2)
                    : (m.group(3) != null ? m.group(3) : m.group(4));
            if (k != null && v != null) {
                map.put(k.toLowerCase(Locale.ROOT), v.replace("\\\"", "\"").replace("\\'", "'"));
            }
        }
        return map;
    }

    private static Map<String, String> parseHeaderObject(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        if (s == null) {
            return out;
        }
        try {
            JSONObject j = new JSONObject(s);
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.put(k, j.optString(k));
            }
            return out;
        } catch (Exception ignored) {
        }
        Matcher m = Pattern.compile("[\\\"']?([\\w-]+)[\\\"']?\\s*:\\s*[\\\"']([^\\\"']*)[\\\"']")
                .matcher(s);
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }
}
