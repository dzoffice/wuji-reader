package com.wuji.reader.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.wuji.reader.R;
import com.wuji.reader.core.Tasks;
import com.wuji.reader.news.ArticleExtractor;
import com.wuji.reader.news.BoardContent;
import com.wuji.reader.news.HotItem;
import com.wuji.reader.store.Prefs;
import com.wuji.reader.ui.ReaderTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * 新闻正文页。
 *
 * 复用小说阅读器的布局（activity_reader.xml）：上栏标题、中间滚动正文、
 * 下栏字号调节；上一章/下一章两个按钮隐藏（新闻没有章节概念），
 * 「复制」按钮复制原文链接。
 *
 * 正文由 {@link BoardContent} 按榜单策略抓取（知乎走原文链接/摘要、
 * 贴吧直抓热议页、百度走移动搜索、微博/抖音走相关报道），
 * 通用站点兜底走 {@link ArticleExtractor}。
 */
public class ArticleActivity extends BaseActivity {

    private String url = "";
    private String title = "";
    private String board = "";
    private String desc = "";
    private float fontSp = 19f;

    private TextView tvContent;
    private TextView tvTitle;
    private ObservableScrollView scroll;
    private View topBar;
    private View bottomBar;

    /** 九宫格 9 格动作配置（与小说阅读器共用一份用户配置） */
    private int[] nineGrid = NineGridConfig.defaults();
    private float downX;
    private float downY;
    private long downTime;

    /** 相关报道：抓不到正文时检索出来的结果，可点开继续读 */
    private final List<ArticleExtractor.Link> related = new ArrayList<>();
    /** 相关报道列表容器（正文下方，默认 GONE） */
    private LinearLayout relatedBox;

    /**
     * 摘要聚合模式：原网页打不开（头条系等 JS 壳页）时，把 Bing 多源摘要
     * 拼成长文显示。每点一次「摘要+」多聚合 {@link #SUMMARY_STEP} 条。
     */
    private boolean summaryMode = false;
    private String summaryQuery = "";
    /**
     * 最初点进来的热搜词（如「只有369摸到过高地」）。
     * 从相关报道点进来时会由 openRelated 带下来——「摘要+」检索用它，
     * 不用报道标题（报道标题检索会跑偏）。
     */
    private String hotQuery = "";
    private int summaryCount = 0;
    private static final int SUMMARY_STEP = 3;
    private View btnExtend;

    /** 自动滚动（新闻同样支持） */
    private final Handler autoHandler = new Handler(Looper.getMainLooper());
    private Runnable autoTask;
    private boolean autoOn = false;
    private int autoSpeedLevel = 1;
    private static final int[][] AUTO_SPEED = {{1, 60}, {2, 35}, {4, 20}};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        url = getIntent().getStringExtra("url");
        title = getIntent().getStringExtra("title");
        board = getIntent().getStringExtra("board");
        desc = getIntent().getStringExtra("desc");
        hotQuery = getIntent().getStringExtra("query");
        if (url == null) {
            url = "";
        }
        if (title == null) {
            title = "";
        }
        if (board == null) {
            board = "";
        }
        if (desc == null) {
            desc = "";
        }
        if (hotQuery == null) {
            hotQuery = "";
        }

        tvContent = findViewById(R.id.tvContent);
        tvTitle = findViewById(R.id.tvTitle);
        scroll = findViewById(R.id.scroll);
        // 右侧可拖拽滚动条（原生滚动条只能看不能拖）
        ((FastScrollBar) findViewById(R.id.fastBar)).bind(scroll);

        // 新闻没有"上一章/下一章"，隐藏这两个按钮
        findViewById(R.id.btnPrev).setVisibility(View.GONE);
        findViewById(R.id.btnNext).setVisibility(View.GONE);
        // 九宫格菜单改由分区点击触发，底部"菜单"按钮保留为快捷入口
        findViewById(R.id.btnMenu).setOnClickListener(v -> showNewsMenu());

        topBar = findViewById(R.id.topBar);
        bottomBar = findViewById(R.id.bottomBar);
        relatedBox = findViewById(R.id.relatedBox);

        fontSp = Prefs.getFontSp(this);
        // 正文用 dp：不吃全局 fontScale，避免被连乘两次
        tvContent.setTextSize(TypedValue.COMPLEX_UNIT_DIP, fontSp);
        autoSpeedLevel = Prefs.getAutoSpeed(this);

        // 阅读背景色（与小说阅读器一致，整页纯色、文字黑）
        ReaderTheme.apply(this, findViewById(R.id.readerRoot), tvContent);

