$RELEASE_NOTES

[//]: # (ANI-SERVER-MAGIC-SEPARATOR)

[//]: # (注意: api server 依赖这个特殊分隔符)

[//]: # (对于所有可用的变量列表, 参考 CI release.yml 的 step release-notes)

[github-win-x64]: https://github.com/open-ani/ani/releases/download/$GIT_TAG/ani-$TAG_VERSION-windows-x86_64.zip

[github-win-aarch64]: https://github.com/open-ani/ani/releases/download/$GIT_TAG/ani-$TAG_VERSION-windows-aarch64.zip

[github-mac-x64]: https://github.com/open-ani/ani/releases/download/$GIT_TAG/ani-$TAG_VERSION-macos-x86_64.dmg

[github-mac-aarch64]: https://github.com/open-ani/ani/releases/download/$GIT_TAG/ani-$TAG_VERSION-macos-aarch64.dmg

[github-android]: https://github.com/open-ani/ani/releases/download/$GIT_TAG/ani-$TAG_VERSION-universal.apk

[github-android-arm64-v8a]: https://github.com/open-ani/ani/releases/download/$GIT_TAG/ani-$TAG_VERSION-arm64-v8a.apk

[github-android-armeabi-v7a]: https://github.com/open-ani/ani/releases/download/$GIT_TAG/ani-$TAG_VERSION-armeabi-v7a.apk

[github-android-x86_64]: https://github.com/open-ani/ani/releases/download/$GIT_TAG/ani-$TAG_VERSION-x86_64.apk

[cf-win-x64]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION-windows-x86_64.zip

[cf-win-aarch64]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION-windows-aarch64.zip

[cf-linux-x64]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION-linux-x86_64.appimage

[cf-mac-x64]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION-macos-x86_64.zip

[cf-mac-aarch64]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION-macos-aarch64.dmg

[cf-ios]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION.ipa

[cf-android]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION-universal.apk

[cf-android-arm64-v8a]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION-arm64-v8a.apk

[cf-android-armeabi-v7a]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION-armeabi-v7a.apk

[cf-android-x86_64]: https://d.myani.org/$GIT_TAG/ani-$TAG_VERSION-x86_64.apk

[ghproxy-win-x64]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2Fopen-ani%2Fani%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION-windows-x86_64.zip

[ghproxy-win-aarch64]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2Fopen-ani%2Fani%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION-windows-aarch64.zip

[ghproxy-mac-x64]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2Fopen-ani%2Fani%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION-macos-x86_64.zip

[ghproxy-linux-x64]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2Fopen-ani%2Fani%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION-linux-x86_64.appimage

[ghproxy-mac-aarch64]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2Fopen-ani%2Fani%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION-macos-aarch64.dmg

[ghproxy-ios]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2Fopen-ani%2Fani%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION.ios

[ghproxy-android]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2Fopen-ani%2Fani%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION-universal.apk

[ghproxy-android-arm64-v8a]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2Fopen-ani%2Fani%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION-arm64-v8a.apk

[ghproxy-android-armeabi-v7a]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2Fopen-ani%2Fani%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION-armeabi-v7a.apk

[ghproxy-android-x86_64]: https://ghfast.top/?q=https%3A%2F%2Fgithub.com%2Fopen-ani%2Fani%2Freleases%2Fdownload%2F$GIT_TAG%2Fani-$TAG_VERSION-x86_64.apk

[macOS 无法打开解决方案]: https://myani.org/wiki/macos-unable-to-open

[Windows下字体与背景颜色异常解决方案]: https://myani.org/wiki/windows-font-bg-color-issue

[Linux 安装说明]: https://myani.org/wiki/linux-install

[macOS Intel芯片版本安装教程]: https://myani.org/wiki/macos-intel-install

[macos-intel-issue]: https://github.com/open-ani/animeko/issues/1345

[linux-issue]: https://github.com/open-ani/animeko/issues/944

[iOS 自签]: https://myani.org/wiki/ios-install

## 下载

[//]: # (@formatter:off  因为"版本"前面不能换行)

优先下载与自己设备架构对应的安装包，体积更小、更省存储；不确定或装不上时再用 `universal`（包含全部架构，体积最大）。

[//]: # (@formatter:on)

| 处理器架构                | 适用于               | 下载                                                                                                      |
|---------------------|-------------------|---------------------------------------------------------------------------------------------------------|
| arm64-v8a (64 位, 推荐) | 几乎所有电视与电视盒子       | [GitHub][github-android-arm64-v8a]       |
| armeabi-v7a (32 位)   | 旧电视盒子             | [GitHub][github-android-armeabi-v7a] |
| x86_64              | x86 电视盒子及模拟器      | [GitHub][github-android-x86_64]                |
| universal           | 所有设备（不确定架构时选这个）   | [GitHub][github-android]                |

[github-android-qr]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-universal.apk.github.qrcode.png

## 本次更新

修复国内用户直连TMDB失败因此加载不出背景的问题。为**长按返回**增加一个动作面板；另外修了一批播放、缓存和图片加载的问题。

### 遥控器：长按手势与动作面板

* 长按播放键现在也是打开动作面板。
* **首页按返回的行为改成三选一**（设置-界面）：直接退出 / 弹出动作面板 / **连按两次退出**（默认）。
* 面板的播放卡**没有后台播放时变成「接下来播放」** ：支持直接进播放器/后台准备播放（都没有为空）。
* 卡片右端那一条现在是**后台播放的开关**：正在播时是 ✕（关掉它），没有在播时是**「在后台准备这一集」**——不进播放器，先让它在后台把数据源找好、缓冲上，你接着浏览，好了照常提示。慢的源要十几秒，这样就不用进去干等了。
* 侧边栏**「正在播放」图标**单独恢复（关闭功能删除）：图标本身在后台还在找源/缓冲时会跳动，就绪后静止，出问题变色。
* **角色与制作人员的卡片长按可以放大看图**：条目详情页、「查看全部」大网格、播放器里那两份都支持，返回键关掉。

### 播放器

* 「角色」和「制作人员」按下确定打开详情页同款「查看全部」大网格。
* 部分修复**画面偶发变成假 HDR**问题，再遇到可反馈。

### 其他

* 更新提示里的**「查看详情」现在按 markdown 显示**：加粗、链接、行内代码、图片都能正常渲染，不再是一堆星号和方括号（图片走的是评论区那条加载链路）。
* **设置、缓存管理、播放历史这类页面在返回栈里只留一份**：以前从动作面板反复跳设置会一层层叠上去，返回要按很多次才退得出来；条目详情、人物这些内容页不受影响（来回浏览的历史要留着）。
* 相邻卡片背景图会自动预加载。
* 退出播放页后短时间内再回去，进度条缩略图不再从头重建（以前每进一次都要另起一个播放器解析一遍，既卡切页动画又占内存；反复快进快出时甚至会把应用撑到被系统杀掉）。
* 后台加载好的默认提示音换成更轻的一声。

### 修复

* **修正一批作品的背景图、剧照与单集简介配错成同系列的另一部作品**。比如高达ZZ 拿的是初代高达（1979）的图、47 集剧照全空；攻壳机动队 1995 剧场版拿的是 2026 年新番的图；闪光的哈萨维 第3部 也拿成了初代高达；Fate/stay night UBW 第二季拿的是 2006 年那部旧改编。升级后会自动重新匹配，不用清数据。
* **重制版不再被老版本顶掉**。福星小子（2022 重制版及其第二季）、猎人（2011）、鬼太郎（1971/1985/1996/2018 各版）、足球小将（2001）等，此前一律取到同名的最老那一版的图。
* **重制版、复播版与「次篇」这类条目的选集卡片也有图了**。它们在 TMDB 上要么是个只有集号和时长的空壳（高达SEED HD重制版，48 集一张图都没有）、要么根本搜不到（高达SEED DESTINY HD重制版、剑风传奇 次篇）。现在会去原作对应的那一季取图，按集名逐字对位——两边给汉字标读音的方式不一致（「宇宙(そら)の傷跡」vs「宇宙の傷跡」）也能对上；个别集两边差一个字的（高达SEED 第 10 集 Bangumi 少打了一个假名、柯南第 662 集、混沌武士第 19 集），靠前后两集的集号夹出来。
* **「总集篇」「序章」「特别版」「第一季」这类条目也能找到图了**。以前只在名字尾部是英文副标题时才会去掉尾词重搜，日文/中文的尾词一律不动，于是剑风传奇 次篇、高达SEED FREEDOM 特别版、鬼太郎诞生 真生版、凡人修仙传 星海飞驰篇 序章、十万个冷笑话 第一季、甲铁城的卡巴内利 总集篇 等等要么退回系列主条目（常常是几十年前的初代），要么干脆无图。现在从最后一个空格起逐词去掉尾词再搜——实测 350 个条目里救回 21 个条目的背景图和 31 集剧照，且没有一处把原本对的图换掉。
* **部分作品终于有分集剧照了**。两种此前一张图都取不到的情况：分集在 Bangumi 上没有播出日期、而作品在 TMDB 上是多季合并的（如南家三姐妹 再来一碗／欢迎回来）；以及剧场版——这条链路以前只认剧集不认电影，所以攻壳机动队 1995、千与千寻、你的名字、剧场版咒术回战 0、逆袭的夏亚、EVA 剧场版 Air／真心为你 等都拿不到画面和时长，现在会取自己那部电影的。
* **从详情页返回时不会再「结果全没了」**。返回搜索结果、追番列表或首页时，焦点要先回到你原来停的那张卡（越靠后的卡越久，实测 0.3~2 秒）；这段时间里按返回，此前会被当成「焦点不在卡片上」而交给上一层——搜索页退回只剩搜索框（看起来就是结果全丢了）、追番页一步弹回首页、在首页则直接弹出退出确认。现在这段时间里的返回键归卡片区。
* **从播放器点开人物、进人物全屏页再返回，播放不会停在暂停上了**。进那一页仍会临时暂停（画面本来就看不见），返回时按你进去之前的样子还回来：之前在播就接着播，之前是你自己按的暂停就保持暂停。

> Android TV 遥控器使用说明、系统版本要求与已知问题，请见仓库 [README 的「📺 Android TV 版说明」](https://github.com/GrahamZen/animeko#-android-tv-版说明)。
