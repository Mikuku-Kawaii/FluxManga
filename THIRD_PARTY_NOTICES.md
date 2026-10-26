# 第三方声明索引

基础依赖快照日期：2026-10-21，后续适配声明按各节日期追加。2026-10-26 的 0.1.0 公开版本保留这些原始许可与修改记录；下文“本轮未构建”等表述描述当时增补阶段，不代表当前发布状态。各组件保留原作者版权与原始许可；本文件不替代以下完整文本，也不是最终 APK 的完整 SBOM。

2026-10-22 增补：[固定版本与本地文件索引](docs/third-party/source-adapters.json)、[离线核对及后续修源流程](docs/release/source-upgrades.md)。本次只补维护记录，不更换上游版本、许可或应用实现。

## 图源协议与解析适配

| 上游 / 固定提交 | 采用范围 | 许可与修改声明 |
| --- | --- | --- |
| [fumiama/copymanga](https://github.com/fumiama/copymanga/tree/0fdd3f4489c8ec02f41b4a570aba3b11acfb889b) | 拷贝的端点 / DTO、认证与收藏范围、章节正文协议适配；没有嵌入完整客户端或可选 Comancry / Comandy 运行时 | [GPLv3](app/src/main/assets/licenses/copymanga-GPL-3.0.txt)、[NOTICE](app/src/main/assets/licenses/copymanga-NOTICE.txt) |
| [Keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source/tree/ff521c17bb3e503c01e658e8fac00b60a95829f3) | 包子匿名搜索、详情、章节、正文、更新与分类选择器；没有引入扩展宿主或广告图片裁剪管线 | [Apache 2.0](app/src/main/assets/licenses/baozi-Apache-2.0.txt)、[NOTICE](app/src/main/assets/licenses/baozi-NOTICE.txt) |
| [skepsun/kototoro-parsers](https://github.com/skepsun/kototoro-parsers/tree/a94b6704997081b0b57544d3404dfc6809c9ae4b) | 包子 multipart 登录 / TSID 与个人书架可见快照；本项目补会话、选择导入与事务恢复 | [GPLv3](app/src/main/assets/licenses/baozi-account-GPL-3.0.txt)、[NOTICE](app/src/main/assets/licenses/baozi-account-NOTICE.txt) |
| [Keiyoushi Picacomic](https://github.com/keiyoushi/extensions-source/tree/ff521c17bb3e503c01e658e8fac00b60a95829f3/src/zh/picacomic) 与 [niuhuan/pica-go](https://github.com/niuhuan/pica-go/tree/2c208674152c3031862a6dd0b8b99bb45835f950) | 2026-10-24：PicACG 账号 / 签名 / 稳定 UID、收藏 / 分类 / 搜索、详情 / 章节 / 图片协议适配；不引入 Mihon / Go 宿主、上游 DNS 分流或 HTTP 初始化 | [Apache-2.0 与 MIT 完整文本](app/src/main/assets/licenses/picacg-LICENSES.txt)、[来源与修改声明](app/src/main/assets/licenses/picacg-NOTICE.txt) |

[Mihon 固定提交](https://github.com/mihonapp/mihon/tree/d001e6f210e6a077a30dd9a1392573a3225bba2f) 的 Apache 2.0 实现用于 M5 方案评估，没有整体移植其应用 / Viewer / 数据库；相关取舍见既有阶段记录。BSDIFF40 更新适配见下方新增声明；Komga 是用户配置的外部服务，并未把 Komga 服务端作为客户端依赖分发。

## E-Hentai 协议适配（2026-10-24）

2026-10-24 E-Hentai 增补：采用 [xiaojieonly/Ehviewer_CN_SXJ@dfe356244d23](https://github.com/xiaojieonly/Ehviewer_CN_SXJ/tree/dfe356244d234c91f2bf65af60903f80b407a838) 中明确标注 Apache-2.0 / Copyright 2016 Hippo Seven 的画廊、收藏、页面与账号解析文件。仓库根 LICENSE 混排 GPL / Apache，原样保留为证据，不宣称整个仓库统一 Apache；完整 [Apache 文本](app/src/main/assets/licenses/ehentai-Apache-2.0.txt)来自原 seven332/EhViewer 固定版本。项目整体继续 GPL-3.0-only。Kotlin / jsoup 适配、私有传输与原图边界见 [NOTICE](app/src/main/assets/licenses/ehentai-NOTICE.txt)，没有引入上游宿主或新运行依赖。

## 加密书库备份（2026-10-24）

声明依赖 `net.lingala.zip4j:zip4j:2.11.5`，上游 [Zip4j 固定版本](https://github.com/srikanth-lingala/zip4j/tree/8e2b74980b0f12a6fe6a4a8cdbfed626782a15c8)，Apache-2.0。直接使用 AES-256 ZIP 输入 / 输出流，没有修改或复制密码实现；完整 [LICENSE](docs/third-party/portable-backup/LICENSE.txt)、[NOTICE](docs/third-party/portable-backup/NOTICE.txt) 与 Maven POM 保留，已加入应用许可目录生成器。没有非测试传递依赖；本轮未构建，不更新旧构建依赖快照或宣称包体成本已测量。

## BSDIFF40 更新适配（2026-10-24）

`BsdiffPatch.kt` 的文件格式与控制流适配自 [FreeBSD 固定提交](https://github.com/freebsd/freebsd-src/tree/725a9f47324d42037db93c27ceb40d4956872f3e/usr.bin/bsdiff)（Colin Percival，BSD-2-Clause）；改为 Kotlin 有界解压、旧包随机访问与独立文件顺序写出。[完整许可](docs/third-party/update-delta/bsdiff-LICENSE.txt)、[修改说明](docs/third-party/update-delta/NOTICE.txt)和固定源码均保留。

BZip2 解压新增声明依赖 Apache Commons Compress 1.28.0，沿其 POM 引入 Commons IO 2.20.0、Commons Lang 3.18.0、Commons Codec 1.19.0，均按 Apache-2.0 分发。[原始 LICENSE / NOTICE](docs/third-party/update-delta/)已接入应用许可目录生成器。这些是新增源码声明，未重新解析或伪造旧 `inventory.json` 的构建快照，无本轮 APK。发布工具只调用外部提供的 BSDIFF40 生成器，不捆绑其可执行程序；未使用 bsdiff4 / jbsdiff 运行时。

## 离线图片重组规则（M4 准备）

2026-10-23 图片算法增补：[JMComic-Crawler-Python 固定提交](https://github.com/hect0x7/JMComic-Crawler-Python/tree/303904252f586ef0123f468feac2b890c9cba8d8) 的 MD5 切片数与行重排规则移植为 Kotlin 元数据 / Rust 区域计划，Android 保持原生解码；仅离线合成章节使用，不代表新增受支持图源。保留 [MIT 原文](app/src/main/assets/licenses/jm-image-MIT.txt)与 [NOTICE](app/src/main/assets/licenses/jm-image-NOTICE.txt)，没有引入 Python / Pillow / 上游网络运行时。

2026-10-24 JMComic 接入：用户明确确认 M4 后，沿用同一 MIT 固定提交，提取移动 API 登录 / Cookie、AES 响应、收藏 / 搜索分页、详情 / 章节及正文地址协议，适配到 Kotlin 与已有 Rust reqwest 会话。新增 JmComic / JmCover / JmReaderRepository 和页面衔接，私有缓存与图片重组沿用项目原实现；无新运行时或依赖。NOTICE 与 source-adapters.json 已扩展范围；未测试、编译、打包或请求真实账号服务。

## 液态材质核心（M5）

从 [Kyant0/AndroidLiquidGlass 固定提交](https://github.com/Kyant0/AndroidLiquidGlass/tree/65ab177e90e5c1d8c62e70cf7755841982da65f6) 的 `internal/Shaders.kt` 提取圆角矩形距离和 circleMap 边缘折射，适配双形状融合、法线、采样边界与现有主题 / 生命周期；保留 Copyright 2025 Kyant、[Apache 2.0 原文](app/src/main/assets/licenses/liquid-material-Apache-2.0.txt)及[修改声明](app/src/main/assets/licenses/liquid-material-NOTICE.txt)。未引入 Backdrop / shapes / KMP 运行依赖。

[Haze 固定提交](https://github.com/chrisbanes/haze/tree/8a96c505dde451b74edd76c562228f5a6f2e9ba0)（Apache 2.0）仅用于渲染结构与缓存方案评估，没有移植其实现。[评估记录](docs/verification/m5-liquid-enhancements-local/status.md)区分候选和实际分发源码。

## Android 与原生运行时

完整版本、POM 声明、校验值及原始声明见 [113 项 Android runtime 清单](docs/third-party/android-runtime.md) 和 [机器清单](docs/third-party/inventory.json)。

| 组件 | 许可处理 |
| --- | --- |
| AndroidX / Compose / Navigation / Room，Kotlin / kotlinx 等 | 对照各版本 POM；Apache 2.0 原文与实际归档 NOTICE 已保存，不能用一个通用文本取代组件自身版权 |
| jsoup 1.21.2 | [MIT 原文与版权](app/src/main/assets/licenses/jsoup-MIT.txt) |
| JNA 5.17.0 | 原始许可为 Apache 2.0 或 LGPL 2.1+；本项目选择 Apache 2.0 分发路径。[原始声明](docs/third-party/licenses/jna-LICENSE)、[Apache 文本](docs/third-party/licenses/jna-AL2.0)及其原生桥包含的 [libffi MIT 声明](docs/third-party/licenses/jna-libffi-LICENSE)一并保留 |
| SQLCipher Android / SQLCipher 4.9.0 | 分别保留 [Android 包许可](docs/third-party/licenses/sqlcipher-android-LICENSE)和[核心许可](docs/third-party/licenses/sqlcipher-LICENSE.md)，均为含归属要求的 BSD 风格许可；不得移除 Zetetic 声明 |
| SQLCipher 原生包内 OpenSSL | 本地 4.9.0 AAR 的原生库可识别 OpenSSL 3.0.16 版本字符串，保留[该版本许可](docs/third-party/licenses/OpenSSL-LICENSE.txt)。这项静态证据不替代最终二进制构成核验 |
| SQLite | SQLCipher 基于 SQLite；SQLite 上游声明为 public domain，见[官方说明](https://sqlite.org/copyright.html)。SQLCipher 自身新增代码仍遵循其许可 |

## Rust 与开发工具

[181 项 Cargo 锁定依赖](docs/third-party/rust-lock.md)保留各包原始 SPDX 表达式、来源 / 校验和与许可文本，包括 UniFFI 的 MPL 2.0、ring 的 Apache / ISC、Unicode 数据及根证书数据的许可。清单包含宿主绑定生成、可选和其他平台依赖，不代表 181 项都链接进 Android。`OR` 表示许可选项，`AND` 表示须同时满足的条件。

[构建 / 测试直接依赖清单](docs/third-party/build-test-direct.json)另列 AGP、Kotlin / KSP 插件、Room compiler、测试框架与调试工具；JUnit 4.13.2 的 [EPL 1.0](docs/third-party/licenses/JUnit-EPL-1.0.txt)独立保存。这不是完整开发工具链 SBOM，Gradle、JDK、Rust / LLVM、Android SDK / NDK 的安装包不在本项目分发范围内，各自安装许可继续适用。

材料采集方法、范围和最终发布前核验边界见[依赖材料说明](docs/third-party/README.md)。

## SQLCipher 保留原库适配（2026-10-22）

`PreservingDatabaseFactory.kt` 的 helper 转发结构适配自 SQLCipher Android 固定提交 `ffe6c588…`；修改与完整 BSD-3-Clause 条款见 [NOTICE](docs/third-party/sqlcipher-preserving-adapter/NOTICE.md) / [LICENSE](docs/third-party/sqlcipher-preserving-adapter/LICENSE)。现有依赖版本保持，不新增数据库引擎。

## ExHentai 独立权限与画廊适配（2026-10-24）

沿用上述固定 Ehviewer_CN_SXJ 提交中明确 Apache-2.0 的 EhUrl、EhCookieStore、CookieSignInScene、EhEngine 与画廊 / 收藏解析文件；复用同一核心与完整 Apache 文本。EX 新增独立设置 / Cookie / igneous、本站权限响应、缩略图与媒体凭据范围，不引入完整宿主、全局 Cookie 存储或新运行依赖。具体修改与归属见 [EX NOTICE](app/src/main/assets/licenses/exhentai-NOTICE.txt)；项目整体仍 GPL-3.0-only。

## EH / EX 中文标签数据格式（2026-10-24）

固定参考 EhTagTranslation/Database@6c228f5ac76ca415e897dcc074f7e77c2afa14c2 与 v7.29288.1 的 db.text.json 结构。词典内容属于其编辑者并按 CC BY-NC-SA 3.0 CN 提供，不将其重许可为 GPL；本应用仅实现格式导入，不内置完整数据或上游可执行代码。许可原文与修改边界见 [NOTICE](app/src/main/assets/licenses/eh-tags-NOTICE.txt) 和[数据许可](app/src/main/assets/licenses/eh-tags-CC-BY-NC-SA-3.0-CN.txt)。上游 README 的使用登记与未来分发另行处理，本轮没有对外发送消息。

2026-10-24 EH / EX 后续：同一固定 Apache-2.0 文件继续用于 addfav 收藏字段、hc=1 评论读取及评论选择器。新增独立网站写入确认 / 重新读取核对与纯文本评论页；没有引入上游宿主、新运行依赖或评论发布。变更已追加到 EH / EX NOTICE 与现有来源索引。

2026-10-24 EH / EX 评论操作增补：同一固定提交的 EhEngine、GalleryDetailParser 与新增缓存的 GetEditCommentParser / VoteCommentParser（Copyright 2016 Hippo Seven，Apache-2.0）用于评论表单、原编辑文本与投票回执适配。复用 Kotlin/jsoup 和 Rust 专用请求，不引入完整客户端或新运行依赖；完整归属和修改说明见 EH / EX NOTICE，固定文件及哈希列入来源索引。没有实际发表评论或投票。

2026-10-24 EH / EX 密码登录增补：同一固定提交的 EhEngine.signIn、EhUrl 与 SignInParser（Apache-2.0 / Copyright 2016 Hippo Seven）适配论坛表单和成功 / 失败解析，SignInScene 仅用于流程参考。复用既有 Rust 传输、Kotlin/jsoup 与平台 HttpCookie；不复制宿主、全局 Cookie 数据库或 WebView。修改记录见 EH / EX NOTICE，固定资料与本地文件哈希见来源索引。

