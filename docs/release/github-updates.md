# GitHub 自动更新与发布

官方仓库：[Mikuku-Kawaii/FluxManga](https://github.com/Mikuku-Kawaii/FluxManga)。公开配置位于 `release/update.properties`：stable 渠道、清单 URL、RSA 公钥。Fork 应改为自己的配置；也可用 Gradle 参数 `fluxUpdateUrl`、`fluxUpdatePublicKey`、`fluxUpdateChannel` 覆盖，空 URL 可关闭检查。

## 客户端行为

启动后非阻断检查 `https://github.com/Mikuku-Kawaii/FluxManga/releases/latest/download/update-v2.signed`；清单先验签、检查过期时间与包名 / 渠道，再比较 versionCode。下载支持断点续传、暂停、完整包和可选 BSDIFF40 差分；安装前检查 APK 长度、SHA-256、包名、版本、ABI、minSdk 和同一签名身份，最后由 Android 确认安装。

可选的自动下载只在应用前台、非计费网络执行，默认关闭；离开前台会暂停。没有静默安装，也没有后台常驻检查服务。GitHub 不可达时显示失败与重试，不改用未经签名的镜像。

## 维护者发布一次新版本

1. 修改版本说明；构建更高 versionCode 的同签名双 ABI release APK。公开 0.1.0 基线为 code 3；0.1.1 为 code 4，0.1.2 为 code 5，本次使用完整包更新。
2. 准备候选源码提交并固定 SHA，构建期间不要继续修改应用源码。只提交源码、schema、lockfile、构建脚本、公共配置与许可材料。
3. 用仓库外的 PKCS#8 RSA 私钥生成清单。PowerShell 7 示例：

```powershell
pwsh -File scripts/prepare-release.ps1 `
  -Apk app/build/outputs/apk/release/app-release.apk `
  -SigningKey <仓库外的更新私钥.pk8> `
  -Notes release/notes/0.1.2.md `
  -OutputDirectory build/publish/v0.1.2
```

脚本校验 APK 并生成 `FluxManga-0.1.2-universal.apk`、`update-v2.signed`、`release-artifacts.json`、`SHA256SUMS.txt`。默认清单有效期 365 天；到期前应发布新的签名清单 / 版本，不能放任渠道过期。

4. 用 `git archive --format=zip --prefix=FluxManga-0.1.2/ -o <源码ZIP> <提交SHA>` 生成对应源码，将源码 ZIP 哈希追加到校验清单。
5. 推送该提交及版本 tag，然后显式运行发布工具：

```powershell
# Assets 是明确列出的 APK、清单、元数据、源码 ZIP 和 SHA256SUMS.txt；不要传整个目录。
.\scripts\publish-github-release.ps1 -Tag v0.1.2 -Commit <40位提交SHA> `
  -Notes release/notes/0.1.2.md -Assets $assets -Publish
```

工具通过本机 Git Credential Manager 或进程 `GH_TOKEN` 登录 GitHub；凭据不进入仓库 / 日志。先建立草稿，逐项核对 GitHub 返回的大小和 SHA-256，再公开并设为 latest。不传 `-Publish` 时保留草稿；已公开版本不被覆盖。API 依据：[Release](https://docs.github.com/en/rest/releases/releases)、[Release assets](https://docs.github.com/en/rest/releases/assets)。

## 签名与恢复

APK 私钥决定能否覆盖旧安装，清单 RSA 私钥决定能否发布可信更新，两者独立；都必须离线备份并保持私密。仓库只有公钥。不要把这些私钥放进 Git、Release 或公开 Actions 日志。0.1.0 沿用现有预览签名以保留用户数据；没有执行证书轮换。

首个公开版本只有完整包；后续具备两个同签名 APK 时，可用 `scripts/prepare-update-delta.py` 和可信 BSDIFF40 生成器准备差分。仅当补丁更小且基线哈希完全匹配时采用，客户端保留完整包路径。不要把只有一版 APK 的状态称作差分发布完成。

当前流程是维护者显式本地签名发布，不会在每次 push 自动打包。用户安装后的检查与下载由客户端更新器完成。


0.1.2 修复旧下载器在 Android 沙箱中查询文件系统挂载点的兼容问题，改为直接查询暂存目录可用空间。受影响旧包需手动覆盖安装一次；清单与 APK 的验签、哈希和身份校验不降低。失败提示区分 HTTP、超时、DNS、安全连接和文件 I/O；完整但哈希错误的暂存包在重试时重新下载，部分下载仍支持续传。
