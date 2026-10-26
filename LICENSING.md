# FluxManga 许可说明

FluxManga 自有代码采用 **GPL-3.0-only**，完整条款见根目录 [LICENSE](LICENSE)。第三方代码、依赖与素材保留各自许可及版权；漫画内容、网站商标、用户原文件与账号数据不属于项目授权范围。

## 对应源码

公开仓库：[Mikuku-Kawaii/FluxManga](https://github.com/Mikuku-Kawaii/FluxManga)。每次正式发布在 [Releases](https://github.com/Mikuku-Kawaii/FluxManga/releases) 提供固定版本 tag、APK 及对应源码 ZIP。源码包含 Kotlin / Rust 实现、Room schema、依赖锁定、构建与安装脚本、公开配置和所需许可材料。请使用 APK 同版本的源码，而非随时间变化的 main 分支。

构建方式见[构建与安装](docs/release/build-and-install.md)，归档方式见[源码归档](docs/release/source-archive.md)。私钥、用户漫画、数据库、凭据、个人运行日志和设备截图不随源码发布；SDK / JDK 等工具由构建者按说明安装。

## 第三方材料

[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 列出固定上游、许可和修改声明。原始许可保存在 `app/src/main/assets/licenses` 与 `docs/third-party`，构建时生成应用内「设置 → 关于 → 开源许可与声明」目录。

历史依赖清单是当时的解析快照，新增组件另有声明；它不是最终 APK 的完整 SBOM。使用及再次分发时须遵循实际组件的 GPL、MPL、Apache、BSD、MIT、Unicode 等原始条件。
