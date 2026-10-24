package com.wuji.reader.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import com.wuji.reader.store.Prefs;

/**
 * 全局字号（界面字号）。
 *
 * 实现方式：**不改任何一个布局的 android:textSize**，而是给每个 Activity 的
 * Context 注入一个 fontScale（= 用户字号 / 布局基准字号 BASE_SP）。
 * 布局里写的都是 sp，sp 会乘以 fontScale，所以整个 App 的文字
 * —— 书源名、书名、简介、目录、新闻、漫画、按钮、菜单、对话框、Adapter 条目
 * —— 会一起放大，不需要逐个文件改，也不会漏。
 *
 * ⚠️ 阅读正文必须绕开这套缩放：正文用
 * {@code setTextSize(TypedValue.COMPLEX_UNIT_DIP, x)}，
 * 因为 dp 不吃 fontScale；否则会被「全局缩放 × 正文字号」连乘两次而失控。
 *
 * 默认 30：墨水屏 1872×1404 下 16sp 太小，书源名/书名/简介都看不清。
 */
public final class UiFont {

    /** 布局里正文/列表的名义基准字号（sp） */
    public static final float BASE_SP = 16f;
    public static final float MIN_SP = 22f;
    public static final float MAX_SP = 44f;
    public static final float DEFAULT_SP = 30f;

    private UiFont() {
    }

    /** 用户设定的界面字号（sp），已做范围收敛 */
    public static float sp(Context c) {
        float v = Prefs.getUiFontSp(c);
        if (v < MIN_SP) {
            v = MIN_SP;
        }
        if (v > MAX_SP) {
            v = MAX_SP;
        }
        return v;
    }

    /** fontScale 倍率 */
    public static float scale(Context c) {
        return sp(c) / BASE_SP;
    }

    /** 把 fontScale 注入 Context（在 Activity.attachBaseContext 里调用） */
    public static Context wrap(Context c) {
        try {
            Resources res = c.getResources();
            Configuration cfg = new Configuration(res.getConfiguration());
            cfg.fontScale = scale(c);
            return c.createConfigurationContext(cfg);
        } catch (Throwable t) {
            // 拿不到资源就原样返回，绝不让字号问题把 App 拖崩
            return c;
        }
    }
}
