> **文件性质**：内部 QA 测试记录（0.20.0 发布版终验）。
> 完整证据（logcat / 无障碍树 / 截图 / 脚本）在 qa/ 目录，该目录已列入 .gitignore 不入库；
> 本文是结论摘要，随发布树一起保留，便于回溯"这一版测过什么、结论是什么"。
> 文中 <repo> = 仓库根目录，<LDPlayer9> = 模拟器安装目录。

# wuji-reader 0.20.0 发布版终验报告（QA）

- **报告人**：software-qa-engineer（Edward）
- **报告日期**：2026-10-24
- **被测对象**：`<repo>/dist\wuji-reader-0.20.0-release.apk`
  - 大小：**8,205,494 B**
  - SHA256：**`2be035de09ebab7bb9e2be20ba7009a4a136e4d487511e5a36a607d169c535c0`**
  - versionName **0.20.0** / versionCode **20**
- **测试环境**：LDPlayer9（`<LDPlayer9>/`），设备 `emulator-5554`，Android 9 / API 28，显示 1600×900
- **证据目录**：`<repo>/qa\`
  - 运行日志：`run-rel.log`、`run-rel2.log`、`run-rel3.log`、`run-rel4.log`、`run-rel5.log`、`run-rel6.log`
  - 崩溃日志：`logcat-release2.txt` ~ `logcat-release6.txt`
  - 无障碍树：`uirel3-*.xml`、`uirel4-*.xml`、`uirel5-*.xml`、`uirel6-*.xml`
  - 截图：`rel3/4/5/6-*.png`
  - 回环校验文件：`roundtrip-export.json`
  - 装机 APK：`installed-release-base.apk`

---

## 0. 重要说明：被测 artifact 在测试过程中被替换过一次（结论均以最终版为准）

| 时间 | artifact | 大小 | sha256 | 状态 |
|---|---|---|---|---|
| 11:45 首次安装 | 初版发布包 | 8,129,191 B | `28c8fd4af9a4e209a37207620dd0f1e824cd1ff8989554f668d607c70a09eb61` | ❌ 发现 P0：关于页显示占位符 `<your-account>` |
| **11:57 重新构建** | **最终发布包** | **8,205,494 B** | **`2be035de09ebab7bb9e2be20ba7009a4a136e4d487511e5a36a607d169c535c0`** | ✅ 占位符已替换为 `https://github.com/dzoffice/wuji-reader` |

工程侧在 11:55 修改了 `app/src/main/java/com/wuji/reader/ui/AboutActivity.java`（`REPO_URL` 占位符 → 真实仓库地址），11:57 重新产出 APK。
**本报告中 A/B/D/回归 的所有结论，均在"最终发布包"上重新跑过并复现通过**；初版包唯一的缺陷（占位符外露）已确认修复。

---

## 1. 静态复查（E）—— ✅ 全部通过

| # | 检查项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 发布树无内置书源（无 `assets/`、`sources/`、`lib/` 条目） | ✅ | `unzip -l` 仅 standard-java 资源与 `classes.dex`，无任何 sources 相关条目 |
| 2 | 权限面最小化 | ✅ | Manifest 仅 `android.permission.INTERNET`、`android.permission.ACCESS_NETWORK_STATE` 两项；无存储/相机/定位/电话等危险权限 |
| 3 | 已删死类零残留 | ✅ | 19 个已删类名全部 0 命中 |
| 4 | 历史站点关键词 / `JsPacker` / 混淆壳零残留 | ✅ | 全部 0 命中 |
| 5 | 「无极VIP」字样 | ✅ | 仅作为 `docs/legal-review.md` 中的历史风险描述出现，代码/资源中 0 命中 |
| 6 | 发布文件清单齐全 | ✅ | `LICENSE`（GPL-3.0 全文，35,149 B / 674 行）、`README.md`、`docs/legal-review.md`、`docs/compliance.md` 均在发布树内 |
| 7 | 敏感地址排查 | ✅ | 全树唯一 `127.0.0.1` 为 androidx 库内常量，非本项目代码 |
| 8 | 横屏静态支持 | ✅ | 11 个 Activity **全部** `screenOrientation = NONE`（无锁定）、**全部** `configChanges="orientation\|screenSize\|keyboardHidden"`；`res/` 无 `layout-land`（单一自适应布局集，旋转即重新布放） |
| 9 | 源码硬编码站点地址 | ✅ | `grep 'https\?://' app/src/main/java/` 命中项全部为：新闻热榜 API（`NewsRegistry.BASE_URL`）、百度/微博热榜抓取器、关于页链接、以及"判断字符串是否是 URL"的工具逻辑；**无任何书源站点地址** |

