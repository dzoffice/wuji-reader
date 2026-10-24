package com.wuji.reader.news;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 热榜时间格式化。
 *
 * minSdk 23 且没有 coreLibraryDesugaring，禁止 java.time，统一走
 * SimpleDateFormat + 系统默认时区。
 *
 * 兼容三种上游时间形态：
 *   - 毫秒 epoch（13 位，今日热榜 item.timestamp 实测）；
 *   - 秒 epoch（10 位，防御别的榜）；
 *   - ISO 8601（board.updateTime 实测形如 2026-10-20T08:05:19.589Z）。
 * 解析不了一律返回空串——墨水屏老设备上「不显示」远好过崩溃或显示 1970。
 */
public final class TimeFmt {

    private static final String SHORT_PATTERN = "MM-dd HH:mm";
    private static final String FULL_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private TimeFmt() {
    }

    /** 列表 meta 用：10-20 08:08；解析失败返回空串 */
    public static String shortOf(String raw) {
        Long ms = parseEpochMs(raw);
        return ms == null ? "" : format(new Date(ms), SHORT_PATTERN);
    }

    /** 详情弹窗用：2026-10-20 08:08:08；解析失败返回空串 */
    public static String fullOf(String raw) {
        Long ms = parseEpochMs(raw);
        return ms == null ? "" : format(new Date(ms), FULL_PATTERN);
    }

    /** ISO 8601（上游 board.updateTime）→ 列表用短格式；不是该形态返回空串 */
    public static String shortOfIso(String iso) {
        if (iso == null || iso.trim().isEmpty()) {
            return "";
        }
        String s = iso.trim();
        SimpleDateFormat f = new SimpleDateFormat(
                s.contains(".") ? "yyyy-MM-dd'T'HH:mm:ss.SSS" : "yyyy-MM-dd'T'HH:mm:ss",
                Locale.US);
        try {
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            Date d = f.parse(s);
            if (d == null) {
                return "";
            }
            return format(d, SHORT_PATTERN);
        } catch (Exception e) {
            return "";
        }
    }

    private static Long parseEpochMs(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            int dot = s.indexOf('.');
            if (dot > 0) {
                s = s.substring(0, dot);
            }
            long v = Long.parseLong(s);
            if (v <= 0) {
                return null;
            }
            if (s.length() <= 10) {
                v *= 1000L; // 秒 → 毫秒
            }
            return Long.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String format(Date d, String pattern) {
        try {
            SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
            f.setTimeZone(java.util.TimeZone.getDefault());
            return f.format(d);
        } catch (Exception e) {
            return "";
        }
    }
}
