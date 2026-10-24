package com.wuji.reader.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wuji.reader.R;
import com.wuji.reader.core.Tasks;
import com.wuji.reader.model.Item;
import com.wuji.reader.model.Result;
import com.wuji.reader.source.BookSource;
import com.wuji.reader.source.ComicSource;
import com.wuji.reader.source.SourceManager;
import com.wuji.reader.source.SourceRegistry;
import com.wuji.reader.ui.adapter.ItemAdapter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 主页：小说 / 漫画切换 + 源切换 + 分类 + 搜索 + 网格列表 + 翻页。
 *
 * 墨水屏注意：全部寻找都走 Tasks.io，结果回主线程刷新，
 * 主线程不放任何网络 / 解析操作。
 *
 * 源数据统一走 SourceManager（启用+排序+自定义后的生效集合），
 * SourceRegistry 只保留 Legado 兜底加载与旧链路索引。
 */
public class MainActivity extends BaseActivity {

    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_AUTHOR = "author";
    public static final String EXTRA_COVER = "cover";
    public static final String EXTRA_URL = "url";
    /** 来源源 id，跨源场景下比 EXTRA_SOURCE（下标）更可靠 */
    public static final String EXTRA_SOURCE_ID = "sourceId";

    private boolean isBook = true;
    private int sourceIndex = 0;
    private String currentType = "";
    private int pageNo = 1;
    private int totalPage = 1;
    private String query = "";
    /** 全局跨源搜索模式：无视当前选中的源，并发搜全部生效源 */
    private boolean globalMode = false;

    private LinearLayout sourceRow;
    private LinearLayout typeRow;
    private HorizontalScrollView typeScroll;
    private EditText editSearch;
    private TextView status;
    private TextView pageInfo;
    private Button btnGlobal;
    private Button btnPrev;
    private Button btnNext;
    private ItemAdapter adapter;
    private Runnable unbindScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // App.onCreate 已经加载过；这里幂等兜底，避免任何启动路径漏掉 Legado 源
        SourceRegistry.init(getApplicationContext());