        // 九宫格分区控制：与小说阅读器共用同一份用户配置
        nineGrid = NineGridConfig.load(this);
        setupZoneTouch();

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnFontDown).setOnClickListener(v -> changeFont(-2f));
        findViewById(R.id.btnFontUp).setOnClickListener(v -> changeFont(2f));
        findViewById(R.id.btnAuto).setOnClickListener(v -> toggleAutoScroll());
        // 「摘要+」只在摘要聚合模式出现（打不开原网页时），默认隐藏
        btnExtend = findViewById(R.id.btnExtend);
        btnExtend.setOnClickListener(v -> {
            if (summaryMode && !summaryQuery.isEmpty()) {
                loadSummaries(summaryCount + SUMMARY_STEP);
            }
        });

        tvTitle.setText(title.isEmpty() ? "新闻正文" : title);
        load();
    }

    @Override
    protected void onPause() {
        stopAutoScroll();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopAutoScroll();
        super.onDestroy();
    }

    private void load() {
        if (url.isEmpty() && desc.trim().isEmpty()) {
            tvContent.setText("没有可读取的链接");
            return;
        }
        tvContent.setText("正在抓取正文…");

        final String u = url;
        final String t = title;
        final String b = board;
        final String d = desc;

        Tasks.io(() -> {
            ArticleExtractor.Article art = null;
            String err = null;
            try {
                HotItem item = new HotItem();
                item.url = u;
                item.title = t;
                item.desc = d;
                art = BoardContent.fetch(item, b);
            } catch (Exception e) {
                err = safe(e.getMessage());
            }
            final ArticleExtractor.Article a = art;
            final String em = err;
            Tasks.ui(() -> {
                boolean hasRelated = a != null && a.links != null && !a.links.isEmpty();
                if (hasRelated) {
                    // 相关报道模式（百度/微博/抖音检索通道）：正文区只放说明行，
                    // 报道列表由 relatedBox 渲染成可点条目
                    if (!a.title.isEmpty()) {
                        tvTitle.setText(a.title);
                    }
                    related.clear();
                    related.addAll(a.links);
                    renderRelated();
                    StringBuilder sb = new StringBuilder();
                    sb.append(a.text);
                    if (!u.isEmpty()) {
                        sb.append("\n\n（原文：").append(u).append("）");
                    }
                    tvContent.setText(sb.toString());
                    scroll.scrollTo(0, 0);
                    return;
                }
                if (a != null && a.text != null && !a.text.isEmpty()) {
                    if (!a.title.isEmpty()) {
                        tvTitle.setText(a.title);
                    }
                    related.clear();
                    renderRelated();
                    StringBuilder sb = new StringBuilder();
                    if (a.author != null && !a.author.isEmpty()) {
                        sb.append("作者：").append(a.author).append("\n\n");
                    }
                    sb.append(a.text);
                    if (!u.isEmpty()) {
                        sb.append("\n\n（原文：").append(u).append("）");
                    }
                    tvContent.setText(sb.toString());
                    scroll.scrollTo(0, 0);
                    return;
                }
                // 抓不到正文（贴吧反爬 / 头条系 JS 壳页）：先按 v17 行为显示
                // 上游摘要 + 原文链接，**不自动检索**（自动检索极易跑偏）。
                // 「摘要+」只在用户主动点击时才去检索更多相关内容。
                related.clear();
                renderRelated();
                tvContent.setText(fallbackText(em, d));
                scroll.scrollTo(0, 0);
                String q = !hotQuery.isEmpty() ? hotQuery : title;
                if (!q.trim().isEmpty()) {
                    summaryMode = true;
                    summaryQuery = q;
                    btnExtend.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    /**
     * 摘要聚合：用户主动点「摘要+」时，用**最初的热搜词**（不是报道标题）
     * 检索多来源摘要拼成长文。
     *
     * 用报道标题检索会跑偏：标题带【】、数字、站点后缀，Bing 分词后
     * 常常只命中其中一两个字（实测「说个骚的这把只有369…」被解析成
     * 「说」，返回一堆汉语字典释义）。热搜词短且明确，检索结果才相关。
     *
     * @param n 本次要聚合的条数（点一次加 {@link #SUMMARY_STEP} 条）
     */
    private void loadSummaries(int n) {
        summaryCount = n;
        tvContent.setText("正在检索与「" + summaryQuery + "」相关的内容…");
        btnExtend.setVisibility(View.GONE);
        final String q = summaryQuery;
        Tasks.io(() -> {
            String text = null;
            String err = null;
            try {
                text = BoardContent.searchSummaries(q, n);
            } catch (Exception e) {
                err = safe(e.getMessage());
            }
            final String t = text;
            final String em = err;
            Tasks.ui(() -> {
                if (t != null && !t.isEmpty()) {
                    tvContent.setText(t);
                    btnExtend.setVisibility(View.VISIBLE);
                } else {
                    // 没有相关内容：回到摘要视图，按钮留着可再试
                    tvContent.setText(fallbackText(em, desc));
                    btnExtend.setVisibility(View.VISIBLE);
                    if (em != null && !em.isEmpty()) {
                        Toast.makeText(this, em, Toast.LENGTH_SHORT).show();
                    }
                }
                scroll.scrollTo(0, 0);
            });
        });
    }

    /** 抓取失败时的兜底文案：标题 + 上游摘要（如有）+ 链接 */
    private String fallbackText(String err, String itemDesc) {
        StringBuilder sb = new StringBuilder();
        sb.append("正文抓取失败");
        if (err != null && !err.isEmpty()) {
            sb.append("：").append(err);
        }
        sb.append("\n\n");
        if (itemDesc != null && !itemDesc.trim().isEmpty()) {
            sb.append("【摘要】\n").append(itemDesc.trim()).append("\n\n");
        }
        if (!url.isEmpty()) {
            sb.append("原文链接：\n").append(url)
                    .append("\n\n（可复制链接到浏览器打开）");
        } else {
            sb.append("（该条没有可用链接）");
        }
        return sb.toString();
    }

    private void changeFont(float delta) {
        fontSp += delta;
        if (fontSp < 14f) {
            fontSp = 14f;
        }
        if (fontSp > 44f) {
            fontSp = 44f;
        }
        tvContent.setTextSize(TypedValue.COMPLEX_UNIT_DIP, fontSp);
        Prefs.setFontSp(this, fontSp);
    }

    // ------------------------------------------------------------------
    // 九宫格分区控制（新闻正文）
    // ------------------------------------------------------------------

    /**
     * 与小说阅读器同一套判定：OnTouchListener 一律返回 false，
     * 滚动完全交给 ScrollView；只在"位移小、时间短"时算点击。
     */
    private void setupZoneTouch() {
        final float slop = 20f * getResources().getDisplayMetrics().density;
        scroll.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    downTime = System.currentTimeMillis();
                    return false;
                case android.view.MotionEvent.ACTION_UP:
                    long dt = System.currentTimeMillis() - downTime;
                    if (dt < 700
                            && Math.abs(e.getX() - downX) < slop
                            && Math.abs(e.getY() - downY) < slop) {
                        handleZoneTap(e.getX(), e.getY());
                    }
                    return false;
                default:
                    return false;
            }
        });
    }

    private void handleZoneTap(float x, float y) {
        // 相关报道区域不参与九宫格：那一整块的点击要留给"打开某条报道"，
        // 否则九宫格会把相关报道盖住，点了没反应或触发别的功能。
        if (isInRelatedBox(y)) {
            return;
        }
        int w = scroll.getWidth();
        int h = scroll.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        int col = (int) (x * NineGridConfig.COLS / w);
        int row = (int) (y * NineGridConfig.ROWS / h);
        if (col < 0 || col >= NineGridConfig.COLS || row < 0 || row >= NineGridConfig.ROWS) {
            return;
        }
        NineGridAction act =
                NineGridConfig.actionAt(nineGrid, NineGridConfig.indexOf(row, col));
        if (act == NineGridAction.NONE) {
            return;
        }
        runAction(act);
    }

    /** 新闻版动作分发：没有章节概念，翻章动作退化成翻页，目录退化成相关报道 */
    private void runAction(NineGridAction act) {
        switch (act) {
            case CLEAR_SCREEN:
                toggleBars();
                break;
            case MENU:
                showNewsMenu();
                break;
            case PREV_PAGE:
            case PREV_CHAPTER:
                scrollPage(-1);
                break;
            case NEXT_PAGE:
            case NEXT_CHAPTER:
                scrollPage(1);
                break;
            case FONT_UP:
                changeFont(2f);
                break;
            case FONT_DOWN:
                changeFont(-2f);
                break;
            case TOC:
            case RELATED:
                showRelated();
                break;
            case TOP:
                scroll.scrollTo(0, 0);
                break;
            case BOTTOM:
                scrollToBottom();
                break;
            case PROGRESS:
                showProgress();
                break;
            case REFRESH:
                load();
                break;
            case COPY:
                copyContent();
                break;
            case BACK:
                finish();
                break;
            case SETTINGS:
                startActivity(new android.content.Intent(this, SettingsActivity.class));
                break;
            case AUTO_SCROLL:
                toggleAutoScroll();
                break;
            default:
                break;
        }
    }

    /**
     * 判断这次点击是否落在"相关报道"区域内。
     *
     * y 来自 ScrollView 的 MotionEvent，是**视口坐标**（不含滚动量）；
     * relatedBox.getTop() 是**内容坐标**，要减掉 scrollY 才能对齐。
     */
    private boolean isInRelatedBox(float yInViewport) {
        if (relatedBox == null || relatedBox.getVisibility() != View.VISIBLE) {
            return false;
        }
        int top = relatedBox.getTop() - scroll.getScrollY();
        int bottom = top + relatedBox.getHeight();
        return yInViewport >= top && yInViewport <= bottom;
    }

    /**
     * 把相关报道渲染成一行行可点的条目（而不是塞进正文纯文本）。
     * 行本身带 OnClickListener，会消费掉点击，所以不会漏给九宫格。
     */
    private void renderRelated() {
        if (relatedBox == null) {
            return;
        }
        relatedBox.removeAllViews();
        if (related.isEmpty()) {
            relatedBox.setVisibility(View.GONE);
            return;
        }
        final float density = getResources().getDisplayMetrics().density;
        final int pad = (int) (12 * density + 0.5f);
        float rowSp = Math.max(fontSp - 2f, 14f);

        for (int i = 0; i < related.size(); i++) {
            final ArticleExtractor.Link l = related.get(i);
            if (l == null || l.url == null || l.url.isEmpty()) {
                continue;
            }
            TextView row = new TextView(this);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            StringBuilder sb = new StringBuilder();
            sb.append(i + 1).append(". ")
                    .append(l.title == null ? "" : l.title);
            if (l.summary != null && !l.summary.isEmpty()) {
                sb.append('\n').append(l.summary);
            }
            row.setText(sb.toString());
            row.setTextColor(0xFF000000);
            // 同样用 dp，避免被全局 fontScale 再乘一次
            row.setTextSize(TypedValue.COMPLEX_UNIT_DIP, rowSp);
            row.setPadding(pad, pad, pad, pad);
            // 加下划线，提示这条可以点
            row.setPaintFlags(row.getPaintFlags()
                    | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            row.setOnClickListener(v -> openRelated(l));
            relatedBox.addView(row);

            View line = new View(this);
            line.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            line.setBackgroundColor(0xFF000000);
            relatedBox.addView(line);
        }
        relatedBox.setVisibility(
                relatedBox.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    /** 打开某条相关报道（复用本页，走通用正文抽取通道） */
    private void openRelated(ArticleExtractor.Link l) {
        if (l == null || l.url == null || l.url.isEmpty()) {
            Toast.makeText(this, "这条没有可用链接", Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent i = new android.content.Intent(this, ArticleActivity.class);
        i.putExtra("url", l.url);
        i.putExtra("title", l.title == null ? "" : l.title);
        i.putExtra("board", "");
        i.putExtra("desc", l.summary == null ? "" : l.summary);
        // 把最初的热搜词带给下一页：「摘要+」用它检索，避免用报道标题跑偏
        i.putExtra("query", title);
        startActivity(i);
    }

    private void toggleBars() {
        boolean hide = topBar.getVisibility() == View.VISIBLE;
        topBar.setVisibility(hide ? View.GONE : View.VISIBLE);
        bottomBar.setVisibility(hide ? View.GONE : View.VISIBLE);
    }

    private void scrollPage(int dir) {
        int max = scroll.maxScroll();
        int y = scroll.getScrollY();
        int page = scroll.getHeight();
        if (page <= 0) {
            page = 800;
        }
        int target = y + dir * page;
        if (target < 0) {
            target = 0;
        }
        if (target > max) {
            target = max;
        }
        scroll.scrollTo(0, target);
    }

    private void scrollToBottom() {
        View child = scroll.getChildAt(0);
        if (child != null) {
            scroll.scrollTo(0, Math.max(0, child.getHeight()));
        }
    }

    private void showProgress() {
        int max = scroll.maxScroll();
        int pct = max <= 0 ? 100 : Math.round(scroll.getScrollY() * 100f / max);
        Toast.makeText(this, "已读 " + pct + "%", Toast.LENGTH_SHORT).show();
    }

    private void showNewsMenu() {
        List<String> items = new ArrayList<>();
        items.add(related.isEmpty() ? "相关报道" : "相关报道（" + related.size() + "）");
        items.add("字号+");
        items.add("字号-");
        items.add("回顶部");
        items.add("跳到底部");
        items.add("阅读进度");
        items.add("复制正文");
        items.add("自动滚动");
        items.add("滚动速度");
        items.add("阅读设置");
        items.add("清屏");
        String[] arr = items.toArray(new String[items.size()]);
        new AlertDialog.Builder(this)
                .setTitle("阅读菜单")
                .setItems(arr, (d, which) -> {
                    switch (which) {
                        case 0: showRelated(); break;
                        case 1: changeFont(2f); break;
                        case 2: changeFont(-2f); break;
                        case 3: scroll.scrollTo(0, 0); break;
                        case 4: scrollToBottom(); break;
                        case 5: showProgress(); break;
                        case 6: copyContent(); break;
                        case 7: toggleAutoScroll(); break;
                        case 8: pickAutoSpeed(); break;
                        case 9: startActivity(new android.content.Intent(this, SettingsActivity.class)); break;
                        case 10: toggleBars(); break;
                        default: break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 相关报道：抓不到正文（微博/抖音/百度走检索通道）时，
     * 把检索到的每条结果列出来，点哪条就打开哪条的正文。
     */
    private void showRelated() {
        if (related.isEmpty()) {
            Toast.makeText(this, "本条没有相关报道", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] titles = new String[related.size()];
        for (int i = 0; i < related.size(); i++) {
            ArticleExtractor.Link l = related.get(i);
            titles[i] = (i + 1) + ". " + (l.title == null ? "" : l.title);
        }
        new AlertDialog.Builder(this)
                .setTitle("相关报道（共 " + related.size() + " 条）")
                .setItems(titles, (d, which) -> {
                    ArticleExtractor.Link l = related.get(which);
                    if (l == null || l.url.isEmpty()) {
                        Toast.makeText(this, "这条没有可用链接", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    android.content.Intent i =
                            new android.content.Intent(this, ArticleActivity.class);
                    i.putExtra("url", l.url);
                    i.putExtra("title", l.title);
                    i.putExtra("board", "");
                    i.putExtra("desc", "");
                    startActivity(i);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------
    // 自动滚动
    // ------------------------------------------------------------------

    private void ensureAutoTask() {
        if (autoTask != null) {
            return;
        }
        autoTask = new Runnable() {
            @Override
            public void run() {
                if (!autoOn) {
                    return;
                }
                int max = scroll.maxScroll();
                if (scroll.getScrollY() >= max) {
                    stopAutoScroll();
                    Toast.makeText(ArticleActivity.this, "已到末尾，自动滚动停止",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                scroll.scrollBy(0, AUTO_SPEED[autoSpeedLevel][0]);
                autoHandler.postDelayed(this, AUTO_SPEED[autoSpeedLevel][1]);
            }
        };
    }

    private void startAutoScroll() {
        autoOn = true;
        ensureAutoTask();
        autoHandler.removeCallbacks(autoTask);
        autoHandler.postDelayed(autoTask, AUTO_SPEED[autoSpeedLevel][1]);
        Toast.makeText(this, "自动滚动：开", Toast.LENGTH_SHORT).show();
    }

    private void stopAutoScroll() {
        autoOn = false;
        if (autoTask != null) {
            autoHandler.removeCallbacks(autoTask);
        }
    }

    private void toggleAutoScroll() {
        if (autoOn) {
            stopAutoScroll();
            Toast.makeText(this, "自动滚动：关", Toast.LENGTH_SHORT).show();
        } else {
            startAutoScroll();
        }
        View btn = findViewById(R.id.btnAuto);
        if (btn instanceof TextView) {
            ((TextView) btn).setText(autoOn ? "停止滚动" : "自动滚动");
        }
    }

    private void pickAutoSpeed() {
        String[] names = {"慢", "中", "快"};
        new AlertDialog.Builder(this)
                .setTitle("自动滚动速度")
                .setSingleChoiceItems(names, autoSpeedLevel, (d, which) -> {
                    autoSpeedLevel = which;
                    Prefs.setAutoSpeed(this, which);
                    d.dismiss();
                    Toast.makeText(this, "速度：" + names[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copyContent() {
        String text = tvContent.getText() == null ? "" : tvContent.getText().toString();
        if (text.trim().isEmpty()) {
            Toast.makeText(this, "正文还是空的", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("正文", text));
        Toast.makeText(this, "已复制正文", Toast.LENGTH_SHORT).show();
    }

    private void copyLink() {
        if (url.isEmpty()) {
            Toast.makeText(this, "没有可复制的链接", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("新闻链接", url));
        Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
