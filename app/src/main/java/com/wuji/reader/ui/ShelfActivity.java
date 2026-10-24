package com.wuji.reader.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wuji.reader.R;
import com.wuji.reader.model.Item;
import com.wuji.reader.source.BookSource;
import com.wuji.reader.source.ComicSource;
import com.wuji.reader.source.SourceManager;
import com.wuji.reader.store.Favorites;
import com.wuji.reader.ui.adapter.ItemAdapter;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 书架：本地收藏条目网格。点击回 DetailActivity，长按移出书架。
 */
public class ShelfActivity extends BaseActivity {

    private ItemAdapter adapter;
    private TextView tvCount;
    private TextView tvEmpty;
    private Runnable detachScrollTop;

    /** Item → 完整收藏 key / 是否小说（与当前列表同生命周期） */
    private final Map<Item, String> keyOf = new IdentityHashMap<>();
    private final Map<Item, Boolean> bookOf = new IdentityHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shelf);

        tvCount = findViewById(R.id.tvCount);
        tvEmpty = findViewById(R.id.tvEmpty);

        RecyclerView recycler = findViewById(R.id.recycler);
        int span = Math.max(2, Math.min(4,
                getResources().getConfiguration().screenWidthDp / 180));
        recycler.setLayoutManager(new GridLayoutManager(this, span));
        adapter = new ItemAdapter(this::openDetail);
        adapter.setOnItemLongClick(this::confirmRemove);
        recycler.setAdapter(adapter);
        detachScrollTop = com.wuji.reader.ui.ScrollTopHelper.attach(recycler, this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (detachScrollTop != null) {
            detachScrollTop.run();
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 同 MainActivity：旋转不重建 Activity，网格列数需按新宽度手动重算
        RecyclerView rv = findViewById(R.id.recycler);
        if (rv != null && rv.getLayoutManager() instanceof GridLayoutManager) {
            int span = Math.max(2, Math.min(4, newConfig.screenWidthDp / 180));
            ((GridLayoutManager) rv.getLayoutManager()).setSpanCount(span);
        }
    }

    private void refresh() {
        List<Item> items = Favorites.list(this);
        List<String> keys = Favorites.keys(this);
        keyOf.clear();
        bookOf.clear();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            String k = i < keys.size() ? keys.get(i) : "";
            keyOf.put(it, k);
            bookOf.put(it, Favorites.isBookKey(k));
        }
        adapter.setData(items);
        tvCount.setText(items.size() + " 条");
        boolean empty = items.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        findViewById(R.id.recycler).setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /** 打开详情：extras 与 MainActivity.openDetail 完全一致 */
    private void openDetail(Item it) {
        Boolean bookBoxed = bookOf.get(it);
        boolean isBook = bookBoxed != null && bookBoxed;
        String sourceId = it.sourceId == null ? "" : it.sourceId;

        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra(MainActivity.EXTRA_KIND, isBook ? "book" : "comic");
        i.putExtra(MainActivity.EXTRA_SOURCE_ID, sourceId);
        i.putExtra(MainActivity.EXTRA_SOURCE, indexOfSource(isBook, sourceId));
        i.putExtra(MainActivity.EXTRA_ID, it.id);
        i.putExtra(MainActivity.EXTRA_TITLE, it.title);
        i.putExtra(MainActivity.EXTRA_AUTHOR, it.author);
        i.putExtra(MainActivity.EXTRA_COVER, it.cover);
        i.putExtra(MainActivity.EXTRA_URL, it.url);
        startActivity(i);
    }

    private int indexOfSource(boolean isBook, String sourceId) {
        if (sourceId == null || sourceId.isEmpty()) {
            return 0;
        }
        try {
            if (isBook) {
                List<BookSource> list = SourceManager.books();
                for (int i = 0; i < list.size(); i++) {
                    if (sourceId.equals(list.get(i).getId())) {
                        return i;
                    }
                }
            } else {
                List<ComicSource> list = SourceManager.comics();
                for (int i = 0; i < list.size(); i++) {
                    if (sourceId.equals(list.get(i).getId())) {
                        return i;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private void confirmRemove(Item it) {
        final String key = keyOf.get(it);
        if (key == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage("移出书架？")
                .setNegativeButton("取消", null)
                .setPositiveButton("移出书架", (DialogInterface d, int w) -> {
                    Favorites.remove(this, key);
                    Toast.makeText(this, "已移出书架", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .show();
    }
}
