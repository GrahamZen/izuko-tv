[//]: # (迁移跳板包的 release 说明模板. scripts/tag-migration-bridge.sh 把它换成跳板 tag 上的 ci-helper/release-template.md ——)

[//]: # (老包走镜像回落时读的就是 tag 上的那份模板, 只在 CI 里换的话它们看到的是新包的说明. 变量与 release-template.md 相同.)

[//]: # (老包的更新气泡只显示「## 本次更新」之后的前 4 行, 所以最要紧的话放在那 4 行里.)

[bridge-arm64-v8a]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-arm64-v8a.apk

[bridge-armeabi-v7a]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-armeabi-v7a.apk

[bridge-x86_64]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-x86_64.apk

[bridge-universal]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-universal.apk

[bridge-legacy-arm64-v8a]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-legacy-arm64-v8a.apk

[bridge-legacy-armeabi-v7a]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-legacy-armeabi-v7a.apk

[bridge-legacy-universal]: https://github.com/GrahamZen/animeko/releases/download/$GIT_TAG/ani-$TAG_VERSION-legacy-universal.apk

这是改名前的旧版应用（Animeko）的**最后一个版本**，作用只有一个：把你带到改名后的新应用「Izuko TV」。

## 下载

只有还在用旧版、应用内更新又用不了的时候，才需要手动下载这里的安装包。**新用户请直接安装新应用**：到 [Izuko TV 的 Releases 页面](https://github.com/GrahamZen/izuko-tv/releases/latest) 下载。

| 处理器架构                | 适用于               | 下载                                  |
|---------------------|-------------------|-------------------------------------|
| arm64-v8a           | 64 位电视与电视盒子       | [GitHub][bridge-arm64-v8a]          |
| armeabi-v7a         | 32 位电视与电视盒子       | [GitHub][bridge-armeabi-v7a]        |
| x86_64              | x86 电视盒子及模拟器      | [GitHub][bridge-x86_64]             |
| universal           | 所有设备（不确定架构时选这个）   | [GitHub][bridge-universal]          |
| legacy arm64-v8a    | Android 7.1 ~ 8.0 的 64 位盒子 | [GitHub][bridge-legacy-arm64-v8a]   |
| legacy armeabi-v7a  | Android 7.1 ~ 8.0 的 32 位盒子 | [GitHub][bridge-legacy-armeabi-v7a] |
| legacy universal    | Android 7.1 ~ 8.0，不确定架构时选这个 | [GitHub][bridge-legacy-universal]   |

## 本次更新
- 应用改名为 Izuko TV。这一版是过渡版本：更新后在右下角的提示里点「自动更新」，按说明安装新应用
- 新应用第一次打开时会自动带上现在的设置、数据源与订阅、播放记录和续播位置、弹幕屏蔽规则和已缓存的视频；它改为直接连接 Bangumi，Bangumi 要重新登录一次
- 新应用搬完缓存之前，请不要卸载旧版，也不要在旧版里删除或新建缓存；还没下载完的网页缓存不会搬过去
- 搬完缓存会自动重启一次并提示卸载旧版，然后引导你选择连接 Bangumi 的方式并登录；之后的更新都在新应用里收

新应用是一个单独安装的应用，装好后主屏幕上会多出「Izuko TV」，旧版会显示为「Animeko（旧版）」，卸载之前两个会同时存在。收藏和观看进度存在 Bangumi 上，不受影响。

用邮箱登录过 Animeko 账号、没有连接 Bangumi 的话，收藏只存在 Animeko 服务器上，新应用里看不到：迁移前先用手机打开 Web 控制台，在「设置」最上面的账号里（电视上没登录的先用邮箱登录）点「导出收藏」，把文件存在手机上；新应用更新到 1.0.5 或更高版本后，在它的 Web 控制台里导进本地用户。

## 交流群

使用中遇到问题、想提建议或反馈 bug，[欢迎进群](https://qm.qq.com/q/JaXFdpv3mC)，或用手机 QQ 扫下面的二维码/搜索群号1045984894。
入群问题的答案：$REPO_OWNER。

![加入 QQ 反馈群](https://quickchart.io/qr?text=https%3A%2F%2Fqm.qq.com%2Fq%2FJaXFdpv3mC&size=200&margin=2&ecLevel=M&dark=000000&light=ffffff)
