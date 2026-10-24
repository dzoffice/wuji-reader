package com.wuji.reader.ui;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;

import com.wuji.reader.R;

/**
 * 关于页。
 *
 * 内容（对应发布版要求）：
 *   - 上游声明：本软件基于 wuji-tauri（GPL-3.0）衍生，附上游仓库地址；
 *   - 项目地址：本仓库的 GitHub 地址（占位，发布时替换为真实地址）；
 *   - 版本信息：动态读 PackageInfo 的 versionName + versionCode，
 *     不写死，免得每次发版都要改代码；
 *   - 开源许可：GPL-3.0 声明，指向随源码分发的 LICENSE 文件；
 *   - 特性与墨水屏优化历程：占位文案，随版本演进补充。
 *
 * 墨水屏取向：纯黑白、无动画；整页 ScrollView 可滚动；
 * 所有字号均为 sp，会被 BaseActivity 的全局字号统一缩放。
 */
public class AboutActivity extends BaseActivity {

    /** 上游项目地址（wuji-tauri，GPL-3.0），真实存在 */
    private static final String UPSTREAM_URL = "https://github.com/moshstudio/wuji-tauri";

    /**
     * 本项目仓库地址。
     * 发布到 GitHub 后请把这里替换成真实仓库地址；
     * 替换前页面会原样显示这个占位符，不伪造假链接。
     */
    private static final String REPO_URL = "https://github.com/dzoffice/wuji-reader";

    /** 特性占位（4-6 条，随版本演进补充） */
    private static final String FEATURES =
            "· 纯原生实现：无 WebView、无 JS 引擎，抓取由 OkHttp + Jsoup 完成\n"
                    + "· 墨水屏取向：纯黑白高对比，无动画、无阴影、无半透明\n"
                    + "· 书源管理：导入 Legado 规则书源 JSON，支持启用 / 停用 / 排序\n"
                    + "· 小说 / 漫画 / 新闻热榜三个板块，书架与阅读进度本地保存\n"
                    + "· 全局字号：界面字号与正文字号独立可调\n"
                    + "· 兼容 Android 6.0（minSdk 23）老墨水屏设备";

    /** 墨水屏优化历程占位 */
    private static final String EINK_HISTORY =
            "· v0.20.0 发布版净化：移除全部内置书源改为导入制；新增关于页与 GPL-3.0 许可\n"
                    + "· v0.11.0 全局字号注入：界面 / 正文字号分离，九宫格布局设置\n"
                    + "· v0.8.0 源管理页：启用 / 停用 / 排序 / 自定义源；漫画阅读器缩放与拖动\n"
                    + "· 早期版本：墨水屏主题（纯黑白、无动画）；新闻热榜分批并发与超时预算";

    /**
     * 使用声明（合规矩块，v0.20.0 发布版新增）。
     * 文案与 README「合规设计说明」保持一致；发布版不内置任何源，
     * 这些声明既是告知用户，也是本项目合规边界的一部分。
     */
    private static final String USE_NOTICE =
            "· 仅供学习与研究使用\n"
                    + "· 本软件不提供、不内置、不分发任何书源或版权内容，全部数据源由用户自行配置\n"
                    + "· 请勿用于任何侵权或违法用途，用户自行承担使用后果\n"
                    + "· 仅作收集公开、非付费、非限制的新闻内容之用\n"
                    + "· 完全免费、非营利：无广告、无收费、无会员制";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // 版本信息动态读，读不到时显示占位而不是崩溃
        String versionName = "未知";
        int versionCode = 0;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi.versionName != null) {
                versionName = pi.versionName;
            }
            versionCode = pi.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
            // 读自己的包信息不该失败；失败了就保留占位值
        }

        TextView tvVersion = findViewById(R.id.tvVersion);
        tvVersion.setText("版本 " + versionName + "（versionCode " + versionCode + "）");

        TextView tvUpstreamUrl = findViewById(R.id.tvUpstreamUrl);
        tvUpstreamUrl.setText(UPSTREAM_URL);

        TextView tvRepoUrl = findViewById(R.id.tvRepoUrl);
        tvRepoUrl.setText(REPO_URL);

        TextView tvFeatures = findViewById(R.id.tvFeatures);
        tvFeatures.setText(FEATURES);

        TextView tvEinkHistory = findViewById(R.id.tvEinkHistory);
        tvEinkHistory.setText(EINK_HISTORY);

        TextView tvUseNotice = findViewById(R.id.tvUseNotice);
        tvUseNotice.setText(USE_NOTICE);
    }
}