        sourceRow = findViewById(R.id.sourceRow);
        typeRow = findViewById(R.id.typeRow);
        typeScroll = findViewById(R.id.typeScroll);
        editSearch = findViewById(R.id.editSearch);
        status = findViewById(R.id.status);
        pageInfo = findViewById(R.id.pageInfo);
        btnGlobal = findViewById(R.id.btnGlobal);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);

        RecyclerView recycler = findViewById(R.id.recycler);
        int span = Math.max(2, Math.min(4,
                getResources().getConfiguration().screenWidthDp / 180));
        recycler.setLayoutManager(new GridLayoutManager(this, span));
        adapter = new ItemAdapter(this::openDetail);
        recycler.setAdapter(adapter);
        // 网格回顶按钮 + 滑动栏；onDestroy 解绑
        unbindScroll = ScrollTopHelper.attach(recycler, this);

        Button tabNovel = findViewById(R.id.tabNovel);
        Button tabComic = findViewById(R.id.tabComic);
        tabNovel.setOnClickListener(v -> switchKind(true));
        tabComic.setOnClickListener(v -> switchKind(false));
        // 「新闻」是跳板按钮：本项目没有 WebView，点击进 NewsActivity 看热榜，
        // 不在 MainActivity 内切换内容
        findViewById(R.id.tabNews).setOnClickListener(v ->
                startActivity(new Intent(this, NewsActivity.class)));

        findViewById(R.id.btnShelf).setOnClickListener(v ->
                startActivity(new Intent(this, ShelfActivity.class)));

        // 「设置」（内含九宫格布局入口）
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // 「源管理」固定在源行行首（不随源 chips 横滑滚走）
        findViewById(R.id.btnSourceManage).setOnClickListener(v ->
                startActivity(new Intent(this, SourceManageActivity.class)));

        btnGlobal.setOnClickListener(v -> toggleGlobalMode());

        findViewById(R.id.btnSearch).setOnClickListener(v -> doSearch());
        editSearch.setOnEditorActionListener((v, actionId, event) -> {
            doSearch();
            return true;
        });

        btnPrev.setOnClickListener(v -> {
            if (!globalMode && pageNo > 1) {
                pageNo--;
                load();
            }
        });
        btnNext.setOnClickListener(v -> {
            if (!globalMode && pageNo < totalPage) {
                pageNo++;
                load();
            }
        });

        buildSourceRow();
        highlightTab();
        syncGlobalButton();
        load();
    }

    @Override
    protected void onDestroy() {
        if (unbindScroll != null) {
            unbindScroll.run();
            unbindScroll = null;
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 横竖屏切换后按新宽度重算网格列数。Manifest 已声明 configChanges
        // （旋转不重建 Activity，避免重新联网拉列表），span 是在 onCreate 里
        // 按当时的 screenWidthDp 算的，这里手动同步，否则横屏会停留竖屏的 2 列。
        RecyclerView rv = findViewById(R.id.recycler);
        if (rv != null && rv.getLayoutManager() instanceof GridLayoutManager) {
            int span = Math.max(2, Math.min(4, newConfig.screenWidthDp / 180));
            ((GridLayoutManager) rv.getLayoutManager()).setSpanCount(span);
        }
    }

    // ------------------------------------------------------------------
    // 结构搭建
    // ------------------------------------------------------------------

    private void switchKind(boolean book) {
        if (book == isBook) {
            return;
        }
        isBook = book;
        sourceIndex = 0;
        pageNo = 1;
        query = "";
        currentType = "";
        globalMode = false;
        editSearch.setText("");
        btnPrev.setEnabled(true);
        btnNext.setEnabled(true);
        highlightTab();
        syncGlobalButton();
        buildSourceRow();
        load();
    }

    private void buildSourceRow() {
        sourceRow.removeAllViews();
        List<String> names = new ArrayList<>();
        if (isBook) {
            for (BookSource s : SourceManager.books()) {
                names.add(s.getName());
            }
        } else {
            for (ComicSource s : SourceManager.comics()) {
                names.add(s.getName());
            }
        }
        for (int i = 0; i < names.size(); i++) {
            final int idx = i;
            Button btn = makeChip(names.get(i));
            // 全局模式下没有「当前源」，源 chip 全部置未选中
            setChipSelected(btn, !globalMode && i == sourceIndex);
            btn.setOnClickListener(v -> {
                if (globalMode) {
                    // 点击任一源 chip 自动退出全局模式，并直接加载该源
                    setGlobalMode(false);
                    sourceIndex = idx;
                    pageNo = 1;
                    query = "";
                    editSearch.setText("");
                    buildSourceRow();
                    load();
                    return;
                }
                if (sourceIndex == idx) {
                    return;
                }
                sourceIndex = idx;
                pageNo = 1;
                query = "";
                editSearch.setText("");
                buildSourceRow();
                load();
            });
            sourceRow.addView(btn);
        }
        // 「源管理」不在这里追加：它是固定在源行行首的独立按钮
        // （见 activity_main.xml 的 btnSourceManage），不随源 chips 滚动
        buildTypeRow();
    }

    private void buildTypeRow() {
        typeRow.removeAllViews();
        List<String> types = getTypes();
        if (types.isEmpty()) {
            typeScroll.setVisibility(View.GONE);
            currentType = "";
            return;
        }
        typeScroll.setVisibility(View.VISIBLE);
        for (int i = 0; i < types.size(); i++) {
            final String t = types.get(i);
            final int idx = i;
            Button btn = makeChip(t);
            setChipSelected(btn, i == 0);
            btn.setOnClickListener(v -> {
                currentType = t;
                pageNo = 1;
                for (int j = 0; j < typeRow.getChildCount(); j++) {
                    setChipSelected((Button) typeRow.getChildAt(j), j == idx);
                }
                load();
            });
            typeRow.addView(btn);
        }
        currentType = types.get(0);
    }

    private List<String> getTypes() {
        try {
            if (isBook) {
                List<BookSource> list = SourceManager.books();
                if (sourceIndex < list.size()) {
                    return list.get(sourceIndex).types();
                }
            } else {
                List<ComicSource> list = SourceManager.comics();
                if (sourceIndex < list.size()) {
                    return list.get(sourceIndex).types();
                }
            }
        } catch (Throwable ignored) {
            // 这里跑在 onCreate 主线程：types() 解析畸形自定义源可能抛 Error，
            // catch(Exception) 抓不住，必须 Throwable
        }
        return new ArrayList<>();
    }

    // ------------------------------------------------------------------
    // 搜索 / 加载
    // ------------------------------------------------------------------

    /**
     * 「全局」按钮：开 → 有关键词立即搜、无关键词提示；
     * 关 → 回到当前源正常加载。
     */
    private void toggleGlobalMode() {
        if (globalMode) {
            setGlobalMode(false);
            load();
            return;
        }
        setGlobalMode(true);
        query = editSearch.getText() == null
                ? "" : editSearch.getText().toString().trim();
        if (query.isEmpty()) {
            status.setText("全局搜索请输入关键词");
            return;
        }
        doGlobalSearch();
    }

    private void setGlobalMode(boolean on) {
        globalMode = on;
        syncGlobalButton();
        if (on) {
            btnPrev.setEnabled(false);
            btnNext.setEnabled(false);
            pageInfo.setText("");
            buildSourceRow();
        } else {
            btnPrev.setEnabled(true);
            btnNext.setEnabled(true);
            buildSourceRow();
        }
    }

    private void syncGlobalButton() {
        setChipSelected(btnGlobal, globalMode);
    }

    private void doSearch() {
        query = editSearch.getText() == null ? "" : editSearch.getText().toString().trim();
        pageNo = 1;
        View cur = getCurrentFocus();
        if (cur != null) {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(cur.getWindowToken(), 0);
            }
        }
        if (globalMode) {
            if (query.isEmpty()) {
                status.setText("全局搜索请输入关键词");
                return;
            }
            doGlobalSearch();
            return;
        }
        load();
    }

    /** 全局跨源搜索：GlobalSearch 在工作线程回调，回主线程刷新 */
    private void doGlobalSearch() {
        status.setText(R.string.loading);
        final String q = query;
        GlobalSearch.search(isBook, q, (merged, fails) -> {
            final List<Item> m = merged == null ? new ArrayList<Item>() : merged;
            final List<String> f = fails == null ? new ArrayList<String>() : fails;
            Tasks.ui(() -> applyGlobalResult(m, f));
        });
    }

    private void applyGlobalResult(List<Item> merged, List<String> fails) {
        adapter.setData(merged);
        if (merged.isEmpty()) {
            String s = "全局没有搜索结果";
            if (!fails.isEmpty()) {
                s += "（" + fails.size() + " 个源失败）";
            }
            status.setText(s);
        } else {
            Set<String> hitSources = new HashSet<>();
            for (Item it : merged) {
                if (it.sourceId != null) {
                    hitSources.add(it.sourceId);
                }
            }
            status.setText("全局 · 共 " + merged.size()
                    + " 条 · " + hitSources.size() + " 个源有结果");
        }
        // 全局模式禁用翻页
        btnPrev.setEnabled(false);
        btnNext.setEnabled(false);
        pageInfo.setText("");
    }

    private void load() {
        final List<BookSource> bookSrcs =
                isBook ? SourceManager.books() : null;
        final List<ComicSource> comicSrcs =
                !isBook ? SourceManager.comics() : null;
        if (isBook && bookSrcs.isEmpty()) {
            status.setText("没有可用的小说源");
            return;
        }
        if (!isBook && comicSrcs.isEmpty()) {
            status.setText("没有可用的漫画源");
            return;
        }

        final int p = pageNo;
        final String q = query;
        final String t = currentType;
        final boolean book = isBook;
        final int si = sourceIndex;

        status.setText(R.string.loading);
        Tasks.io(() -> {
            Result<Item> r;
            try {
                if (book) {
                    // sourceIndex 可能是陈旧值（用户在源管理页停用过源后集合变短）
                    BookSource s = bookSrcs.get(clamp(si, bookSrcs.size()));
                    r = q.isEmpty() ? s.getRecommendBooks(p, t) : s.search(q, p);
                } else {
                    ComicSource s = comicSrcs.get(clamp(si, comicSrcs.size()));
                    r = q.isEmpty() ? s.getRecommendComics(p, t) : s.search(q, p);
                }
            } catch (Throwable e) {
                // 低内存墨水屏上 OOM/StackOverflowError 也可能出现，必须抓 Throwable
                final String msg = msgOf(e);
                Tasks.ui(() -> {
                    // 先清空列表再报错：否则上一个 tab 的数据会残留在网格里，
                    // 用户会误以为当前源有数据（QA 冒烟实测复现）
                    adapter.setData(new ArrayList<>());
                    pageInfo.setText("");
                    status.setText("加载失败：" + msg);
                });
                return;
            }
            final Result<Item> rr = r;
            Tasks.ui(() -> {
                // 源可能返回 null 或 list 为 null，直接解引用会 NPE 崩进程
                List<Item> list = (rr == null || rr.list == null)
                        ? new ArrayList<Item>() : rr.list;
                adapter.setData(list);
                totalPage = Math.max(1, rr == null ? 1 : rr.totalPage);
                // 从全局模式退回单源后恢复翻页
                btnPrev.setEnabled(true);
                btnNext.setEnabled(true);
                if (list.isEmpty()) {
                    // 空列表分两种：没输入关键词=该源没有推荐页，提示去搜索；
                    // 输入了关键词=真的没搜到。避免出现「一片空白+暂无内容」让人误以为坏了。
                    if (q.isEmpty()) {
                        status.setText(isBook ? "该源没有推荐，请搜索书名" : "该源没有推荐，请用搜索");
                    } else {
                        status.setText("没有搜索结果，换个关键词试试");
                    }
                } else {
                    status.setText("共 " + list.size() + " 条");
                }
                pageInfo.setText(p + " / " + totalPage);
            });
        });
    }

    private void openDetail(Item it) {
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra(EXTRA_KIND, isBook ? "book" : "comic");
        String sid = it.sourceId == null ? "" : it.sourceId;
        i.putExtra(EXTRA_SOURCE_ID, sid);
        i.putExtra(EXTRA_SOURCE, indexOfSource(sid));
        i.putExtra(EXTRA_ID, it.id);
        i.putExtra(EXTRA_TITLE, it.title);
        i.putExtra(EXTRA_AUTHOR, it.author);
        i.putExtra(EXTRA_COVER, it.cover);
        i.putExtra(EXTRA_URL, it.url);
        startActivity(i);
    }

    /**
     * sourceId 在 SourceManager 生效集合中的下标；
     * 找不到（如 Legado 老链路 item.sourceId 为空）退化为 0。
     */
    private int indexOfSource(String sourceId) {
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

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private void highlightTab() {
        Button n = findViewById(R.id.tabNovel);
        Button c = findViewById(R.id.tabComic);
        Button w = findViewById(R.id.tabNews);
        setChipSelected(n, isBook);
        setChipSelected(c, !isBook);
        // 「新闻」是跳板按钮，MainActivity 本身不展示新闻内容，常置未选中；
        // 从 NewsActivity 返回时仍保留小说 / 漫画的高亮状态
        setChipSelected(w, false);
    }

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

    private static String msgOf(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    /** 把可能越界的下标夹到 [0, size) */
    private static int clamp(int idx, int size) {
        if (size <= 0) {
            return 0;
        }
        return idx < 0 ? 0 : (idx >= size ? size - 1 : idx);
    }
}
