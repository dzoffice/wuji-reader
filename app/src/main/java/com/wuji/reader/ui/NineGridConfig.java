package com.wuji.reader.ui;

import android.content.Context;

import com.wuji.reader.store.Prefs;

/**
 * 九宫格布局：3×3 共 9 格。
 *
 * 索引排列（0 = 左上，8 = 右下）：
 *   0 1 2
 *   3 4 5
 *   6 7 8
 */
public final class NineGridConfig {

    public static final int CELLS = 9;
    public static final int COLS = 3;
    public static final int ROWS = 3;

    /**
     * 默认布局（按需求）：
     *   左上 = 清屏；顶部两侧（上中 + 右上）= 阅读菜单；
     *   左下 = 上一页（屏幕翻页）；右下 = 下一页（屏幕翻页）；中下 = 目录。
     * 中间三格留空，避免误触。
     *
     * 注意：上一页/下一页是"整屏翻页"（按可视高度滚动），与底部栏的
     * 上一章/下一章（切换章节）是两个不同概念，用户明确要的是"页"不是"章"。
     */
    private static final int[] DEFAULT = {
            NineGridAction.CLEAR_SCREEN.ordinal(),
            NineGridAction.MENU.ordinal(),
            NineGridAction.MENU.ordinal(),
            NineGridAction.NONE.ordinal(),
            NineGridAction.NONE.ordinal(),
            NineGridAction.NONE.ordinal(),
            NineGridAction.PREV_PAGE.ordinal(),
            NineGridAction.TOC.ordinal(),
            NineGridAction.NEXT_PAGE.ordinal()
    };

    private NineGridConfig() {
    }

    /** 读用户配置；读不到/格式坏 → 返回默认布局 */
    public static int[] load(Context c) {
        int[] saved = Prefs.getNineGrid(c);
        if (saved == null || saved.length != CELLS) {
            return DEFAULT.clone();
        }
        return saved;
    }

    public static void save(Context c, int[] cells) {
        if (cells == null || cells.length != CELLS) {
            return;
        }
        Prefs.setNineGrid(c, cells);
    }

    public static int[] defaults() {
        return DEFAULT.clone();
    }

    public static NineGridAction actionAt(int[] cells, int index) {
        if (cells == null || index < 0 || index >= cells.length) {
            return NineGridAction.NONE;
        }
        return NineGridAction.byId(cells[index]);
    }

    /** 格子 (row,col) → 索引 */
    public static int indexOf(int row, int col) {
        if (row < 0 || row >= ROWS || col < 0 || col >= COLS) {
            return -1;
        }
        return row * COLS + col;
    }

    /** 给人看的格子位置名，如「左上」「中下」 */
    public static String positionName(int index) {
        if (index < 0 || index >= CELLS) {
            return "";
        }
        String[] rows = {"上", "中", "下"};
        String[] cols = {"左", "中", "右"};
        return rows[index / COLS] + cols[index % COLS];
    }
}
