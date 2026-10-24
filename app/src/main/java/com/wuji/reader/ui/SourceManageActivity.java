package com.wuji.reader.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wuji.reader.R;
import com.wuji.reader.core.SourceKit;
import com.wuji.reader.core.Tasks;
import com.wuji.reader.news.NewsRegistry;
import com.wuji.reader.source.BookSource;
import com.wuji.reader.source.ComicSource;
import com.wuji.reader.source.SourceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 源管理页：小说 / 漫画 / 新闻三类源的启用停用、排序、自定义增删、一键重置。
 *
 * 导入（v0.20.0 起三种入口，全部落在同一套 addCustom 逻辑上）：
 *   1.「添加」：粘贴 Legado 书源 JSON 或 https:// 开头的 json 链接；
 *   2.「从文件选」：SAF（ACTION_OPEN_DOCUMENT）选本地 json 文件，
 *      不需要任何存储权限；
 *   3.「从网址导入」：输入 URL，Tasks.io 用 SourceKit.fetchBytes 下载
 *      （带移动端 UA），按字节自行兜底编码。
 * 导入结果用 SourceManager.addCustomBookReport / addCustomComicReport
 * 的可读文案 toast 反馈（成功几个 / 哪类失败）。
 *
 * 导出：「导出全部」把自定义源写成 {"books":[...],"comics":[...]} 合辑，
 * 经 SAF（ACTION_CREATE_DOCUMENT）由用户自选保存位置；该合辑可被本页
 * 三种入口原样重新导入（round-trip）。
 *
 * 交互铁律同全工程：网络/文件 IO 只在 Tasks.io，主线程只做
 * SourceManager 读写与列表刷新；黑白墨水屏风格。
 */
public class SourceManageActivity extends BaseActivity {

    private static final int MODE_BOOK = 0;
    private static final int MODE_COMIC = 1;
    private static final int MODE_NEWS = 2;

    private static final int REQ_IMPORT_FILE = 1001;
    private static final int REQ_EXPORT_FILE = 1002;

    private int mode = MODE_BOOK;

    private LinearLayout chipRow;
    private LinearLayout importExportRow;
    private Button btnAdd;
    private RecyclerView recycler;
    private RowAdapter adapter;
    private Runnable unbind;

    /** 待写入的导出内容：选完保存位置后在 onActivityResult 里落盘 */
    private String pendingExportJson;

    /** 列表行模型（三类源共用） */
    static class Row {
        final String id;
        final String name;
        final String meta;
        final boolean custom;
        final boolean enabled;

        Row(String id, String name, String meta, boolean custom, boolean enabled) {
            this.id = id;
            this.name = name;
            this.meta = meta;
            this.custom = custom;
            this.enabled = enabled;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_source_manage);

        // App.onCreate 已初始化；幂等兜底
        SourceManager.init(getApplicationContext());

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        chipRow = findViewById(R.id.chipRow);
        btnAdd = findViewById(R.id.btnAdd);
        importExportRow = findViewById(R.id.importExportRow);
        buildChips();

        findViewById(R.id.btnReset).setOnClickListener(v -> confirmReset());
        btnAdd.setOnClickListener(v -> showAddDialog());
        findViewById(R.id.btnImportFile).setOnClickListener(v -> pickImportFile());
        findViewById(R.id.btnImportUrl).setOnClickListener(v -> showUrlDialog());
        findViewById(R.id.btnExport).setOnClickListener(v -> pickExportFile());

        recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RowAdapter();
        recycler.setAdapter(adapter);

        unbind = ScrollTopHelper.attach(recycler, this);
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (unbind != null) {
            unbind.run();
            unbind = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT_FILE && (resultCode != RESULT_OK || data == null)) {
            // 用户取消了保存：丢掉待写内容，避免下次残留
            pendingExportJson = null;
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT_FILE) {
            readImportFile(uri);
        } else if (requestCode == REQ_EXPORT_FILE) {
            writeExportFile(uri);
        }
    }

    // ------------------------------------------------------------------
    // 类别切换
    // ------------------------------------------------------------------

    private void buildChips() {
        chipRow.removeAllViews();
        String[] names = {"小说", "漫画", "新闻"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button btn = makeChip(names[i]);
            btn.setOnClickListener(v -> setMode(idx));
            chipRow.addView(btn);
        }
        syncChips();
    }

