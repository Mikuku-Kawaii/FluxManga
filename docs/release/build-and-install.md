# 构建与安装

0.1.3 / versionCode 6，包名 `com.flux.manga`，minSdk 31。发行包包含 arm64-v8a、x86_64，启用 R8 与资源压缩，不启用 debuggable。

## Windows 构建环境

- JDK 17（本轮使用 17.0.20.1+1）、MSVC C++ Build Tools。
- Android SDK Platform 36、Build Tools 35.0.0、NDK 28.2.13676358。
- Rust 1.98.1，目标 aarch64-linux-android / x86_64-linux-android，cargo-ndk 4.1.2。
- Gradle Wrapper 8.13、AGP 8.13.0、Kotlin 2.2.20，版本目录见 `gradle/libs.versions.toml`。

设置 `JAVA_HOME`、`ANDROID_HOME`；也可将工具放在 `scripts/env.ps1` 约定的本地 `.tools` 目录。不要提交 `local.properties` 或工具安装目录。

```powershell
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
.\gradlew.bat :app:assembleDebug
```

`preBuild` 自动构建双 ABI Rust 库、生成 UniFFI Kotlin 绑定和许可资产。无需预先复制 `.so` 或生成代码。Linux / macOS 构建脚本尚未提供。

## 签名发行

在本机进程设置 `FLUX_SIGNING_STORE`、`FLUX_SIGNING_STORE_PASSWORD`、`FLUX_SIGNING_ALIAS`、`FLUX_SIGNING_KEY_PASSWORD`；私钥与密码不能提交到仓库。

```powershell
.\scripts\build-release.ps1 -VersionName 0.1.3 -VersionCode 6
```

产物：`app/build/outputs/apk/release/app-release.apk`。未提供签名变量时，普通 `assembleRelease` 只生成未签名 APK，不可直接分发；发布脚本要求显式提供签名。

官方 0.1.0 为兼容早期预览用户保留原签名证书，使用 release 配置构建。后续版本必须使用同一私钥并递增 versionCode。Fork 必须使用自己的签名与更新渠道，不能覆盖官方安装。

## 安装与保留数据

在 Android 上打开 APK，按系统提示授权安装；或在明确连接目标设备后使用 `adb -s <设备序列号> install -r <APK>`。安装者必须确认来源、版本和签名兼容。

签名冲突、降级或数据库异常时停止安装，不卸载、不清库、不强制降级。应用 ID 相同并不保证签名一致。保留用户原文件及重要备份；打包成功不等于设备功能验收通过。

发布、校验与更新清单见 [GitHub 更新流程](github-updates.md)。
