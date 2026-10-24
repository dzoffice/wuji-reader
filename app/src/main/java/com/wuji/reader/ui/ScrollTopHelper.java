package com.wuji.reader.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

/**
 * 回顶按钮 + 右侧滑动栏（墨水屏取供：无动画、纯黑白）。
 *
 *  - attach(RecyclerView, Activity)：右下角「回到顶部」按钮（滚动超约 2 屏才显示）
 *    + 右侧 3dp 黑色滑动栏（高度 = 可见/总 比例，随滚动位移）；点击回顶。
 *  - attach(ScrollView, Activity)：只加回顶按钮。
 *
 * 返回值是解绑 Runnable，调用方在 onDestroy 里执行；
 * 挂载失败（无 content 根视图）返回 null，调用方需判空。
 * 全部操作都在 UI 线程（滚动回调 / 全局布局回调均为主线程）。
 */
public final class ScrollTopHelper {

    /** 底栏一般 52dp，按钮抬到底栏之上 */
    private static final int BOTTOM_MARGIN_DP = 64;
    private static final int BAR_WIDTH_DP = 3;
    private static final int BAR_MIN_HEIGHT_DP = 24;
    private static final int BTN_W_DP = 64;
    private static final int BTN_H_DP = 40;

    private ScrollTopHelper() {
    }

    // ------------------------------------------------------------------
    // RecyclerView：回顶按钮 + 滑动栏
    // ------------------------------------------------------------------

    public static Runnable attach(RecyclerView rv, Activity a) {
        if (rv == null || a == null) {
            return null;
        }
        final ViewGroup root = (ViewGroup) a.findViewById(android.R.id.content);
        if (root == null) {
            return null;
        }

        final TextView btn = makeTopButton(a);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                dip(a, BTN_W_DP), dip(a, BTN_H_DP));
        blp.gravity = Gravity.END | Gravity.BOTTOM;
        blp.setMargins(0, 0, dip(a, 8), dip(a, BOTTOM_MARGIN_DP));
        root.addView(btn, blp);

        final View bar = new View(a);
        bar.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                dip(a, BAR_WIDTH_DP), dip(a, BAR_MIN_HEIGHT_DP));
        barLp.gravity = Gravity.END | Gravity.TOP;
        root.addView(bar, barLp);

        btn.setVisibility(View.GONE);
        bar.setVisibility(View.GONE);

        final Runnable updater = new Runnable() {
            @Override
            public void run() {
                int h = root.getHeight();
                if (h <= 0) {
                    return;
                }
                int offset = rv.computeVerticalScrollOffset();
                int range = rv.computeVerticalScrollRange();
                int extent = rv.computeVerticalScrollExtent();
                // 滚动超约 2 屏才显示回顶按钮
                btn.setVisibility(offset > rv.getHeight() * 2 ? View.VISIBLE : View.GONE);
                if (range <= 0 || extent <= 0 || range <= extent) {
                    bar.setVisibility(View.GONE);
                    return;
                }
                int bh = Math.max(dip(a, BAR_MIN_HEIGHT_DP), (int) ((long) extent * h / range));
                bh = Math.min(bh, h);
                int top = (int) ((long) offset * (h - bh) / (range - extent));
                ViewGroup.LayoutParams lp = bar.getLayoutParams();
                if (lp != null) {
                    lp.height = bh;
                    bar.setLayoutParams(lp);
                }
                bar.setTranslationY(top);
                bar.setVisibility(View.VISIBLE);
            }
        };

        final RecyclerView.OnScrollListener sl = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updater.run();
            }
        };
        rv.addOnScrollListener(sl);

        final ViewTreeObserver.OnGlobalLayoutListener gl = updater::run;
        rv.getViewTreeObserver().addOnGlobalLayoutListener(gl);

        btn.setOnClickListener(v -> rv.scrollToPosition(0));
        updater.run();

        return () -> {
            rv.removeOnScrollListener(sl);
            ViewTreeObserver obs = rv.getViewTreeObserver();
            if (obs != null && obs.isAlive()) {
                obs.removeOnGlobalLayoutListener(gl);
            }
            root.removeView(btn);
            root.removeView(bar);
        };
    }

    // ------------------------------------------------------------------
    // ScrollView：只加回顶按钮
    // ------------------------------------------------------------------

    public static Runnable attach(ScrollView sv, Activity a) {
        if (sv == null || a == null) {
            return null;
        }
        final ViewGroup root = (ViewGroup) a.findViewById(android.R.id.content);
        if (root == null) {
            return null;
        }

        final TextView btn = makeTopButton(a);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                dip(a, BTN_W_DP), dip(a, BTN_H_DP));
        blp.gravity = Gravity.END | Gravity.BOTTOM;
        blp.setMargins(0, 0, dip(a, 8), dip(a, BOTTOM_MARGIN_DP));
        root.addView(btn, blp);
        btn.setVisibility(View.GONE);

        final Runnable updater = () -> {
            int y = sv.getScrollY();
            btn.setVisibility(y > sv.getHeight() * 2 ? View.VISIBLE : View.GONE);
        };

        final ViewTreeObserver.OnScrollChangedListener scl = updater::run;
        sv.getViewTreeObserver().addOnScrollChangedListener(scl);

        final ViewTreeObserver.OnGlobalLayoutListener gl = updater::run;
        sv.getViewTreeObserver().addOnGlobalLayoutListener(gl);

        btn.setOnClickListener(v -> sv.scrollTo(0, 0));
        updater.run();

        return () -> {
            ViewTreeObserver obs = sv.getViewTreeObserver();
            if (obs != null && obs.isAlive()) {
                obs.removeOnScrollChangedListener(scl);
                obs.removeOnGlobalLayoutListener(gl);
            }
            root.removeView(btn);
        };
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static TextView makeTopButton(Activity a) {
        TextView b = new TextView(a);
        b.setText("回到顶部");
        b.setTextSize(13);
        b.setTextColor(Color.BLACK);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setStroke(dip(a, 2), Color.BLACK);
        b.setBackground(bg);
        return b;
    }

    private static int dip(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
