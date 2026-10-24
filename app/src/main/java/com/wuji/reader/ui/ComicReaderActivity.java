package com.wuji.reader.ui;

import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.wuji.reader.R;
import com.wuji.reader.core.Tasks;
import com.wuji.reader.model.Chapter;
import com.wuji.reader.model.Item;
import com.wuji.reader.source.ComicSource;
import com.wuji.reader.source.SourceManager;
import com.wuji.reader.store.Prefs;

import java.util.ArrayList;
import java.util.List;

/**
 * 漫画阅读器。
 *
 * 逐张 ImageView 翻页（不做滑动 ViewPager），
 * 这样墨水屏每次翻页都是干净的单图全刷，也方便后续接物理翻页键。
 *
 * 缩放/拖动（MATRIX）：双指缩放 clamp 1.0–4.0（1.0 = fitCenter），
 * 单指在放大状态下拖动并 clamp 图片边界，底栏按钮以画面中心 ×1.25 / ÷1.25，
 * 双击 1x↔2.5x；翻页/换话后重置 fitCenter。全部主线程操作，无动画。
 *
 * 取源（跨源安全）：sourceId extra 优先，经 SourceManager.comicById；
 * 为空回退 int source 经 SourceManager.comics()（越界保护）。
 */
public class ComicReaderActivity extends BaseActivity {

    private String sourceId = "";
    private String id = "";
    private String title = "";
    private String url = "";
    private int index = 0;

    private final List<Chapter> chapters = new ArrayList<>();
    private final List<String> pages = new ArrayList<>();
    private int chapterIndex = 0;
    private int pageIndex = 0;

    private ImageView imgPage;
    private TextView tvLoading;
    private TextView tvTitle;
    private TextView tvIndex;
    private View topBar;
    private View bottomBar;

    /** 九宫格 9 格动作配置（与小说/新闻共用一份用户配置） */
    private int[] nineGrid = NineGridConfig.defaults();

    /** 生效源（启用 + 排序后的集合里按 id 或下标取） */
    private ComicSource source;

    // ---- 缩放/拖动状态（1.0 = fitCenter） ----
    private final Matrix matrix = new Matrix();
    private float zoom = 1f;
    private static final float ZOOM_MIN = 1f;
    private static final float ZOOM_MAX = 50f;
    private static final float ZOOM_STEP = 1.25f;
    private static final float ZOOM_DOUBLE_TAP = 2.5f;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private float lastX;
    private float lastY;

