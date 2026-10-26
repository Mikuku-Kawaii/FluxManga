<div align="center">

# FluxManga · 流光漫匣

**把找漫画、整理书库和接着读，放在同一个地方。**

Android 原生漫画阅读器 · 双空间 · 多图源 · 本地 CBZ · 液态玻璃界面

[![Release](https://img.shields.io/github/v/release/Mikuku-Kawaii/FluxManga?color=4B8DFF)](https://github.com/Mikuku-Kawaii/FluxManga/releases/latest)
[![Android](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](#下载与安装)
[![License](https://img.shields.io/badge/License-GPL--3.0--only-blue)](LICENSE)

[下载 APK](https://github.com/Mikuku-Kawaii/FluxManga/releases/latest) · [功能](#能做什么) · [构建](docs/release/build-and-install.md) · [反馈问题](https://github.com/Mikuku-Kawaii/FluxManga/issues)

</div>

## 能做什么

### 从不同来源，回到同一个书库

- **混合发现**：推荐、最近更新、关键词搜索和标签结果统一展示；各图源独立分页与重试。
- **账号导入**：连接支持的图源账号，将网站收藏 / 书架导入当前空间；支持全部导入、取消、续办和去重，保留已有阅读位置。
- **本地阅读**：导入 CBZ，校验归档并按自然页序阅读。应用保存自己的副本，原文件保持不动。
- **书库整理**：封面网格 / 列表、继续阅读、收藏筛选、批量管理、用户标签与作品关联。

### 把阅读留在正文里

- 默认全屏，轻触呼出阅读工具与页码滑条。
- 左右翻页、右到左翻页、上下逐页与条漫模式；支持缩放、音量键翻页和宽屏双页。
- 接近章节末尾时预加载下一话，正文直接续接；只有当前最后一话后显示结束页。
- 整章预加载（0.1.1）：阅读时持续缓存当前章，优先当前位置及后续页，再补齐前面的未缓存页；下一话仍只预热前三张，实际进入后才开始整章缓存。
- 按作品保存阅读方式和位置，支持书签、每日阅读时长与作品统计。
- 章节下载与离线阅读；公开来源支持后台队列和进度通知，私有下载保留独立加密路径。

### 两个空间，一致的体验

表世界采用白蓝配色，里世界采用黑橙配色。书库、进度、账号和缓存按空间隔离，界面与主要操作保持一致。

液态底栏、融球切换与全屏转场配有低效果、减少动画等独立选项。采样默认关闭、模糊默认关闭、微染色默认开启；图像正文不模糊。私有空间使用加密存储、窗口保护与可选身份验证，不能代替设备自身的安全保护。

## 图源

| 来源 | 主要能力 | 连接方式 / 边界 |
| --- | --- | --- |
| 本地 CBZ | 导入、封面、阅读、进度、分组 | 无需账号，保留原文件 |
| Komga | 搜索、最近更新、标签、目录、阅读、授权列表导入 | 连接自己的 Komga 服务；无首页推荐接口 |
| 拷贝漫画 | 推荐、最近更新、搜索、标签、阅读、云收藏导入 | 支持应用内网页登录并接回会话 |
| 包子漫画 | 推荐、更新、分类、阅读、书架导入 | 官网登录 / 注册、浏览器验证；书架范围取决于官网返回 |
| JMComic | 推荐、更新、搜索 / 编号直达、阅读、收藏导入 | 里世界；账号与网页登录接回 |
| PicACG | 推荐、分类、搜索、阅读、收藏导入 | 里世界；需要有效账号，未伪造网页登录入口 |
| E-Hentai / ExHentai | 搜索、标签、画廊阅读、收藏导入 | 里世界；EX 权限独立核验，EH 登录成功不代表 EX 可用 |

图源能力依赖对应服务、账号权限与网络状态。项目不提供漫画资源、账号或服务镜像，也不保证所有网站始终可访问。详细范围见[图源说明](docs/release/supported-sources.md)。

## 下载与安装

前往 [GitHub Releases](https://github.com/Mikuku-Kawaii/FluxManga/releases/latest) 下载 `FluxManga-0.1.2-universal.apk`。

- **Android 12 / API 31 及以上**；一个 APK 包含 `arm64-v8a` 与 `x86_64`。
- 首次安装按 Android 提示允许对应来源安装；升级直接覆盖安装，保留应用数据。
- 0.1.2 为早期公开版本，仍在持续开发；请保留重要原文件和备份。
- 如果系统提示签名冲突，请先停止安装并核对来源，不要通过卸载或清库解决。

### 自动更新

应用启动后非阻断检查本仓库的签名更新清单，也可以在「设置 → 应用更新」手动检查。下载后校验长度、SHA-256、包名、版本和 APK 签名，再交给 Android 系统确认安装。

可选开启前台非计费网络自动下载，默认不自动下载。下载支持暂停 / 续传；有匹配的可信差分包时可使用差分，否则下载完整 APK。**0.1.2 / versionCode 5 修复下载前的空间检查兼容问题，本次提供完整包。** 0.1.0 / 0.1.1 若点击下载立即失败，请从 Releases 手动覆盖安装 0.1.2 一次；修复不能通过更新服务器清单注入旧客户端。后续发布递增 `versionCode` 并沿用同一签名，安装保留现有数据。

更新清单与 APK 都托管在 GitHub Releases，不使用用户的图源账号。发布方法见[自动更新与发布](docs/release/github-updates.md)。

## 构建

当前构建脚本面向 **Windows x64**，需要 JDK 17、Android SDK 36、Build Tools 35.0.0、NDK 28.2.13676358、Rust 1.98.1、cargo-ndk 4.1.2 和 MSVC C++ 构建工具。Gradle Wrapper 与依赖版本随源码固定。

```powershell
git clone https://github.com/Mikuku-Kawaii/FluxManga.git
cd FluxManga
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
# 设置 JAVA_HOME、ANDROID_HOME，然后构建本机调试版本
.\gradlew.bat :app:assembleDebug
```

发行签名不在仓库内。自行编译的 APK 通常无法覆盖官方签名包；Fork 应配置自己的更新地址、公钥与签名。详见[构建与安装](docs/release/build-and-install.md)。

## 架构

```text
Compose 页面 → ViewModel / WorldSession → Kotlin Repository
                                      ├─ Room / SQLCipher / Android Keystore
                                      ├─ Kotlin 图源与图片管线
                                      └─ UniFFI → Rust 核心与部分图源协议
```

| 目录 | 职责 |
| --- | --- |
| `app/src/main` | Android 界面、导航、阅读器、图源、存储、下载与更新 |
| `app/schemas` | Room 数据库历史 schema |
| `rust-core` | Rust 核心、协议模型与 UniFFI 接口 |
| `scripts` | 双 ABI 构建、发布产物准备与发布工具 |
| `release` | 公开更新渠道配置、版本说明；不含私钥 |
| `docs/third-party` | 第三方来源、许可原文与声明 |

### 上游复用

<a id="upstream-reuse"></a>

图源和底层能力优先参考成熟实现，在固定版本与许可下提取必要协议，再适配本项目的空间隔离、取消与数据所有权。保留上游版权与修改说明，不整套引入另一阅读器或扩展宿主。详见[第三方声明](THIRD_PARTY_NOTICES.md)。

<a id="ui-reference-20261019"></a>

界面沿用已确认的白蓝 / 黑橙视觉体系；本地设计参考与个人设备截图不随仓库发布。功能和构建记录不等于所有设备上的体验验证，当前限制见[已知限制](docs/release/known-limitations.md)。

## 反馈与贡献

欢迎提交 Issue 或 Pull Request。报告问题时请提供应用版本、Android 版本、图源名称、重现步骤及脱敏后的错误信息；不要上传密码、Cookie、Token、个人数据库或私有阅读内容。

## 许可证

FluxManga 自有代码采用 [GPL-3.0-only](LICENSE)。第三方代码、依赖与素材遵循各自许可，见 [LICENSING.md](LICENSING.md) 和 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。漫画内容与网站商标不包含在项目授权中。
