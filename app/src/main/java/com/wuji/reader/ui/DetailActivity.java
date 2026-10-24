package com.wuji.reader.ui;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.wuji.reader.R;
import com.wuji.reader.core.Tasks;
import com.wuji.reader.model.Chapter;
import com.wuji.reader.model.Item;
import com.wuji.reader.source.BookSource;
import com.wuji.reader.source.ComicSource;
import com.wuji.reader.source.SourceManager;
import com.wuji.reader.source.SourceRegistry;
import com.wuji.reader.store.Favorites;
import com.wuji.reader.store.Prefs;
import com.wuji.reader.ui.adapter.ChapterAdapter;

import java.util.ArrayList;
import java.util.List;

/** 详情页：封面 + 简介 + 目录，入口是 MainActivity 的网格点击 */
public class DetailActivity extends BaseActivity {

    private boolean isBook = true;
    private int si;
    private String id = "";
    private String title = "";
    private String author = "";
    private String cover = "";
    private String url = "";
    private String sourceId = "";

    private Item detail;
    private ChapterAdapter adapter;
    private Button btnContinue;
    private Button btnFav;
    private TextView tvIntro;
    private Button btnJump;
    private TextView tvChapterCount;
    private Runnable unbindScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        Intent in = getIntent();
        isBook = !"comic".equals(in.getStringExtra(MainActivity.EXTRA_KIND));
        si = in.getIntExtra(MainActivity.EXTRA_SOURCE, 0);
        id = safe(in.getStringExtra(MainActivity.EXTRA_ID));
        title = safe(in.getStringExtra(MainActivity.EXTRA_TITLE));
        author = safe(in.getStringExtra(MainActivity.EXTRA_AUTHOR));
        cover = safe(in.getStringExtra(MainActivity.EXTRA_COVER));
        url = safe(in.getStringExtra(MainActivity.EXTRA_URL));

        sourceId = safe(in.getStringExtra(MainActivity.EXTRA_SOURCE_ID));
        if (sourceId.isEmpty()) {
            // 老链路没带 sourceId：按 si 回退到注册表下标。
            // si 的语义是 SourceManager 生效集合里的下标，而这里索引的是
            // SourceRegistry（只含内置源），两个列表长度不同，必须先判界，
            // 否则老设备上直接 IndexOutOfBoundsException 崩在 onCreate 主线程。
            try {
                if (isBook) {
                    if (si >= 0 && si < SourceRegistry.BOOKS.size()) {
                        sourceId = safe(SourceRegistry.BOOKS.get(si).getId());
                    }
                } else if (si >= 0 && si < SourceRegistry.COMICS.size()) {
                    sourceId = safe(SourceRegistry.COMICS.get(si).getId());
                }
            } catch (Throwable ignored) {
                // 取不到就留空，后续 loadDetail 会走 SourceManager 兜底并给出提示
            }
        }

        TextView tvTitle = findViewById(R.id.tvTitle);
        TextView tvName = findViewById(R.id.tvName);
        TextView tvAuthor = findViewById(R.id.tvAuthor);
        ImageView imgCover = findViewById(R.id.imgCover);
        btnContinue = findViewById(R.id.btnContinue);
        btnFav = findViewById(R.id.btnFav);
        tvIntro = findViewById(R.id.tvIntro);
        applyCompactIntro();

        tvTitle.setText(title);
        tvName.setText(title);
        tvAuthor.setText(author.isEmpty() ? "" : "作者：" + author);

        if (!cover.isEmpty()) {
            Glide.with(this).load(cover)
                    .placeholder(new android.graphics.drawable.ColorDrawable(
                            ContextCompat.getColor(this, R.color.ink_white)))
                    .into(imgCover);
        }

        RecyclerView rv = findViewById(R.id.chapterRecycler);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChapterAdapter((idx, ch) -> openReader(idx));
        rv.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnContinue.setOnClickListener(v -> openReader(Prefs.getProgress(this, progressKey())));
        btnFav.setOnClickListener(v -> toggleFav());
        refreshFavButton();

        // 目录回顶按钮 + 滑动栏；onDestroy 解绑
        unbindScroll = ScrollTopHelper.attach(rv, this);

        btnJump = findViewById(R.id.btnJump);
        tvChapterCount = findViewById(R.id.tvChapterCount);
        btnJump.setOnClickListener(v -> jumpToChapter());