    /**
     * 放大后翻页的顶点微调（像素）：默认左移 3、下移 3。
     * 图片贴左上时会露出扫描件自身的白边，靠这个偏移把起点挪进画面内容。
     * 用户可在「菜单 → 顶置微调」里改，改完立刻生效。
     */
    private int offX = -3;
    private int offY = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_comic);

        String sid = safe(getIntent().getStringExtra("sourceId"));
        int si = getIntent().getIntExtra("source", 0);
        id = safe(getIntent().getStringExtra("id"));
        title = safe(getIntent().getStringExtra("title"));
        url = safe(getIntent().getStringExtra("url"));
        index = getIntent().getIntExtra("index", 0);

        // 取源：sourceId 优先；为空按旧下标回退（越界保护）
        ComicSource src = null;
        if (!sid.isEmpty()) {
            src = SourceManager.comicById(sid);
        } else {
            List<ComicSource> list = SourceManager.comics();
            if (si >= 0 && si < list.size()) {
                src = list.get(si);
            }
        }
        if (src == null) {
            toast("源不存在或已停用");
            finish();
            return;
        }
        source = src;
        sourceId = src.getId();

        imgPage = findViewById(R.id.imgPage);
        tvLoading = findViewById(R.id.tvLoading);
        tvTitle = findViewById(R.id.tvTitle);
        tvIndex = findViewById(R.id.tvIndex);
        topBar = findViewById(R.id.comicTopBar);
        bottomBar = findViewById(R.id.comicBottomBar);
        tvTitle.setText(title);

        // 九宫格分区控制：与小说/新闻共用同一份用户配置
        nineGrid = NineGridConfig.load(this);

        // 放大翻页的顶点微调（像素）
        offX = Prefs.getComicOffX(this);
        offY = Prefs.getComicOffY(this);

        imgPage.setScaleType(ImageView.ScaleType.MATRIX);
        imgPage.setImageMatrix(matrix);
        imgPage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyZoomKeep(zoom));
        scaleDetector = new ScaleGestureDetector(this, new ScaleListener());
        gestureDetector = new GestureDetector(this, new GestureListener());
        imgPage.setOnTouchListener((v, event) -> onImageTouch(event));

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPrev).setOnClickListener(v -> showPage(pageIndex - 1));
        findViewById(R.id.btnNext).setOnClickListener(v -> showPage(pageIndex + 1));
        findViewById(R.id.btnZoomIn).setOnClickListener(v -> zoomBy(ZOOM_STEP));
        findViewById(R.id.btnZoomOut).setOnClickListener(v -> zoomBy(1f / ZOOM_STEP));

        loadBook();
    }

    // ------------------------------------------------------------------
    // 缩放 / 拖动
    // ------------------------------------------------------------------

    private boolean onImageTouch(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        scaleDetector.onTouchEvent(event);
        if (scaleDetector.isInProgress()) {
            return true;
        }
        if (event.getPointerCount() == 1) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX();
                    lastY = event.getY();
                    // 必须消费 DOWN：否则本视图不会成为手势目标，
                    // 后续 MOVE / 双指事件都不会到达，缩放与拖动全失效
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (zoom > 1.001f) {
                        float dx = event.getX() - lastX;
                        float dy = event.getY() - lastY;
                        lastX = event.getX();
                        lastY = event.getY();
                        matrix.postTranslate(dx, dy);
                        clampMatrix();
                        imgPage.setImageMatrix(matrix);
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float factor = detector.getScaleFactor();
            float target = zoom * factor;
            if (target < ZOOM_MIN) {
                target = ZOOM_MIN;
            }
            if (target > ZOOM_MAX) {
                target = ZOOM_MAX;
            }
            float ratio = target / zoom;
            matrix.postScale(ratio, ratio, detector.getFocusX(), detector.getFocusY());
            zoom = target;
            clampMatrix();
            imgPage.setImageMatrix(matrix);
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            float target = zoom > 1.05f ? ZOOM_MIN : ZOOM_DOUBLE_TAP;
            float ratio = target / zoom;
            matrix.postScale(ratio, ratio, e.getX(), e.getY());
            zoom = target;
            clampMatrix();
            imgPage.setImageMatrix(matrix);
            return true;
        }

        /**
         * 单指轻点 → 九宫格分区动作。
         *
         * 必须用 onSingleTapConfirmed 而不是 onSingleTapUp：它只在"确认这次不是
         * 双击"之后才回调，否则每次双击缩放都会被当成两次点击，误触发翻页。
         */
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            handleZoneTap(e.getX(), e.getY());
            return true;
        }
    }

    /** 底栏按钮：以画面中心缩放 */
    private void zoomBy(float factor) {
        float target = zoom * factor;
        if (target < ZOOM_MIN) {
            target = ZOOM_MIN;
        }
        if (target > ZOOM_MAX) {
            target = ZOOM_MAX;
        }
        float ratio = target / zoom;
        matrix.postScale(ratio, ratio, imgPage.getWidth() / 2f, imgPage.getHeight() / 2f);
        zoom = target;
        clampMatrix();
        imgPage.setImageMatrix(matrix);
    }

    /** 重置为 fitCenter（zoom 记 1.0）；翻页/换话/布局变化后调用 */
    private void resetToFit() {
        Drawable d = imgPage.getDrawable();
        int vw = imgPage.getWidth();
        int vh = imgPage.getHeight();
        if (d == null || vw <= 0 || vh <= 0) {
            return;
        }
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) {
            return;
        }
        matrix.reset();
        float s = Math.min((float) vw / dw, (float) vh / dh);
        matrix.postScale(s, s);
        matrix.postTranslate((vw - dw * s) / 2f, (vh - dh * s) / 2f);
        zoom = ZOOM_MIN;
        imgPage.setImageMatrix(matrix);
    }

    /**
     * 翻页/布局变化后重新铺版，但保留用户调过的倍数。
     *
     * 逐话阅读常是连续几十页，如果每翻一页都掉回 1 倍，用户把小字放大到
     * 合适比例后就没法读了。这里先按 fitCenter 铺好，再以**左上角 (0,0)**
     * 为焦点叠加指定倍数，最后把 zoom 字段恢复回去。
     *
     * 焦点必须是 (0,0) 而不是画面中心：配合 clampMatrix 的"贴边"逻辑，
     * 图片超出视图时左上角对齐视图左上角，翻页后新图**从顶部开始显示**。
     * 用画面中心做焦点时上下各露一半，用户翻一页先看到的是图片中段。
     */
    private void applyZoomKeep(float target) {
        resetToFit(); // 内部会把 zoom 置成 1
        float keep = Math.min(Math.max(target, ZOOM_MIN), ZOOM_MAX);
        if (keep > 1.001f) {
            matrix.postScale(keep, keep, 0f, 0f);
            clampMatrix();
            // 顶点微调：贴边之后再按用户设定偏移，用来裁掉扫描件自身的白边。
            // 只在重建矩阵时加一次，手动拖动不会累加。
            if (offX != 0 || offY != 0) {
                matrix.postTranslate(offX, offY);
            }
            imgPage.setImageMatrix(matrix);
        }
        zoom = keep;
    }

    /** 放大后把图片约束在视图内：超出则贴边，不足则居中 */
    private void clampMatrix() {
        Drawable d = imgPage.getDrawable();
        if (d == null) {
            return;
        }
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        int vw = imgPage.getWidth();
        int vh = imgPage.getHeight();
        if (dw <= 0 || dh <= 0 || vw <= 0 || vh <= 0) {
            return;
        }
        RectF r = new RectF(0, 0, dw, dh);
        matrix.mapRect(r);
        float dx = 0f;
        float dy = 0f;
        if (r.width() <= vw) {
            dx = (vw - r.width()) / 2f - r.left;
        } else if (r.left > 0) {
            dx = -r.left;
        } else if (r.right < vw) {
            dx = vw - r.right;
        }
        if (r.height() <= vh) {
            dy = (vh - r.height()) / 2f - r.top;
        } else if (r.top > 0) {
            dy = -r.top;
        } else if (r.bottom < vh) {
            dy = vh - r.bottom;
        }
        if (dx != 0f || dy != 0f) {
            matrix.postTranslate(dx, dy);
        }
    }

    // ------------------------------------------------------------------
    // 目录 / 翻页
    // ------------------------------------------------------------------

    private void loadBook() {
        tvLoading.setText(R.string.loading);
        tvLoading.setVisibility(View.VISIBLE);

        Tasks.io(() -> {
            Item d;
            try {
                d = source.getComicDetail(makeItem(), 1);
            } catch (Exception e) {
                final String msg = safe(e.getMessage());
                Tasks.ui(() -> tvLoading.setText("加载目录失败：" + msg));
                return;
            }
            final List<Chapter> chs = d.hasChapters()
                    ? new ArrayList<>(d.chapters) : new ArrayList<>();

            Tasks.ui(() -> {
                chapters.clear();
                chapters.addAll(chs);
                if (chs.isEmpty()) {
                    tvLoading.setText("没有取到目录");
                    return;
                }
                if (index >= chs.size()) {
                    index = 0;
                }
                loadChapter(index, false);
            });
        });
    }

    private void loadChapter(int ci, boolean toLast) {
        final int target = ci;
        tvLoading.setText(R.string.loading);
        tvLoading.setVisibility(View.VISIBLE);

        Tasks.io(() -> {
            List<String> imgs;
            try {
                imgs = source.getContent(makeItem(), chapters.get(target));
            } catch (Exception e) {
                final String msg = safe(e.getMessage());
                Tasks.ui(() -> tvLoading.setText("加载图片失败：" + msg));
                return;
            }
            final List<String> ps = imgs;

            Tasks.ui(() -> {
                chapterIndex = target;
                pages.clear();
                if (ps != null) {
                    pages.addAll(ps);
                }
                Prefs.setProgress(this, progressKey(), target);

                if (pages.isEmpty()) {
                    tvLoading.setText("这一话没有取到图片");
                    tvIndex.setText("");
                    return;
                }
                int start;
                if (toLast) {
                    start = pages.size() - 1;
                } else {
                    start = Prefs.getComicPage(this, pageKey());
                    if (start < 0 || start >= pages.size()) {
                        start = 0;
                    }
                }
                showPage(start);
            });
        });
    }

    private void showPage(int p) {
        if (pages.isEmpty()) {
            return;
        }
        if (p < 0) {
            // 往回翻越界 -> 跳上一话末尾
            if (chapterIndex > 0) {
                loadChapter(chapterIndex - 1, true);
            } else {
                toast("已经是第一页");
            }
            return;
        }
        if (p >= pages.size()) {
            if (chapterIndex < chapters.size() - 1) {
                loadChapter(chapterIndex + 1, false);
            } else {
                toast("没有更多了");
            }
            return;
        }

        pageIndex = p;
        Prefs.setComicPage(this, pageKey(), p);
        tvIndex.setText((p + 1) + " / " + pages.size());
        tvTitle.setText(safe(chapters.get(chapterIndex).title)
                + "  " + title);

        tvLoading.setText(R.string.loading);
        tvLoading.setVisibility(View.VISIBLE);

        Object model = pages.get(p);
        String referer = source == null ? null : source.imageReferer();
        if (referer != null && !referer.isEmpty()) {
            model = new GlideUrl(pages.get(p),
                    new LazyHeaders.Builder().addHeader("Referer", referer).build());
        }

        final float keepZoom = zoom; // 记录当前倍数，新图就位后原样恢复

        Glide.with(this)
                .load(model)
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(GlideException e, Object model,
                                                Target<Drawable> t, boolean isFirst) {
                        tvLoading.setText("图片加载失败");
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable r, Object model,
                                                   Target<Drawable> t, DataSource ds,
                                                   boolean isFirst) {
                        tvLoading.setVisibility(View.GONE);
                        // 新图就位后重新铺版并保持用户倍数（post 保证 drawable 已进 target）
                        imgPage.post(() -> applyZoomKeep(keepZoom));
                        return false;
                    }
                })
                .into(imgPage);
    }

    // ------------------------------------------------------------------
    // 九宫格分区控制（漫画）
    //
    // 语义对齐漫画：上一页/下一页 = 上一张/下一张图；
    // 上一章/下一章 = 上一话/下一话；目录 = 选话；另有放大/缩小。
    // ------------------------------------------------------------------

    private void handleZoneTap(float x, float y) {
        int w = imgPage.getWidth();
        int h = imgPage.getHeight();
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

    private void runAction(NineGridAction act) {
        switch (act) {
            case CLEAR_SCREEN:
                toggleBars();
                break;
            case MENU:
                showComicMenu();
                break;
            case PREV_PAGE:
                showPage(pageIndex - 1);
                break;
            case NEXT_PAGE:
                showPage(pageIndex + 1);
                break;
            case PREV_CHAPTER:
                gotoChapter(chapterIndex - 1);
                break;
            case NEXT_CHAPTER:
                gotoChapter(chapterIndex + 1);
                break;
            case TOC:
                showToc();
                break;
            case ZOOM_IN:
                zoomBy(ZOOM_STEP);
                break;
            case ZOOM_OUT:
                zoomBy(1f / ZOOM_STEP);
                break;
            case TOP:
                showPage(0);
                break;
            case BOTTOM:
                showPage(pages.size() - 1);
                break;
            case PROGRESS:
                showProgress();
                break;
            case REFRESH:
                showPage(pageIndex);
                break;
            case BACK:
                finish();
                break;
            case SETTINGS:
                startActivity(new android.content.Intent(this, SettingsActivity.class));
                break;
            default:
                // 字号/复制/自动滚动/相关报道对漫画不适用，静默忽略
                break;
        }
    }

    /** 越界保护：chapters 可能还没加载完，直接 loadChapter 会数组越界 */
    private void gotoChapter(int ci) {
        if (chapters.isEmpty()) {
            toast("目录还没加载好");
            return;
        }
        if (ci < 0) {
            toast("已经是第一话");
            return;
        }
        if (ci >= chapters.size()) {
            toast("没有更多章节了");
            return;
        }
        loadChapter(ci, false);
    }

    private void toggleBars() {
        boolean hide = topBar.getVisibility() == View.VISIBLE;
        topBar.setVisibility(hide ? View.GONE : View.VISIBLE);
        bottomBar.setVisibility(hide ? View.GONE : View.VISIBLE);
    }

    private void showProgress() {
        String ch = (chapterIndex + 1) + "/" + chapters.size();
        String pg = pages.isEmpty() ? "-" : ((pageIndex + 1) + "/" + pages.size());
        toast("第 " + ch + " 话 · 第 " + pg + " 页");
    }

    private void showComicMenu() {
        String[] items = {
                "目录（选话）", "上一页", "下一页", "上一话", "下一话",
                "放大", "缩小", "本话首页", "本话末页", "阅读进度", "阅读设置", "清屏",
                "顶置微调（白边）"
        };
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("漫画菜单")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: showToc(); break;
                        case 1: showPage(pageIndex - 1); break;
                        case 2: showPage(pageIndex + 1); break;
                        case 3: gotoChapter(chapterIndex - 1); break;
                        case 4: gotoChapter(chapterIndex + 1); break;
                        case 5: zoomBy(ZOOM_STEP); break;
                        case 6: zoomBy(1f / ZOOM_STEP); break;
                        case 7: showPage(0); break;
                        case 8: showPage(pages.size() - 1); break;
                        case 9: showProgress(); break;
                        case 10: startActivity(new android.content.Intent(this, SettingsActivity.class)); break;
                        case 11: toggleBars(); break;
                        case 12: showOffsetDialog(); break;
                        default: break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 顶置微调：调整放大翻页后贴左上的位置（像素）。
     *
     * 默认 X=-3（左移）、Y=3（下移）。左移能裁掉图片左侧白边；
     * 顶部白边要把 Y 调成负数（上移）。改完立即重排，不用退出。
     */
    private void showOffsetDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density + 0.5f);
        box.setPadding(pad, pad, pad, pad);

        TextView tip = new TextView(this);
        tip.setText("单位：像素，可填负数。\nX 负数=左移（裁左侧白边）；Y 正数=下移，负数=上移（裁顶部白边）。");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        box.addView(tip);

        TextView lx = new TextView(this);
        lx.setText("X 偏移（默认 -3）");
        box.addView(lx);
        final EditText etX = new EditText(this);
        etX.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        etX.setText(String.valueOf(offX));
        box.addView(etX);

        TextView ly = new TextView(this);
        ly.setText("Y 偏移（默认 3）");
        box.addView(ly);
        final EditText etY = new EditText(this);
        etY.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        etY.setText(String.valueOf(offY));
        box.addView(etY);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("顶置微调")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", (d, w) -> {
                    int nx = parseInt(etX.getText().toString(), offX);
                    int ny = parseInt(etY.getText().toString(), offY);
                    // 限幅，避免手滑输入离谱值把图整张推出屏幕
                    nx = Math.max(-200, Math.min(200, nx));
                    ny = Math.max(-200, Math.min(200, ny));
                    offX = nx;
                    offY = ny;
                    Prefs.setComicOff(this, offX, offY);
                    applyZoomKeep(zoom); // 立即重排生效
                    toast("顶点偏移：X=" + offX + "，Y=" + offY);
                })
                .show();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 选话：话数少直接列，话数多改"输入话数跳转"，避免一次塞几千条把低内存拖垮 */
    private void showToc() {
        if (chapters.isEmpty()) {
            toast("目录还没加载好");
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
                .setTitle("目录（共 " + chapters.size() + " 话）")
                .setItems(titles, (d, which) -> loadChapter(which, false))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showChapterJumpDialog() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setHint("话数，1 起");
        int pad = (int) (16 * getResources().getDisplayMetrics().density + 0.5f);
        et.setPadding(pad, pad, pad, pad);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("跳转到话（共 " + chapters.size() + " 话）")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("跳转", (d, w) -> {
                    int n;
                    try {
                        n = Integer.parseInt(et.getText().toString().trim());
                    } catch (Exception e) {
                        toast("请输入数字");
                        return;
                    }
                    int idx = n - 1;
                    if (idx < 0) {
                        idx = 0;
                    }
                    if (idx > chapters.size() - 1) {
                        idx = chapters.size() - 1;
                    }
                    loadChapter(idx, false);
                })
                .show();
    }

    private Item makeItem() {
        Item it = new Item();
        it.id = id;
        it.url = url;
        it.title = title;
        it.sourceId = sourceId;
        return it;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private String progressKey() {
        return "comic:" + sourceId + ":" + id;
    }

    private String pageKey() {
        return progressKey() + "#" + chapterIndex;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
