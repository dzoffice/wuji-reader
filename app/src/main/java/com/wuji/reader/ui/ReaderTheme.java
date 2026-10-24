package com.wuji.reader.ui;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import com.wuji.reader.store.Prefs;

/**
 * 阅读背景色主题。
 *
 * 墨水屏取向：背景色只能选"整页纯色"，不做渐变、不做图片。
 * 文字一律纯黑（ink_black），保证电子墨水的高对比可读性。
 *
 * 索引含义（与 {@link com.wuji.reader.store.Prefs#getBgIndex} 一致）：
 *   0 = 白色，1 = 米黄，2 = 护眼绿，3 = 灰色。
 */
public final class ReaderTheme {

    public static final int WHITE = 0;
    public static final int CREAM = 1;
    public static final int GREEN = 2;
    public static final int GRAY = 3;
    public static final int COUNT = 4;

    private static final int[] BG = {
            0xFFFFFFFF, // 白
            0xFFF3E9D2, // 米黄
            0xFFCFE8D2, // 护眼绿
            0xFFE2E2E2  // 灰
    };

    private static final String[] LABELS = {"白色", "米黄", "护眼绿", "灰色"};

    private ReaderTheme() {
    }

    /** 当前应使用的背景色（ARGB 整型） */
    public static int bgColor(Context c) {
        int idx = Prefs.getBgIndex(c);
        if (idx < 0 || idx >= COUNT) {
            idx = WHITE;
        }
        return BG[idx];
    }

    public static String[] labels() {
        return LABELS.clone();
    }

    /** 把背景色同时刷到整页 root 与正文 TextView（文字保持黑） */
    public static void apply(Context c, View root, TextView content) {
        int col = bgColor(c);
        if (root != null) {
            root.setBackgroundColor(col);
        }
        if (content != null) {
            content.setBackgroundColor(col);
        }
    }
}
