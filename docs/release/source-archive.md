# 对应源码归档

公开仓库只跟踪发布所需源码、Room schema、依赖锁定、构建脚本、公开渠道配置及许可。历史设备资料和本机数据不进入 Git；GitHub 自动生成的版本源码包与额外的 `FluxManga-<version>-source.zip` 均绑定发行提交。

```powershell
git archive --format=zip --prefix=FluxManga-0.1.0/ -o build/publish/FluxManga-0.1.0-source.zip <40位提交SHA>
Get-FileHash build/publish/FluxManga-0.1.0-source.zip -Algorithm SHA256
```

归档与 APK 必须来自同一份冻结源码。构建时生成 UniFFI 绑定、原生库和许可资产，因此无需把 build / target 目录放入源码包。保留 Gradle Wrapper JAR、Cargo.lock、Room schema 及上游原始许可。

本机 `scripts/source-release.py` 是历史完整工作区归档工具，引用本机维护材料；公开发布采用这里的 Git 提交归档流程。不要上传整个工作目录 ZIP。
