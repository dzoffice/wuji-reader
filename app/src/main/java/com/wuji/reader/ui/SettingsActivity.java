package com.wuji.reader.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.wuji.reader.R;
import com.wuji.reader.store.Prefs;
import com.wuji.reader.ui.ReaderTheme;

/**
 * 设置页。
 *
 * 墨水屏取向：整页就是几行大按钮，没有二级复杂表单。
 * 九宫格布局的入口在这里（用户需求：设置中增加入口）。
 */
public class SettingsActivity extends BaseActivity {

    private TextView tvFont;
    private float fontSp = 30f;
    /** 界面字号（全局），改完要 recreate 才生效 */
    private float uiFontSp = 30f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        tvFont = findViewById(R.id.tvFontValue);
        fontSp = Prefs.getFontSp(this);
        uiFontSp = Prefs.getUiFontSp(this);
        updateFontLabel();
        updateUiFontLabel();
        updateBgLabel();

        // 九宫格布局：核心入口
        findViewById(R.id.btnNineGrid).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, NineGridSettingsActivity.class)));

        findViewById(R.id.btnFontMinus).setOnClickListener(v -> changeFont(-2f));
        findViewById(R.id.btnFontPlus).setOnClickListener(v -> changeFont(2f));

        findViewById(R.id.btnUiFontMinus).setOnClickListener(v -> changeUiFont(-2f));
        findViewById(R.id.btnUiFontPlus).setOnClickListener(v -> changeUiFont(2f));

        findViewById(R.id.btnBg).setOnClickListener(v -> pickBg());

        // 「关于本软件」：打开完整的关于页（上游声明 / 版本 / GPL-3.0 / 特性 / 墨水屏历程）
        findViewById(R.id.btnAbout).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, AboutActivity.class)));
    }

    /**
     * 界面字号：影响全部 sp 文字（书源名/书名/简介/目录/新闻/漫画/菜单）。
     * 它是注入 Context 的 fontScale，改完必须重建 Activity 才能生效。
     */
    private void changeUiFont(float delta) {
        uiFontSp += delta;
        if (uiFontSp < UiFont.MIN_SP) {
            uiFontSp = UiFont.MIN_SP;
        }
        if (uiFontSp > UiFont.MAX_SP) {
            uiFontSp = UiFont.MAX_SP;
        }
        Prefs.setUiFontSp(this, uiFontSp);
        updateUiFontLabel();
        Toast.makeText(this, "界面字号 " + Math.round(uiFontSp) + " sp", Toast.LENGTH_SHORT).show();
        recreate();
    }

    private void updateUiFontLabel() {
        TextView tv = findViewById(R.id.tvUiFontValue);
        if (tv != null) {
            tv.setText(Math.round(uiFontSp) + " sp");
        }
    }

    private void updateBgLabel() {
        TextView tv = findViewById(R.id.tvBgValue);
        if (tv != null) {
            int idx = Prefs.getBgIndex(this);
            String[] labels = ReaderTheme.labels();
            tv.setText((idx >= 0 && idx < labels.length) ? labels[idx] : labels[0]);
        }
    }

    private void pickBg() {
        String[] labels = ReaderTheme.labels();
        int cur = Prefs.getBgIndex(this);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("阅读背景色")
                .setSingleChoiceItems(labels, cur, (d, which) -> {
                    Prefs.setBgIndex(this, which);
                    updateBgLabel();
                    d.dismiss();
                    Toast.makeText(this, "背景色：" + labels[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateFontLabel() {
        if (tvFont != null) {
            tvFont.setText(Math.round(fontSp) + " sp");
        }
    }

    private void changeFont(float delta) {
        fontSp += delta;
        if (fontSp < 14f) {
            fontSp = 14f;
        }
        if (fontSp > 44f) {
            fontSp = 44f;
        }
        Prefs.setFontSp(this, fontSp);
        updateFontLabel();
        Toast.makeText(this, "正文字号 " + Math.round(fontSp) + " sp", Toast.LENGTH_SHORT).show();
    }
}
