<div align="center">

# FluxManga · 流光漫匣

**想看的漫画，放进同一个书库。**

一款 Android 漫画阅读器，连接在线图源与本地文件，陪你从发现新作读到下一话。

[![Release](https://img.shields.io/github/v/release/Mikuku-Kawaii/FluxManga?color=4B8DFF)](https://github.com/Mikuku-Kawaii/FluxManga/releases/latest)
[![Android](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](#下载与安装)
[![License](https://img.shields.io/badge/License-GPL--3.0--only-blue)](LICENSE)

[下载 FluxManga](https://github.com/Mikuku-Kawaii/FluxManga/releases/latest) · [认识功能](#认识-fluxmanga) · [支持的来源](#支持的来源) · [反馈问题](https://github.com/Mikuku-Kawaii/FluxManga/issues)

</div>

## 认识 FluxManga

### 发现下一部想读的作品

在首页浏览推荐和最近更新，按关键词或标签寻找漫画。多个来源的结果可以一起查看，每个来源独立加载；你也可以进入单个来源浏览。找到喜欢的作品后，将它加入书库，或者从已有图源账号导入收藏。

### 把喜欢的作品整理成自己的书库

在线漫画和导入的本地 CBZ 都可以放进书库，以网格或列表浏览。使用收藏、标签和分组整理作品，从最近阅读的位置继续；书库根据可获得的目录，在阅读状态后追加“ · 有未看章节”；更新日期统一以灰色显示，已完结作品显示“已完结”。

长按书目进入多选，原来的“收藏（已选 N 部）”和阅读状态筛选区域会在滚动到顶部后吸顶，下方只显示“全选、清空、取消收藏”。操作沿用筛选栏的文字按钮样式，不新增凸起面板或填色大按钮。本地 CBZ 导入时会保存应用副本，原文件保留在原处。

### 用喜欢的方式阅读

阅读器默认全屏，轻触画面即可打开章节、书签和页码工具。你可以选择左右翻页、从右向左翻页、上下逐页或条漫模式，也可以缩放、使用音量键翻页，或在宽屏上使用双页。

阅读位置和阅读方式按作品保存。接近章节末尾时会预加载下一话，让阅读自然续接；支持下载的章节也可以离线阅读。阅读记录与统计帮你回顾自己的阅读时间。

### 两个空间，两份书库

表世界采用清爽的白蓝配色，里世界采用黑橙配色。两个空间各自保存书库、阅读进度、图源账号和缓存，可以按需要切换。

里世界提供加密存储、窗口保护和可选身份验证。液态玻璃界面、切换动画与低效果选项可在设置中调整；漫画正文不会被模糊处理。空间保护不能代替设备本身的安全设置。

<a id="ui-reference-20261019"></a>

## 支持的来源

| 来源 | 适合做什么 | 使用前需要知道 |
| --- | --- | --- |
| **本地 CBZ** | 导入自己的漫画文件，离线阅读 | 应用保存副本，原文件保留 |
| **Komga** | 浏览和阅读自己的漫画服务器 | 需要自行配置服务器及访问凭据 |
| **拷贝漫画** | 浏览、搜索、阅读和导入云收藏 | 支持应用内网页登录；导入范围取决于可用接口 |
| **包子漫画** | 浏览、阅读和导入网站书架 | 网站可能要求登录或浏览器验证，书架导入范围以网站返回为准 |
| **JMComic** | 在里世界浏览、搜索、阅读及导入收藏 | 可用线路和内容取决于服务与账号 |
| **PicACG** | 在里世界浏览、搜索、阅读及导入收藏 | 需要有效账号 |
| **E-Hentai / ExHentai** | 在里世界搜索、阅读画廊及导入收藏 | ExHentai 权限需要单独确认 |

各来源的功能、登录方式和导入范围不同，访问也会受到站点、账号权限及网络影响。FluxManga 不提供漫画资源、账号或服务镜像。[查看详细图源说明](docs/release/supported-sources.md)。

包子支持会话恢复：保留独立网站会话，过期后先恢复网站验证，确需登录时才使用已保存的自动登录凭据。恢复成功会接续失败的读取；需要人工验证或修正密码时，可在图源设置中处理。旧版 WebView 使用兼容的 Cookie 恢复方式。已有账号、书库和阅读进度保留。

## 下载与安装

从 [GitHub Releases](https://github.com/Mikuku-Kawaii/FluxManga/releases/latest) 下载 **FluxManga-1.0.1-universal.apk**。

- 需要 **Android 12 或更高版本**；安装包支持 arm64-v8a 和 x86_64。
- 首次安装时，按 Android 的提示允许相应来源安装应用。
- 从同签名的旧版升级时，可直接覆盖安装并保留应用数据。若遇到签名冲突，请先核对安装包来源。

应用启动时会检查更新，也可以在「设置 → 关于与更新」手动检查。下载完成后，应用会校验安装包，再由 Android 系统确认安装。

## 开始使用

1. 安装并打开应用，在首页浏览推荐，或通过搜索和标签寻找作品。
2. 打开「设置 → 图源管理」，按需连接图源账号或配置 Komga 服务器；可在对应来源导入已有收藏。
3. 在书库中添加本地 CBZ、管理收藏和标签，或从上次阅读的位置继续。
4. 阅读时轻触画面，切换阅读方式、查看目录、添加书签或跳转页码。
5. 需要不同的书库与账号时，切换表世界和里世界，再分别配置来源。

应用提供备份与记录导出入口。更换设备前，请主动备份需要保留的数据，并妥善保存本地漫画原文件。

## 给开发者

界面使用 Jetpack Compose，Android 层负责生命周期、数据和图片处理；部分核心能力与图源协议由 Rust 提供，通过 UniFFI 与 Kotlin 连接。

当前构建脚本面向 Windows x64。配置 JDK 17、Android SDK、NDK 和 Rust 后，可运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

完整环境、发行签名与安装说明见[构建文档](docs/release/build-and-install.md)。自行签名的构建通常不能覆盖官方安装包。

<a id="upstream-reuse"></a>

项目在固定版本与许可条件下参考上游实现，并保留相应版权与修改说明；详情见[第三方声明](THIRD_PARTY_NOTICES.md)。

## 反馈与贡献

欢迎通过 [Issues](https://github.com/Mikuku-Kawaii/FluxManga/issues) 反馈问题，或通过 Pull Request 参与改进。反馈时请附上应用版本、Android 版本、图源和重现步骤，并移除密码、Cookie、Token 与私人内容。

## 许可证

FluxManga 自有代码采用 [GPL-3.0-only](LICENSE)。第三方代码、依赖和素材遵循各自许可，详见 [LICENSING.md](LICENSING.md) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
