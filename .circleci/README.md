# CircleCI macOS 手动构建

本配置构建供自行签名的 iOS IPA。使用 CircleCI Free 的 `m4pro.medium`（6 vCPU、28 GB RAM），
Xcode 固定为 `26.4.1`，对应仓库 Kotlin 2.4 系列支持的 Xcode 26.4。
构建环境使用 JBR 21（带 JCEF）、Android SDK 37.0、CocoaPods 和仓库的 Gradle 锁脚本。

## 启动条件

`run_ios` 默认是 `false`，普通 push / PR 不会创建 macOS 构建任务。
显式传入 `run_ios=true` 后，仍须批准 `approve_macos_start` 才会分配 Mac 和运行安装、构建命令。
配置中没有定时任务或自动发布步骤。

账户连接时选择 Free 套餐，只连接 `GrahamZen/izuko-tv`，保持项目的自动触发器关闭。
配置文件路径为 `.circleci/config.yml`；配置与源码分支应包含同一套 `.circleci` 文件。
完成连接不需要运行示例项目或点击第一次构建。

需要实际构建时，在 CircleCI 的 Run Pipeline 中选择源码分支，传入：

```json
{"run_ios": true, "build_type": "release"}
```

确认分支和剩余额度后，批准 `approve_macos_start`。`build_type` 也支持 `debug`。
成功后的 Artifacts 中提供 `izuko-ios-release.ipa` 或 `izuko-ios-debug.ipa`。
未配置 Apple 签名证书或应用服务密钥；依赖这些密钥的应用功能需要另行配置。

## 免费档限制

截至 2026-10-24，Free 每月提供 30,000 credits，`m4pro.medium` 每分钟使用 200 credits，
未计其他资源消耗时约 150 分钟。56 GB 的 `m4pro.large` 不属于 Free 套餐。
账户保持 Free 且不开启付费；存储等资源也可能消耗额度。
本配置仅上传 IPA，不持久保存整套 Gradle / Kotlin 缓存。

28 GB 低于建议的 32 GB。Gradle 最大堆为 12 GB、Kotlin 守护进程最大堆为 16 GB，
单 worker 串行构建仍可能使用系统交换区；全量优化 Release 的内存和用时必须通过实际构建验证。
每次任务会检查 Apple Silicon、至少 24 GiB RAM 和 100 GiB 可用空间；规格不足时在编译前退出。
账户注册和配置静态检查不代表已验证远程构建，也不预留一台持续存在的虚拟机。

参考：[CircleCI Free](https://circleci.com/pricing/)、
[macOS 规格](https://circleci.com/docs/guides/execute/using-macos/)、
[计算额度](https://circleci.com/pricing/price-list/)、
[手动批准](https://circleci.com/docs/guides/orchestrate/workflows/#hold-a-workflow-for-a-manual-approval)、
[Kotlin 兼容性](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)。
