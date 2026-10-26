# 依赖许可材料

2026-10-21 的源码依赖快照，用于 M5 发布资料。未打包、未安装、未重新运行应用测试；正式版本还要对实际分发 APK / 源码归档核验。根项目许可见 [LICENSING.md](../../LICENSING.md)，上游移植声明见 [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)。

## 清单范围

| 材料 | 范围与依据 |
| --- | --- |
| [source-adapters.json](source-adapters.json) | 2026-10-22 增补的三源四条适配记录：固定提交、原声明、代表性缓存与核心本地文件哈希；[离线检查 / 升级流程](../release/source-upgrades.md)，不替代下列依赖清单或完整源码归档 |
| [android-runtime.md](android-runtime.md) | `releaseRuntimeClasspath` 解析出的 113 项组件；包括没有独立二进制的元数据变体，已合并重复引用并排除仅作为约束的 `(c)` 行 |
| [rust-lock.md](rust-lock.md) | Cargo.lock 中 181 个外部包；包含 host / 可选 / 非 Android 平台，不能据此断言最终链接组成 |
| [inventory.json](inventory.json) | Maven 坐标 / 原始 POM 许可 / POM 和本机归档哈希 / 声明路径，以及 Cargo 包版本 / 校验和 / manifest 哈希 / 原始许可表达式 |
| [build-test-direct.json](build-test-direct.json) | 10 个构建、测试和调试直接依赖的 POM 许可；不冒充整个 Gradle / JDK / SDK 工具链清单 |
| `maven-metadata/`、`maven-notices/` | 本机发布 POM 及 AAR / JAR（含 classes.jar）内原样提取的 LICENSE / NOTICE / COPYRIGHT；不复制二进制包 |
| `rust-licenses/`、`licenses/` | 锁定 crate 的原始许可文件、补充的固定上游原文和通用许可正文；不改写上游版权 |

Guava `listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` 的许可继承父 POM，父 POM 一并保存。八个 UniFFI 包的已下载 crate 未包含完整许可文本，补充 v0.29.4 的 MPL 2.0 原文；r-efi 的 `AUTHORS` 自身含版权与许可，按原文件保留。JNA 的双许可声明与 libffi 声明、SQLCipher Android / 核心与 OpenSSL 的原文独立保留。补充原文来源与哈希保存于本地历史评估；公开版本保留许可原文及固定上游声明。

当前目录中的许可原文只做字节复制。Android POM 是声明依据；不能仅凭缓存里某个包存在，就判断它进入 APK。Cargo SPDX 不做统一简化，例如 Unicode、MPL、CDLA 与 ring 的组合许可仍需分别遵循。

## 复核方法

在已具备依赖缓存的 Windows 环境，可只解析运行时依赖（该命令不编译、不生成 APK）：

```powershell
. .\scripts\env.ps1
.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath --offline --console=plain --no-daemon
```

历史解析输出保留于本地开发记录。`--offline` 需要已有缓存；缓存缺失时明确失败，不能据此声称没有依赖。当前 Activity Compose 在版本目录声明 1.11.0，但解析选用 1.12.0；清单以解析值为准，未改依赖配置。

Cargo 清单按 `rust-core/Cargo.lock` 的包名、版本、registry 校验和，与本机对应 crate 的 Cargo.toml / 许可文件匹配；未执行编译或更改 lockfile。最终 Android feature / ABI 链接范围需在用户允许制作发布产物时另验。

2026-10-22 已新增应用内离线许可浏览代码：从本目录已有 Android runtime / Cargo 材料及项目 / 适配声明构建目录与去重原文，入口为设置 → 关于 → 开源许可与声明，构建逻辑见 `gradle/license-assets.gradle.kts`。构建 / 测试直接依赖仍在本目录，不宣称覆盖完整工具链；本轮未打包，尚未核验实际 APK 的资源分发。正式发布应将所需许可 / NOTICE 与对应源码取得说明随实际分发物提供，并核对源码归档和二进制一致；现有公开渠道、签名和产物流程见[发布文档](../release/github-updates.md)。