**结论**：发布树"零书源 + 纯导入制 + 最小权限 + GPL-3.0 合规"与设计承诺一致，静态层无阻塞项。

---

## 2. 安装与零源冒烟（A）—— ✅ 通过

| 检查点 | 结果 |
|---|---|
| 卸载旧版 | ✅ `Success` |
| 安装 | ✅ `INSTALL: Performing Streamed Install \| Success` |
| 装机哈希三方校验 | ✅ `pm path` 取出 base.apk → pull → `DEVICE_HASH_MATCH = True`（host dist == 装机 APK） |
| 冷启动 | ✅ pid 2773 / 2763 / 2573 各轮均稳定，无重启 |
| 首页控件 | ✅ 10 个按钮全部渲染：`小说 \| 漫画 \| 新闻 \| 书架 \| 设置 \| 全局 \| 搜索 \| 源管理 \| 上一页 \| 下一页` |
| 零源空态 | ✅ 状态文案 `[没有可用的小说源]`；截图 51,314 B（稀疏空态，非白屏、非骨架屏） |
| 源管理页可达 | ✅ 返回/源管理/小说/漫画/新闻/重置/添加/从文件选/从网址导入/导出全部 全部渲染；小说/漫画 chip 行为空；新闻 chip 行预置 知乎/掘金/36氪/腾讯新闻/澎湃新闻/网易新闻/今日头条/IT之家/哔哩哔哩，均带 上移/下移/停用/删除 |
| 崩溃检查 | ✅ 5 轮 logcat 全部 `CRASH/ANR hits = 0`，结束瞬间进程存活 |

**结论**：零书源发布形态下安装、启动、空态、导航、源管理全部正常。

---

## 3. 关于页（B）—— ✅ 通过（最终版）

| 检查点 | 结果 | 证据 |
|---|---|---|
| GPL-3.0 声明 | ✅ | `本软件以 GNU 通用公共许可证 第 3 版（GPL-3.0）发布。` + `完整条款见随源码分发的 LICENSE 文件。` |
| 上游项目声明 | ✅ | `本软件基于开源项目 wuji-tauri（GPL-3.0）衍生的原生 Android 客户端。` + `https://github.com/moshstudio/wuji-tauri` |
| 项目地址 | ✅ | `https://github.com/dzoffice/wuji-reader`（占位符 `<your-account>` 已消除，`ABOUT has placeholder your-account = False`） |
| 版本号 | ✅ | `版本 0.20.0（versionCode 20）`，与 `app/build.gradle` 一致 |
| 5 条使用声明 | ✅ | 全部渲染，原文见下（a11y 树 `uirel4-about-3..10.xml`，页面滚到底部后取得） |
| 特性列表 / 墨水屏历程 | ✅ | 6 条特性 + 4 条历程均渲染 |

实际渲染的 5 条使用声明（逐字取自无障碍树）：

```
· 仅供学习与研究使用
· 本软件不提供、不内置、不分发任何书源或版权内容，全部数据源由用户自行配置
· 请勿用于任何侵权或违法用途，用户自行承担使用后果
· 仅作收集公开、非付费、非限制的新闻内容之用
· 完全免费、非营利：无广告、无收费、无会员制
```

> 备注：首轮测试时这 5 条未取到，根因是我的滑动坐标写成了 `y=1300`（超出 900 屏高）导致页面从未滚动，属测试手段问题；修正为 `input swipe 800 760 800 200` 后连续滚动即稳定取到全文。

---

## 4. 横屏适配（C）—— ⚠️ 静态通过；横屏视口已实测渲染；90°/270° 竖长视口因环境受限未验证

