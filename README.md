# wuji-reader · 无极阅读墨水屏衍生版

> ⚠️ **非官方衍生版**：本项目基于 [wuji-tauri](https://github.com/moshstudio/wuji-tauri)（GPL-3.0）开发，与上游项目及「无极」商标持有者无任何关联。「无极」商标权利归上游所有，GPL-3.0 许可证不授予商标使用权。

> ⚠️ **仅供学习与研究使用**：本项目**不提供、不内置、不分发任何书源、漫画源或版权内容**，仅提供一个空壳阅读框架，全部数据源由用户自行配置。请勿将本软件用于任何侵权或违法用途，**用户使用本软件产生的一切后果由用户自行承担**。本软件**仅作收集公开、非付费、非限制的新闻内容之用**。

> 💰 **完全免费、非营利**：无广告、无收费、无会员制、无任何形式的营利设计。

一款为 **Android 6（API 23）墨水屏设备** 重写的原生阅读器：**无 WebView、无 native 库、纯 Java 实现**。
提供小说、漫画、新闻热榜三类内容的订阅源阅读，交互为遥控器/触屏双重优化。

## 为什么不是原版就能用

目标设备（如 Boyue Lemon Read Plus）出厂 WebView 约为 Chrome 44：Vue 3 依赖 `Proxy`（Chrome 49+），且 Proxy 语义**无法 polyfill**，厂商又锁死 WebView 不可升级 —— 原版「装得上但一运行就崩」。
本项目的答案：**彻底不用 WebView**，以 OkHttp + Jsoup + Glide 原生重写，minSdk 23 直接跑。

## 特性

- **墨水屏优先**：纯黑白无动画、大点击区、全局字号一次缩放（22–44，默认 30）
- **遥控器九宫格**：3×3 分区手势，翻页/目录/清屏/字号/缩放等 20 个动作可自定义
- **小说阅读**：目录、自动滚动（三档速度、到底自动续章）、四档背景色、进度记忆
- **漫画阅读**：逐图翻页（单图全刷）、双指缩放 1–50×、单指拖动、双击 2.5×、翻页保持倍数、**顶置微调**（消除扫描白边）
- **新闻热榜**：多榜单；正文抓取（站点专用选择器 + 密度算法）；抓不到正文时相关报道可点开，并支持多源摘要聚合（带相关性过滤，不显示不相干内容）
- **源管理**：Legado 书源 JSON 导入 / 启用 / 停用 / 排序 / 删除
- **横屏竖屏皆可**：手机、平板、墨水屏一版通吃

## 发布版不含任何源

出于合规与清晰度考虑，**本仓库不内置、不分发任何书源/漫画源数据**（第三方社区源许可状态不明）。
全部源由用户自行导入 Legado 格式 JSON：

1. 打开 App → 设置 → 源管理
2. 粘贴或选择 Legado 书源 JSON（单个对象或数组均可）
3. 导入成功即出现在列表，可启用/停用/排序

## 合规设计说明

本项目的合规边界参照国内爬虫司法判例（参见 [Crawler_Illegal_Cases_In_China](https://github.com/xushijiu/Crawler_Illegal_Cases_In_China)）设计：

| 判例红线 | 本项目的规避设计 |
|---|---|
| 破解验证码 / 规避安全验证机制 | ✅ 不包含任何验证码识别、签名破解、反爬对抗代码 |
| 抓取数据并贩卖获利 | ✅ 无任何营利设计：无广告、无收费、无会员、无数据导出接口供第三方 |
| 内置盗版内容供阅读获利 | ✅ **零内置源、零预置内容**，全部数据源由用户自行配置 |
| 高频请求致目标站点宕机 | ✅ 单源串行请求 + 现有请求间隔设计，无并发压测行为 |
| 抓取公民个人信息 | ✅ 无个人信息采集、存储、上传行为，纯本地阅读器 |
| 提供侵入计算机信息系统的程序 | ✅ 仅对公开网页做常规 HTTP 请求与解析，不突破任何技术防护措施 |

**再次提示**：软件是中性工具，数据源由用户自行配置与控制；
请遵守目标网站的 robots 协议与服务条款，合理控制请求频率，
不要抓取、传播任何未经授权的内容。**违法使用产生的全部责任由使用者承担。**

## 从源码构建

```bash
# 环境：JDK 17 + Android SDK（platform 34 / build-tools 34.0.0）
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

- minSdk 23 / targetSdk 34，`coreLibraryDesugaring` 已开启（jsoup 在 API 23 上的 `java.util.stream` 依赖）
- 无 WebView、无 native 库；依赖仅 OkHttp 4.12 / Jsoup 1.17.2 / Glide 4.16.0 / androidx 全家桶（见 NOTICE）

## 墨水屏优化要点（摘要）

| 方面 | 做法 |
|---|---|
| 架构 | Tauri/WebView → 纯 Java 原生，绕开 Chrome 44 无 Proxy 的死局 |
| 编码 | GBK 站走 `bytes()` 嗅探，不用 OkHttp 硬解 UTF-8 |
| 遥控器 | 原生按键走 `window.dispatchEvent`，统一挂 window 监听 |
| 全局字号 | `Configuration.fontScale` 注入，布局零改动全量缩放 |
| 兼容 | core library desugaring 解决 API 23 类加载崩溃 |
| 刷新 | 纯色背景 + 文字，无动画无渐变，贴合 E-Ink 刷新 |
| 手势 | 九宫格分区点击、漫画 `onSingleTapConfirmed` 避开双击误触 |

## 与上游的差异

1. 技术栈：Tauri/Vue/Rust + WebView → 纯 Java 原生
2. 目标设备：通用桌面/移动 → Android 6 墨水屏 + 遥控器优先
3. 模块裁剪：去除影视、美图模块
4. 交互重构：九宫格、全局字号、墨水屏配色、顶置微调等
5. 源策略：**不含任何内置源**，全部用户自行导入
6. 名称：`wuji-reader`（非官方衍生版）

完整历次优化记录见 [docs/optimization-history.md](docs/optimization-history.md)。

## 许可证

**GPL-3.0** —— 见 [LICENSE](LICENSE)。

- 本项目为 wuji-tauri 的衍生作品，依 GPL-3.0 以相同许可证发布；
- 分发本项目的二进制（APK）时，随仓库提供完整对应源码；
- 上游版权声明与许可证文本予以保留，修改处已在本文档标注。

第三方组件许可清单见 [NOTICE](NOTICE)。

## 致谢

- [wuji-tauri](https://github.com/moshstudio/wuji-tauri) —— 上游项目（GPL-3.0）
- 订阅源契约与 Legado 书源格式 —— 来自开源阅读器生态
