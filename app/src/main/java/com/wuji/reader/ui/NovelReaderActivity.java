package com.wuji.reader.ui;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.wuji.reader.R;
import com.wuji.reader.core.Tasks;
import com.wuji.reader.model.Chapter;
import com.wuji.reader.model.Item;
import com.wuji.reader.source.BookSource;
import com.wuji.reader.source.SourceManager;
import com.wuji.reader.store.Prefs;
import com.wuji.reader.ui.ReaderTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * 小说阅读器。
 *
 * 墨水屏取向：ScrollView + TextView 纯文本（不用 ViewPager 翻页动画），
 * 字号可调并持久化，翻章节回滚到顶部，主线程不做网络。
 *
 * 取源（跨源安全）：sourceId extra 优先，经 SourceManager.bookById；
 * 为空回退 int source 经 SourceManager.books()（越界保护）。
 */
public class NovelReaderActivity extends BaseActivity {

    private String sourceId = "";
    private String id = "";
    private String title = "";
    private String url = "";
    private int index = 0;

    private final List<Chapter> chapters = new ArrayList<>();
    private float fontSp = 19f;

    private TextView tvContent;
    private TextView tvTitle;
    private ObservableScrollView scroll;
    private View topBar;
    private View bottomBar;

    /** 九宫格 9 格的动作配置（索引 0=左上 … 8=右下） */
    private int[] nineGrid = NineGridConfig.defaults();
    /** 记录按下位置，用于区分"点击"与"滚动" */
    private float downX;
    private float downY;
    private long downTime;

    /** 自动滚动：主线程 Handler 驱动，离开页面必须停 */
    private final Handler autoHandler = new Handler(Looper.getMainLooper());
    private Runnable autoTask;
    private boolean autoOn = false;
    private int autoSpeedLevel = 1; // 0=慢 1=中 2=快
    // 每档：[每次滚动像素, 间隔毫秒]
    private static final int[][] AUTO_SPEED = {{1, 60}, {2, 35}, {4, 20}};

    /** 生效源（启用 + 排序后的集合里按 id 或下标取） */
    private BookSource source;
    private Runnable unbindScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        String sid = safe(getIntent().getStringExtra("sourceId"));
        int si = getIntent().getIntExtra("source", 0);
        id = safe(getIntent().getStringExtra("id"));
        title = safe(getIntent().getStringExtra("title"));
        url = safe(getIntent().getStringExtra("url"));
        index = getIntent().getIntExtra("index", 0);

        // 取源：sourceId 优先；为空按旧下标回退（越界保护）
        BookSource src = null;
        if (!sid.isEmpty()) {
            src = SourceManager.bookById(sid);
        } else {
            List<BookSource> list = SourceManager.books();
            if (si >= 0 && si < list.size()) {
                src = list.get(si);
            }
        }
        if (src == null) {
            showToast("源不存在或已停用");
            finish();
            return;
        }
        source = src;
        sourceId = src.getId();

        tvContent = findViewById(R.id.tvContent);
        tvTitle = findViewById(R.id.tvTitle);
        scroll = findViewById(R.id.scroll);
        topBar = findViewById(R.id.topBar);
        bottomBar = findViewById(R.id.bottomBar);
        // 右侧可拖拽滚动条（原生滚动条只能看不能拖）
        ((FastScrollBar) findViewById(R.id.fastBar)).bind(scroll);

        // 九宫格分区控制：点内容区不同位置触发不同功能
        nineGrid = NineGridConfig.load(this);
        setupZoneTouch();

        fontSp = Prefs.getFontSp(this);
        // 正文用 dp：dp 不吃全局 fontScale，避免"全局字号 × 正文字号"被连乘两次
        tvContent.setTextSize(TypedValue.COMPLEX_UNIT_DIP, fontSp);

        // 阅读背景色（整页纯色，文字保持黑）
        ReaderTheme.apply(this, findViewById(R.id.readerRoot), tvContent);
        autoSpeedLevel = Prefs.getAutoSpeed(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPrev).setOnClickListener(v -> showChapter(index - 1));
        findViewById(R.id.btnNext).setOnClickListener(v -> showChapter(index + 1));
        findViewById(R.id.btnFontDown).setOnClickListener(v -> changeFont(-2f));
        findViewById(R.id.btnFontUp).setOnClickListener(v -> changeFont(2f));
        findViewById(R.id.btnMenu).setOnClickListener(v -> showReadingMenu());
        findViewById(R.id.btnAuto).setOnClickListener(v -> toggleAutoScroll());

