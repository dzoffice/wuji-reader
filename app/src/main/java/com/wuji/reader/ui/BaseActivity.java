package com.wuji.reader.ui;

import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 所有 Activity 的基类，只干一件事：把全局字号（fontScale）注入进来。
 *
 * 这样布局里的 sp 会被统一放大/缩小，无需逐个改布局。
 * 另外处理"改完字号回到上一个页面"的刷新：onResume 时比对倍率，
 * 变了就 recreate 自己，让新字号立刻生效（已存在的 Activity 不会自动重建）。
 */
public abstract class BaseActivity extends AppCompatActivity {

    private float appliedScale = -1f;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(UiFont.wrap(newBase));
    }

    @Override
    protected void onResume() {
        super.onResume();
        float s = UiFont.scale(this);
        if (appliedScale < 0f) {
            // 首次进入：记下当前倍率
            appliedScale = s;
            return;
        }
        if (Math.abs(s - appliedScale) > 0.01f) {
            // 字号被改过：重建自己，让新 fontScale 生效
            appliedScale = s;
            recreate();
        }
    }
}
