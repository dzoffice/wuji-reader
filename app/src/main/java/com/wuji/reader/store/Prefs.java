package com.wuji.reader.store;

import android.content.Context;
import android.content.SharedPreferences;

import com.wuji.reader.core.CrashGuard;

/** 阅读进度、字号等本地存储（v1 用 SharedPreferences，后续换 SQLite） */
public final class Prefs {

    private static final String NAME = "reader";
    private static final String K_NINE_GRID = "nine_grid";
    private static final String K_BG = "reader_bg";
    private static final String K_AUTO_SPEED = "reader_auto_speed";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /**
     * 下面三个读取一律做类型兜底：这台墨水屏从 v5/v6 时代就在用，
     * 若历史版本曾以别的类型写入同一个 key，SharedPreferences.getInt/getFloat
     * 会抛 ClassCastException；而调用点大多在主线程（onCreate / 点击监听），
     * 抛出去就是「已停止运行」。读不出来就用默认值，不影响使用。
     */
    public static int getProgress(Context c, String key) {
        try {
            return sp(c).getInt("progress_" + key, 0);
        } catch (Throwable t) {
            CrashGuard.log("Prefs.progress", t);
            return 0;
        }
    }

    public static void setProgress(Context c, String key, int idx) {
        try {
            sp(c).edit().putInt("progress_" + key, idx).apply();
        } catch (Throwable t) {
            CrashGuard.log("Prefs.progress-set", t);
        }
    }

    /**
     * 正文字号（阅读页正文）。默认 30：墨水屏上 19sp 太小。
     * 注意：正文用 dp 设置，不吃全局 fontScale，避免被连乘两次。
     */
    public static float getFontSp(Context c) {
        try {
            return sp(c).getFloat("font_sp", 30f);
        } catch (Throwable t) {
            CrashGuard.log("Prefs.fontSp", t);
            return 30f;
        }
    }

    public static void setFontSp(Context c, float v) {
        try {
            sp(c).edit().putFloat("font_sp", v).apply();
        } catch (Throwable t) {
            CrashGuard.log("Prefs.fontSp-set", t);
        }
    }

    public static int getComicPage(Context c, String key) {
        try {
            return sp(c).getInt("comicpage_" + key, 0);
        } catch (Throwable t) {
            CrashGuard.log("Prefs.comicPage", t);
            return 0;
        }
    }

    public static void setComicPage(Context c, String key, int v) {
        try {
            sp(c).edit().putInt("comicpage_" + key, v).apply();
        } catch (Throwable t) {
            CrashGuard.log("Prefs.comicPage-set", t);
        }
    }

    /** 九宫格 9 格的动作配置（存 JSON 数组）；读不出返回 null，交给上层用默认值 */
    public static int[] getNineGrid(Context c) {
        try {
            String s = sp(c).getString(K_NINE_GRID, "");
            if (s == null || s.isEmpty()) {
                return null;
            }
            org.json.JSONArray arr = new org.json.JSONArray(s);
            if (arr.length() != 9) {
                return null;
            }
            int[] out = new int[9];
            for (int i = 0; i < 9; i++) {
                out[i] = arr.optInt(i, 0);
            }
            return out;
        } catch (Throwable t) {
            CrashGuard.log("Prefs.nineGrid", t);
            return null;
        }
    }

    public static void setNineGrid(Context c, int[] cells) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (int v : cells) {
                arr.put(v);
            }
            sp(c).edit().putString(K_NINE_GRID, arr.toString()).apply();
        } catch (Throwable t) {
            CrashGuard.log("Prefs.nineGrid-set", t);
        }
    }

    /** 阅读背景色：0=白 1=米黄 2=护眼绿 3=灰，存索引 */
    public static int getBgIndex(Context c) {
        try {
            return sp(c).getInt(K_BG, 0);
        } catch (Throwable t) {
            CrashGuard.log("Prefs.bg", t);
            return 0;
        }
    }

    public static void setBgIndex(Context c, int v) {
        try {
            sp(c).edit().putInt(K_BG, v).apply();
        } catch (Throwable t) {
            CrashGuard.log("Prefs.bg-set", t);
        }
    }

    /**
     * 界面字号（全局，sp）。默认 30。
     * 它不直接改某个 View，而是换算成 fontScale 注入 Activity 的 Context，
     * 从而让所有 sp 文字一起放大（见 UiFont / BaseActivity）。
     */
    public static float getUiFontSp(Context c) {
        try {
            return sp(c).getFloat("ui_font_sp", 30f);
        } catch (Throwable t) {
            CrashGuard.log("Prefs.uiFontSp", t);
            return 30f;
        }
    }

    public static void setUiFontSp(Context c, float v) {
        try {
            sp(c).edit().putFloat("ui_font_sp", v).apply();
        } catch (Throwable t) {
            CrashGuard.log("Prefs.uiFontSp-set", t);
        }
    }

    /** 自动滚动速度档位：0=慢 1=中 2=快 */
    public static int getAutoSpeed(Context c) {
        try {
            return sp(c).getInt(K_AUTO_SPEED, 1);
        } catch (Throwable t) {
            CrashGuard.log("Prefs.autoSpeed", t);
            return 1;
        }
    }

    public static void setAutoSpeed(Context c, int v) {
        try {
            sp(c).edit().putInt(K_AUTO_SPEED, v).apply();
        } catch (Throwable t) {
            CrashGuard.log("Prefs.autoSpeed-set", t);
        }
    }

    // ---- 漫画放大翻页后的"顶点微调"（像素） ----
    // 默认：左移 3px、下移 3px —— 翻页贴左上时会露出图片自身的白边，
    // 用这个偏移把显示起点挪进画面内容里。用户可在漫画菜单里随时改。

    private static final String K_COMIC_OFF_X = "comic_off_x";
    private static final String K_COMIC_OFF_Y = "comic_off_y";

    /** X 偏移（负=左移） */
    public static int getComicOffX(Context c) {
        try {
            return sp(c).getInt(K_COMIC_OFF_X, -3);
        } catch (Throwable t) {
            CrashGuard.log("Prefs.comicOffX", t);
            return -3;
        }
    }

    /** Y 偏移（正=下移） */
    public static int getComicOffY(Context c) {
        try {
            return sp(c).getInt(K_COMIC_OFF_Y, 3);
        } catch (Throwable t) {
            CrashGuard.log("Prefs.comicOffY", t);
            return 3;
        }
    }

    public static void setComicOff(Context c, int x, int y) {
        try {
            sp(c).edit().putInt(K_COMIC_OFF_X, x).putInt(K_COMIC_OFF_Y, y).apply();
        } catch (Throwable t) {
            CrashGuard.log("Prefs.comicOff-set", t);
        }
    }
}