        loadDetail();
    }

    @Override
    protected void onDestroy() {
        if (unbindScroll != null) {
            unbindScroll.run();
            unbindScroll = null;
        }
        super.onDestroy();
    }

    /**
     * 横屏矮屏压缩简介预览行数，把高度让给目录列表。
     *
     * 详情页是「固定头（顶栏 52 + 封面 152 + 简介 + 目录标题 + 跳转行）+ 目录
     * RecyclerView(weight=1)」结构，没有整体滚动。横屏矮屏（如手机横屏
     * 360-430dp 高）下简介仍占 7 行会把目录列表压到 0 高度。
     * Manifest 已声明 configChanges（旋转不重建 Activity），所以旋转后
     * 也要在这里重算。竖屏高度恒 >= 480dp，保持 7 行不变。
     */
    private void applyCompactIntro() {
        boolean shortHeight = getResources().getConfiguration().screenHeightDp < 480;
        tvIntro.setMaxLines(shortHeight ? 2 : 7);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyCompactIntro();
    }

    /** 目录跳转：输入章节号 / 快捷按钮，clamp 后滚动到位并高亮 */
    private void jumpToChapter() {
        if (adapter == null || adapter.getItemCount() == 0) {
            Toast.makeText(this, "目录还没加载好", Toast.LENGTH_SHORT).show();
            return;
        }
        final int size = adapter.getItemCount();
        if (size > 200) {
            Toast.makeText(this, "目录较长，可输入章节号跳转", Toast.LENGTH_SHORT).show();
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density + 0.5f);
        root.setPadding(pad, pad, pad, pad);

        final EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setHint("章节号，1 起");
        root.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        Button b100 = quickBtn("第100条", v -> et.setText("100"));
        Button bPlus = quickBtn("+100条", v -> {
            int cur = parseInt(et.getText() == null ? "" : et.getText().toString().trim(), 1);
            et.setText(String.valueOf(cur + 100));
        });
        Button bLast = quickBtn("最后一章", v -> et.setText(String.valueOf(size)));
        quick.addView(b100);
        quick.addView(bPlus);
        quick.addView(bLast);
        root.addView(quick, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("跳转到章节（共 " + size + " 章）")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("跳转", (d, w) -> {
                    int n = parseInt(et.getText() == null ? "" : et.getText().toString().trim(), 1);
                    int idx = n - 1;
                    if (idx < 0) {
                        idx = 0;
                    }
                    if (idx > size - 1) {
                        idx = size - 1;
                    }
                    RecyclerView rv2 = findViewById(R.id.chapterRecycler);
                    rv2.scrollToPosition(idx);
                    adapter.setSelected(idx);
                    Toast.makeText(this, "已跳到第 " + (idx + 1) + " 章",
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private Button quickBtn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setText(text);
        b.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.ink_black));
        b.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.ink_white));
        b.setOnClickListener(l);
        return b;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFavButton();
    }

    private void refreshFavButton() {
        boolean fav = Favorites.isFav(this,
                Favorites.key(isBook, sourceId, id));
        btnFav.setText(fav ? "已收藏" : "收藏");
    }

    private void toggleFav() {
        String k = Favorites.key(isBook, sourceId, id);
        if (Favorites.isFav(this, k)) {
            Favorites.remove(this, k);
            Toast.makeText(this, "已移出书架", Toast.LENGTH_SHORT).show();
        } else {
            Item it = new Item();
            it.id = id;
            it.title = title;
            it.author = author;
            it.cover = cover;
            it.url = url;
            it.sourceId = sourceId;
            Favorites.add(this, it, isBook);
            Toast.makeText(this, "已加入书架", Toast.LENGTH_SHORT).show();
        }
        refreshFavButton();
    }

    private void loadDetail() {
        Item base = new Item();
        base.id = id;
        base.title = title;
        base.author = author;
        base.cover = cover;
        base.url = url;
        base.sourceId = sourceId;

        tvIntro.setText(R.string.loading);
        btnContinue.setEnabled(false);

        Tasks.io(() -> {
            Item d;
            try {
                if (isBook) {
                    BookSource s = SourceManager.bookById(sourceId);
                    if (s == null) {
                        s = legacy(SourceRegistry.BOOKS, si, "小说源");
                    }
                    d = s.getBookDetail(base, 1);
                } else {
                    ComicSource s = SourceManager.comicById(sourceId);
                    if (s == null) {
                        s = legacy(SourceRegistry.COMICS, si, "漫画源");
                    }
                    d = s.getComicDetail(base, 1);
                }
            } catch (Exception e) {
                final String msg = safe(e.getMessage());
                Tasks.ui(() -> tvIntro.setText("加载失败：" + msg));
                return;
            }
            final Item got = d;
            final List<Chapter> chs = got.hasChapters()
                    ? new ArrayList<>(got.chapters) : new ArrayList<>();

            Tasks.ui(() -> {
                detail = got;
                adapter.setData(chs);
                tvChapterCount.setText("共 " + chs.size() + " 章");
                if (chs.isEmpty()) {
                    tvIntro.setText("没有取到目录");
                    btnContinue.setEnabled(false);
                    return;
                }
                tvIntro.setText(got.desc == null || got.desc.isEmpty()
                        ? "" : got.desc);
                btnContinue.setEnabled(true);
                int last = Prefs.getProgress(this, progressKey());
                if (last > 0 && last < chs.size()) {
                    btnContinue.setText("继续阅读（第 " + (last + 1) + " 章）");
                }
            });
        });
    }

    private void openReader(int rawIndex) {
        int idx = rawIndex;
        if (idx < 0) {
            idx = 0;
        }
        Intent i = new Intent(this,
                isBook ? NovelReaderActivity.class : ComicReaderActivity.class);
        i.putExtra("source", si);
        i.putExtra("sourceId", sourceId);
        i.putExtra("id", id);
        i.putExtra("title", title);
        i.putExtra("url", url);
        i.putExtra("index", idx);
        startActivity(i);
    }

    /** SourceManager 找不到 sourceId 时按旧索引回退注册表 */
    private static <T> T legacy(List<T> list, int idx, String what) throws Exception {
        if (list == null || list.isEmpty()) {
            throw new Exception("没有可用的" + what);
        }
        if (idx < 0 || idx >= list.size()) {
            idx = 0;
        }
        return list.get(idx);
    }

    private String progressKey() {
        return (isBook ? "book" : "comic") + ":" + sourceId + ":" + id;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