### 4.1 静态证据 —— ✅ 全部满足
- 11 个 Activity **全部** `screenOrientation = NONE`（无任何方向锁定）；
- 11 个 Activity **全部** 声明 `configChanges="orientation|screenSize|keyboardHidden"`，旋转不会重建 Activity、不会丢阅读进度/滚动位置；
- `res/` 无 `layout-land`，采用单一自适应布局集（`match_parent` / `wrap_content`），旋转即由容器重新布放，不存在"只有一套布局所以横屏会错"的风险；
- `dumpsys window displays` 显示 `rng=900x864-1600x1564`，框架侧确实暴露了非当前尺寸的视口能力。

### 4.2 已实测：横屏视口（1600 宽 × 900 高）下 8 个页面全部正常渲染
该 LDPlayer9 实例的面板物理尺寸为 **1600×900（宽>高，横长）**，`dumpsys window displays` 实测
`init=1600x900 cur=1600x900 app=1600x900`，即**应用视口本就是 1600 宽 × 900 高的横长视口**。
本报告第 2、3、5、6 节采集的全部截图，本身就是这个横长视口下的实渲染，无一例布局截断/重叠/超宽：

| 页面 | 截图 | 大小 | 渲染判定 |
|---|---|---|---|
| 主页（零源空态） | `rel5-home.png` | 51,314 B | ✅ 10 按钮完整 |
| 新闻列表 | `rel5-news.png` | 190,368 B | ✅ 知乎 30 条 + 10 榜单 chip |
| 书架 | `rel5-shelf.png` | 36,774 B | ✅ 空态文案完整 |
| 设置 | `rel5-settings.png` | 62,841 B | ✅ 6 组设置项完整 |
| 九宫格布局 | `rel6-ninegrid.png` | 76,659 B | ✅ 3×3 九宫 + 恢复默认 |
| 关于（滚到底） | `rel4-about-bottom.png` | 186,312 B | ✅ 5 条声明完整 |
| 源管理（导入后） | `rel4-after-import.png` | 68,490 B | ✅ 测试源A/B + 4 操作钮 |
| 关于（滚到底，v3） | `rel3-about-bottom.png` | 207,116 B | ✅ 同上 |

### 4.3 未验证：90°/270°（900 宽 × 1600 高竖长视口）—— 环境受限，明确不计通过
共尝试 **5 种**方式，全部无法让该 LDPlayer9 实例真正切屏：

| # | 方式 | 结果 |
|---|---|---|
| 1 | `settings put system accelerometer_rotation 0` + `user_rotation 1` | ❌ `cur`/`app` 仍 1600×900 |
| 2 | `content insert --uri content://settings/system --bind name:s:user_rotation --bind value:i:1` | ❌ 同上 |
| 3 | `ldconsole action --index 0 --key rotate --value 1` | ❌ 无效果 |
| 4 | `ldconsole action --index 0 --key rotate --value 90` / `270` | ❌ 无效果，`user_rotation` 保持 0 |
| 5 | 重启应用后再探测 | ❌ `init=cur=app=1600x900` 恒定 |

关键实测事实（`run-rel7.log`，三次探针：旋转前 / `user_rotation=1` 后 / 重启应用后，三者输出完全一致）：

```
init=1600x900 240dpi  cur=1600x900  app=1600x900  rng=900x864-1600x1564
```

- `user_rotation` **能被写入**（实测 0 → 1），但显示层的 `cur` / `app` **恒定不变** —— 说明是 LDPlayer9 该实例的窗口/渲染层不响应系统旋转设置，不是应用问题。
- 所有截图（含命名带 `landscape` 的历史截图）经 PNG IHDR **大端序**解析实测均为 **1600×900**，无一张发生尺寸变化。
- `ldconsole list2` 确认本机**只有一个模拟器实例**（index 0，1600×900×240），"换实例"无可操作对象；新建实例耗时且不保证有效，未做。

**结论**：C 项记为"静态通过 + 横长视口实测通过 + 竖长视口因环境受限未验证"。缺的这一块不是代码缺陷证据，建议在支持旋转的真机/模拟器上补一次 5 分钟回归（切到 90°/270°，看主页/阅读器/源管理三页即可）。

---

## 5. SAF 导入 / 导出闭环（D）—— ✅ 通过（完整 round-trip）