    private void setMode(int m) {
        if (mode == m) {
            return;
        }
        mode = m;
        syncChips();
        refresh();
    }

    private void syncChips() {
        for (int j = 0; j < chipRow.getChildCount(); j++) {
            setChipSelected((Button) chipRow.getChildAt(j), j == mode);
        }
        // 新闻榜没有自定义源，隐藏「添加」与整行导入/导出
        boolean news = mode == MODE_NEWS;
        btnAdd.setVisibility(news ? View.GONE : View.VISIBLE);
        importExportRow.setVisibility(news ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    private void refresh() {
        List<Row> rows = new ArrayList<>();
        if (mode == MODE_BOOK) {
            for (BookSource s : SourceManager.allBooks()) {
                boolean custom = SourceManager.isCustomBook(s.getId());
                rows.add(new Row(s.getId(), s.getName(),
                        (custom ? "自定义" : "内置") + " · " + s.getId(),
                        custom, SourceManager.isEnabled(s.getId())));
            }
        } else if (mode == MODE_COMIC) {
            for (ComicSource s : SourceManager.allComics()) {
                boolean custom = SourceManager.isCustomComic(s.getId());
                rows.add(new Row(s.getId(), s.getName(),
                        (custom ? "自定义" : "内置") + " · " + s.getId(),
                        custom, SourceManager.isEnabled(s.getId())));
            }
        } else {
            for (String t : SourceManager.allNewsTypes()) {
                rows.add(new Row(t, NewsRegistry.labelOf(t), "新闻榜 · " + t,
                        false, SourceManager.isNewsEnabled(t)));
            }
        }
        adapter.setData(rows);
    }

    private final class RowAdapter extends RecyclerView.Adapter<RowAdapter.VH> {

        private List<Row> data = new ArrayList<>();

        void setData(List<Row> list) {
            data = list == null ? new ArrayList<Row>() : list;
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.item_source_manage, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            final Row row = data.get(pos);
            h.name.setText(row.name);
            h.meta.setText(row.meta);
            h.toggle.setText(row.enabled ? "停用" : "启用");
            h.delete.setVisibility(row.custom ? View.VISIBLE : View.GONE);

            h.up.setOnClickListener(v -> {
                if (mode == MODE_NEWS) {
                    SourceManager.moveNews(row.id, -1);
                } else {
                    SourceManager.move(row.id, -1);
                }
                refresh();
            });
            h.down.setOnClickListener(v -> {
                if (mode == MODE_NEWS) {
                    SourceManager.moveNews(row.id, 1);
                } else {
                    SourceManager.move(row.id, 1);
                }
                refresh();
            });
            h.toggle.setOnClickListener(v -> {
                if (mode == MODE_NEWS) {
                    SourceManager.setNewsEnabled(row.id, !row.enabled);
                } else {
                    SourceManager.setEnabled(row.id, !row.enabled);
                }
                refresh();
            });
            h.delete.setOnClickListener(v -> confirmDelete(row));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView meta;
            final Button up;
            final Button down;
            final Button toggle;
            final Button delete;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.tvName);
                meta = v.findViewById(R.id.tvMeta);
                up = v.findViewById(R.id.btnUp);
                down = v.findViewById(R.id.btnDown);
                toggle = v.findViewById(R.id.btnToggle);
                delete = v.findViewById(R.id.btnDelete);
            }
        }
    }

    // ------------------------------------------------------------------
    // 导入：粘贴 / 文件（SAF）/ 网址
    // ------------------------------------------------------------------

    private void showAddDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dip(16);
        root.setPadding(pad, pad, pad, pad);

        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setHint("粘贴 Legado 书源 JSON 或 https:// 开头的 json 链接");
        et.setMinLines(4);
        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        et.setTextColor(ContextCompat.getColor(this, R.color.ink_black));
        et.setHintTextColor(ContextCompat.getColor(this, R.color.ink_gray));
        root.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("添加源")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("添加", (DialogInterface d, int w) -> {
                    String raw = et.getText() == null ? "" : et.getText().toString().trim();
                    if (raw.isEmpty()) {
                        Toast.makeText(this, "请输入 JSON 或链接", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    doAdd(raw);
                })
                .show();
    }

    private void showUrlDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dip(16);
        root.setPadding(pad, pad, pad, pad);

        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setHint("https:// 开头的 json 链接");
        et.setTextColor(ContextCompat.getColor(this, R.color.ink_black));
        et.setHintTextColor(ContextCompat.getColor(this, R.color.ink_gray));
        root.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("从网址导入")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", (DialogInterface d, int w) -> {
                    String url = et.getText() == null ? "" : et.getText().toString().trim();
                    if (url.isEmpty()) {
                        Toast.makeText(this, "请输入链接", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    doAdd(url);
                })
                .show();
    }

