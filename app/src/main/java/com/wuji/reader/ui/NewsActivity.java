package com.wuji.reader.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wuji.reader.R;
import com.wuji.reader.core.Tasks;
import com.wuji.reader.news.HotApiClient;
import com.wuji.reader.news.HotBoard;
import com.wuji.reader.news.HotItem;
import com.wuji.reader.news.NewsRegistry;
import com.wuji.reader.news.TimeFmt;
import com.wuji.reader.source.SourceManager;
import com.wuji.reader.ui.adapter.NewsAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * 新闻热榜页。
 *
 * 交互铁律：本项目没有任何 WebView（这是整个项目存在的原因），
 * 点击新闻条目不打开网页，改为弹 AlertDialog 展示详情 + 「复制链接」。
 *
 * 加载策略（复刻 wuji hot-api 经验）：分批并发 6 / 单榜超时 10s / 总预算 30s /
 * 渐进渲染；默认选中第一个真正有内容的榜（至少一条 title 非空），
 * 一个能看的都没有时显示失败原因汇总——老设备没有控制台，这是唯一排查手段。
 */
public class NewsActivity extends BaseActivity {

    private LinearLayout boardRow;
    private TextView status;
    private NewsAdapter adapter;

    private final List<HotBoard> boards = new ArrayList<>();
    private int selected = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_news);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        boardRow = findViewById(R.id.boardRow);
        status = findViewById(R.id.status);

        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new NewsAdapter(this::showItem);
        recycler.setAdapter(adapter);

        buildBoardRow();
        loadAll();
    }

    // ------------------------------------------------------------------
    // 结构搭建
    // ------------------------------------------------------------------

    private void buildBoardRow() {
        boardRow.removeAllViews();
        // 榜单 chips 与 HotApiClient 拉取列表保持同一来源：启用过滤 + 排序
        List<String> types = SourceManager.newsTypes();
        for (int i = 0; i < types.size(); i++) {
            final int idx = i;
            Button btn = makeChip(NewsRegistry.labelOf(types.get(i)));
            setChipSelected(btn, i == selected);
            btn.setOnClickListener(v -> selectBoard(idx));
            boardRow.addView(btn);
        }
    }

    private void refreshChips() {
        for (int j = 0; j < boardRow.getChildCount(); j++) {
            setChipSelected((Button) boardRow.getChildAt(j), j == selected);
        }
    }

    private void selectBoard(int idx) {
        if (idx < 0 || idx >= boards.size()) {
            return;
        }
        selected = idx;
        refreshChips();

        HotBoard b = boards.get(idx);
        // 过滤 title 为空的条目（百度榜实测只回 title 为 undefined 的空记录），
        // 避免出现"共 1 条"但列表空白的误导状态
        List<HotItem> show = new ArrayList<>();
        for (HotItem it : b.data) {
            if (it != null && it.title != null && !it.title.trim().isEmpty()) {
                show.add(it);
            }
        }
        adapter.setData(show);
        if (b.isFailed()) {
            status.setText(b.label + " 加载失败：" + b.failReason);
        } else if (show.isEmpty()) {
            status.setText(b.label + " 暂无有效内容");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(b.label).append(" · 共 ").append(show.size()).append(" 条");
            if (b.title != null && !b.title.isEmpty()) {
                sb.append(" · ").append(b.title);
            }
            String upd = TimeFmt.shortOfIso(b.updateTime);
            if (!upd.isEmpty()) {
                sb.append(" · 更新于 ").append(upd);
            }
            status.setText(sb.toString());
        }
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    private void loadAll() {
        status.setText(R.string.loading);
        Tasks.io(() -> {
            HotApiClient client = new HotApiClient();
            client.fetch(new HotApiClient.Callback() {
                @Override
                public void onBatch(List<HotBoard> snapshot) {
                    Tasks.ui(() -> applySnapshot(snapshot));
                }

                @Override
                public void onFinish(List<HotBoard> snapshot) {
                    Tasks.ui(() -> applySnapshot(snapshot));
                }
            });
        });
    }

    private void applySnapshot(List<HotBoard> snapshot) {
        boards.clear();
        boards.addAll(snapshot);

        // 默认选中第一个真正有内容的榜；用户手动点过之后不再自动改
        if (selected < 0) {
            for (int i = 0; i < boards.size(); i++) {
                if (boards.get(i).hasContent()) {
                    selected = i;
                    break;
                }
            }
        }

        if (selected < 0) {
            // 一个能看的榜都没有：汇总失败原因
            adapter.setData(new ArrayList<>());
            status.setText(failureSummary());
            return;
        }
        selectBoard(selected);
    }

    private String failureSummary() {
        StringBuilder sb = new StringBuilder("所有榜单均无可用内容：");
        int shown = 0;
        int failed = 0;
        for (HotBoard b : boards) {
            if (b.hasContent()) {
                continue;
            }
            failed++;
            if (shown < 8) {
                if (shown > 0) {
                    sb.append("；");
                }
                sb.append(b.label).append("：").append(b.isFailed() ? b.failReason : "空数据");
                shown++;
            }
        }
        if (failed > shown) {
            sb.append("；…等共 ").append(failed).append(" 个失败");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 条目点击：无 WebView，弹窗 + 复制链接
    // ------------------------------------------------------------------

    /**
     * 点击新闻条目：能读正文就直接进正文页，不再弹「链接地址」弹窗。
     * 只有既没有链接、又没有摘要（确实读不出正文）时才回退到详情弹窗，
     * 让用户至少能看到标题并复制链接。
     */
    private void showItem(HotItem item) {
        if (item == null) {
            return;
        }
        final String link = item.bestLink();
        boolean hasDesc = item.desc != null && !item.desc.trim().isEmpty();
        if (!link.isEmpty() || hasDesc) {
            openArticle(item, boardTypeOf());
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("标题：").append(item.title.isEmpty() ? "（无）" : item.title).append('\n');
        String time = TimeFmt.fullOf(item.timestamp);
        sb.append("时间：").append(time.isEmpty() ? "（无）" : time).append('\n');
        sb.append("热度：").append(item.hot.isEmpty() ? "（无）" : item.hot).append('\n');
        sb.append("作者：").append(item.author.isEmpty() ? "（无）" : item.author).append('\n');
        sb.append("链接：").append(link.isEmpty() ? "（无）" : link);

        new AlertDialog.Builder(this)
                .setTitle(item.title.isEmpty() ? "新闻详情" : item.title)
                .setMessage(sb.toString())
                .setPositiveButton("复制链接", (d, w) -> copyLink(link))
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 当前选中榜单的 type（weibo/zhihu/tieba...），供 BoardContent 分治抓取 */
    private String boardTypeOf() {
        if (selected >= 0 && selected < boards.size()) {
            return boards.get(selected).type;
        }
        return "";
    }

    /** 抓正文：不开网页，按榜单策略把正文拉下来本地抽纯文本 */
    private void openArticle(HotItem item, String boardType) {
        String link = item.bestLink();
        if (link.isEmpty() && (item.desc == null || item.desc.trim().isEmpty())) {
            Toast.makeText(this, "没有可读取的链接", Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent i = new android.content.Intent(this, ArticleActivity.class);
        i.putExtra("url", link);
        i.putExtra("title", item.title);
        i.putExtra("board", boardType);
        i.putExtra("desc", item.desc == null ? "" : item.desc);
        startActivity(i);
    }

    private void copyLink(String link) {
        if (link.isEmpty()) {
            Toast.makeText(this, "没有可复制的链接", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("新闻链接", link));
        Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    // 工具（样式对齐 MainActivity）
    // ------------------------------------------------------------------

    private Button makeChip(String text) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        Button b = new Button(this);
        b.setLayoutParams(lp);
        b.setMinWidth(dip(92));
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setPadding(dip(14), 0, dip(14), 0);
        b.setText(text);
        return b;
    }

    private void setChipSelected(Button b, boolean sel) {
        b.setBackgroundColor(ContextCompat.getColor(this,
                sel ? R.color.ink_black : R.color.ink_white));
        b.setTextColor(ContextCompat.getColor(this,
                sel ? R.color.ink_white : R.color.ink_black));
    }

    private int dip(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