| 步骤 | 操作 | 结果 |
|---|---|---|
| 1 | `adb push test-sources.json → /sdcard/Download/` | ✅ 219 B，`-rw-rw----` |
| 2 | 触发媒体扫描 | ✅ `MEDIA_SCANNER_SCAN_FILE` 广播 |
| 3 | 源管理 → **从文件选** | ✅ DocumentsUI 拉起 |
| 4 | DocumentsUI 导航 | ✅ `显示根目录` → `下载` → 命中 `test-sources.json`（tap 174,518） |
| 5 | 导入结果 | ✅ 源管理页出现 `测试源A`、`测试源B` 两行，各行带 `上移/下移/停用/删除`；pid 存活 |
| 6 | **从网址导入** 对话框 | ✅ 正常弹出：标题 `从网址导入`、输入框 hint `https:// 开头的 json 链接`、`取消` / `导入` 按钮；EditText count = 1；点取消正常关闭 |
| 7 | **导出全部** | ✅ `ACTION_CREATE_DOCUMENT` 拉起，默认文件名 `wuji-reader-sources.json`，Save Picker 落在 `下载`，EditText count = 1，点 `保存`（1530,858）成功 |
| 8 | 落盘回拉 | ✅ 设备侧 `/sdcard/Download/wuji-reader-sources.json` 239 B；pull 到 `qa/roundtrip-export.json` 227 B |
| 9 | **JSON round-trip 校验** | ✅ 见下 |

回拉文件内容（证明导出→导入可闭环）：

```json
{"books":[{"bookSourceName":"测试源A","bookSourceUrl":"https:\/\/example.com\/","bookSourceType":0,"enabled":true},{"bookSourceName":"测试源B","bookSourceUrl":"https:\/\/example.org\/","bookSourceType":0,"enabled":true}],"comics":[]}
```

校验结论：含 `"books"` 键 ✅、含 `测试源A` ✅、含 `测试源B` ✅、`comics` 为空数组 ✅ —— 与本页注释声称的"导出→导入可 round-trip"一致。

**异常路径观测（正向）**：在早期轮次中一次误触打开了非 JSON 文件，应用行为为 toast `正在读取文件…` → `正在导入…` → `失败：JSON 格式不正确`，**未崩溃、未 ANR**，`SourceManager` 打出 `json not an object or array` 后正常返回。说明导入的编码兜底与异常分支是健壮的。

---

## 6. 附加回归（最终版 artifact 上复跑）—— ✅ 通过

| 模块 | 结果 | 证据 |
|---|---|---|
| 新闻热榜 | ✅ | `NEWS status = [知乎 · 共 30 条 · 知乎 · 更新于 10-24 12:05]`；10 个榜单 chip：返回/知乎/掘金/36氪/腾讯新闻/澎湃新闻/网易新闻/今日头条/IT之家/哔哩哔哩；截图 190,368 B |
| 书架 | ✅ | `SHELF texts = [返回 \| 书架 \| 0 条 \| 书架还是空的，去小说/漫画页点收藏吧]` —— 空态文案正确，无崩溃 |
| 设置页 | ✅ | `返回 \| 设置 \| 九宫格布局设置 \| 界面字号（全局） 30 sp A- A+ \| 正文字号 30 sp A- A+ \| 阅读背景色 白色 选择 \| 关于本软件` |
| 九宫格布局设置页 | ✅ | `NineGridSettingsActivity` 可达；3×3 九宫全部渲染：`上左=清屏 / 上中=阅读菜单 / 上右=阅读菜单 / 中左=无 / 中中=无 / 中右=无 / 下左=上一页 / 下中=目录 / 下右=下一页`；`恢复默认布局` 在位；11 个可点节点 |
| 源管理页 | ✅ | 见第 5 节（导入后含自定义源行与 4 个操作按钮） |
| 崩溃 | ✅ | 6 轮 logcat 累计 `FATAL EXCEPTION` / `E/AndroidRuntime` / `ANR` 命中均为 **0** |

---

## 7. 汇总与结论

| 验收项 | 结论 |
|---|---|
| A 安装 + 零源冒烟 | ✅ 通过 |
| B 关于页（GPL-3.0 / 上游 / 版本 / 5 条使用声明 / 项目地址） | ✅ 通过 |
| C 横屏适配 | ⚠️ 静态通过 + **横长视口（1600×900，本机实际视口）8 个页面实测渲染通过**；90°/270° 竖长视口因本 LDPlayer9 实例不响应旋转（5 种方式全试过）**未验证** |
| D SAF 导入 / 导出 round-trip | ✅ 通过 |
| E 静态复查（零源 / 权限 / 死类 / LICENSE / 敏感信息） | ✅ 通过 |
| 附加回归（新闻 / 书架 / 设置 / 九宫格 / 源管理 / 崩溃） | ✅ 通过 |

