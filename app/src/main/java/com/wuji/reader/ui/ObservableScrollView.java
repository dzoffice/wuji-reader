package com.wuji.reader.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ScrollView;

/**
 * 把滚动位置暴露成监听的 ScrollView（供 FastScrollBar 双向联动）。
 *
 * onScrollChanged 与 onLayout 都回调 listener：前者跟随手指滚动，
 * 后者覆盖"正文 setText / 字号变化导致内容高度改变"的场景，
 * 保证滑块位置与长度始终和内容同步。
 */
public class ObservableScrollView extends ScrollView {

    public interface Listener {
        void onScrolled(int scrollY, int maxScroll);
    }

    private Listener listener;

    public ObservableScrollView(Context c) {
        super(c);
    }

    public ObservableScrollView(Context c, AttributeSet a) {
        super(c, a);
    }

    public ObservableScrollView(Context c, AttributeSet a, int s) {
        super(c, a, s);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        notifyListener();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        notifyListener();
    }

    /** 当前可滚动最大距离（未布局完返回 0） */
    public int maxScroll() {
        View child = getChildAt(0);
        if (child == null || getHeight() <= 0) {
            return 0;
        }
        return Math.max(0, child.getHeight() - getHeight());
    }

    private void notifyListener() {
        if (listener != null) {
            listener.onScrolled(getScrollY(), maxScroll());
        }
    }
}
