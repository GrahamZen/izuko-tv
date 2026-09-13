[//]: # (ANI-SERVER-MAGIC-SEPARATOR)

[//]: # (注意: api server 依赖这个特殊分隔符)

[//]: # (对于所有可用的变量列表, 参考 CI release.yml 的 step release-notes)

[github-win-x64]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-windows-x86_64.zip

[github-mac-x64]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-macos-x86_64.dmg

[github-mac-aarch64]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-macos-aarch64.dmg

[github-android]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-universal.apk

[github-android-arm64-v8a]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-arm64-v8a.apk

[github-android-armeabi-v7a]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-armeabi-v7a.apk

[github-android-x86_64]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-x86_64.apk

[github-android-legacy-arm64-v8a]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-legacy-arm64-v8a.apk

[github-android-legacy-armeabi-v7a]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-legacy-armeabi-v7a.apk

[github-android-legacy]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-legacy-universal.apk

[cf-win-x64]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION-windows-x86_64.zip

[cf-linux-x64]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION-linux-x86_64.appimage

[cf-mac-x64]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION-macos-x86_64.zip

[cf-mac-aarch64]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION-macos-aarch64.dmg

[cf-ios]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION.ipa

[cf-android]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION-universal.apk

[cf-android-arm64-v8a]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION-arm64-v8a.apk

[cf-android-armeabi-v7a]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION-armeabi-v7a.apk

[cf-android-x86_64]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION-x86_64.apk

[ghproxy-win-x64]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2FGrahamZen%2Fanimeko%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION-windows-x86_64.zip

[ghproxy-mac-x64]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2FGrahamZen%2Fanimeko%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION-macos-x86_64.zip

[ghproxy-linux-x64]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2FGrahamZen%2Fanimeko%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION-linux-x86_64.appimage

[ghproxy-mac-aarch64]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2FGrahamZen%2Fanimeko%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION-macos-aarch64.dmg

[ghproxy-ios]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2FGrahamZen%2Fanimeko%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION.ipa

[ghproxy-android]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2FGrahamZen%2Fanimeko%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION-universal.apk

[ghproxy-android-arm64-v8a]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2FGrahamZen%2Fanimeko%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION-arm64-v8a.apk

[ghproxy-android-armeabi-v7a]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2FGrahamZen%2Fanimeko%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION-armeabi-v7a.apk

[ghproxy-android-x86_64]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2FGrahamZen%2Fanimeko%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION-x86_64.apk

[macOS 无法打开解决方案]: https://myani.org/wiki/macos-unable-to-open

[Windows下字体与背景颜色异常解决方案]: https://myani.org/wiki/windows-font-bg-color-issue

[Linux 安装说明]: https://myani.org/wiki/linux-install

[macOS Intel芯片版本安装教程]: https://myani.org/wiki/macos-intel-install


[iOS 自签]: https://myani.org/wiki/ios-install

下方有 QQ 群二维码，也可以[点这里进群](https://qm.qq.com/q/JaXFdpv3mC)/搜索群号1045984894。入群问题的答案：$REPO_OWNER

## 下载

[//]: # (@formatter:off  因为"版本"前面不能换行)

优先下载与自己设备架构对应的安装包，体积更小、更省存储；不确定或装不上时再用 `universal`（包含全部架构，体积最大）。

[//]: # (@formatter:on)

| 处理器架构                | 适用于               | 下载                                                                                                      |
|---------------------|-------------------|---------------------------------------------------------------------------------------------------------|
| arm64-v8a | 64 位电视与电视盒子       | [GitHub][github-android-arm64-v8a]       |
| armeabi-v7a   | 32 位电视与电视盒子             | [GitHub][github-android-armeabi-v7a] |
| x86_64              | x86 电视盒子及模拟器      | [GitHub][github-android-x86_64]                |
| universal           | 所有设备（不确定架构时选这个）   | [GitHub][github-android]                |
| legacy arm64-v8a    | Android 7.1 ~ 8.0 的 64 位盒子 | [GitHub][github-android-legacy-arm64-v8a] |
| legacy armeabi-v7a  | Android 7.1 ~ 8.0 的 32 位盒子 | [GitHub][github-android-legacy-armeabi-v7a] |
| legacy universal    | Android 7.1 ~ 8.0，不确定架构时选这个 | [GitHub][github-android-legacy] |

[github-android-qr]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-universal.apk.github.qrcode.png

### Android 7.1 兼容包

表格前四行要求 Android 8.1 及以上。装不上并提示 `INSTALL_FAILED_OLDER_SDK` 的话，改用带 `legacy` 的后三行，它们支持到 Android 7.1。

兼容包和正式包功能一致，但只在少量设备上验证过；能装正式包就别用它。另外 Android 7.1 ~ 8.0 上 BT 引擎跑在应用进程内，退到后台被系统回收后下载不会保活。

## 本次更新


- 新番时间表改版：左边显示选中那一部的封面、评分、标签和简介，背景换成它的横版大图；右边按播出时间一行一部、字更大，你追的番标心形；进页直接落在今天你追的下一部，左右键直接换天
- 「手机遥控」改名「Web 控制台」，手机、电脑的浏览器都能打开；数据源名字前显示图标
- Web 控制台的搜索结果、播放记录和缓存列表可以左右滑出缓存、收藏、删除按钮，滑到底直接执行；播放记录和订阅可长按多选一起删除；右上角「?」可以查看各页的使用说明
- 点卡片进详情页的放大动画改成更跟手的节奏，画面停稳前按钮就已出现，停稳即可直接操作；视觉效果选「流畅」时也有放大
- Web 控制台可以用邮箱登录或注册 Animeko 账号，已登录时可绑定或更换邮箱
- Web 控制台的缓存页可以直接从在看 / 想看里挑番缓存，在看的番会标出有几集更新、几集还没缓存
- 新番时间表打开更快：在首页停一下再进，基本一打开就有内容
- 列表页换背景、进详情页和片尾「接下来播放」更省性能，4K 界面和配置较低的电视上更流畅

----

- 修复从新番时间表返回首页时焦点跳到「立即观看」上，一按确认就误进了轮播那部的详情页
- 修复内存较小的设备（如 1GB 左右的投影仪）看海贼王、火影等长番时，加载或换集闪退
- 修复部分电视因系统里自带了同名组件，在线源全部搜不到结果或一打开播放器就闪退
- 修复部分在线源的页面里混有无效链接时，整个源搜不到结果

### 已知问题

* **画质增强（设置 - 播放 - 默认画质增强）在电视上不可用，建议保持「关」**：NVIDIA Shield 上开启后只有声音没有画面；部分机型能播但严重掉帧（实测 24fps 的片只出 6fps 左右）并很快卡住，之后连不带增强的视频也可能起不来，需要强制停止应用恢复。

> Android TV 遥控器使用说明、系统版本要求与已知问题，请见仓库 [README 的「📺 Android TV 版说明」](https://github.com/GrahamZen/animeko#-android-tv-版说明)。

## 交流群

使用中遇到问题、想提建议或反馈 bug，[欢迎进群](https://qm.qq.com/q/JaXFdpv3mC)，或用手机 QQ 扫下面的二维码/搜索群号1045984894。
入群问题的答案：$REPO_OWNER。

![加入 QQ 反馈群](https://quickchart.io/qr?text=https%3A%2F%2Fqm.qq.com%2Fq%2FJaXFdpv3mC&size=200&margin=2&ecLevel=M&dark=000000&light=ffffff)
