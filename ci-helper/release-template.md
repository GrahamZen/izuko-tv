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

有问题想反馈？本页最下方有 QQ 群二维码，也可以直接[点这里进群](https://qm.qq.com/q/JaXFdpv3mC)。

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

重做了评论和评分的弹窗，调整了播放器的部分不一致的外观，适配了一起看功能和多选数据源功能。

### 播放器

* 评论回复弹窗重做，不可回复的评论不再显示输入框并增加只读提示。
* 评论面板显示楼中回复。

### 界面

* 电视上不再显示「取消」「关闭」这类按钮：表示真实动作的「取消」（取消下载、取消收藏、退出多选）照旧保留。

### 修复

* 设置页左右两栏互相记住焦点停留位置：从右侧按左键回到左栏上次停留的项（首次回到当前选中分类），从左栏按右键回到右侧上次停留的设置项；滑块上按返回键也可回到左栏。同时移除了电视上无法使用的分栏宽度拖动把手，右侧设置项不再套一层圆角卡片，直接铺在页面背景上。
* 修复进播放器显示假倍速：界面上写着 1.25x 而实际是原速（记住的倍速一直是假的，手动调一次才真的生效）。两个原因：倍速原本在后台线程应用，ExoPlayer 拒绝跨线程访问直接抛异常，而显示值在抛异常前就已经改了，那个同步任务也就此挂掉；另外倍速是在起播前下发的，音频管线要到起播时才按新资源建立，建好后没人再把倍速交给它。现在起播后会补发一次。
* 播放器里调倍速现在会记住（与手机端一致）：之前电视上改完不写回设置，切集或重新起播就被弹回原值。
* 移除电视上的「倍速范围」设置：两个滑块端点在遥控器上根本调不动，而它又会顺带改掉常驻倍速与长按倍速（范围调回来这两个值不会还原），是上面那个假倍速的源头。倍速范围现在固定为播放器支持的全范围 0.25x–4x（播放器里左右键可调的档位更多，长按倍速也能设更高），不再读旧配置，以前被改窄过也不受影响。
* 「选择数据源后自动关闭弹窗」这条设置以前根本没接线：不管开还是关，点任何一个数据源都会把选择器关掉。现在按设置来，默认（关）是选完留在选择器里，换的源不合适可以当场再点一个，不必重新唤出面板；打开它才恢复「选完即关」。播放器里的数据源面板与详情页的数据源弹窗都遵守这条。
* 后台「已加载好」的提示改为等真的开播了才发，回去就能直接看：以前拿到播放地址就提示，而播放器还要取容器头、建解码器、缓冲首帧（慢的源要十几秒），回去仍是黑屏、进度条右边还是 0:00。同时修掉一处抢资源：回到播放页时进度条缩略图的预热会立刻另起一路解码去读同一个远程文件（它假设播放位置附近的数据一定已缓冲好，而此时播放器才刚开始取文件头），现在改为等起播之后再预热。
* 退出播放页后台继续加载数据源时不再报「加载失败：未知错误」：网页数据源解析要靠界面挂载的 WebView，播放页一销毁就解析不了，会把所有源试一遍全部失败。
* 手动匹配的弹幕源不再因改设置而丢失，弹幕时间轴调整立即生效。
* Toast 配色跟随应用主题。
* 追番页不再出现封面反复变回灰色占位的闪烁，也不会因此把焦点弹回顶部标签。
* 快速翻动选集或封面时，个别卡片加载失败后不再一直空着，会自动重试。

> Android TV 遥控器使用说明、系统版本要求与已知问题，请见仓库 [README 的「📺 Android TV 版说明」](https://github.com/GrahamZen/animeko#-android-tv-版说明)。

## 问题反馈群

使用中遇到问题、想提建议或反馈 bug，[欢迎进群](https://qm.qq.com/q/JaXFdpv3mC)，或用手机 QQ 扫下面的二维码。

入群问题的答案是本仓库作者的 GitHub 用户名：$REPO_OWNER

![加入 QQ 反馈群](https://quickchart.io/qr?text=https%3A%2F%2Fqm.qq.com%2Fq%2FJaXFdpv3mC&size=200&margin=2&ecLevel=M&dark=000000&light=ffffff)