**总测试轮次**：7 轮模拟器实跑 + 全量静态复查。
**崩溃/ANR**：0。
**发布阻塞项（P0）**：0。

### 遗留事项（不阻塞发布，但建议跟进）

1. **90°/270° 竖长视口未验证（C 的剩余部分）** —— 横长视口（1600×900，即本模拟器实际视口）下 8 个页面已实测渲染正常；静态层面无方向锁定、`configChanges` 齐全、布局自适应。唯一缺口是 900×1600 竖长视口，因该 LDPlayer9 实例对 5 种旋转方式均不响应而无法取证。建议在支持旋转的真机/模拟器上补一次 5 分钟回归（切 90°/270°，看主页/阅读器/源管理三页即可）。**该缺口不计为通过，但不构成发布阻塞。**
2. **书源内容层未测** —— 发布版零内置源，导入的 `测试源A/B` 指向 `example.com`，仅验证了"导入→列表→导出"的数据通路，**未验证真实书源的列表/详情/正文抓取**。该能力在 v12 内部版已测过（4 个小说源全链路），但发布版本身不含源，需用户导入真实源后才能覆盖，属设计使然。
3. **`九宫格布局设置`页内交互未深测** —— 本轮验证了 3×3 网格渲染与默认值、`恢复默认布局` 在位；点格子弹出 14 项动作选择器、改后持久化、阅读器内分区点击等细项未在发布版上复跑（v12 内部版已覆盖）。
4. **dex 级占位符核查为工程侧结论，未由 QA 独立复验** —— 工程侧报告"`dzoffice/wuji-reader` 已入 classes3.dex，`your-account` 0 残留"；QA 侧的独立证据是运行时关于页实际显示 `https://github.com/dzoffice/wuji-reader`、`<your-account>` 在无障碍树中 0 命中（`uirel4-about-*.xml`），两者一致。

### 变更记录（测试侧）

- 初版发布包（8,129,191 B / `28c8fd4a…`）关于页显示占位符 `https://github.com/<your-account>/wuji-reader`，已作为 P0 反馈；工程侧已修复并重新构建，最终版复测通过。
- 被测 artifact 在测试过程中被替换一次（11:57 重建），本报告全部结论均以最终包（8,205,494 B / `2be035de…`）复跑通过为准。

---

## 附：测试脚本与复现方式

```powershell
# 7 轮脚本（ASCII-only + [char]0xXXXX 中文，规避 PowerShell 5.1 ANSI 解码问题）
<repo>/qa\smoke-release.ps1     # 第 1 轮（初版包）
<repo>/qa\smoke-release2.ps1    # 第 2 轮（初版包）
<repo>/qa\smoke-release3.ps1    # 第 3 轮（最终包：滚动修正/旋转三法/SAF）
<repo>/qa\smoke-release4.ps1    # 第 4 轮（最终包：SAF round-trip 打通）
<repo>/qa\smoke-release5.ps1    # 第 5 轮（最终包：新闻/书架/设置回归）
<repo>/qa\smoke-release6.ps1    # 第 6 轮（最终包：九宫格设置页）
<repo>/qa\smoke-release7.ps1    # 第 7 轮（最终包：旋转事实探针）
```

关键测量口径（供后续复用）：
- PNG 尺寸必须按 **大端序** 读 IHDR：`od -An --endian=big -tu4 -j16 -N4`；`[BitConverter]::ToInt32` 是小端，会读出垃圾值。
- 本设备屏高 900，滚动/滑动的 y 必须 ≤ 900（曾因 `y=1300` 导致页面全程未滚动）。
- DocumentsUI 根目录入口是 toolbar 左上 `content-desc="显示根目录"` 的 ImageButton（约 54,84），不是"最近"文字。
- 中文匹配串务必核对码点（本轮两次踩坑：`宫`=0x5BAB 误写为 0x683C；`仅供学习与研究使用` 中间的 `、` 不能省）。