    /**
     * 统一入口：原始文本（JSON 明文 / https:// 链接）。
     * 网络下载放 Tasks.io；主线程只收结果。
     */
    private void doAdd(String raw) {
        final String text = raw == null ? "" : raw.trim();
        Toast.makeText(this, "正在添加…", Toast.LENGTH_SHORT).show();
        Tasks.io(() -> {
            String json = text;
            String err = null;
            try {
                if (text.startsWith("http://") || text.startsWith("https://")) {
                    // 按字节拉再自行兜底编码：OkHttp 的 body.string() 只认
                    // Content-Type 里的 charset，GBK 的 json 链接会整段乱码
                    byte[] bytes = SourceKit.fetchBytes(text, SourceKit.baseHeaders());
                    json = decodeMaybeGbk(bytes);
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String j = (err == null) ? json : null;
            final String emsg = err;
            Tasks.ui(() -> {
                if (j == null) {
                    String why = (emsg == null || emsg.isEmpty()) ? "网络错误" : emsg;
                    Toast.makeText(this, "下载失败：" + why, Toast.LENGTH_LONG).show();
                    return;
                }
                importJsonText(j);
            });
        });
    }

    /**
     * 导入一段 JSON 文本（主线程调用，内部再转 io 做解析落盘）。
     * 兼容本页「导出全部」产出的 {"books":[...],"comics":[...]} 合辑：
     * 拆成小说/漫画两路分别导入，保证导出->导入可 round-trip。
     */
    private void importJsonText(String raw) {
        final String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "内容为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (text.startsWith("{")) {
            try {
                JSONObject wrapper = new JSONObject(text);
                JSONArray books = wrapper.optJSONArray("books");
                JSONArray comics = wrapper.optJSONArray("comics");
                if (books != null || comics != null) {
                    importCombined(books == null ? "[]" : books.toString(),
                            comics == null ? "[]" : comics.toString());
                    return;
                }
            } catch (Throwable ignored) {
                // 不是合辑格式（普通单对象源也以 { 开头），按单对象继续
            }
        }
        importSingle(text);
    }

    /** 单类导入：小说模式走书源，漫画模式走漫画源 */
    private void importSingle(String json) {
        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show();
        final int m = mode;
        Tasks.io(() -> {
            final String report = (m == MODE_COMIC)
                    ? SourceManager.addCustomComicReport(json)
                    : SourceManager.addCustomBookReport(json);
            Tasks.ui(() -> {
                Toast.makeText(this, report, Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    /** 合辑导入：books / comics 两路分别导入，汇总回报 */
    private void importCombined(String booksJson, String comicsJson) {
        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show();
        final int expect = countJsonEntries(booksJson) + countJsonEntries(comicsJson);
        Tasks.io(() -> {
            String r1 = SourceManager.addCustomBookReport(booksJson);
            String r2 = SourceManager.addCustomComicReport(comicsJson);
            boolean okA = r1.startsWith("成功");
            boolean okB = r2.startsWith("成功");
            final String msg;
            if (okA && okB) {
                msg = "成功导入 " + expect + " 个源";
            } else if (okA) {
                msg = "小说源已导入；漫画源：" + r2;
            } else if (okB) {
                msg = "漫画源已导入；小说源：" + r1;
            } else {
                msg = r1 + "；" + r2;
            }
            Tasks.ui(() -> {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    // ------------------------------------------------------------------
    // SAF：选文件导入 / 选位置导出（不需要任何存储权限）
    // ------------------------------------------------------------------

    /**
     * SAF 选文件。type 设成通配 MIME：部分文件管理器对 .json 报的 mime 不准，
     * 写死 application/json 会什么都选不到；CATEGORY_OPENABLE 保证可打开。
     */
    private void pickImportFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_IMPORT_FILE);
        } catch (Exception e) {
            Toast.makeText(this, "系统文件选择器不可用", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickExportFile() {
        String json = SourceManager.exportCustomSourcesJson();
        if (json == null) {
            Toast.makeText(this, "还没有导入任何源", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingExportJson = json;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "wuji-reader-sources.json");
        try {
            startActivityForResult(intent, REQ_EXPORT_FILE);
        } catch (Exception e) {
            pendingExportJson = null;
            Toast.makeText(this, "系统文件选择器不可用", Toast.LENGTH_SHORT).show();
        }
    }

    /** 读 SAF 文件全文（IO 在线程里），按编码兜底转 String 后走统一导入 */
    private void readImportFile(Uri uri) {
        Toast.makeText(this, "正在读取文件…", Toast.LENGTH_SHORT).show();
        Tasks.io(() -> {
            String json = null;
            String err = null;
            InputStream in = null;
            try {
                in = getContentResolver().openInputStream(uri);
                if (in == null) {
                    throw new IllegalStateException("打不开所选文件");
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                json = decodeMaybeGbk(bos.toByteArray());
            } catch (Exception e) {
                err = e.getMessage();
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            final String j = json;
            final String emsg = err;
            Tasks.ui(() -> {
                if (j == null || j.trim().isEmpty()) {
                    String why = (emsg == null || emsg.isEmpty()) ? "空文件" : emsg;
                    Toast.makeText(this, "读取失败：" + why, Toast.LENGTH_LONG).show();
                    return;
                }
                importJsonText(j);
            });
        });
    }

    /** 把导出内容写进 SAF 选定的位置（IO 在线程里），UTF-8 */
    private void writeExportFile(Uri uri) {
        final String json = pendingExportJson;
        pendingExportJson = null;
        if (json == null) {
            return;
        }
        final int count = countCombined(json);
        Tasks.io(() -> {
            String err = null;
            OutputStream out = null;
            try {
                out = getContentResolver().openOutputStream(uri);
                if (out == null) {
                    throw new IllegalStateException("打不开目标文件");
                }
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                err = e.getMessage();
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            final String emsg = err;
            Tasks.ui(() -> {
                if (emsg == null) {
                    Toast.makeText(this, "已导出 " + count + " 个源", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "导出失败：" + emsg, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    // ------------------------------------------------------------------
    // 删除 / 重置
    // ------------------------------------------------------------------

    private void confirmDelete(Row row) {
        new AlertDialog.Builder(this)
                .setMessage("删除自定义源「" + row.name + "」？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (DialogInterface d, int w) -> {
                    if (SourceManager.removeCustom(row.id)) {
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
                    }
                    refresh();
                })
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("重置源管理")
                .setMessage("清掉自定义源、排序与停用设置，恢复默认？")
                .setNegativeButton("取消", null)
                .setPositiveButton("重置", (DialogInterface d, int w) -> {
                    SourceManager.reset();
                    Toast.makeText(this, "已重置", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .show();
    }

    // ------------------------------------------------------------------
    // 工具（样式对齐 MainActivity / NewsActivity）
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

    /**
     * 编码兜底：先按 UTF-8 解；出现替换符 U+FFFD 说明是 GBK 等遗留编码，
     * 按 GBK 重解。Legado 源 JSON 两种编码都有人发。
     */
    private static String decodeMaybeGbk(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') < 0) {
            return utf8;
        }
        try {
            return new String(bytes, "GBK");
        } catch (Throwable ignored) {
            return utf8;
        }
    }

    /** 数一段 JSON 的源条数：数组取 length，单对象取 1，都不是取 0 */
    private static int countJsonEntries(String json) {
        if (json == null || json.trim().isEmpty()) {
            return 0;
        }
        try {
            return new JSONArray(json).length();
        } catch (Throwable notArray) {
            try {
                new JSONObject(json);
                return 1;
            } catch (Throwable bad) {
                return 0;
            }
        }
    }

    /** 合辑 {"books":[...],"comics":[...]} 的总条数 */
    private static int countCombined(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray b = o.optJSONArray("books");
            JSONArray c = o.optJSONArray("comics");
            return (b == null ? 0 : b.length()) + (c == null ? 0 : c.length());
        } catch (Throwable t) {
            return 0;
        }
    }
}