        // 正文回顶按钮（ScrollView 只挂回顶，不挂滑动栏）
        unbindScroll = ScrollTopHelper.attach(scroll, this);

        loadBook();
    }

    @Override
    protected void onPause() {
        stopAutoScroll();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopAutoScroll();
        if (unbindScroll != null) {
            unbindScroll.run();
            unbindScroll = null;
        }
        super.onDestroy();
    }

    private void loadBook() {
        tvContent.setText(R.string.loading);
        Tasks.io(() -> {
            Item d;
            try {
                d = source.getBookDetail(makeItem(), 1);
            } catch (Exception e) {
                final String msg = safe(e.getMessage());
                Tasks.ui(() -> tvContent.setText("加载目录失败：" + msg));
                return;
            }
            final List<Chapter> chs = d.hasChapters()
                    ? new ArrayList<>(d.chapters) : new ArrayList<>();

            Tasks.ui(() -> {
                chapters.clear();
                chapters.addAll(chs);
                if (chs.isEmpty()) {
                    tvContent.setText("没有取到目录");
                    return;
                }
                if (index >= chs.size()) {
                    index = 0;
                }
                showChapter(index);
            });
        });
    }

    private void showChapter(int i) {
        if (chapters.isEmpty()) {
            return;
        }
        if (i < 0) {
            showToast("已经是第一章");
            return;
        }
        if (i >= chapters.size()) {
            showToast("没有更多章节了");
            return;
        }
        final int pos = i;
        index = pos;
        Prefs.setProgress(this, progressKey(), pos);

        Chapter c = chapters.get(pos);
        tvTitle.setText(c.title == null ? "" : c.title);
        tvContent.setText(R.string.loading);
        scroll.scrollTo(0, 0);

        Tasks.io(() -> {
            String text;
            try {
                text = source.getContent(makeItem(), chapters.get(pos));
            } catch (Exception e) {
                final String msg = safe(e.getMessage());
                Tasks.ui(() -> tvContent.setText("加载正文失败：" + msg));
                return;
            }
            final String t = text;
            Tasks.ui(() -> {
                tvContent.setText(t == null || t.isEmpty() ? "（正文为空）" : t);
                scroll.scrollTo(0, 0);
            });
        });
    }

    // ------------------------------------------------------------------
    // 九宫格分区控制
    // ------------------------------------------------------------------

    /**
     * 内容区点击 → 九宫格分区。
     *
     * 关键：OnTouchListener 一律返回 false，滚动完全交给 ScrollView 自己做；
     * 只在"位移小于阈值且按下时间短"时判定为点击并派发动作。
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

    /** 坐标 → 3×3 格子 → 动作 */
    private void handleZoneTap(float x, float y) {
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
        int idx = NineGridConfig.indexOf(row, col);
        NineGridAction act = NineGridConfig.actionAt(nineGrid, idx);
        if (act == NineGridAction.NONE) {
            return;
        }
        runAction(act);
    }

    private void runAction(NineGridAction act) {
        switch (act) {
            case CLEAR_SCREEN:
                toggleBars();
                break;
            case MENU:
                showReadingMenu();
                break;
            case PREV_CHAPTER:
                showChapter(index - 1);
                break;
            case NEXT_CHAPTER:
                showChapter(index + 1);
                break;
            case PREV_PAGE:
                scrollPage(-1);
                break;
            case NEXT_PAGE:
                scrollPage(1);
                break;
            case FONT_UP:
                changeFont(2f);
                break;
            case FONT_DOWN:
                changeFont(-2f);
                break;
            case TOC:
                showToc();
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
                showChapter(index);
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
     * 整屏翻页：按当前可视高度向上/向下滚动一屏。
     * 与"上一章/下一章"不同——这里不切章节，只在当前正文里滚动。
     */
    private void scrollPage(int dir) {
        int max = scroll.maxScroll();
        int y = scroll.getScrollY();
        // 已经在章首还想往上翻 → 翻到上一章（已是第一章时 showChapter 会提示）
        if (dir < 0 && y <= 0) {
            showChapter(index - 1);
            return;
        }
        // 已经在章末还想往下翻 → 翻到下一章（同上，末章会提示）
        if (dir > 0 && y >= max) {
            showChapter(index + 1);
            return;
        }
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

    /** 清屏：隐藏上下工具栏，正文占满屏幕；再点一次恢复 */
    private void toggleBars() {
        boolean hide = topBar.getVisibility() == View.VISIBLE;
        topBar.setVisibility(hide ? View.GONE : View.VISIBLE);
        bottomBar.setVisibility(hide ? View.GONE : View.VISIBLE);
    }

    /** 阅读菜单：把常用动作集中到一处（墨水屏上比记九宫格方便） */
    private void showReadingMenu() {
        String[] items = {
                "目录", "字号+", "字号-", "回顶部", "跳到底部",
                "阅读进度", "复制正文", "自动滚动", "滚动速度", "阅读设置", "清屏"
        };
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("阅读菜单")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: showToc(); break;
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

    // ------------------------------------------------------------------
    // 自动上下滚动（速度可调）
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
                int y = scroll.getScrollY();
                if (y >= max) {
                    // 本章读完：还有下一章就接着滚，已是最后一章才停
                    if (index + 1 < chapters.size()) {
                        showChapter(index + 1);
                        autoHandler.postDelayed(this, AUTO_SPEED[autoSpeedLevel][1]);
                    } else {
                        stopAutoScroll();
                        showToast("已到最后一章，自动滚动停止");
                    }
                    return;
                }
                int step = AUTO_SPEED[autoSpeedLevel][0];
                scroll.scrollBy(0, step);
                autoHandler.postDelayed(this, AUTO_SPEED[autoSpeedLevel][1]);
            }
        };
    }

    private void startAutoScroll() {
        autoOn = true;
        ensureAutoTask();
        autoHandler.removeCallbacks(autoTask);
        autoHandler.postDelayed(autoTask, AUTO_SPEED[autoSpeedLevel][1]);
        showToast("自动滚动：开");
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
            showToast("自动滚动：关");
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
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("自动滚动速度")
                .setSingleChoiceItems(names, autoSpeedLevel, (d, which) -> {
                    autoSpeedLevel = which;
                    Prefs.setAutoSpeed(this, which);
                    d.dismiss();
                    showToast("速度：" + names[which]);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 目录：章节少时直接列；章节多（上千章）时用"输入章节号"跳转，
     * 避免一次塞几千个字符串进对话框把低内存墨水屏拖垮。
     */
    private void showToc() {
        if (chapters.isEmpty()) {
            showToast("目录还没加载好");
            return;
        }
        if (chapters.size() > 200) {
            showChapterJumpDialog();
            return;
        }
        String[] titles = new String[chapters.size()];
        for (int i = 0; i < chapters.size(); i++) {
            Chapter c = chapters.get(i);
            titles[i] = (i + 1) + ". " + (c == null || c.title == null ? "" : c.title);
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("目录（共 " + chapters.size() + " 章）")
                .setItems(titles, (d, which) -> showChapter(which))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showChapterJumpDialog() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setHint("章节号，1 起");
        int pad = (int) (16 * getResources().getDisplayMetrics().density + 0.5f);
        et.setPadding(pad, pad, pad, pad);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("跳转到章节（共 " + chapters.size() + " 章）")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("跳转", (d, w) -> {
                    int n;
                    try {
                        n = Integer.parseInt(et.getText().toString().trim());
                    } catch (Exception e) {
                        showToast("请输入数字");
                        return;
                    }
                    int idx = n - 1;
                    if (idx < 0) {
                        idx = 0;
                    }
                    if (idx > chapters.size() - 1) {
                        idx = chapters.size() - 1;
                    }
                    showChapter(idx);
                })
                .show();
    }

    private void scrollToBottom() {
        android.view.View child = scroll.getChildAt(0);
        if (child != null) {
            scroll.scrollTo(0, Math.max(0, child.getHeight()));
        }
    }

    private void showProgress() {
        int max = scroll.maxScroll();
        int pct = max <= 0 ? 100 : Math.round(scroll.getScrollY() * 100f / max);
        int total = chapters.size();
        String where = (index + 1) + "/" + total;
        showToast("第 " + where + " 章 · 本章 " + pct + "%");
    }

    private void copyContent() {
        String text = tvContent.getText() == null ? "" : tvContent.getText().toString();
        if (text.trim().isEmpty()) {
            showToast("正文还是空的");
            return;
        }
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            showToast("复制失败");
            return;
        }
        cm.setPrimaryClip(android.content.ClipData.newPlainText("正文", text));
        showToast("已复制本章正文");
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

    private Item makeItem() {
        Item it = new Item();
        it.id = id;
        it.url = url;
        it.title = title;
        it.sourceId = sourceId;
        return it;
    }

    private void showToast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private String progressKey() {
        return "book:" + sourceId + ":" + id;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
