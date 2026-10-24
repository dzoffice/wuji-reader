package com.wuji.reader.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.wuji.reader.R;

/**
 * 九宫格布局编辑页。
 *
 * 3×3 网格，点任意一格 → 弹出动作列表选功能 → 立刻保存。
 * 墨水屏上格子要大（整格 ≥ 100dp 高），点不准是最大的体验问题。
 */
public class NineGridSettingsActivity extends BaseActivity {

    private final Button[] cells = new Button[9];
    private int[] layout = NineGridConfig.defaults();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nine_grid);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        int[] ids = {
                R.id.cell0, R.id.cell1, R.id.cell2,
                R.id.cell3, R.id.cell4, R.id.cell5,
                R.id.cell6, R.id.cell7, R.id.cell8
        };
        for (int i = 0; i < 9; i++) {
            Button b = findViewById(ids[i]);
            cells[i] = b;
            final int idx = i;
            b.setOnClickListener(v -> pickAction(idx));
        }

        findViewById(R.id.btnReset).setOnClickListener(v -> {
            layout = NineGridConfig.defaults();
            NineGridConfig.save(this, layout);
            refresh();
            toast("已恢复默认布局");
        });

        // 读用户已保存的配置
        int[] saved = NineGridConfig.load(this);
        if (saved != null) {
            layout = saved;
        }
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < 9; i++) {
            NineGridAction act = NineGridConfig.actionAt(layout, i);
            String pos = NineGridConfig.positionName(i);
            cells[i].setText(pos + "\n" + act.label);
        }
    }

    private void pickAction(int idx) {
        final NineGridAction[] all = NineGridAction.values();
        String[] names = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            names[i] = all[i].label;
        }
        new AlertDialog.Builder(this)
                .setTitle(NineGridConfig.positionName(idx) + " 区设置")
                .setItems(names, (d, which) -> {
                    layout[idx] = all[which].ordinal();
                    NineGridConfig.save(this, layout);
                    refresh();
                    toast(NineGridConfig.positionName(idx) + " → " + all[which].label);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
