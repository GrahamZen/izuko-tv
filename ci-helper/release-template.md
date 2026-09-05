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

[github-android-qr]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-universal.apk.github.qrcode.png

## 本次更新


- 遇到问题、想提建议？欢迎加入 QQ 交流群，二维码与群号在最下方
- 新增退出播放页后保留播放状态：数据源、播放流、进度都还在，侧边栏「正在播放」一键回去，后台加载好或卡住会提示
- 新增遥控器长按返回 / 长按播放键弹出的动作面板：正在播放（或接下来播放）卡、一起看等圆钮、服务连通检测
- 电视端卡片导航改为「聚焦框不动、卡片滑动」，聚焦效果统一为主题色描边
- 跳过 OP/ED 改为四档可选：自动跳过 / 自动跳过且保留按钮 / 只显示按钮 / 不跳过，电视上按钮在进度条上方
- 评论支持显示 bangumi 表情与图片，可在播放器里发本集评论并插入表情
- 在线源缓存接入播放器的数据源菜单，没下完显示为不可用，下完即可选择
- 电视端搜索页新增筛选入口，不输入关键词也能直接按标签、评分、排序查看结果
- 电视端在选集条上换集时，焦点自动跟到新的当前集
- 新增电视端一集快播完时弹出的「接下来播放」卡片：ED 结束（没有标记则距结尾一段时间）显示下一集的预览图与标题，倒计时结束自动播放，确定键立刻播、返回键收起，时间与剧照可在设置里调，也可关闭
- 电视端播放器把「弹幕列表」与「发送弹幕」合并成一个「弹幕」按钮：点击即发，往上导航仍是弹幕列表
- 电视端设置里隐藏「动态渐变背景」开关：电视用的新详情页上它不生效，开了只是白耗性能
- 电视端播放器里的角色、制作人员、人物预览等弹窗改为半透明，能看到后面的画面还在放

----

- 修复国内直连 TMDB 失败导致加载不出背景图，优化背景图、剧照与条目的匹配
- 修复在线源缓存下载时整机卡顿、遥控器按键没反应
- 修复从人物、关联条目页返回时退回页面顶部，现在回到离开前的位置
- 修复刚开机时订阅更新赶在网络就绪前失败，一小时内不再重试，表现为只剩磁力源
- 修复部分电视盒子的系统组件顶掉应用内网页解析库，导致在线源全部搜索失败
- 修复没下完的缓存被当成可播资源自动选中
- 修复删除正在播放的那一集缓存后播放报「未知错误」，删除前会先提示
- 修复缓存的误删与删除不生效，合并期间显示「合并中」而不是停在「下载中 100%」
- 修复探索页「继续观看」改了收藏后卡片一直不更新
- 修复在另一台设备或另一个安装里改了收藏后，本机「继续观看」与电视主屏「接下来播放」还挂着已看完的番
- 修复下一集是特别篇（SP、OVA）时「下一集」按钮消失、不自动连播
- 修复假倍速（显示 1.25x 实际原速）与长按倍速松手后残留
- 修复跳过 OP 后画面被拉回上次进度、倍速播放时常跳不过去、提示只给一次
- 修复暂停后按 Home 键离开时播放器有时会自己在后台出声
- 修复电视端长按选集卡看完本集详情、关掉弹窗后聚焦框消失，要再按一下方向键焦点才回来
- 修复电视端进入详情页时配色先是主题色、随后跳成动态色，缓存命中的作品现在进去就是最终配色
- 部分修复画面偶发变成假 HDR
- 修复电视上「分享日志文件」闪退，改为「导出当日日志文件」可存到 U 盘
- 修复网页数据源上单集作品（剧场版等）因集号解析失败搜不到资源
- 修复换过几个 WEB 数据源后应用越用越卡
- 优化进详情页与卡片背景的加载速度，相邻卡片背景自动预加载
- 没有横版背景图的作品改用竖版封面顶上，详情页背景图往下保留更多
- 播放器各层压暗遮罩整体调亮
- 播放器上方显示数据源对应的条目名，和在看的作品对不上时给黄色提示
- 播放器选集条把特别篇按序号插在正片之间
- 电视端选集条展开着时不再 5 秒自动收起，与面板、弹窗一致
- 长按左右键挪进度改为越按越快，按住直接进入拖动预览
- 「角色」和「制作人员」可打开「查看全部」大网格，卡片长按放大看图
- 更新提示的「查看详情」改为应用内弹窗，Markdown 正常渲染
- 首页按返回改为三选一：直接退出 / 弹出动作面板 / 连按两次退出（默认）
- 新增后台会话提示音（音色可选、可关闭），移除电视上调不动的「倍速范围」设置
- 设置 - 网络的 TMDB 连通性拆成「背景图接口」与「背景图 CDN」两项
- 同步上游：评论改由服务端合并下发、新缓存管理页、BT 额外 trackers、PikPak HTTPS 缓存 BT、Jellyfin/Emby 账号密码登录、移除新手引导向导、mediamp 0.3.0 与 Kotlin 2.4.10 / Compose 1.11.1

### 已知问题

* **画质增强（设置 - 播放 - 默认画质增强）在电视上不可用，建议保持「关」**：NVIDIA Shield 上开启后只有声音没有画面；部分机型能播但严重掉帧（实测 24fps 的片只出 6fps 左右）并很快卡住，之后连不带增强的视频也可能起不来，需要强制停止应用恢复。

> Android TV 遥控器使用说明、系统版本要求与已知问题，请见仓库 [README 的「📺 Android TV 版说明」](https://github.com/GrahamZen/animeko#-android-tv-版说明)。

## 交流群

使用中遇到问题、想提建议或反馈 bug，[欢迎进群](https://qm.qq.com/q/JaXFdpv3mC)，或用手机 QQ 扫下面的二维码/搜索群号1045984894。
入群问题的答案：$REPO_OWNER。

![加入 QQ 反馈群](https://quickchart.io/qr?text=https%3A%2F%2Fqm.qq.com%2Fq%2FJaXFdpv3mC&size=200&margin=2&ecLevel=M&dark=000000&light=ffffff)
