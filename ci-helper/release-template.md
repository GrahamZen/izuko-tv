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

|                  | 下载                                               | 常见问题                                        |
|------------------|--------------------------------------------------|---------------------------------------------|
| 安卓 电视      | [主线](https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-universal.apk)       |                                             |

[github-android-qr]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-universal.apk.github.qrcode.png

<details>
<summary> Android 细分架构下载 </summary>

[//]: # (@formatter:off  因为"版本"前面不能换行)

优先下载与自己设备架构对应的安装包，体积更小、更省存储；不确定或装不上时再用 `universal`（包含全部架构，体积最大）。

[//]: # (@formatter:on)

| 处理器架构              | 适用于             | 下载                                                                                                      |
|--------------------|-----------------|---------------------------------------------------------------------------------------------------------|
| universal (推荐)     | 所有设备            | [GitHub][github-android]                                     |
| arm64-v8a (64 位)   | 几乎所有手机和平板      | [GitHub][github-android-arm64-v8a]       |
| armeabi-v7a (32 位) | 旧手机             | [GitHub][github-android-armeabi-v7a] |
| x86_64             | Chromebook 及模拟器 | [GitHub][github-android-x86_64]                |

</details>

## 本次更新

修复若干问题，给播放器增加两项遥控器操作，并重做了两个在电视上不好用的弹窗。

### 播放器

* **进度条拖拽预览**：画面上连按两次前进 / 后退键进入拖拽态——暂停播放、浮出进度条。确认键或播放键跳转并继续播放，返回键只收起界面、停在原来的位置。
* **长按确认键倍速播放**：播放器界面隐藏时长按确认键切到 2.5 倍速（倍率在设置里可调），松手恢复。
* **评论回复弹窗重做**：改成居中大弹窗，被回复的评论完整显示在输入框上方（含正文里的图片；太长可用上下键翻，翻到底再按下键进输入框），没有关闭按钮，返回键关闭后焦点回到刚点开的那条评论。发不出去的评论（Bangumi 的评论、楼中回复）不再给一个用不了的输入框，改成只读查看。
* **评论面板显示楼中回复**：主楼下面依次列出它的回复，缩进并用一条竖线连起来；回复的是同层另一条回复时，顶上标出「回复 某某」。

### 界面

* **评分弹窗重做（电视）**：居中大弹窗替代手机版的对话框，版式与手机版一致（图标、标题、分数、星星都居中）。星星是一整行，左右键加减一分；上下键依次走过评价正文、仅自己可见、确认。焦点不靠方框而是靠高亮色表现（星星换色、「仅自己可见」文字换色）。没有取消按钮——返回键就是取消（写过评价则先问一句是否放弃），关闭后焦点回到打开它的那个评分按钮。
* **播放器里进度条下方的选集卡片改成黑白配色**：与进度条旁的胶囊按钮一致（未聚焦半透明白底白字，聚焦即白底黑字，聚焦描边纯白）——卡片浮在画面上，彩色会跟画面本身抢注意力。详情页里的选集卡片不变。
* **详情页里没有缩略图的选集卡片改成半透明玻璃底**：原来是一块实心色块，把底下的背景图整块盖掉；现在透出背景，与同一页的标签、信息带按钮是同一种底。
* **选集条下方的简介不再自动滚动**：改成最多三行、放不下直接截断。左右切换聚焦集很频繁，一段自己在动的文字反而干扰。
* **电视上不再显示「取消」「关闭」这类按钮**：返回键已经是关闭的出口，这些按钮既多余又占掉一个焦点位（方向键要多走一格才能到确定 / 发送 / 删除上）。涉及播放器右侧各面板、更换弹幕、弹幕延迟、选集、评分、缓存与设置里的各个对话框；表示真实动作的「取消」（取消下载、取消收藏、退出多选）照旧保留。

### 修复

* 从进度条上方的面板里点开东西（回复评论、角色/制作人员预览、弹幕延迟调整、相关推荐的条目详情页）之后返回，焦点会消失、方向键全失效。现在焦点一律回到刚点开的那一条，且这些界面开着期间播放器控件不再自动隐藏。
* 详情页（含「查看全部」评论里的写评价入口）打开评分弹窗后按返回，焦点会消失。
* 电视上「更换弹幕」弹窗里的按钮不论有没有焦点都是主题色，看不出方向键停在哪个上：现在只有聚焦的那个是主题色。
* 电视上的居中弹窗（评分、评论列表、缓存等）在深色主题下标题与正文是黑字压深底。
* 32 位（armeabi-v7a）与 x86_64 设备自动更新后安装提示不兼容：更新器没有按设备架构挑安装包，一律下载了 arm64-v8a 那个。
* 部分新番的详情页背景图与屏保剧照一直不出来：新番刚开播时 TMDB 上往往还没有这些图，此前查过一次没有就会被永久记住，之后补了图也不会再取。
* 追番页把某个分类下的卡片改成其他收藏状态后，焦点会跳到第一个标签，而选中的分类还停在原处。
* 新版本提示气泡出现时焦点不会移出气泡。

### 设置

* 代理连通性测试增加 TMDB（详情页背景图与选集剧照的来源），并显示每一项的耗时。

> Android TV 遥控器使用说明、系统版本要求与已知问题，请见仓库 [README 的「📺 Android TV 版说明」](https://github.com/GrahamZen/animeko#-android-tv-版说明)。
