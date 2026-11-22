# onnxruntime 的两个本地 pod

iOS 上识别网站图片验证码用 onnxruntime (见 `IosOnnxImageCaptchaRecognizer`), 经 CocoaPods 的 `onnxruntime-objc` 与它依赖的
`onnxruntime-c` 两个 pod 接进来。两个 pod 都从本地目录装 (`:path`), 不从 CocoaPods 官方源下载:
官方 podspec 里的包地址 `download.onnxruntime.ai` 证书与域名不符、下不了, onnxruntime 的 GitHub 仓库也已取不到。

| pod | 放在哪 | 内容 |
|---|---|---|
| `onnxruntime-objc` 1.22.0 | `onnxruntime-objc/` (随仓库) | 官方 podspec 原样; `objectivec/` 下 podspec 列出的 24 个源码文件与 `LICENSE` (MIT) |
| `onnxruntime-c` 1.22.0 | `build/onnxruntime-c/` (构建时生成) | 官方 pod 包解开: `onnxruntime.xcframework` (静态库)、`Headers/`、`LICENSE`; 加上 `onnxruntime-c.podspec.json` |

## 来源与核对

- 两份 podspec: CocoaPods 官方源 (`cdn.cocoapods.org`) 上 1.22.0 的原文, 没改。`source` 一栏 (下载地址) 对本地 pod 不起作用。
- `onnxruntime-objc/` 的源码: 取自 `Arm-China/Compass_Onnxruntime` 的 `rel-1.22.0` 分支 (onnxruntime 发布分支的 fork),
  提交 `132d4d83c9c7b749803a81a3cd006b32a57d22dd`; 每个文件都与该提交 git 树里的哈希对过。`objectivec/` 在这个分支上最后的改动是上游的
  #22982 (2024-12), fork 没改过这部分。
- `onnxruntime-c` 的 pod 包 (`pod-archive-onnxruntime-c-1.22.0.zip`): `fetch-onnxruntime-c.sh` 先试官方下载站, 再试一个把原包放进仓库的项目
  (`zubair-io/Maple`, 固定到提交 `e4e240593b`), 按 SHA-256 `90d9de5a139087a6b05a18125d01d01d198820e1731e6f0f11b38749b2ab181f` 核对,
  对不上就不用。这个校验值是 onnxruntime 官方 Swift Package 里写的那个, 几个互不相关的项目 (`OpenProofingFoundation/openproofing-sdks`、
  `mdd335/triglosa`、`zubair-io/Maple`) 抄的都是它。

## 怎么用

`:app:shared:application:fetchOnnxruntimeC` 调 `fetch-onnxruntime-c.sh` 把 C 库解到 `build/onnxruntime-c/`; Kotlin 那边的 `podInstallSynthetic*`
与宿主工程 `:app:ios:podInstall` 都依赖它。下载的包缓存在 `build/onnxruntime-c-download/`, CI 用 actions/cache 留着这个目录。

换版本时: 两份 podspec 换成新版本的原文, `objectivec/` 换成新版本的源码, 脚本里改版本号与校验值, `IosOnnxImageCaptchaRecognizer` 按新版本的接口核对。
