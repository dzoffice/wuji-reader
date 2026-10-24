package com.wuji.reader.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 可拖拽的右侧滚动条（原生 ScrollView 自带滚动条只能看不能拖）。
 *
 * 视觉：右侧 6dp 纯黑圆角滑块 + 白色轨道，墨水屏高对比；
 * 触控热区整条 28dp 宽，手指不必精确按在细滑块上。
 *
 * 交互：按下/拖动 → 滑块中心跟手 → 内容按比例直接跳转；
 * 手指滑 ScrollView 时滑块反向同步（bind 内部挂 ObservableScrollView 监听）。
 * 内容不足一屏时不画滑块、不响应拖动。
 */
public class FastScrollBar extends View {

    private static final float THUMB_W_DP = 6f;
    private static final float TOUCH_W_DP = 28f;
    private static final float MIN_THUMB_H_DP = 48f;

    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private ObservableScrollView target;
    private float dpu;
    private float total;
    private float visible;

    public FastScrollBar(Context c) {
        super(c);
        init();
    }

    public FastScrollBar(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public FastScrollBar(Context c, AttributeSet a, int s) {
        super(c, a, s);
        init();
    }

    private void init() {
        dpu = getResources().getDisplayMetrics().density;
        thumb.setColor(0xFF000000);
    }

    /** 绑定右侧滚动区；重复调用会覆盖旧监听 */
    public void bind(ObservableScrollView sv) {
        target = sv;
        sv.setListener((y, max) -> sync());
        sync();
    }

    private void sync() {
        View child = target == null ? null : target.getChildAt(0);
        total = child == null ? 0 : child.getHeight();
        visible = target == null ? 0 : target.getHeight();
        invalidate();
    }

    private boolean draggable() {
        return target != null && total > 0 && visible > 0 && visible < total
                && target.maxScroll() > 0;
    }

    private float thumbHeight() {
        float h = getHeight();
        return Math.max(MIN_THUMB_H_DP * dpu, visible / total * h);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFFFFFFFF);
        if (!draggable()) {
            return;
        }
        float h = getHeight();
        float th = thumbHeight();
        float y = target.getScrollY() / (float) target.maxScroll() * (h - th);
        float w = THUMB_W_DP * dpu;
        rect.set(getWidth() - w, y, getWidth(), y + th);
        c.drawRoundRect(rect, w / 2f, w / 2f, thumb);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!draggable()) {
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                jumpTo(ev.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    /** 滑块中心对齐手指，内容按比例跳转（墨水屏无动画，直接 scrollTo） */
    private void jumpTo(float y) {
        int max = target.maxScroll();
        if (max <= 0) {
            return;
        }
        float h = getHeight();
        float th = thumbHeight();
        float frac = (y - th / 2f) / Math.max(1f, h - th);
        if (frac < 0f) {
            frac = 0f;
        }
        if (frac > 1f) {
            frac = 1f;
        }
        target.scrollTo(0, Math.round(frac * max));
    }
}
