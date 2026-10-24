package com.wuji.reader.legado;

import org.jsoup.parser.Parser;

/**
 * 把正文 HTML 洗成墨水屏友好的纯文本：
 * br / p / div 转换行，去脚本样式，去标签，压缩空行。
 */
public final class Html {

    private Html() {
    }

    public static String toText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String s = html;
        s = s.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</p\\s*>", "\n");
        s = s.replaceAll("(?i)</div\\s*>", "\n");
        s = s.replaceAll("(?i)</li\\s*>", "\n");
        s = s.replaceAll("(?i)</h[1-6]\\s*>", "\n");
        s = s.replaceAll("<[^>]+>", "");
        s = Parser.unescapeEntities(s, false);
        s = s.replace('\u00a0', ' ').replace('\u3000', ' ');
        String[] lines = s.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder();
        int blank = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                blank++;
                if (blank > 1) {
                    continue;
                }
            } else {
                blank = 0;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t);
        }
        return sb.toString().trim();
    }
}
