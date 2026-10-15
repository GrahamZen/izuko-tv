<div align="center">

<img src=".readme/images/logo.png" alt="Izuko TV" width="128"/>

# Izuko TV

**为 Android TV / Google TV 打造的追番客户端**

| 首次安装 | 下载 / 更新 | 许可证 | 原项目 |
|:---:|:---:|:---:|:---:|
| [![Installer](https://img.shields.io/badge/Installer-install--izuko.apk-043b69?labelColor=06599d)](https://github.com/GrahamZen/izuko-tv/releases/download/installer/install-izuko.apk) | [![Release](https://img.shields.io/github/v/release/GrahamZen/izuko-tv.svg?maxAge=3600&label=Download&labelColor=06599d&color=043b69&include_prereleases)](https://github.com/GrahamZen/izuko-tv/releases/latest) | [![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE.txt) | [![Based on Animeko](https://img.shields.io/badge/open--ani%2Fanimeko-181717?logo=github&logoColor=white)](https://github.com/open-ani/animeko) |

</div>

> [!NOTE]
> Izuko TV 是 [Animeko](https://github.com/open-ani/animeko)（by OpenAni and contributors）的**第三方修改版**，依照 [AGPL-3.0](LICENSE.txt) 发布。它为电视重新设计了界面，并直接连接 [Bangumi][Bangumi]，不依赖 Animeko 服务器。
> 本项目与 OpenAni 团队**没有隶属关系，也未获其认可**；使用中遇到的问题请在[本仓库](https://github.com/GrahamZen/izuko-tv/issues)反馈，不要打扰上游。

## 功能

- **Bangumi 同步**：番剧信息与评论，收藏、评分和观看进度直接读写你的 Bangumi 账号；官方连不上时自动改用加密直连或社区镜像
- **多数据源**：BT（[动漫花园][dmhy]、[Mikan]）、在线源、规则源、苹果 CMS、网盘（含 WebDAV / NAS）、Jellyfin / Emby，支持订阅和自定义，自动选源
- **弹幕**：来自[弹弹play][ddplay]，也可以发送
- **播放器**：拖动进度时预览画面，长按倍速，片尾自动接下一集；字幕和音轨按语言自动选；BT 资源边下边播，也能提前缓存到本地
- **找番**：首页按你的收藏推荐，另有新番时间表和按标签、年份的搜索
- **手机操作**：扫电视上的二维码打开 Web 控制台，不用装应用，在手机上打字搜索、登录、控制播放、管理缓存和设置，也能在手机上跟着电视同步播放
- **多用户**：家里每人一份收藏、进度和 Bangumi 登录，开机选人；不想登录也能用本地用户，收藏只存在电视上，以后可以导入 Bangumi
- **换电视**：新电视经局域网把旧电视的用户、设置和数据源一次搬过来；设置也能导出成文件备份
- **系统集成**：主屏幕频道、屏保、应用内更新

## 界面预览

| <img src=".readme/images/features/tv-explore-hero.png" alt="探索页首屏" width="380"/> | <img src=".readme/images/features/tv-explore-more.png" alt="更多推荐" width="380"/> |
|:---:|:---:|
| 探索页：热门动画轮播与推荐 | 「因为你喜欢」推荐行，按确认推荐更多相似的作品 |
| <img src=".readme/images/features/tv-details.png" alt="详情页" width="380"/> | <img src=".readme/images/features/tv-details-episodes.png" alt="选集" width="380"/> |
| 详情页：评分、标签与收藏人数 | 剧情简介与分集剧照：长按剧照看完整单集介绍 |
| <img src=".readme/images/features/tv-poster-wall.png" alt="海报墙" width="380"/> | <img src=".readme/images/features/tv-web-console.png" alt="Web 控制台" width="380"/> |
| 海报墙：卡片大小可自由调整 | 手机扫码打开 Web 控制台 |

https://github.com/user-attachments/assets/7f9fe051-a904-4157-a317-b13284390fec

追番页、搜索页、主屏幕频道与屏保等更多截图见[界面预览](docs/guide/screenshots.md)。

## 下载安装

- **第一次装**：下载[安装助手](https://github.com/GrahamZen/izuko-tv/releases/download/installer/install-izuko.apk)（`install-izuko.apk`），装到电视或电视盒子上打开，它会挑对安装包，下载并装上 Izuko TV
- **更新**：有新版本时首页会提示，在「设置 → 软件更新」里直接更新
- **自己选安装包**：在 [Releases](https://github.com/GrahamZen/izuko-tv/releases/latest) 下载 APK，默认下 `universal`；需要 Android 8.1 及以上，Android 7.1 用文件名带 `legacy` 的兼容包

Windows、macOS、Linux 和 iOS 请使用[上游 Animeko](https://github.com/open-ani/animeko)。

### 装不上怎么办

解析错误、签名冲突、文件管理器里看不到安装包、各品牌电视拦截安装的办法，见[安装指南](docs/guide/install.md#装不上怎么办)。

## 使用说明

在海报上按确认，先看背景大图和简介，再按一次进详情页；也可以设置为直接进详情页或直接播放。在播放器以外，**长按返回键或播放键**打开动作面板。

播放时，常用操作都在这几个按键上：

<img src="docs/guide/images/remote-player.svg" alt="播放器遥控器指南：上键打开选源；确认键播放或暂停，按住默认 2.5 倍速、松开恢复；左右键快退或快进 5 秒，按住或连按预览进度，确认跳转、返回取消；下键显示进度条与控制按钮，方向键在按钮间移动；返回键逐层收起，最后退出播放，长按直接回到画面；播放键播放或暂停，预览时从该位置播放；独立快退、快进键切换上一集、下一集" width="780"/>

动作面板示意图、浏览流程和小提示见[使用说明](docs/guide/usage.md)。

## 常见问题

- [和 Animeko 有什么区别？](docs/guide/faq.md#和-animeko-有什么区别)
- [视频从哪里来？](docs/guide/faq.md#视频从哪里来)
- [连不上 Bangumi、手机上登录不了怎么办？](docs/guide/faq.md#连不上-bangumi-怎么办)
- [弹幕从哪里来？](docs/guide/faq.md#弹幕从哪里来)

## 参与开发

欢迎提交 PR。

- [Kotlin 多平台][Kotlin Multiplatform]架构，界面使用 [Compose Multiplatform][Compose Multiplatform]；电视界面在独立的 `app/shared/ui-tv` 模块
- 沿用上游基于 [libtorrent][libtorrent] 的 BT 引擎（为边下边播优化）和[多平台播放器](https://github.com/open-ani/mediamp)（Android 底层为 [ExoPlayer][ExoPlayer]）

开发说明见 [CONTRIBUTING](docs/contributing/README.md)，与上游的差异见 [FORK.md](FORK.md)。

## 许可证与致谢

Izuko TV 基于 [open-ani/animeko](https://github.com/open-ani/animeko) 修改而来，以 [GNU Affero General Public License v3.0](LICENSE.txt) 发布，与上游相同。

- 源代码中原有的版权声明（Copyright (C) OpenAni and contributors）均予保留；
- 相对上游的修改记录在本仓库的提交历史与 [FORK.md](FORK.md) 中，完整源代码即本仓库；
- 「Animeko」名称与图标归 OpenAni 所有，Izuko TV 使用自己的名称与图标，不代表上游。

规则源的步骤式写法参考了 [AniBaka](https://github.com/AniBakaBaka/AniBaka)，并可导入其规则格式（`anx-rule/2`）。规则归各自作者所有，Izuko TV 不附带任何第三方规则。

感谢 OpenAni 团队与所有 Animeko 贡献者。

[Bangumi]: http://bangumi.tv

[dmhy]: http://www.dmhy.org/

[Mikan]: https://mikanani.me/

[ddplay]: https://www.dandanplay.com/

[Kotlin Multiplatform]: https://kotlinlang.org/docs/multiplatform.html

[Compose Multiplatform]: https://www.jetbrains.com/compose-multiplatform/

[libtorrent]: https://libtorrent.org/

[ExoPlayer]: https://developer.android.com/media/media3/exoplayer
