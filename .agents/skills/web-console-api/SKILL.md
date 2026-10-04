---
name: web-console-api
description: 用 HTTP 操作 Izuko TV (安卓电视上的追番应用) 的 Web 控制台。拿到控制台地址 (http://IP:端口/<token>/) 就能用 curl 调接口，不需要源码或 adb：看电视在播什么、播放控制、换源换集、弹幕、搜番开播、播放记录、收藏、缓存、数据源与网盘、设置、账号、多用户、应用更新、日志与诊断；debug 包另有调试接口 (查改数据库、拦截或统计请求、调试开关)。
---

# Izuko TV Web 控制台接口说明书

Izuko TV 在电视上开一个局域网 HTTP 服务，叫 Web 控制台。用户在手机或电脑浏览器里打开的控制台网页，背后调的就是下面这些接口；网页能做的事，用 curl 都能做。

这份说明书写给只拿到控制台地址的程序或 AI agent。各接口的方法、参数和返回字段都按应用代码整理。电视上的应用版本比说明书新时，可能有接口对不上：遇到 405，或返回里有说明书没写的字段，以实际返回为准。

## 地址

- 地址形如 `http://192.168.1.23:41892/AbCdEf1234/`，最后一段是 token。下文 `<地址>` 指这一整串，结尾带 `/`；接口地址是 `<地址>api/...`。
- **token 相当于密码**：有了它就能操作这台电视和上面登录的账号。不要把它写进报告、提交、日志或公开的地方；给人看时打码。
- 地址由用户给。电视上能找到它的地方：长按播放键弹出的面板右上角二维码、应用启动时弹出的二维码、设置里标着「在手机上」的行。
- 端口按应用包固定：正式包 41892，debug 包 41893，其他包名 41894~41898。端口被占时会临时换成随机端口。token 一直不变，用户在电视上「重置地址」或重装应用后才换。电视的 IP 可能随路由器分配变化。
- 只有同一局域网能连上。电视或你这边开着 VPN (TUN 模式) 时可能连不上。
- 先确认能用：`curl -s "<地址>api/notice"` 返回 JSON 就是通的。返回 404，说明 token 不对或地址已重置，要向用户要新地址。连不上，说明是网络问题：不在同一网段、VPN、电视关机，或应用没在运行。

## 请求

- GET 请求 (HEAD 同 GET) 的参数放在查询串里。POST 请求的参数用 `application/x-www-form-urlencoded` 表单，UTF-8 百分号编码。同一个参数要传多个值 (比如多个标签) 就把这个键重复写几次。少数接口另外说明了参数格式 (逗号分隔的 id 列表、JSON 字符串、上传原始字节)。
- 中文和特殊字符一律让 curl 编码：POST 用 `--data-urlencode "q=葬送的芙莉莲"`；GET 用 `-G --data-urlencode ...`。Windows 命令行可能把中文转坏 (表现为「这个数据源已不在列表里」「这条缓存已经不在了」之类)，这时把值写进 UTF-8 文件，用 `--data-urlencode "key@文件"` 传。
- 请求体默认最大 64 KB，超过回 413。上传安装包分块、导入设置备份、反馈照片这几处上限更大，见各自的接口。
- 每个请求处理完就断开连接 (`Connection: close`)。电视同时最多处理 16 个请求，满了回 503，稍后重试即可。

## 响应

- 大多数接口返回 JSON (`application/json; charset=utf-8`)。写操作大多返回 `{"ok": true/false, "message": "…"}`，下文简写为「返回 ok/message」。成败看 `ok`。`message` 是给人看的提示，跟着电视上应用的语言 (简体、繁体或英文)，下文引用的都是简体原文。
- 也有接口返回图片、视频、字幕或文件，各接口会写明。
- 状态码：
  - 200：接口处理了请求。业务上失败也是 200，带 `ok:false`。
  - 400：参数或路径编码不对。
  - 404：token 不对、地址已重置，或请求的资源不存在。
  - 405：token 对，但这个路径或方法不认识 (比如该用 POST 时用了 GET)。
  - 413：请求体太大。
  - 503：电视忙或排不上队，稍后重试。
- 轮询：网页大约每 1.5 秒轮询一次 `api/player`，每 2 秒轮询一次 `api/notice`。带版本号的状态接口 (`api/player`、`api/search/results`) 在内容没变时只返回 `same:true`，照各接口说明把上次的 `v` 带上。不要比网页轮询得更勤，同一台电视上可能还开着用户的网页。

## 电视界面与前后台

- 会让电视换页面的操作 (搜索、开播、打开详情、从播放记录或缓存开播、回到播放器) 会同时收起电视上的面板和二维码弹窗。应用界面已经退出但进程还在时，如果用户开启并授权了「从手机打开 Izuko」(`POST api/settings/front`)，会先拉起应用 (最多等 10 秒)；否则返回 `ok:false` 和一段说明。
- 播放器上的操作 (播放控制、换源、换集、改搜索条件、音轨、弹幕) 在应用不在前台时，会先把它叫回前台，最多等约 5 秒。电视息屏、在屏保里或切到了别的应用，都可以从 `GET api/notice` 的 `away` 看出来；`POST api/tv/front` 能把应用切回前台。
- 后台保留的播放会话 (用户退出播放页后还留着的那一集) 能换源、换集，但不接受播放控制。

## 使用规矩

- 这些接口改的是用户的真实数据：收藏、评分、播放记录、缓存、数据源、设置、账号和用户档，以及电视上正在播的内容。只做用户要你做的事。删除、退出登录、切换或删除用户、装包、换电视迁移，还有调试区的改数据库和拦截网络，动手前先征得用户同意。
- 用户的手机网页和遥控器可能同时在操作这台电视，两次调用之间状态可能已经变了。操作前先读一次状态，操作后再读一次确认。
- 密码、cookie、令牌之类只写不读的字段，不要回显给别人。

## 常用流程

下面的例子先设 `A='http://IP:端口/<token>/'`。

1. 看电视在干什么：
   ```sh
   curl -s "${A}api/notice"      # away = 应用不在前台; user = 当前用户档
   curl -s "${A}api/player"      # available / title / episode / selectedSource / playback{playing, position, duration}
   ```
   `available:false` 时，`reason` 为 `none` 表示没在播；为 `background` 表示有后台会话，可以用 `POST api/player/open` 接回来。
2. 播放控制：
   ```sh
   curl -s -X POST -d action=pause "${A}api/player/control"
   curl -s -X POST -d action=seek -d ms=600000 "${A}api/player/control"   # 跳到 10:00
   curl -s -X POST -d action=speed -d v=1.5 "${A}api/player/control"
   ```
   成功时 `message` 为空，结果看下一次 `GET api/player` 里的 `playback`。
3. 换源与换集：从 `GET api/player` 的 `groups[].items[].id` 选一条，`POST api/player/select id=…`；从 `episodes[].id` 选一集，`POST api/player/episode id=…`。换源会像在电视上点选一样记住偏好。
4. 搜一部番并开播：
   ```sh
   curl -s -X POST --data-urlencode "q=葬送的芙莉莲" "${A}api/search"
   curl -s "${A}api/search/results"          # 一直轮询到 pending=false 且 refreshing=false
   curl -s -X POST -d id=<条目id> "${A}api/search/play"
   ```
   已经知道 Bangumi 条目 id 时，可以直接 `api/search/play` 或 `api/search/open`。
5. 按播放记录接着看：`GET api/history` 找到条目 `id`，然后 `POST api/history/play id=…`。
6. 缓存几集：`GET api/cache?subject=<条目id>` 拿剧集 id，然后 `POST api/cache/auto subject=… episodes=101,102` 自动挑资源，用 `GET api/cache` 的 `batch` 看进度。想自己挑资源，走 `GET api/cache/candidates` → `POST api/cache/pick`。已有的全部缓存用 `GET api/caches` 查看。
7. 改设置：`GET api/settings` 里列着能改的通用设置项 (`key`、输入方式、当前值、可选值)，用 `POST api/settings/generic/set key=… value=…` 改。代理、Bangumi 地址等有专门的接口，见「设置、账号与用户」。
8. 数据源：`GET api/sources` 拿实例 id，然后启用或停用、调整顺序、测试；数据源订阅在 `api/sources/subs`。

## 章节

- 页面与通用：控制台网页、电视状态提示、启动弹窗、切回前台、图片转发、数据源图标、诊断日志回传
- 播放器：状态、播放控制、换源、换集、改搜索条件、音轨字幕、弹幕、收藏评分
- 播放器附加功能：截图、同步播放到别的设备、播放失败报告、网盘分享链接、从网盘挑文件
- 搜索、播放记录、收藏与时间表
- 缓存：给一部番挑集缓存、自动批量缓存，已有缓存的暂停、继续、删除、播放
- 数据源与网盘：数据源的启用、排序、测试、导入导出、订阅；网盘、WebDAV、本地文件夹
- 设置、账号与用户：代理、Bangumi 地址、通用设置项、设置备份、账号登录、多用户
- 维护：应用更新、反馈、日志、性能诊断、换电视迁移
- 调试接口 (只有 debug 包)：查改数据库、拦截和统计请求、翻电视设置页、调试开关、播放链路探针

每个接口一节，标题是 `### <方法> <路径>`，路径里的 `{名字}` 是变量。

## 页面与通用

控制台根地址本身 (`GET`, 路径为空) 返回给人用的控制台网页 (HTML)。网页里的搜索表单已按电视当前的查询填好, 表单里 `name="tag"`、`name="year"` 的选项就是 `api/search` 可用的标签和年份。本章其余接口是各标签页通用的: 电视状态提示、启动弹窗、把 Izuko 切回前台、图片转发、数据源图标、诊断日志回传。

### GET api/notice
读电视的状态提示, 网页大约每 2 秒轮询一次。
- 参数: `after`, 可选, 上次拿到的 `seq`。不传时只返回当前序号作为基线, 不返回文案。
- 返回:
  - `seq`: 提示序号, 只增不减。
  - `text`: 后台保留的播放会话的最新提示 (如准备好了、出问题), 只在 `after` 小于 `seq` 时出现。期间有多条提示时只返回最新一条。
  - `away`: Izuko 不在电视前台 (屏保、切到别的应用、息屏)。
  - `cachePending`: 只在 `away=true` 且有没下完的 BT 缓存停着时出现。`bgRestricted`: 在此基础上, 系统还限制了后台运行时出现。
  - `launchDialog`: 电视上启动时弹出的二维码弹窗还开着。
  - `frontOn` / `frontGranted`: 「切到电视前台」开没开 (`POST api/settings/front`); 「显示在其他应用的上层」授没授权 (Android 9 及以下恒为 true)。
  - `keep`: 「退出后保留 Web 控制台」开没开 (`POST api/settings/keep`)。
  - `lang`: app 当前语言, `zh-CN` / `zh-HK` / `zh-TW` / `en`。
  - `user`: 电视当前用户档的 id (整数)。
  - `bgm`: 不透明字符串, Bangumi 的连接线路或登录方式变了它就会变。
- 注意: `user` 变了说明电视换了用户, 之前读到的按用户区分的数据 (播放记录、收藏等) 都要重新读取; `lang` 变了, 之后各接口 message 的语言也会跟着变。

### POST api/launch-dialog/close
关掉电视上启动时弹出的二维码弹窗。
- 参数: `auto`, 可选; 为 `1` 时先让弹窗显示 1.5 秒「手机已连接」再关, 不传就立即关。
- 返回: `closed`, 布尔。弹窗本来就没开时为 false, 这时什么都不做。不返回 ok/message。

### POST api/tv/front
把 Izuko 切到电视前台; 电视休眠或在屏保中时会先点亮屏幕。
- 参数: 无。
- 返回 ok/message。
- 注意: 已经在前台时返回 ok=true「Izuko 已在电视上显示」。不要求开着「切到电视前台」开关, 但 Android 10 起需要「显示在其他应用的上层」授权, 没授权时返回 ok=false「尚未授权…」。拉起失败时返回 ok=false「无法打开 Izuko，请在电视上手动打开。」。

### GET api/img
由电视用自己的网络 (跟随应用的代理与 Bangumi 线路设置) 拉取一张图并原样返回, 供手机连不上图床时使用。
- 参数: `u`, 必填, 完整 URL 编码后的图片地址。一般直接用列表接口给的候选地址 (`api/img?u=…`), 不用自己拼。
- 返回: 图片二进制; `Content-Type` 沿用源站的 `image/*` (拿不到时为 `image/jpeg`); 带 `Cache-Control: private, max-age=86400`。
- 注意:
  - 只转发以下地址, 其他地址 (或含 `..` 的地址) 回 400: 以 `https://image.tmdb.org/t/p/` 或 `https://lain.bgm.tv/` 开头的; `https://api.bgm.tv/v0/subjects/{id}/image?type=…` (`type` 为 small / grid / large / medium / common)。Bangumi 镜像域名的地址会先换回原站地址再判断。
  - 拉取失败 (源站返回非 2xx、单张超过 4 MiB、超时或连不上) 时回 502; 同一地址失败后 60 秒内直接回 502。
  - 每次拉取最多 5 秒, 同时最多拉 3 张, 排队 1.5 秒还轮不到就回 503。某个图床连着 2 次连不上时, 之后 30 秒内这个图床上没缓存的图一律回 503。
  - 遇到 502 / 503 时换下一个候选地址, 不要重试同一个。电视在内存里缓存最近约 24 MiB 的图。

### GET api/source-icon
返回数据源名字前的图标。
- 参数: `id`, 必填, 数据源实例 id 或数据源 id。
- 返回: 图片二进制。内置源 (蜜柑、动漫花园、Bangumi) 返回应用里打包的 `image/png`; 其他源返回它自己配置的图标地址, 经电视转发 (超时、并发、熔断和 `api/img` 一样, 不受图床白名单限制)。带 `Cache-Control: private, max-age=86400`。
- 注意: 缺少 `id` 时回 400; 找不到这个数据源 (读取数据源列表最多等 2 秒) 或它没有图标时回 404。

### POST api/client-log
往电视的应用日志里写一行诊断文本, 不改任何状态。
- 参数: `msg`, 要写的文本, 超过 300 字的部分会截掉; 为空时不写。
- 返回: 只有 `{"ok": true}`, 没有 message。
- 注意: 写成 `[WebConsole] <msg>` (警告级)。每分钟最多写 60 条, 多出的直接丢掉, 返回照样是 ok。

## 播放器

读写电视上正在播的那一集: 看状态与候选资源、换源、换集、改搜索条件、播放控制、音轨字幕、弹幕、收藏评分。典型顺序: 先 `GET api/player` 看 `available` / `background`, 用 `groups[].items[].id` 调 `select`、用 `episodes[].id` 调 `episode`, 播起来后用 `control`; 播放页不在前台时用 `open` 回到保留的会话, 什么都没在播时用 `upnext` 开播。
操作对象: 前台播放页优先, 没有时是后台保留的播放会话 (`background: true`: 能换源、换集、改搜索条件, 不能播放控制)。两者都没有时, 依赖播放页的接口回 `ok:false`「电视当前不在播放页」。
叫回前台: POST `api/player/control`、`select`、`episode`、`request`、`track` 与 `api/player/danmaku/*` 时, 若 Izuko 不在电视前台且网页设置开了「从手机打开 Izuko」并已授权, 先把应用切到前台再执行, 请求最多多等约 5 秒。`open`、`upnext`、`details` 要电视界面: 界面已退出时按同一设置尝试拉起 (最多等 10 秒), 拉不起来回 `ok:false` 和一段说明。

### GET api/player
播放器状态, 网页每秒轮询一次。
- 参数: `v` 上次拿到的版本号 (可选, 空 = 要完整内容); `res` / `sub` / `all` 只看某个分辨率 / 字幕语言 / 字幕组, 取值用 `filters` 里各项的 `value` (空 = 不筛); `ex=1` 连被排除的候选一起列; `full=<数据源 id>` 该源的候选不截断; `stats=1` 要播放信息。筛选只影响这次返回, 不改电视上的偏好。
- 返回 (没有播放页与后台会话时): `available:false`, `reason` = `none` (什么都没在播) 或 `background` (有保留会话但还没接上, 可调 `open`); 有会话时附 `title`、`session{kind,label,text}`; 没有会话时可能有 `upNext{subjectId,title,episode,continuing,position,duration,art?}` (「接下来播放」, 可调 `upnext`; 毫秒, `continuing` = 接着上次没看完的)。
- 返回 (有播放页或后台会话): `available:true`、`background`、`subjectId`、`title`、`episode` (「第 N 话  标题」)、`collection` (收藏类型名, 未取到时无)、`score` (我的评分)、`art[]` (卡片底图, 相对地址 `api/img?u=…`, 依次试)、`selectedId` / `selectedTitle` (正在用的资源)、`selectedSourceId` / `selectedSource` / `selectedMeta` (它的数据源与「分辨率 · 字幕 · 字幕组」)、`episodes[{id,label,current}]` (`label` 前有 `✓ ` = 看过, 带「（未播出）」= 没播出)、`request{primary,others[],sort,ep}` 与 `requestIsDefault` (当前搜索条件)、`loading` (还有数据源在查)、`sources[{id,name,state,count}]` (不含本地缓存与已停用的源; `state`: `loading`/`captcha`/`limited`/`login`/`failed`/`paused`/`done`)、`danmaku`、`tracks` (见下面弹幕与 track 接口)、`commentLink{url,episode,viaMirror}` (本集 Bangumi 评论页)、`session{kind,label,text}` (后台会话的进展; 前台只在还没播起来时有; `kind`: `ready`/`busy`/`attention`/`error`)、`drives`、`failures` / `prevFailures` (网盘与失败报告用, 见对应接口)。
- 候选: `excludedCount`; `filters{resolution[],subtitle[],alliance[]}`, 每项 `{value,label,count}`; `groups[{id,name,kind,total,more,items[]}]` 按数据源分组, `kind` = `cache`/`local`/`drive`/`web`/`bt`, 每源最多 40 条 (`more` = 没列出的条数, 正在用的那条总会列出); `items[]` 每条 `{id,title,resolution,subtitles,alliance,size,bitrate,cached,published(毫秒),url}`, 被排除的另有 `excluded:true`、`reason` (原因文案)、`blocked` (true = 不能选), 放行的另有 `similarity` (0~100)、`subjectMatch` (`FUZZY`/`EXACT`)、`episodeMatch` (`NONE`/`EP`/`SORT`/`SEASON`)。
- 每次都附 `v`; 有播放页或后台会话时还附 `playback{playing,position,duration,mediaKey,volume,muted,volumeStep,speed,speedMin,speedMax,speedStep,stats[{k,v}]}` (毫秒; `volume` 0~1, 取不到音量时无 volume/muted/volumeStep; 播放器不支持倍速时无 speed 四项; `stats` 只在 `stats=1` 时有, 刚开始采集时为空数组)。`v` 与上次相同时只回 `{v, same:true, playback}`, `playback` 不参与版本号。

### POST api/player/select
换用某个候选资源, 与电视上在选源面板点选同一条路。
- 参数: `id` 候选的 `id` (`groups[].items[].id`), 必填; 被排除的也能选。
- 返回 ok/message。
- 注意: 会像电视上点选一样记住分辨率、字幕组、数据源等偏好, 影响以后的自动选源。失败: 「这个数据源已不在列表里，请刷新」(候选已变, 重新 GET)、「这个资源现在不能播放（缓存还没下完）」(`blocked:true`)。后台会话也能换, 回到播放器后接着播。

### POST api/player/cache
用某个候选缓存电视当前在播的这一集。
- 参数: `id` 候选的 `id`, 必填。
- 返回 ok/message。
- 注意: 不叫应用回前台; BT 资源要等 Izuko 回到电视前台才开始下 (message 会说明)。失败: 「这一条已经是缓存了」「这一集已经在下载了」「这一集已经缓存好了」「这个资源不支持缓存，换一个试试」「这个数据源已不在列表里，请刷新」。

### POST api/player/open
把电视切回正在播放的那一集 (保留的播放会话)。
- 参数: 无。
- 返回 ok/message。
- 注意: 已在播放页时直接回 `ok:true`「电视已在播放页」; 没有保留会话回「电视上没有正在播放的内容」。会让电视换页面并收起电视上的动作面板 / 二维码弹窗。

### POST api/player/upnext
在电视上开播「接下来播放」那一集 (`GET api/player` 里的 `upNext`)。
- 参数: 无; 以电视此刻的「接下来播放」为准。
- 返回 ok/message。
- 注意: 有播放页或后台会话时回 `ok:false`「电视已经在播放了」; 没有可播的回「没有可播放的内容」。进度由播放器按该集的播放记录续。会让电视进入播放页。

### POST api/player/request
修改本集的搜索条件并重新搜索全部数据源 (同电视上「编辑查询请求」)。
- 参数: `reset=1` 恢复默认 (Bangumi 名称与本集原本的集数, 并清掉为这部番记住的搜索名), 此时其余参数忽略; 否则 `primary` 主搜索名 (必填)、`others` 次要搜索名 (每行一个, 可空)、`sort` 系列内剧集序号、`ep` 条目内序号 (`sort` 与 `ep` 至少填一个)。当前值见 `GET api/player` 的 `request`。
- 返回 ok/message。
- 注意: 搜索名按这部番记住, 以后打开它也用。失败: 「主搜索名不能为空」「两种集数至少要填一个」「数据源还在加载，请稍后再试」。

### POST api/player/refetch
用当前的数据源列表重建这次搜索, 让之后新启用 / 新订阅的数据源也参与。
- 参数: 无。
- 返回 ok/message, 总是 `ok:true`。
- 注意: 没有播放页时也能调, 下次进播放页时生效; 不叫应用回前台。禁用的数据源只在开了完整搜索时一并搜。

### POST api/player/full-search
开启完整搜索: 开播后被暂停的数据源放开重新查, 本播放页之后一直搜完全部数据源 (同电视选源面板里的开关)。
- 参数: 无。
- 返回 ok/message。
- 注意: 要有播放页或后台会话; 不叫应用回前台。

### POST api/player/control
播放控制。
- 参数: `action` 必填, 取值: `toggle` (切换播放 / 暂停)、`play`、`pause`、`back` (后退 10 秒)、`forward` (前进 10 秒)、`mute` (切换静音)、`seek` (另带 `ms` 目标位置毫秒, 夹在 0 到片长之间)、`speed` (另带 `v` 倍速, 大于 0, 量化到 `playback` 的 `speedStep` 档位并夹在 `speedMin`~`speedMax`)、`volume` (另带 `v` 0~1, 按系统音量档位取整, 大于 0 时顺带取消静音)。
- 返回 ok/message; 成功时 message 为空, 结果看下一次 `GET api/player` 的 `playback`。
- 注意: 后台会话回「电视未在播放页，播放控制不可用」; 叫不回前台时回「电视当前没有显示 Izuko，播放控制不可用…」。按明确状态发 `play` / `pause` 比 `toggle` 稳 (刚唤醒的电视可能自己续播)。音量调的是电视系统的媒体音量, 会一直保持; 倍速按应用的「记住播放倍速」设置决定是否记住。其他失败: 「无效的时间」「无效的倍速」「这个播放器不支持倍速」「无效的音量」「这个播放器不支持调音量」「未知操作」。

### POST api/player/episode
在当前播放页里换集 (同电视上的选集侧栏)。
- 参数: `id` 剧集 id (`episodes[].id`, 整数), 必填。
- 返回 ok/message。
- 注意: 已是当前集时直接回 `ok:true`。后台会话也能换, 在后台加载。失败: 「无效的剧集」「这一集不在列表里，请刷新」。

### POST api/player/details
在电视上打开某部番的详情页。
- 参数: `id` 条目 id (整数, 必填, 如 `GET api/player` 的 `subjectId`); `title` 番名 (可选, 详情加载出来前先显示)。
- 返回 ok/message。
- 注意: 不需要在播放页; 在播放页时详情页叠在播放器上面, 电视上按返回回到播放器。会让电视换页面。失败: 「无效的条目」。

### POST api/player/track
选音轨或字幕轨。
- 参数: `kind` = `audio` 或 `sub`; `id` 轨道 id (`tracks.audio.items[].id` / `tracks.subs.items[].id`), 空 = 音轨自动 / 关闭字幕。
- 返回 ok/message。
- 注意: `GET api/player` 的 `tracks{audio{sel,items[{id,name,off?}]}, subs{sel,items[{id,name}]}}` 给出候选与当前选中 (`sel` 为 null = 自动 / 关闭), 播放器不支持时对应项缺失。`off:true` 的音轨电视放不了, 选它回「这台电视放不了这条音轨的格式，请选别的音轨」; 轨道已变或 `kind` 不认识回「这条轨道已经不在了」。电视上明确选过字幕轨时, 播放器可能把这里的选择改回去。

### POST api/player/danmaku/enable
弹幕总开关。
- 参数: `on` = `1` 打开, 其他值或不带 = 关闭。
- 返回 ok/message。
- 注意: 总开关会记住。弹幕状态见 `GET api/player` 的 `danmaku{enabled,loading,canMatch,sources[{service,name,count,on,shift,method,matched}]}` (`method` 是匹配方式文案, `matched` 是匹配到的条目与集名, `canMatch` = 能用下面的手动匹配)。

### POST api/player/danmaku/source
开关某个弹幕源。
- 参数: `service` 弹幕源 id (`danmaku.sources[].service`), 必填; `on` = `1` 打开, 其他值 = 关闭。
- 返回 ok/message。
- 注意: 只对这次播放有效。失败: 「无效的弹幕源」。

### POST api/player/danmaku/shift
设某个弹幕源的时间偏移。
- 参数: `service` 弹幕源 id, 必填; `ms` 偏移毫秒 (整数, 可为负, 夹在 -60000~60000), 必填。
- 返回 ok/message。
- 注意: 设的是绝对值, 当前值见 `danmaku.sources[].shift`; 只对这次播放有效。失败: 「无效的弹幕源」「无效的偏移」。

### POST api/player/danmaku/search
手动匹配弹幕第一步: 在弹弹play 搜条目。
- 参数: `q` 番剧名, 必填。
- 返回: `{ok:true, items[{id,name}]}`; 失败时 ok/message。
- 注意: 要 `danmaku.canMatch` 为 true; 网络请求最多等 20 秒。失败: 「请输入番剧名」「弹弹play 不可用」「搜索超时，请重试」。

### POST api/player/danmaku/episodes
手动匹配第二步: 列出弹弹play 条目的剧集。
- 参数: `sid` 条目 id、`sname` 条目名 (取自 search 的 `items[]`)。
- 返回: `{ok:true, suggested, items[{id,name}]}`; `suggested` 是集号与电视当前这一集相同的那条在 `items` 里的下标, 没有为 -1。
- 注意: 最多等 20 秒。失败: 「无效的条目」「加载剧集超时，请重试」。

### POST api/player/danmaku/apply
手动匹配第三步: 取这一集的弹幕换到当前播放上。
- 参数: `sid`、`sname` (条目)、`eid`、`ename` (剧集, 取自 episodes 的 `items[]`), `sid` 与 `eid` 必填。
- 返回 ok/message (成功时说明换成了哪集、共多少条)。
- 注意: 只在内存里、只对当前这一集有效, 换集即作废; 电视上不会弹出匹配对话框。最多等 20 秒。

### POST api/player/danmaku/send
在电视当前播放位置发一条弹幕 (白色、普通滚动)。
- 参数: `text` 弹幕内容, 必填, 去掉首尾空白后最多 100 字。
- 返回 ok/message。
- 注意: 发到弹弹play, 成功后电视上立刻显示 (后台会话不显示)。最多等 20 秒。失败: 「这一集还没匹配到弹幕库，等电视上的弹幕加载出来再发」「弹弹play 拒绝了这次发送，稍后再试」「弹弹play 发弹幕的额度暂时用完了，过一阵再试」「发送超时，请重试」。

### GET api/player/review
当前这部番的收藏状态与我的评分短评。
- 参数: 无。
- 返回: `ok`、`loggedIn` (本地用户档算已登录)、`title`、`collection` (`WISH`/`DOING`/`DONE`/`ON_HOLD`/`DROPPED`/`NOT_COLLECTED`)、`rewatching` (是否在重温)、`score` (0~10, 0 = 不评分)、`comment`、`private`。
- 注意: 读取最多等 10 秒, 失败回 ok/message「读取收藏信息失败，请重试」。不叫应用回前台。

### POST api/player/review/collect
设置当前这部番的收藏状态。
- 参数: `type` = `WISH` (想看) / `DOING` (在看) / `DONE` (看过) / `ON_HOLD` (搁置) / `DROPPED` (抛弃) / `REWATCH` (开始重温)。
- 返回 ok/message。
- 注意: 写到 Bangumi (本地用户档写本地); 不支持取消收藏, `NOT_COLLECTED` 回失败。`REWATCH` 只对看过的番有效 (否则「只有看过的番能重温」), 有看过记录没同步时也会失败。没登录回「需要先登录…」, 最多等 20 秒。

### POST api/player/review/rate
保存我对当前这部番的评分与短评。
- 参数: `score` 0~10 整数 (必填, 0 = 不评分); `comment` 短评 (可空); `private` = `1` 仅自己可见。
- 返回 ok/message。
- 注意: 三项每次都要带全, 没带的 `comment` 会被清空、没带 `private` 即公开。没收藏的番评不了分, 回「先设置收藏状态（想看 / 在看 / 看过…）再评分」。失败: 「评分要在 0~10 之间」、需要登录、超时。

### GET api/subject/collection
读任意一部番的收藏状态, 不依赖播放页。
- 参数: `id` 条目 id (整数), 必填。
- 返回: `ok`、`title`、`collection` (收藏类型名, 同 `GET api/player/review`)、`rewatching`。
- 注意: 读取最多等 10 秒。失败: 「无效的条目」「读取收藏状态失败，请重试」。

### POST api/subject/collection
设置任意一部番的收藏状态, 不依赖播放页。
- 参数: `id` 条目 id (整数), 必填; `type` 同 `POST api/player/review/collect`。
- 返回 ok/message。
- 注意: 规则与失败同 `POST api/player/review/collect`。

## 播放器附加功能

播放页上的附加操作: 截图 (取画面、字幕、弹幕分层 PNG 与水印素材)、同步播放 (拿电视这一集的播放地址在别处播)、播放失败报告、给这部番添加网盘分享链接、从网盘里给这部番挑文件夹或文件 / 挂字幕。
典型顺序: 先 `GET api/player` 确认电视在播放页, 从它的 `drives` (每项 `id`、`name`、`shares`、`pick`) 拿网盘 id, 从 `playback.mediaKey` 知道在播的媒体换没换, 从 `failures` / `prevFailures` 知道有没有失败报告; 再调本章接口。
共同前提: 接口作用于电视当前的播放会话 (播放页, 或退出播放页后保留在后台的会话); 两者都没有时 JSON 接口回 `ok=false`、message「电视当前不在播放页」。例外在各接口里注明 (同步播放、失败报告、截图水印预设、应用图标、网盘列目录 / 搜索不要求播放会话)。
返回里的相对地址 (如 `api/player/screenshot/...`、`api/img?u=...`、`api/player/mirror/file/...`) 都接在控制台地址后面用。

### POST api/player/screenshot
截下电视此刻的画面, 分成视频、字幕、弹幕三层, 返回各层的取图地址与水印用的信息。
- 参数: 无
- 返回: `ok`; `id` 本次截图 id; `width` / `height` 视频层像素尺寸 (三层同尺寸, 都裁到视频区域); `layers` 对象, 键为 `frame` (总有)、`subtitles` (此刻有字幕才有)、`danmaku` (弹幕开着且屏上有才有), 值为取图的相对地址; `subjectId`; `title` 番名 (可能为 null); `episode` 如「第 12 话」与 `episodeName` 本集标题 (有剧集信息时才有); `position` 截图时的播放位置 (毫秒); `logo` 番剧标题 logo 的相对地址 (拉得到才有); `icon` 应用图标的相对地址。
- 注意: 需要播放页在前台且画面在屏幕上。后台会话回「电视上没有打开播放器，截不了图」; 电视休眠或切到别的应用回「电视上现在没有显示播放画面…」; 设备读不到硬解画面时回 `ok=false`、`unreadable=true`、message「这台设备截不到视频画面」。电视只留最近一张, 再截一张或过 10 分钟后旧 id 的图取不到。截图时最多等 3 秒拉 logo。

### GET api/player/screenshot/{id}/{layer}.png
取某次截图的某一层图片。
- 参数: 路径里 `id` 为 `POST api/player/screenshot` 返回的 id; `layer` 取 `frame`、`subtitles`、`danmaku`、`logo` 之一 (以返回的 `layers` / `logo` 里有的为准)
- 返回: `frame` / `subtitles` / `danmaku` 为 `image/png` (字幕与弹幕层是透明底); `logo` 为 TMDB 的 logo 原图 (Content-Type 照 TMDB 给的)。不支持 Range。
- 注意: PNG 在截图后才在后台编码, 编好前请求会等着 (4K 约一两秒, 最多 30 秒, 超时或编码失败回 503)。id 不是最近一张、已过期或该层不存在回 404。

### GET api/player/screenshot/logos
列这部番在 TMDB 上各种语言的全部标题 logo, 供水印挑选。
- 参数: 无
- 返回: `ok`; `logos` 数组, 每项 `path` (TMDB 文件路径)、`lang` (语言, 可能没有)、`aspect` (宽高比)、`thumb` (w500 缩略图的相对地址)、`src` (依次为原图、w500 两个相对地址, 都是 `api/img?u=...` 经电视转发)。
- 注意: 本进程没查过这部番的 TMDB 条目时要先搜, 最多等 15 秒; 拿不到回 `ok=false`、message「没能从 TMDB 拿到这部番的 logo」。

### GET api/player/screenshot/icon.png
应用图标 (水印选应用名时用)。
- 参数: 无
- 返回: `image/png`, 256×256 像素; 取不到回 404。
- 注意: 不要求播放会话。

### GET api/player/screenshot/presets
列存在电视上的截图水印预设。
- 参数: 无
- 返回: `ok`; `presets` 数组, 每条 `id`、`name`、`kind` (`logo` 或 `text`)、`x`、`y`、`deg`、`scale`, 可选 `preset`; `kind=text` 的还有可选 `align` 与布尔 `title`、`ep`、`time`。
- 注意: 不要求播放会话。

### POST api/player/screenshot/presets/add
新增或覆盖一条水印预设 (同 `id` 的先删再加)。
- 参数: `preset` (必填) 一条预设的 JSON 字符串: `id` 必填, 1~32 位小写字母或数字; `name` 必填, 去掉首尾空白后非空, 超过 40 字截断; `kind` 必填, `logo` 或 `text`; `x`、`y` 0~1, 默认 0.5; `deg` -180~180, 默认 0; `scale` 相对标准大小的倍数, 0.05~40, 默认 1; `preset` 可选, 取 `tl`、`tc`、`tr`、`ml`、`mc`、`mr`、`bl`、`bc`、`br`; 仅 `text`: `align` 可选, 取 `l`、`c`、`r`, `title`、`ep`、`time` 布尔 (缺省为 false)。数值超范围会被夹到范围内, 不认识的字段丢掉。
- 返回: `ok`; 失败时 `message`; `presets` 操作后的全部预设 (格式同 GET)。
- 注意: 格式不对回「预设的格式不对」; 最多存 50 条, 满了回「预设最多存 50 个，先删掉几个」。存在电视上, 不要求播放会话。

### POST api/player/screenshot/presets/delete
删除一条水印预设。
- 参数: `id` (必填) 预设 id
- 返回: `ok`; 失败时 `message`; `presets` 操作后的全部预设。
- 注意: 没有这个 id 回「没有这个预设」。

### GET api/player/mirror
问电视这一集的播放地址能不能交给别的设备直接播 (同步播放)。
- 参数: 无
- 返回: `ok` 恒为 true; `key` 在播媒体的标识 (同 `GET api/player` 的 `playback.mediaKey`, 没装媒体时为空串); `playable`。
  `playable=true` 时: `url` 播放地址 (网上的原地址; 电视在播本机文件时是相对地址 `api/player/mirror/file/{key}/{文件名}`); `kind` 按后缀猜的容器, 取 `hls`、`mp4`、`mkv`、`ts`、`flv`、`webm`、`other`; `host` 地址的主机名 (本机文件为 `tv`); `local` 是否由电视发本机文件; `headers` 电视取流时带的请求头名字 (只给名字, 不给值)。
  本机文件带外挂字幕时还有 `subs` 数组 (每项 `i` 序号、`label` 名字 (可能没有)、`lang` 语言 (可能没有)、`vtt` WebVTT 的相对地址) 与 `subSel` (电视上选着的那条的 `i`, 没选外挂字幕为 -1)。
  `playable=false` 时: `reason` 给人看的原因; `pending` 为 true 表示电视还没装上媒体, 过一会儿再问。
- 注意: 只认 GET (HEAD 回 405)。没有播放会话时也回 `ok=true`、`playable=false`、`pending=false`。不可播的情况: 还没开始播放、边下边播的 BT 等非 http(s) 地址、经电视本机代理播的 (如 HLS 去广告)、要带 Cookie / Authorization 的 (如网盘)。`playable=true` 只说明值得一试, 防盗链与编码只有真去加载才知道。`playback.mediaKey` 变了就该重新问。

### GET api/player/mirror/file/{key}/{name}
发电视此刻在播的本机视频文件 (已下完的缓存或本机文件), 供同步播放。
- 参数: 路径里 `key` 必须等于当前的 `key`; `name` 是文件名 (照 `GET api/player/mirror` 返回的 `url` 用即可, 电视只核对 `key`)。请求头 `Range` 可选。
- 返回: 文件原始字节, 不转码。Content-Type 按扩展名: `mp4`/`m4v` 为 `video/mp4`, `mov` 为 `video/quicktime`, `mkv` 为 `video/x-matroska`, `webm` 为 `video/webm`, `ts` 为 `video/mp2t`, `flv` 为 `video/x-flv`, 其余 `application/octet-stream`。支持 Range (`bytes=a-b`、`bytes=a-`、`bytes=-n`, 多段只取第一段): 回 206 带 `Content-Range`, 不带 Range 回 200; 都带 `Accept-Ranges: bytes`。
- 注意: 支持 GET 与 HEAD, 其它方法 405。`key` 对不上 (换了源或集) 或电视在播的不是本机完整文件回 404; Range 越界回 416 (带 `Content-Range: bytes */总长`)。同时最多 4 路 GET 在传, 超了回 503 带 `Retry-After: 1`。

### GET api/player/mirror/sub/{key}/{i}.vtt
把电视在播的本机文件带的一条外挂字幕转成 WebVTT 发出。
- 参数: 路径里 `key` 必须等于当前的 `key`; `i` 为 `GET api/player/mirror` 返回的 `subs[].i` (直接用 `subs[].vtt`)
- 返回: `text/vtt; charset=utf-8`。ASS/SSA 转过去只留文字, 位置与特效丢掉; SRT 照转。不支持 Range。
- 注意: `key` 对不上或没有这条字幕回 404; 字幕文件超过 8 MiB 回 413。

### GET api/player/failures
读这一集 (及换集前那一集) 的播放失败记录。
- 参数: 无
- 返回: `entries` 数组 (新的在前), 每项 `time` (毫秒时间戳)、`stage` 阶段 (给人看的文案: 解析资源 / 播放器打开 / 播放中)、`reason` 原因文案、`network` 是否网络错误、`source` 数据源名 (本地缓存为「本地缓存」)、`title` 资源标题、`host` 出错的主机名 (可能没有)、`causes` 异常链 (每层一行字符串)、`stack` 完整堆栈 (可能没有); 换集前那一集也失败过时有 `previous` 对象: `episode` (如「第 3 话  标题」, 找不到为「上一集」) 与它自己的 `entries`。
- 注意: 返回里没有 `ok` 字段。没有播放会话或没有失败记录时 `entries` 为空数组。每集最多留 30 条。

### POST api/player/failures
隐藏播放卡上的失败报告入口 (所有连着的控制台一起)。
- 参数: 无
- 返回: `{"ok": true}` (没有 message)
- 注意: 不删记录, `GET api/player/failures` 照样读得到; 只是 `GET api/player` 里的 `failures` / `prevFailures` 不再出现, 之后失败次数变多了会重新出现。

### GET api/player/shares
列这部番在各网盘添加过的分享, 以及能添加分享的网盘。
- 参数: 无
- 返回: `ok`、`message`; `drives` 数组 (能添加分享的网盘), 每项 `id`、`name`、`loggedIn`; `shares` 数组, 每项 `drive` 网盘 id、`driveName`、`id` 分享 id、`title`、`addedAt` (添加时间, 毫秒时间戳), 最近一次打开出了问题时还有 `note` 说明与 `noteLevel` (`attention` = 已转存的集还能播, `error` = 播不了)。

### POST api/player/shares/add
粘贴网盘分享链接, 电视打开分享并按当前这部番认剧集, 认出了就记到这部番名下。
- 参数: `text` (必填) 粘贴的文字, 可含多个链接; 要提取码的把「提取码：xxxx」一起放进来。电视用第一个认得出链接格式的网盘, 一次最多处理 5 个链接。
- 返回: `ok` (至少一个分享认出了剧集并记下才为 true)、`message`; `results` 数组, 每个链接一项: `drive`、`shareId`、`title`、`ok` (这个分享认出了剧集)、`message`; 打开成功时还有 `current` (认出的当前这一集的文件) 与, 当前这一集没认出时, `files` (分享里的视频, 最多 60 个, 多出的个数在 `moreFiles`)。文件项为 `fid`、`name`、`meta` (所在文件夹 · 大小)。
- 注意: 每个链接最多等 30 秒 (并行打开)。认出剧集后会让「我添加的分享」数据源重查, 必要时整次重新搜索, 电视上的视频会重新加载 (message 末尾会说)。一集都没认出时不记下, 但 `files` 可交给 `POST api/player/shares/play` 手动指定。常见失败: 「数据源还在加载，请稍后再试」、「没有能添加分享链接的网盘…」、「没有找到X的分享链接」、「这个分享要提取码…」、「打开分享超时，请稍后再试」。

### POST api/player/shares/delete
删除这部番名下添加的一个分享。
- 参数: `drive` (必填) 网盘 id; `id` (必填) 分享 id (来自 `GET api/player/shares` 的 `shares[]`)
- 返回: 返回 ok/message
- 注意: 删除后让该网盘的「我添加的分享」数据源重查。网盘 id 不认识回「没有这个网盘，请刷新」。

### POST api/player/shares/play
把最近打开过的分享里的某个视频当作当前这一集在电视上播放。
- 参数: `drive` (必填) 网盘 id; `share` (必填) 分享 id; `fid` (必填) 文件 id (来自 `POST api/player/shares/add` 返回的 `current` 或 `files`)
- 返回: 返回 ok/message
- 注意: 只认电视最近打开过的分享 (内存里留最近 16 个, 重启即失), 否则回「找不到这个文件，请重新粘贴链接」。挑的不是自动认出的那一集时, 会先记下「这个文件是第 N 集」并让数据源重查; 最多等 20 秒等它出现在选源列表里再选上, 等不到就先临时播。网盘没登录时仍回 ok, message 末尾提示先登录。

### GET api/player/drive
某个网盘里给这部番已指定的文件夹、文件与字幕, 以及搜索用的名字。
- 参数: `drive` (必填) 网盘 id (来自 `GET api/player` 的 `drives[].id`, `pick` 为 true 的才能搜或列目录)
- 返回: `ok`、`message`; `name` 网盘名; `loggedIn`; `canSearch` (false 时只能用 `api/player/drive/list` 一层层列); `local` (是否本地文件夹而非网盘); `keyword` 建议的搜索词; `names` 这部番的搜索名数组; `folders` 已指定的文件夹 (`fid`、`name`); `files` 已指定的文件 (`fid`、`name`、`meta` 如「当作第 3 集」); `canAttach` 电视正在播的是不是这个网盘的视频 (是才能挂字幕); `subtitles` 已给正在播的视频挂上的字幕 (`fid`、`name`)。
- 注意: 本组接口 `drive` 不认识或为空时一律回「没有这个网盘，请刷新」; 网盘未登录或登录失效时 (需要登录的操作) 回「X没登录或登录已失效，请在「数据源」页登录」。没登录时本接口仍回 ok, 列表为空。

### GET api/player/drive/search
在网盘里按名字搜文件夹、视频与字幕文件。
- 参数: `drive` (必填) 网盘 id; `q` (必填) 搜索词
- 返回: `ok`、`message` (没结果时为「没有找到文件夹或视频」); `items` 数组, 文件夹在前, 每项 `fid`、`name`、`dir` (是否文件夹), 字幕文件有 `sub=true`, 非文件夹有 `meta` (认出的集数如「第 2 季第 3 集」· 大小); 超过 200 项时 `more` 为没列出的个数。
- 注意: 不要求播放会话。`q` 为空回「先输入要搜的名字」。`play` / `folder` / `subtitle` 只认最近搜到或列出的项, 所以要先搜或列。

### GET api/player/drive/list
列网盘里某个文件夹的内容 (只含文件夹、视频与字幕文件)。
- 参数: `drive` (必填) 网盘 id; `fid` (可选) 文件夹 id, 省略或为空时列根目录
- 返回: 同 `GET api/player/drive/search`
- 注意: 不要求播放会话。

### POST api/player/drive/play
把网盘里一个视频当作当前这一集在电视上播放。
- 参数: `drive` (必填) 网盘 id; `fid` (必填) 视频的文件 id (来自最近的 search / list 结果)
- 返回: 返回 ok/message
- 注意: 选源列表里已有这个文件且就是这一集时直接选上; 否则先记下「这个文件是第 N 集」(记在网盘账号里, 之后自动匹配), 让该网盘数据源重查 (必要时整次重新搜索, 电视上的视频会重新加载), 最多等 20 秒再选上, 等不到就先临时播。fid 不在最近结果里或不是视频回「找不到这个文件，请重新搜索」。

### POST api/player/drive/folder
指定「这部番就在这个文件夹里」, 认得出当前这一集就接着播它。
- 参数: `drive` (必填) 网盘 id; `fid` (必填) 文件夹 id (来自最近的 search / list 结果)
- 返回: 返回 ok/message (message 写明认出了哪几集)
- 注意: 文件夹里一集都认不出时不记, 回 `ok=false`; 记下后让数据源重查, 认出当前这一集时同 `play` 最多等 20 秒选上或临时播。fid 不在最近结果里或不是文件夹回「找不到这个文件夹，请重新搜索」。

### POST api/player/drive/forget
删掉这部番已指定的一个文件夹或文件。
- 参数: `drive` (必填) 网盘 id; `fid` (必填) 来自 `GET api/player/drive` 的 `folders[].fid` 或 `files[].fid`
- 返回: 返回 ok/message
- 注意: 删除后让该网盘数据源重查。

### POST api/player/drive/subtitle
把网盘里的字幕文件挂到电视上正在播的视频上, 并选上它。
- 参数: `drive` (必填) 网盘 id; `fid` (必填) 字幕文件的 id (最近 search / list 结果里 `sub=true` 的项)
- 返回: 返回 ok/message
- 注意: 正在播的必须是这个网盘的视频 (`canAttach`), 否则回「只能给X里的视频挂字幕，电视上正在播的不是」。记在网盘账号里, 之后播这个视频都带上。电视上的视频会原地重新加载并回到当前位置, 新字幕在后台最多等 60 秒出现后选上 (接口不等)。

### POST api/player/drive/unsubtitle
取下给正在播的视频挂上的一条字幕。
- 参数: `drive` (必填) 网盘 id; `fid` (必填) 来自 `GET api/player/drive` 的 `subtitles[].fid`
- 返回: 返回 ok/message
- 注意: 同 `subtitle` 要求正在播的是这个网盘的视频; 电视上的视频会原地重新加载。

## 搜索、播放记录、收藏与时间表

这一章用来在电视上找番、开播: 搜番 (结果就是电视搜索页上那份列表)、按播放记录接着看、读在看 / 想看收藏和本周新番时间表。典型顺序: `POST api/search` 提交 → 每秒轮询一次 `GET api/search/results` (带上次的 `v`), 等到 `pending=false`、`refreshing=false` → 拿 `items[].id` 调 `POST api/search/play` 开播, 或 `POST api/search/open` 打开详情页。播放记录、收藏、时间表返回的 `id` 也是 Bangumi 条目 id, 同样可以传给这两个接口。
标了「会让电视换页面」的接口会让电视跳转, 同时收起电视上的动作面板和二维码弹窗。如果 Izuko 不在前台, 而且已开启并授权「切到电视前台」(`POST api/settings/front`), 会顺带切到前台。`api/search`、`api/search/play`、`api/search/open`、`api/history/open`、`api/history/play` 在 Izuko 界面已退出但进程还在时: 已开启并授权就先拉起界面 (最多等 10 秒), 否则返回 ok=false, message 为「Izuko 已退出，无法从手机打开…」。
列表项里的 `cover` / `imgs` 是封面候选地址数组, 按顺序尝试: 第一项是相对控制台根地址的 `api/img?u=…` (经电视转发, 见「页面与通用」), 后面是直连地址。`blur=true` 表示电视的 NSFW 设置要求模糊显示; NSFW 设为隐藏的条目不会出现在列表里。

### POST api/search
让电视按关键词和筛选条件搜番 (会让电视换页面)。
- 参数 (都可选, 但 `q` 和筛选项至少要有一个):
  - `q`: 关键词, 会去掉首尾空白, 最长 200 字。
  - `sort`: 排序, `MATCH` (匹配度, 默认) / `RANK` (排名) / `COLLECTION` (收藏人数) / `DATE` (发布日期); 其他值按 `MATCH` 处理。
  - `rating`: 最低评分, 只认 `7` / `8` / `9`, 其他值忽略。
  - `tag`: 标签, 可以重复传多个 (最多 30 个, 每个最长 50 字)。值是 Bangumi 标签原文, 比如 `科幻`、`日本`、`漫画改`。控制台网页 (根路径) 搜索表单里 `name="tag"` 的复选框列出了常用标签, 不在其中的字符串也照样提交。
  - `year`: 年份, 1900–2100 的整数。可选年份见网页搜索表单里的 `name="year"`。
  - `season`: 季度 `1`–`4` (1 = 1 月冬季番, 2 = 4 月春季番, 3 = 7 月夏季番, 4 = 10 月秋季番), 只在同时给了 `year` 时生效。
- 返回 ok/message。
- 注意: 一次提交会整体替换电视搜索页的关键词、排序、评分、标签、年份和季度, 没给的项就是不筛。没有关键词又没有任何筛选项 (`sort=MATCH` 不算筛选) 时返回 ok=false「请输入关键词或选择筛选项」。电视在搜索页时直接换查询; 不在时把搜索页叠上去, 如果原来在播放页, 播放器留在下面, 按返回能回去。提交后大约 10 秒内, `api/search/results` 会先报 `pending=true`。

### GET api/search/history
读电视搜索页的搜索记录 (和电视上是同一份)。
- 参数: 无。
- 返回: `items`, 字符串数组, 最近的在前, 最多 50 条。

### POST api/search/history/delete
删除一条搜索记录。
- 参数: `q`, 必填, 要删的记录原文 (按全文匹配)。
- 返回 ok/message (成功时 message 为空)。
- 注意: `q` 为空时返回 ok=false「无效的记录」。

### GET api/search/results
读电视搜索页结果面板此刻的内容。电视离开搜索页后, 返回离开前的最后一份。
- 参数: `v`, 可选, 上次返回的版本号。
- 返回:
  - 内容没变 (和 `v` 一致) 时只回 `{"v": …, "same": true}`。
  - 否则返回完整内容并带新的 `v`: `pending` (刚提交的搜索电视还没换上); `available` (有没有结果可显示, `pending=true` 或从没搜过时为 false, 这时没有下面的字段); `live` (电视搜索页的结果面板还在, 能用 `api/search/results/more` 加载更多); `keywords` (电视当前的关键词); `filters` (年份 / 评分 / 标签的一行说明文本); `refreshing` (第一页正在加载); `appending` (正在加载下一页); `end` (已经没有更多); `error` (加载出错的文案, 只在出错时有); `items` (数组)。
  - `items` 的每一项: `id` (条目 id, 整数)、`title`、`info` (一行条目信息文本)、`rating` (如 `★ 8.1 · #123 · 1234 人评分`, 没人评分时为空串)、`nsfw` (布尔)、`cover`、`blur`。
- 注意: 列表就是电视上已经加载的那些, 不会单独再搜一份; 在电视上改了搜索, 这里也跟着变。`pending` 在以下任一情况变为 false: 电视已换上这次的查询并开始刷新, 或已换上且离提交超过 1.5 秒; 提交已满 10 秒。

### POST api/search/results/more
让电视的结果列表加载下一页。
- 参数: 无。
- 返回 ok/message (成功时 message 为空)。
- 注意: 加载是异步的, 新条目要继续轮询 `api/search/results` 才能拿到。电视已离开搜索页 (`live=false`) 时返回 ok=false「电视已离开搜索页，回到搜索页后可以继续加载」。

### POST api/search/results/resume
让电视回到搜索页 (会让电视换页面)。
- 参数: 无。
- 返回 ok/message。
- 注意: 已经在搜索页时直接返回 ok=true「电视已经在搜索页了」。原来的搜索页还在返回栈里时, 电视退回到它, 等于替用户按返回; 上面叠着的页面会退出, 播放器转入后台。结果和翻到的位置都还在, message 为「电视已回到刚才的搜索页」。搜索页已经关了时, 用最后一份结果的查询从第一页重新搜。都没有时返回 ok=false「没有可以恢复的搜索，重新搜一次吧」。

### POST api/search/play
让电视直接播放某部番 (会让电视换页面)。
- 参数: `id`, 必填, Bangumi 条目 id。不限于搜索结果, 任何条目 id 都可以。
- 返回: `ok`、`message`; 电视进入或留在播放页时多一个 `player: true`。
- 注意: 播哪一集: 全部看完了从第一集重温, 否则播收藏进度里的下一集 (没有就播第一集)。如果电视正在播这部番, 就在当前播放器里换集; 已经在播这一集时 message 为「电视正在播…」。条目没有剧集信息时改为打开详情页 (ok=true, 不带 `player`)。没开播时返回 ok=false「「…」还没开播」。条目不在本地缓存时要先联网读取, 最多等 15 秒, 超时返回 ok=false「获取剧集信息超时，请重试」。其他失败: 「无效的条目」(`id` 不是整数)、「电视还没准备好」、「获取剧集信息失败：…」。

### POST api/search/open
让电视打开某部番的详情页 (会让电视换页面)。
- 参数: `id`, 必填, Bangumi 条目 id。任何条目 id 都可以; 这个条目在当前搜索结果里时, 详情页会先用结果里的标题和封面占位。
- 返回 ok/message。
- 注意: 失败 message: 「无效的条目」、「电视还没准备好」。

### GET api/history
读电视的播放记录: 每部番只列最近看的那一集, 最近的在前, 最多 50 部 (电视当前用户的记录)。
- 参数: `lite`, 可选, 传 `lite=1` 时每项不带 `imgs` 和 `blur`。
- 返回: `ok`、`items`。每项: `id` (条目 id)、`title`、`line` (如「第 7 话 · 看到 12:34 / 23:40」或「第 7 话已看完」)、`percent` (0–100, 只在没看完且知道时长时有)、`time` (最后观看时间的文本, 如「今天 21:30」)、`imgs` (封面候选)、`blur`。
- 注意: 用 `api/history/delete` 删掉的番不再列出, 之后又看了才会重新出现。

### POST api/history/open
让电视打开播放记录里某部番的详情页 (会让电视换页面)。
- 参数: `id`, 必填, 条目 id。
- 返回 ok/message。
- 注意: 失败 message: 「无效的条目」、「电视还没准备好」。

### POST api/history/play
按播放记录接着看某部番 (会让电视换页面)。
- 参数: `id`, 必填, 条目 id (要在 `api/history` 的列表里)。
- 返回: `ok`、`message`; 开始播放时多一个 `player: true`。
- 注意: 那一集没看完就从上次的位置接着播; 看完了就播下一集。没有能接着播的下一集时改为打开详情页 (ok=true, 不带 `player`)。电视正在播这部番时就在当前播放器里换集。不在列表里时返回 ok=false「播放记录里找不到这部番，刷新一下再试」; 其他失败: 「无效的条目」、「电视还没准备好」。

### POST api/history/delete
删除一部或几部番的播放记录。
- 参数: `ids` (逗号分隔的多个条目 id) 或 `id` (单个), 二选一, 必填。
- 返回: `ok`、`message`、`deleted` (真正删掉的条目 id 数组, 找不到的不算)。
- 注意: 效果和电视播放历史页里的删除一样, 会改电视上的记录。失败 message: 「无效的条目」、「播放记录里找不到这部番 / 这些番，刷新一下再试」、「删除失败，请重试」、「删除超时，请重试」。

### GET api/collections
读当前用户的「在看」或「想看」收藏, 每种最多 50 部, 最近有变动的在前。
- 参数: `type`, 可选, `WISH` 表示想看, 其他值或不传表示在看 (`DOING`)。
- 返回: `ok`、`items`; 本次同步失败、用的是电视本地已有的数据时带 `stale: true`。每项: `id`、`title`、`line` (如「有 3 集新的 · 2 集没缓存」「已追到最新」「还没开播」「已看完」, 可能没有)、`fresh` (在看里有新的一集, 只在为 true 时出现)、`blur`、`cover`。在看列表里有新集的排在前面。
- 注意: 先联网同步一次收藏, 最多等 12 秒, 同一种收藏一分钟内只同步一次。电视未登录时返回 ok=false 和 `needLogin: true`; 连不上 Bangumi 时返回 ok=false 和 `needLogin: false`。本地用户档不用登录。

### GET api/schedule
读新番时间表: 电视所在这一周 (周一到周日) 每天播出的番。
- 参数: `day`, 可选, 1–7 (1 = 周一 … 7 = 周日)。不传或无效时为今天。
- 返回: `ok`、`today` (今天是周几, 1–7)、`days` (固定 7 项, 周一到周日); 读取中途失败时多一个 `failed` (文案)。`days` 的每项: `weekday` (1–7)、`date` (`YYYY-MM-DD`)、`pending` (这一天还没读完)、`items`。`items` 的每项: `id`、`title`、`line` (如「21:30 · 第 5 话 · 在看」, 播出时间按电视时区, 自己在看 / 想看的番会注明)、`fresh` (自己在看或想看)、`blur`、`cover`。同一部番一天只列一行。
- 注意: 一次请求最多等 8 秒, 等 `day` 那一天读完。其余几天在电视后台继续读取, `pending=true` 的那几天要过一会儿再请求。

## 缓存

给某部番挑几集缓存到电视上，以及查看、暂停、继续、删除、播放电视上已有的缓存。`subject` 是 Bangumi 条目 id，可取自其它章节返回的条目 id 或本章 `api/caches` 的 `groups[].id`；`episode` 是剧集 id，取自 `GET api/cache` 的 `episodes[].id`。
手动缓存：`GET api/cache?subject=` 取剧集 → `GET api/cache/candidates?subject=&episode=` 反复调用，直到 `loading` 为 false → `POST api/cache/pick` 提交 `groups[].items[].id` → 再轮询 `GET api/cache` 看这一集的 `status` / `progress` / `error`。
自动缓存：`POST api/cache/auto` 提交若干集 id，用 `GET api/cache` 的 `batch` 看进度。已有缓存用 `api/caches` 返回的 `cid` 定位（缓存 id 可能含中文与符号，提交前按 UTF-8 做 URL 编码）。
BT 资源只在电视上 Izuko 在前台时才开始下载，返回里的 `btStarting` / `tvBackground` 会说明原因。下文引用的 message 是中文界面的原文，文案跟随应用语言。

### GET api/cache
读取一部番的剧集列表、每集的缓存状态，以及正在进行的自动缓存批次的进度。
- 参数: `subject` 条目 id（必填，整数）。
- 返回: `title` 番名；`episodes[]`：`id` 剧集 id，`label` 集号加集名，`sp` 为 true 表示特别篇，`watched` 表示已标记看过，`status` 取值为 `cached` / `caching` / `none`，`size` 是大小文字（如 "1.2 GB"），缓存中时还有 `progress`（0~100 的整数）和 `progressText`（不足 1% 时带一位小数），`pack` 为 true 表示已缓存的合集覆盖这一集（可用 `api/cache/pack`），`error` 是这一集最近一次失败的原因。 `packTitle` 是合集资源标题；`btStarting` 表示 BT 服务正在启动，`tvBackground` 表示电视上没打开 Izuko；`autoHint` 说明自动缓存会沿用哪个来源；`free` / `freeLabel` 是新缓存所在盘的剩余空间。 `batch` 只在最近一批自动缓存属于这个条目时出现，包含 `running`、`done`、`total`、`created`（这一批已建好的缓存数）、`current`（正在处理的集）、`failures[]`（"集标签：原因" 文字）。正在挑资源时还有 `sourcesDone` / `sourcesTotal`、`waited`（这一集已等的毫秒数）和 `waitLimit`（上限 120000 毫秒）。批次结束后，`batch` 仍保留，`running` 为 false。
- 注意: 读取条目最长约 15 秒，读取缓存状态最长约 5 秒。超时会返回 ok=false（"读取剧集失败，请重试" / "读取缓存状态超时，请重试"），重试即可。调用本接口会预先启动 BT 服务，约 1~1.5 分钟没人调用后释放。网页上每 2 秒轮询一次。

### GET api/cache/candidates
打开某一集的选资源列表，并返回这一集当前查到的候选资源。
- 参数: `subject` 条目 id（必填）；`episode` 剧集 id（必填）。以下可选：`res` 分辨率，`sub` 字幕语言，`all` 字幕组，取值分别来自返回的 `filters.resolution[].value`、`filters.subtitle[].value`、`filters.alliance[].value`；`ex=1` 同时列出被排除的候选；`full=<数据源 id>` 让这个数据源不受每源 40 条的截断。
- 返回: `loading` 表示还有数据源在查询；`primary` / `others[]` / `sort` / `ep` 是当前使用的搜索名与集数，`edited` 表示它们与 Bangumi 默认值不同；`sources[]` 含 `id`、`name`、`count` 和 `state`，`state` 取值为 `loading` / `done` / `failed` / `captcha` / `limited` / `login`；`excludedCount` 是被排除的候选数；`filters` 含 `resolution` / `subtitle` / `alliance`，每项为 `{value,label,count}`。 `groups[]` 按数据源分组，包含 `id`（数据源 id）、`name`、`kind`（`bt` / `web` / `drive` / `local`）、`total`、`more`（被截断的条数）和 `items[]`。每个 item 含 `id`（资源 id，交给 pick）、`title`、`resolution`、`subtitles`、`alliance`、`size`、`bitrate`、`published`（毫秒时间戳）、`url`。被排除的条目另有 `excluded`、`reason` 和 `blocked`；`blocked` 为 true 表示不能选。放行的条目另有 `similarity`（0~100）、`subjectMatch` 和 `episodeMatch`。
- 注意: 整台电视同一时间只有一个选资源会话。调用另一集时，会关闭当前会话并新建一个。首次打开一集最长要约 30 秒，客户端超时应设到 35 秒以上。会话打开后在后台持续查询，3 分钟内没再调用本接口就自动关闭。网页在 `loading` 为 true 时每 1.5 秒轮询一次，否则每 5 秒一次。已经缓存好的资源不会列出。打开失败时返回 "打开这一集失败，请重试"。

### POST api/cache/pick
用候选列表里的一条资源开始缓存这一集。
- 参数: `subject` 条目 id（必填）；`episode` 剧集 id（必填）；`id` 资源 id（必填，取 candidates 的 `groups[].items[].id`）。
- 返回: 返回 ok/message。成功时 message 为 "已开始缓存「…」"；资源是合集时会提示其它集可以直接用合集缓存。
- 注意: 要求当前打开的选资源会话正是这条目的这一集，否则返回 "列表已过期，请重新打开这一集"。"这个资源已不在列表里，请刷新"、"这个资源现在不能选"（`blocked`）、"这个资源不支持缓存，换一个试试"（没有能存放它的存储）都表示要换一条资源。 成功后选资源会话随即关闭。真正建立缓存在后台进行，BT 资源要先下载种子信息，失败原因会写到 `GET api/cache` 这一集的 `error`。本接口不检查这一集是否已经缓存，也不改数据源偏好。

### POST api/cache/pack
用已缓存的合集缓存这一集，不用挑资源（对应 `GET api/cache` 中 `pack: true` 的集）。
- 参数: `subject` 条目 id（必填）；`episode` 剧集 id（必填）。
- 返回: 返回 ok/message。成功时 message 为 "已用合集开始缓存「…」"。
- 注意: 返回 "已缓存的合集里没有这一集，请点「选资源」自己挑" 时，表示没有能覆盖这一集的合集，请改走 candidates → pick。还没播出的集不会复用合集。最长约 15 秒，超时返回 "操作超时，请重试"。

### POST api/cache/auto
自动缓存所选的几集：在后台逐集挑资源并开始缓存。
- 参数: `subject` 条目 id（必填）；`episodes` 剧集 id 列表（必填），用英文逗号分隔，例如 `episodes=101,102,103`。
- 返回: 返回 ok/message。成功时 message 为 "开始为 N 集自动挑资源缓存"。进度看 `GET api/cache` 的 `batch`。
- 注意: 整台电视同一时间只跑一批，上一批没结束时返回 "上一批还在进行，稍等一下"。已在缓存或已缓存的集会跳过。 每集先找能复用的已缓存合集。没有合集时，沿用离这一集最近的已缓存集所用的数据源；没有已缓存的集时，依次沿用刚删除的缓存所用的源、播放时手动选过的源。沿用的源没有这一集就记为失败，不会换源（`autoHint` 会说明沿用哪个源）。以上都没有时，等网络源最多 30 秒，再按偏好挑选。每集挑资源最长约 2 分钟。 挑不到的集写进 `batch.failures` 和该集的 `error`，之后可以手动 pick。挑中资源时，会经下载会话把这部番的资源偏好记成选中的那条。

### POST api/cache/auto-cancel
取消正在进行的自动缓存批次（不分条目）。
- 参数: `remove` 可选，传 `1` 时同时删除这一批已经建好的缓存；不传或传其它值时，只停止还没开始的集。
- 返回: 返回 ok/message，例如 "已取消，还没开始的 N 集不再缓存" 或 "已取消，并删除了这一批已经开始的 N 集"。
- 注意: 没有进行中的批次时返回 "现在没有正在进行的自动缓存"。传 `remove=1` 时最长等约 30 秒删除完成；约 6 秒后还会再检查一遍，删掉取消后才建好的那一集。

### POST api/cache/names
修改当前选资源会话的搜索名与集数，并立即重新搜索。
- 参数: `subject` 条目 id（必填）；`episode` 剧集 id（必填）。`reset` 可选，传 `1` 时搜索名与集数都恢复为 Bangumi 默认值，此时忽略其余参数。`primary` 是主搜索名，不传 reset 时必填且不能为空。`others` 是次要搜索名，每行一个，用 `\n` 分隔。`sort` 是系列内剧集序号，`ep` 是条目内序号，两者至少填一个。
- 返回: 返回 ok/message。改了名字时提示这部番以后缓存和播放都用这个搜索名；只改集数时提示只影响这一集。
- 注意: 要求选资源会话正是这一集，否则返回 "列表已过期，请重新打开这一集"。搜索名按条目持久保存，这部番以后的缓存和播放都会用它，同时清除这部番在线源的搜索缓存。`sort` / `ep` 只影响这一集的这次查询，不保存。提交后继续调用 candidates 查看新结果。

### POST api/cache/close
关闭当前的选资源会话，停止它的数据源查询。
- 参数: 无。
- 返回: 固定返回 `{"ok": true, "message": ""}`。
- 注意: 看完候选但不 pick 时调用。pick 成功后会话已自动关闭，不用再调。

### GET api/caches
列出电视上的全部缓存，按番分组，同时返回剩余空间和下载速度。
- 参数: 无。
- 返回: `count` 是缓存条数；`used` 是已占用空间，`pending` 是没下完的缓存还差多少；`free` / `total` / `freeLabel` 是新缓存所在盘的空间，`lowSpace` 表示剩余空间不够下完；`downloading` 是正在下载的条数，`speed` 是总速度（如 "1.2 MB/s"）。`btStarting` 表示 BT 服务正在启动，`tvBackground` 表示电视上没打开 Izuko，`bgRestricted` 表示系统限制了后台运行，BT 在后台很快会停。以上大小都是文字。 `groups[]` 含 `id`（条目 id）、`title`、`meta`（如 "已完成 3 / 5 集 · 2.1 GB"）、`cover[]`（按顺序尝试的封面地址）、`blur` 和 `items[]`。每个 item 含 `cid`（缓存 id）、`label`、`st` 状态类别、`text` 状态文字、`progress`（`run` / `paused` 时为 0~100）、`size`（"已下 / 总共" 或总大小）、`speed`、`source`（数据源名）、`watched`（如 "已看 40%"）、`pack`（合集）、`packShare`（同一合集里还有几集）和 `playing`（电视正在播这条）。 `st` 取值为 `run` / `paused` / `done` / `failed` / `merging` / `loading`。`loading` 表示状态还没读出来，例如 BT 服务没连上或盘没接上，原因写在 `text`。
- 注意: 速度按相邻两次调用之间已下载字节数的差计算，第一次调用或两次间隔超过 30 秒时没有速度。网页每 2 秒调用一次。读取超过约 8 秒时返回 "读取缓存失败，请重试"。

### POST api/caches/play
在电视上播放这条缓存对应的那一集。
- 参数: `id` 缓存 id（必填，取 `api/caches` 的 `cid`）。
- 返回: 返回 ok/message，成功时另带 `player: true`，表示电视已进入播放页，之后可以用播放器相关接口。
- 注意: 电视正在播这部番时会就地换集。电视进入这一集的播放页，选源时优先用缓存。没下完的 BT 缓存会边下边播；下载中的网页 (m3u8) 缓存还不能播，电视会从其它数据源播放，message 会说明。 Izuko 已退出但控制台还在时，如果开了「从手机打开 Izuko」并授权，会先拉起应用（最多等约 10 秒），否则返回 ok=false 并提示先在电视上打开 Izuko。"这条缓存已经不在了，请刷新" 表示 id 已失效。

### POST api/caches/open
在电视上打开这部番的详情页。
- 参数: `subject` 条目 id（必填）。
- 返回: 返回 ok/message。
- 注意: Izuko 界面不在时的处理与 `api/caches/play` 相同。

### POST api/caches/pause
暂停缓存。可以只暂停一条，也可以一次暂停多条。
- 参数: `id` 是单条缓存 id；或用 `ids` 传多个缓存 id，用英文逗号分隔，可以跨番。带了 `ids` 时忽略 `id`。
- 返回: 返回 ok/message，例如 "已暂停" 或 "已暂停 N 集"。
- 注意: 操作在后台执行，状态在下一次 `api/caches` 中更新。涉及没下完的 BT 缓存而 BT 服务没连上时，返回 "BT 服务未连接，请稍后再试"。`ids` 为空时返回 "请先选择要操作的剧集。"；一条都找不到时返回 "找不到所选缓存，请刷新列表。"。

### POST api/caches/resume
继续下载已暂停的缓存。可以只操作一条，也可以一次操作多条。
- 参数: 同 `api/caches/pause`（`id` 或 `ids`）。
- 返回: 返回 ok/message，例如 "继续下载" 或 "继续下载 N 集"。
- 注意: 失败情形同 `api/caches/pause`。

### POST api/caches/delete
删除缓存，不可恢复。可以删一条，也可以一次删多条。
- 参数: `id` 是单条缓存 id；或用 `ids` 传多个缓存 id，用英文逗号分隔，可以跨番。带了 `ids` 时忽略 `id`。
- 返回: 返回 ok/message。删除完成时为 "已删除「…」" 或 "已删除 N 集缓存"；超时未完成时为 "正在删除…"，此时删除仍在后台继续。
- 注意: 单条最长等约 10 秒，多条最长等约 30 秒。删除合集里的一集不会立刻腾出空间，同一合集的集都删完后才整体回收。删除时会记下这些缓存用的数据源，在应用重启前，随后 `api/cache/auto` 重下这部番时会沿用它。

### POST api/caches/delete-subject
删除一部番在电视上的全部缓存，不可恢复。
- 参数: `subject` 条目 id（必填）。
- 返回: 返回 ok/message，例如 "已删除 N 集缓存"；超时时为 "正在删除 N 集，请稍后刷新。"。
- 注意: 这部番没有缓存时返回 "这部番已经没有缓存了，请刷新"。最长等约 30 秒。和单条删除一样，会记下所用的数据源，供之后的自动缓存沿用。

## 数据源与网盘

管理电视上的数据源 (启停、排序、测试、导入导出、编辑)、数据源订阅, 以及网盘与本地媒体 (云盘登录、WebDAV 服务器、本地文件夹、媒体文件夹)。
典型顺序: `GET api/sources` 取每个源的 `id` → `POST api/sources/enable` / `test` / `move`; 新装先 `POST api/sources/subs/add`, 轮询 `GET api/sources/subs` 到 `updating` 为 false 再看 `GET api/sources`; 网盘先 `GET api/drives` 取 `id` (即各 `api/drive/*` 的 `drive` 参数) 再登录。
数据源改动从下一个播放会话 (下一集 / 重新进播放器) 起生效, 正在播的要立刻用新列表可调 `POST api/player/refetch`。写完立刻 `GET api/sources` 会等列表追上写入 (最多约 3 秒), 读到的就是新状态。
`api/drive/*` 的 POST (除 `folders` 成功时) 返回与 `GET api/drives` 同形的对象 (`ok`、`message`、`drives`); 必填的 `drive` 缺失或不认识时 `ok: false`「没有这个网盘，请刷新」; 处理中出异常 (如网盘核对登录失败) 只回 `ok: false` 与「操作失败：原因」。
下文引用的 message 是中文界面的原文, 文案跟随应用语言。

### GET api/sources
列出全部数据源 (不含本地缓存源) 与可新增的类型。
- 参数: 无
- 返回: `sources[]`: `id` (实例 id, 本章各接口的 `id`)、`factoryId`、`factoryName`、`name`、`description`、`kind` (中文界面为「在线」或「BT」)、`enabled`、`subscription` (来自订阅时为订阅地址, 找不到订阅记录时为空串; 本地添加的为 null)、`origin` (订阅短名: GitHub 地址为「用户/仓库」, 其余为域名; 本地添加的为 null)、`tier` (层级, 越小越优先; 未配置时不给, 选源按 2)、`channelTiers` (线路名 → 层级, 可无)、`webViewDemoted` (要 WebView 而被降一级时为 true, 否则不给)、`editor` (`params` 按参数表单编辑 / `json` 编辑 JSON / `none` 不可编辑; 订阅来的恒为 `none`)、`exportable`、`params[]` (仅 `editor` 为 `params`)、`profile` (画像, 没记录时不给)。
- `profile`: `plays`、`tests`、`failures`、`width`、`height`、`codec`、`kbps`、`webView`、`hlsPlays`、`adPlays` (删到插播广告的次数)、`startupMs`、`searches`、`searchMs`、`searchFailures`、`lastAt` (毫秒时间戳)、`suggestedTier`、`reasons[]` (`ADS` / `WEB_VIEW` / `BELOW_1080P` / `FAILURES` / `SEARCH_FAILS`); 没测到的数值字段省略。
- `params[]` 每项: `name`、`description`、`type` (`boolean` / `enum` / `string`)、`value` (当前值, 没存过为默认值)、`options[]` (enum)、`placeholder` 与 `required` (string)、`visibleWhen` {`param`, `values[]`} (另一参数取这些值时才显示)。
- `templates[]`: 可新增的类型, `factoryId`、`name`、`description`、`editor`、`params[]` (仅 params, `value` 为默认值); 只允许一个实例且已存在的类型不列, 网盘类 (`cloud-drive`、`cloud-drive-added-shares`) 也不列 (网盘配置靠导入或订阅)。

### GET api/sources/export
导出一个数据源的 JSON (与设置页「导出」同格式)。
- 参数: `id` 实例 id (必填)
- 返回: `ok`、`message` (空)、`json` (字符串, 缩进好的 `{factoryId, version, arguments}`)。
- 注意: 只对 `exportable` 为 true 的源有效, 否则 `ok: false`「这个数据源不支持导出」; 订阅来的源也能导出。`json` 可原样作 `save-json` / `import` 的 `text`。

### POST api/sources/test
测试连接 (各类源自己判定, 如拉一次首页或调一次接口)。
- 参数: `id` (必填)
- 返回: 返回 ok/message; 通时「「名」连接正常」, 不通「…连接失败，检查地址、网络或代理设置」, 超时「…测试超时，可能是网络不通或站点很慢」。
- 注意: 最长约 20 秒; 停用的源也能测。

### POST api/sources/deeptest
开始深度测试: 用「葬送的芙莉莲」第 1 集搜一次、解析出结果的视频地址、探测分辨率与码率, 在后台跑。
- 参数: `id` (必填)
- 返回: 当前进度对象 (字段同 `GET api/sources/deeptest`), 刚开始时为 `{"state":"running","stage":"search"}`。
- 注意: 同一个源正在测时不会重开。搜索最长 60 秒、解析 40 秒、探测 30 秒, 整体可能一两分钟; BT 源只测搜索。结果记进该源画像 (标为测试), 测完 `GET api/sources` 的 `profile` 跟着变。

### GET api/sources/deeptest
查深度测试的进度与结果。
- 参数: `id` (必填)
- 返回: `state` (`none` 没测过 / `running` / `done`)、`stage` (running 时: `search` / `resolve` / `probe`)、`searchMs`、`count` (搜到条数)、`title` (选中结果的标题)、`resolveMs`、`webView` (解析是否走了 WebView)、`probeMs`、`container`、`width`、`height`、`codec`、`kbps`、`error`、`detail` (错误说明); 没走到的阶段字段省略。不含 ok/message。
- `error`: `missing` (源不存在) / `search_failed` / `search_timeout` / `search_empty` / `not_web` (BT 源只测搜索) / `needs_webview` (要 WebView 嗅探, 不在播放页测不了, 不算失败) / `resolve_failed` / `probe_failed` / `failed`。
- 注意: 每 2 秒左右查一次直到 `state` 不是 `running`。结果只在内存里, 应用进程重启后回到 `none`。只认 GET, HEAD 回 405。

### POST api/sources/enable
启用或停用数据源, 可批量。
- 参数: `enabled` (`1` 启用, 其它值停用); `ids` (换行分隔的多个实例 id) 或 `id` (单个), 有非空 `ids` 时忽略 `id`。
- 返回: 返回 ok/message (成功时 message 为空)。
- 注意: 不校验 id 是否存在。订阅来的源也能启停。

### POST api/sources/move
把数据源在列表里上移或下移一格。
- 参数: `id` (必填); `dir` (`up` 上移, 其它值如 `down` 下移)
- 返回: 返回 ok/message; 已在顶 / 底时 `ok: true` 且不变。
- 注意: 找不到 `id` 时「数据源不存在，请刷新」(export / test / delete / copy / save-params / save-json 同)。

### POST api/sources/delete
删除一个本地添加的数据源。
- 参数: `id` (必填)
- 返回: 返回 ok/message (成功「已删除」)。
- 注意: 订阅来的源 (`subscription` 不为 null) 拒绝删除, 它会随订阅更新回来; 要删就删整个订阅 (`POST api/sources/subs/delete`), 或停用它。

### POST api/sources/copy
把一个数据源复制为本地源 (去掉订阅归属, 之后可编辑)。
- 参数: `id` (必填)
- 返回: 返回 ok/message。
- 注意: 不返回新 id, 复制品在 `GET api/sources` 里 `subscription` 为 null。原来的源保留 (可另行停用)。只允许一个实例的类型失败「这个类型只能有一个实例，无法复制」。

### POST api/sources/import
导入数据源, 每一个都新建为本地源。
- 参数: `text` (必填), 可以是: 单个 `{factoryId, version, arguments}`; 列表 `{mediaSources:[…]}` 或直接 JSON 数组; 订阅内容 `{exportedMediaSourceDataList:{mediaSources:[…]}}`; AniBaka 规则 (`format` 以 `anx-rule/` 开头的对象或其数组); AniBaka 规则或规则库索引 (`format` 以 `anx-rulehub/` 开头) 的 http(s) 地址, 由电视下载。
- 返回: `ok` (至少导入了一个)、`message` (导入数量与各失败原因)、`added` (导入个数)。
- 注意: 请求体 (URL 编码后) 上限 64 KiB, 超了回 HTTP 413: 大列表拆成几个 JSON 数组分批发, 自己累加 `added`。地址只认 AniBaka, 规则库索引只能用地址导入; 别的格式要贴内容。不去重 (导两次就有两份), 部分失败照样导入其余的; 只允许一个实例且已存在的类型记为失败。不返回新 id。

### POST api/sources/save-params
新增或编辑参数表单类数据源 (`editor` 为 `params`)。
- 参数: `id` (编辑时) 或 `factoryId` (新增时, 取自 `templates`); 每个参数一个字段 `p:<参数名>`: string 填文本, enum 填 `options` 之一, boolean 传 `true` 为开、不传为关。
- 返回: 返回 ok/message (「已保存」/「已添加」)。
- 注意: 整份替换参数, 没传的存为空串或 false, 编辑时要带上全部参数。string 参数按各自规则校验 (被 `visibleWhen` 隐藏的不校验), 不过时「请检查：参数名、…」。只用于 `editor` 为 `params` 的源; 订阅来的拒绝「订阅来的源不能编辑」; 只允许一个实例且已存在的类型新增失败。新增不返回新 id。

### POST api/sources/save-json
用 JSON 覆盖一个 JSON 类数据源 (`editor` 为 `json`) 的配置。
- 参数: `id` (必填); `text` (必填, 单个 `{factoryId, version, arguments}`, 一般由 `GET api/sources/export` 取来再改)
- 返回: 返回 ok/message (「已保存」)。
- 注意: 必须恰好一个数据源且 `factoryId` 与原来相同, 否则「编辑时只能粘贴一个数据源（现在有 N 个）」/「类型不一致：…」; 解析不了「不是有效的数据源 JSON」。订阅来的拒绝。保存前校验并升级到当前版本。

### GET api/sources/subs
数据源订阅列表与更新状态。
- 参数: 无
- 返回: `updating` (是否在后台更新)、`current` / `total` (更新中才有: 正在拉第几个 / 共几个)、`restoreOrder` (「订阅更新时恢复订阅里的顺序」开关)、`items[]`: `id`、`url`、`period` (更新周期文案)、`status` (上次更新结果文案)、`failed` (上次更新失败时为 true, 否则不给)。不含 ok/message。

### POST api/sources/subs/add
添加一个订阅地址并在后台拉取。
- 参数: `url` (必填, http:// 或 https:// 开头)
- 返回: 返回 ok/message (成功「已添加，正在拉取订阅里的数据源」)。
- 注意: 去掉首尾空白后与已有地址相同时「这个订阅已经添加过了」。拉取要几秒, 只拉新加的与已到期的订阅; 轮询 `GET api/sources/subs` 到 `updating` 为 false, 再 `GET api/sources` 看新源。

### POST api/sources/subs/delete
删除订阅, 连同它带来的全部数据源。
- 参数: `ids` (逗号分隔的多个订阅 id) 或 `id` (单个); 带了 `ids` 就只看 `ids`。
- 返回: 返回 ok/message。
- 注意: 一个都找不到时「找不到这个订阅」/「找不到这些订阅」。

### POST api/sources/subs/refresh
强制重新拉取全部订阅。
- 参数: 无
- 返回: 返回 ok/message; 已在更新时 `ok: false`「正在更新，稍等一下」。
- 注意: 后台执行, 进度看 `GET api/sources/subs`。订阅来的源的配置随远端更新, 本地对它们的修改不保留。

### POST api/sources/subs/restore-order
设置「订阅更新时恢复订阅里的顺序」。
- 参数: `on` (`1` 开, 其它值关)
- 返回: 返回 ok/message (message 为空)。

### GET api/drives
列出已配置的网盘与本地媒体 (订阅或导入的云盘、WebDAV 服务器、本地文件夹) 与登录状态。
- 参数: 无
- 返回: `ok`、`message`、`drives[]`: `id` (网盘 id, 即 `drive` 参数)、`name`、`type` (空串 = 云盘 / `webdav` / `local`)、`sourceId` / `sourceName` (这个网盘自己的数据源)、`enabled` (该数据源是否启用; 还没建出来时为 true)、`loggedIn`、`expired`、`nickname`、`username`、`tier` (会员档位名, 可空)、`transcode` (当前是否转码播放)、`supportsTranscoded`、`supportsQr`、`appName` (扫码用的 App)、`mobileOpen` {`android`, `ios`, `inAppUserAgent`} (手机唤起 App 的链接, 支持扫码时才有)、`cookieHint`、`shares` (能播别人的分享)、`supportsPassword`。
- `webdav` 另有 `server`; `local` 另有 `accessible` (此刻能读)、`grantAllFiles` (true 要开「所有文件访问」, false 要「存储」权限)、`grantAdb` (授权用的 adb 命令); 这两类还有 `depth` (往下找几层) 与 `library[]` {`id`, `path`} (媒体文件夹)。
- `qr` (开始扫码后、取消或退出前才有): `state` = `loading` / `waiting` (另有 `link` 二维码内容、`expiresIn` 剩余毫秒) / `confirmed` (手机已确认, 电视在换登录态) / `success` / `expired` / `failed` (另有 `message`)。
- 注意: 不向网盘服务器核对 (那是 `api/drive/check`), `expired` 只反映最近一次核对。从不返回 Cookie 与密码。

### GET api/drive/qr.svg
当前登录二维码的图片, 给另一台设备扫。
- 参数: `drive` (必填)
- 返回: `image/svg+xml`。
- 注意: 只在该网盘 `qr.state` 为 `waiting` 时有, 否则 HTTP 404。

### POST api/drive/check
向网盘服务器核对登录态, 顺便更新昵称与档位。
- 参数: `drive` (可选; 不传则核对全部已登录的网盘)
- 返回: 同 `GET api/drives`。
- 注意: 并行核对, 每个最长 15 秒; 失效的 `expired` 变为 true (旧 Cookie 保留, 等重新登录覆盖)。未登录的跳过; `drive` 不认识时什么都不核对, 照样 `ok: true`。

### POST api/drive/qr/start
在电视上开始扫码登录 (同一网盘之前的扫码作废)。
- 参数: `drive` (必填)
- 返回: 同 `GET api/drives`, 刚开始时 `qr.state` 多为 `loading`。
- 注意: 之后每 2 秒左右 `GET api/drives` 看 `qr`: `waiting` 时把 `link` 做成二维码或用 `GET api/drive/qr.svg`, 手机上也可打开 `mobileOpen` 的链接唤起 App 确认; `success` 即已登录; `expired` 时再调本接口换新码。不支持扫码的网盘 `qr.state` 为 `failed`。

### POST api/drive/qr/cancel
取消该网盘正在进行的扫码。
- 参数: `drive` (必填)
- 返回: 同 `GET api/drives` (该网盘不再有 `qr`)。

### POST api/drive/cookie
用 Cookie 登录网盘。敏感: Cookie 只写不读。
- 参数: `drive` (必填); `cookie` (必填, 登录后的 Cookie 头内容, 可带 `Cookie:` 前缀; 取法见 `cookieHint`)
- 返回: 同 `GET api/drives`, 成功「已登录<网盘名>」。
- 注意: 先向服务器核对, 通过才保存; 缺协议要求的 Cookie 项 (「操作失败：Cookie 里没有 …」) 或核对失败时保留原登录。空值「先把 Cookie 粘到框里」。

### POST api/drive/password
用账号密码登录 (`supportsPassword` 为 true 的网盘, 即 WebDAV)。敏感: 密码只写不读。
- 参数: `drive` (必填)、`username` (必填)、`password`
- 返回: 同 `GET api/drives`, 成功「已登录<名>」。
- 注意: 先列一次根目录核对, 失败回「操作失败：…」并恢复原账号。

### POST api/drive/logout
退出网盘登录 (清掉 Cookie 或账号密码, 保留转码设置), 并取消进行中的扫码。
- 参数: `drive` (必填)
- 返回: 同 `GET api/drives`, 「已退出<名>」。

### POST api/drive/transcode
设置该网盘播放用转码还是原画。
- 参数: `drive` (必填); `on` (`1` 转码, 其它值原画)
- 返回: 同 `GET api/drives`。
- 注意: 未登录也能设; 网盘有没有转码见 `supportsTranscoded`。

### POST api/drive/enable
启用这个网盘自己的数据源 (`sourceId` 那个)。
- 参数: `drive` (必填)
- 返回: 同 `GET api/drives`, 「已启用」或「已经启用了」; 没有该数据源时 `ok: false`「没有找到「名」的数据源，请检查订阅」。

### POST api/drive/webdav/add
添加一台 WebDAV 服务器 (NAS) 并登录。敏感: 密码只写不读。
- 参数: `url` (必填, http:// 或 https:// 开头)、`username` (必填)、`password`、`name` (可选, 默认「NAS」)
- 返回: 同 `GET api/drives`, 「已添加<name>」。
- 注意: 新建数据源后列一次根目录核对; 连不上或账号密码不对时删掉刚建的, 回「操作失败：…」。不返回新 id, 在 `drives` 里按 `type` 为 `webdav` 与 `server` 找。之后可用 `api/drive/folders` + `api/drive/library` 选媒体文件夹 (不选则播放时在整个服务器里按番名找)。

### POST api/drive/webdav/edit
改 WebDAV 服务器的名字与地址, 或本地文件夹的名字。
- 参数: `drive` (必填); `name` (可选, 空则不改); `url` (可选, 空则不改; 本地文件夹忽略)
- 返回: 同 `GET api/drives`, 「已保存」。
- 注意: 只对 `type` 为 `webdav` / `local` 的, 其它「订阅来的网盘不能在这里修改」。改完向服务器核对一次登录 (最长 15 秒)。地址不是 http(s)「地址要以 http:// 或 https:// 开头」。

### POST api/drive/local/add
添加一个本地文件夹数据源 (U 盘、移动硬盘、内部存储)。
- 参数: `name` (可选; 默认「本地文件夹」, 重名时自动编号)
- 返回: 同 `GET api/drives`, 「已添加<name>」。
- 注意: 设备读不了本地文件夹时 `ok: false`「这台设备不能读本地文件夹」。没有读取权限也会加上, 看 `accessible`, 缺权限调 `POST api/drive/local/grant`。不返回新 id。

### POST api/drive/local/grant
在电视上打开读文件的授权页面 (「所有文件访问」设置页或「存储」权限框)。
- 参数: 无
- 返回: 同 `GET api/drives`; 已授权「已经允许了」; 打开了则 `ok: true`, message 说明要在电视上选什么; 电视没有授权入口时 `ok: false`, message 里给 adb 命令。
- 注意: 要人在电视上用遥控器操作, 之后再 `GET api/drives` 看 `accessible`。

### POST api/drive/folders
浏览 WebDAV 服务器或本地各块盘的子文件夹 (选媒体文件夹用)。
- 参数: `drive` (必填); `fid` (可选, 文件夹 id, 空为根)
- 返回: `ok`、`id` (当前文件夹)、`path` (给人看的路径)、`parent` (上级 id, 在根时为空串)、`chosen` (已是媒体文件夹)、`choosable` (能否设为媒体文件夹)、`folders[]` {`id`, `name`}。
- 注意: 只对 `type` 为 `webdav` / `local` 的, 其它 `ok: false`「这个网盘不能选媒体文件夹」(只回 ok/message)。本地的根列出的是各块盘, 根本身不能选。

### POST api/drive/library
增删媒体文件夹, 或改往下找几层 (仅 `webdav` / `local`)。
- 参数: `drive` (必填); `action` (`add` / `remove` / `depth`); `fid` (add / remove 时, 取自 `api/drive/folders` 的 `id`); `depth` (depth 时, 1~3, 超出取边界, 不是数字则不改)
- 返回: 同 `GET api/drives`, 「已加为媒体文件夹」/「已移出媒体文件夹」/ 空。
- 注意: 层数 1 = 媒体文件夹 / 番名, 2 = 媒体文件夹 / 季度 / 番名。

### POST api/drive/remove
删除用户自己加的 WebDAV 服务器或本地文件夹 (连同账号密码与媒体文件夹; 服务器或盘上的文件不动)。
- 参数: `drive` (必填)
- 返回: 同 `GET api/drives`, 「已删除<名>」。
- 注意: 订阅来的网盘拒绝「订阅来的网盘不能在这里删除」。

## 设置、账号与用户

本章接口用来在手机上改电视的设置（代理、Bangumi 连接方式、TMDB 图片、弹幕屏蔽词、通用设置项、PikPak 账号、设置备份），给电视登录或退出 Bangumi，以及管理电视上的多个用户（添加、切换、导入、导出）。
改设置的顺序：先 `GET api/settings` 读当前值，再 POST 对应接口。改完立即生效，不用重启。
密码只写不读：返回里只有 `hasPassword`，提交时密码留空表示保留原密码。唯一的例外是设置备份导出，导出内容里带 Bangumi 登录凭据。
账号和各标签的数据都属于当前用户。当前用户是本地用户时（`GET api/account` 返回 `local: true`）不能登录，`api/account` 下的所有 POST 都回 ok=false。
登录 Bangumi：先调 `GET api/account?host=<IP:端口>`，按返回的 `loginPath` 和 `phoneRelay` 选下面一条路，然后轮询 `GET api/account`，直到 `loggedIn` 为 true。
- 手机授权（`loginPath` 为 `browser` 且 `phoneRelay` 为 true）：调 `POST api/account/login` 并带上 `host`。把返回的 `url` 交给用户，用户在能连到电视的手机或电脑浏览器里打开并授权，授权完浏览器会自动跳回电视。
- 电视授权（`loginPath` 为 `browser`）：调 `POST api/account/login` 并带上 `where=tv`。电视上会弹出授权页，用户用遥控器完成授权。
- 个人令牌（不受 `loginPath` 限制；`loginPath` 为 `token`，即经第三方镜像连接时，只能走这条）：用户在 `tokenPages` 给出的页面生成令牌，再调 `POST api/account/token`。经镜像连接且 `mirrorCred` 为 false 时，要先调 `POST api/settings/bangumi/cred`（`on=1`）。
- 账号密码（`loginPath` 为 `web`，或 `loginPath` 为 `browser` 但 `phoneRelay` 为 false）：先调 `POST api/account/weblogin/start`，把返回的验证码图片给用户看，再调 `POST api/account/weblogin/submit` 提交邮箱、密码和验证码。电视会替用户登录 Bangumi 网页并生成个人令牌。

### GET api/settings
读取网页「设置」里能改的各项设置的当前值。
- 参数: 无。
- 返回: 一个对象，没有 ok 字段，包含以下几块：
  - `proxy`：`mode`（`DISABLED` / `SYSTEM` / `CUSTOM`）、`url`、`username`、`hasPassword`。
  - `bangumi`：`routes`（`origin` / `custom` / `ech` / `mirrors` 四条线路各自开没开）、`echAvailable`（这台设备有没有加密直连）、`custom`（自建镜像的地址）、`mirrors[]`（社区镜像清单）、`allowCredentials`（登录与收藏同步是否也经过镜像）、`loggedIn`。
  - `tmdbImages`：`disabled`、`mode`（`AUTO` / `FIXED` / `CUSTOM`）、`fixed`、`custom`、`hosts[]`（可选的图片入口）。
  - `generic[]`：通用设置项，字段见下一条。
  - `pikpak`：`enabled`（电视上开没开 PikPak）、`username`、`hasPassword`、`signedIn`（有没有登录会话）。
  - `front`：`enabled`、`granted`（电视上有没有授予「显示在其他应用的上层」权限）、`needsPermission`（系统要不要这项权限）。
  - `keep`：`enabled`。
  - `dmfilter`：`enabled`（总开关）、`items[]`（每项为 `{id, regex, on}`）。
  - debug 包另有 `debug` 块，见调试一章。
- 返回（续）: `generic[]` 每项的字段：
  - `key`：提交时用的名字。
  - `page`：网页上的分组，`play` 为播放与弹幕，`bt` 为数据源的 BT 页。
  - `title`、`description`；`placeholder` 可能没有。
  - `editor`：编辑方式，取值为 `lines` / `text` / `toggle` / `number` / `choice`。
  - `value`：当前值。`lines` 是字符串数组，`toggle` 是布尔，`number` 是数字，其余是字符串，也可能是 null。
  - `status`：可能没有，是一段状态文字。
  - `action`：可能没有。有这个字段说明可以调 `api/settings/generic/action`，它的值是按钮上的文字。
  - `number` 类另有 `min` / `max`（可能没有）和 `step: "any"`（表示可以填小数）。
  - `choice` 类另有 `choices[]`，每项为 `{value, label}`。

### POST api/settings/generic/set
修改一项通用设置。
- 参数: `key`（必填，取 `generic[].key`）。`value` 按这一项的 `editor` 写：
  - `lines`：每行一项，用换行分隔。首尾空白和空行会被去掉，重复的行只保留一个；个别项逗号也算分隔。传空串表示清空。
  - `text`：原样保存。字段可为 null 时，传空串保存为 null。
  - `toggle`：传 `1` 或 `true` 表示开，其他值（包括空串）表示关。
  - `number`：整数字段只接受整数，带 `step: "any"` 的字段可以填小数。超出 `min`~`max` 会被拒绝。
  - `choice`：必须是 `choices[].value` 中的一个；空串也可能是合法选项。
- 返回: 返回 ok/message。成功时为 "已保存「标题」"，个别项会另起一行附上说明。
- 注意: 新值整体覆盖旧值，不会追加。改 `lines` 时，要保留的行也要一起传上去。只要有一行格式不对（比如 tracker 地址、IP 规则或正则写错），整次都不保存，message 会指出是哪一行。"没有这一项设置：…" 表示 key 不对，或者这台设备上没有这一项。

### POST api/settings/generic/action
触发某项设置的附加按钮，比如 Tracker 订阅的「立即更新」。
- 参数: `key`（必填，只对 `generic[]` 里带 `action` 的项有效）。
- 返回: 返回 ok/message，message 是这次操作的结果。
- 注意: 同步执行，可能要等联网完成。完成后，`GET api/settings` 里这一项的 `status` 会更新。

### POST api/settings/proxy
保存代理设置。
- 参数:
  - `mode`（必填）：`DISABLED` 表示不用代理，`SYSTEM` 表示跟随系统，`CUSTOM` 表示自定义。
  - `url`：`CUSTOM` 时必填，例如 `http://192.168.1.2:7890` 或 `socks5://192.168.1.2:1080`。其他模式下忽略这个参数，保留原来的地址。
  - `username`（可选）：留空表示不用认证，同时清掉密码。
  - `password`（可选）：留空表示保留原来的密码。
- 返回: 返回 ok/message。
- 注意: 想保留认证，每次保存都要带上 `username`。`SYSTEM` 在电视上通常等于不用代理。"代理地址格式不对…" 表示 `url` 不合法。

### POST api/settings/proxy/test
按当前已保存的代理，测试各项服务能不能连上。
- 参数: 无。
- 返回:
  - `ok`。
  - `message`："全部连接正常" 或 "部分服务连不上"。
  - `items[]`：每项含 `name`（Bangumi、Bangumi Next、TMDB 接口、TMDB 图床）、`ok`、`text`。`text` 成功时为毫秒数（如 "123 ms"），失败时为 "连接失败"。
- 注意: 最长约 25 秒，超时返回 ok=false 和 "测试超时"。

### POST api/settings/bangumi
设置 Bangumi 的连接方式，即开启哪几条线路。
- 参数:
  - `routes`（必填，固定传 `1`）。不传会返回 "无效的连接方式"。
  - `route_origin`、`route_custom`、`route_ech`、`route_mirrors`：分别对应官方直连、自建镜像、加密直连、社区镜像。传 `1` 表示开这条线路，不传或传其他值表示关。至少要开一条。
  - `custom`：自建镜像的根域名，例如 `bangumi.example.com`。开了 `route_custom` 时必填。
  - `cred`：传 `1` 表示登录与收藏同步也经过镜像，不传表示关。
- 返回: 返回 ok/message。
- 注意: 这组设置整体覆盖，没传的线路和 `cred` 都会被关掉。提交前，先从 `GET api/settings` 的 `bangumi.routes` / `allowCredentials` 读出要保留的值，一起传上。如果登录着、只剩社区镜像、`cred` 又关着，所有需要登录的请求（收藏、进度）都会失败，服务端不会阻止这种组合。`echAvailable` 为 false 的设备不能用加密直连。

### POST api/settings/bangumi/cred
只修改「登录与收藏同步也经过镜像」这一项，线路设置不变。
- 参数: `on`：传 `1` 表示开，其他值表示关。
- 返回: 返回 ok/message。
- 注意: 经第三方镜像连接时，不开这一项就登录不了。开了之后，登录凭据也会经过第三方镜像。

### POST api/settings/tmdb-images
设置 TMDB 背景图从哪里加载。
- 参数:
  - `choice`（必填），取值为：`auto`（自动选择入口）、`off`（不加载 TMDB 背景图）、`custom`（用 `custom` 参数里的地址），或者 `tmdbImages.hosts[]` 中的某个地址（只用这一个）。
  - `custom`：`choice=custom` 时必填。写成 `https://域名`（可以带路径），或者写成含 `{path}` 的模板。
- 返回: 返回 ok/message。
- 注意: "认不出这个地址…" 表示 `custom` 不合法。"无效的选项" 表示 `choice` 不是上面几种之一。

### POST api/settings/front
开关「从手机打开 Izuko」。开启并授权后，网页让电视换页面、或者操作播放器时，如果 Izuko 不在前台，会先把它切到前台。
- 参数: `on`：传 `1` 表示开，其他值表示关。
- 返回: `ok`、`message`、`enabled`、`granted`、`needsPermission`，后三个字段的含义同 `GET api/settings` 的 `front`。
- 注意: `needsPermission` 为 true 时，需要在电视上授予「显示在其他应用的上层」权限。开启时如果还没授权：Izuko 在前台，会立即在电视上打开系统授权页；Izuko 在后台，则在 30 分钟内 Izuko 回到前台时打开授权页。切换用户，以及 Izuko 已退出时从手机搜索或点播，都依赖这个开关。

### POST api/settings/keep
开关「电视休眠或离开 Izuko 后仍保持连接」。开启后，按返回键退出 Izuko 时进程和网页控制台会保留下来，同时启动一个前台服务。
- 参数: `on`：传 `1` 表示开，其他值表示关。
- 返回: `ok`、`message`、`enabled`。
- 注意: Android 12 及以上，在 Izuko 处于后台时开启，要等下次打开 Izuko 才生效，message 里会说明。

### POST api/settings/dmfilter/switch
开关弹幕屏蔽词功能。
- 参数: `on`：传 `1` 表示开，其他值表示关。
- 返回: 返回 ok/message。
- 注意: 立即生效，正在播放的弹幕会马上按新规则重新过滤。

### POST api/settings/dmfilter/add
添加一条弹幕屏蔽词。
- 参数: `regex`（必填）：要屏蔽的词或正则，不区分大小写，首尾空白会被去掉。
- 返回: 返回 ok/message。新条目的 id 要从 `GET api/settings` 的 `dmfilter.items[]` 里取。
- 注意: 新加的条目默认启用。内容按正则解释，只想按字面屏蔽时，`( ) [ ] . * + ?` 前面要加 `\`。正则写错会返回 "正则写法有误…"，已经有相同条目会返回 "这条已经有了"。

### POST api/settings/dmfilter/toggle
启用或停用一条屏蔽词。
- 参数: `id`（必填，取 `dmfilter.items[].id`）；`on`：传 `1` 表示启用，其他值表示停用。
- 返回: 返回 ok/message，成功时 message 为空串。
- 注意: 返回 "找不到这条屏蔽词" 表示 id 不对。

### POST api/settings/dmfilter/delete
删除一条屏蔽词。
- 参数: `id`（必填）。
- 返回: 返回 ok/message。

### POST api/settings/pikpak
保存 PikPak 账号。「启用 PikPak」开关只能在电视上改。
- 参数: `username`（首尾空白会被去掉）和 `password`，按以下规则处理：
  - `username` 与已保存的不同：视为换账号。保存这次传的 `password`（没传就保存为空，之后需要补上），并清掉登录会话。
  - `username` 为空：删除账号，密码也一起清掉。
  - `username` 不变、`password` 不为空：更换密码，并清掉登录会话。
  - `username` 不变、`password` 为空：什么都不改。
- 返回: `ok`（总是 true）、`message`、`pikpak`（字段同 `GET api/settings` 的 `pikpak`）。
- 注意: 每次都要带上 `username`，不带等于删除账号。保存后不会马上登录，下次用到 PikPak 时才登录。想当场验证，调 `api/settings/pikpak/test`。

### POST api/settings/pikpak/test
用已保存的账号登录 PikPak，并读一次网盘用量。
- 参数: 无。
- 返回: 返回 ok/message，成功时为 "登录成功，网盘剩余 …"。
- 注意: 最长约 30 秒。返回 "先在电视上打开「启用 PikPak」…"，说明电视上没开 PikPak 或账号信息不全。返回 "这台设备不支持 PikPak"，说明这台设备不能用 PikPak。

### GET api/settings/backup/export
导出当前应用设置的备份。
- 参数: 无。
- 返回: `ok` 和 `content`。`content` 是一段 JSON 文本（字符串），把它原样存成文件即可。内容与电视设置页复制到剪贴板的备份相同。
- 注意: 备份里含 Bangumi 登录凭据，不要外传。PikPak 账号不在备份里。

### POST api/settings/backup/import
用备份覆盖当前的全部设置。
- 参数: 请求体直接放备份原文，即 export 返回的 `content`。格式为 UTF-8 纯文本，不是表单。上限 4 MiB，超过返回 HTTP 413。开头的 BOM 会被去掉。
- 返回: 返回 ok/message。失败时为 "这个文件不是设置备份，或者已经损坏" 或 "文件是空的"。
- 注意: 会覆盖全部设置，无法撤销。备份里的 Bangumi 登录凭据也会写进来。PikPak 账号不会导入。

### GET api/account
读取电视的 Bangumi 登录状态，以及现在能用哪些登录方式。
- 参数: `host`（可选）：访问控制台用的 `IP:端口`，用来计算 `phoneRelay`。
- 返回: 当前用户是本地用户时，只有 `ok`、`local: true`、`loggedIn: false`。其他情况下包含：
  - `loggedIn`。
  - `offline`：因为连不上 Bangumi 才判成未登录，并不是真的退出了登录。
  - 登录着时有 `name`、`bgmName`（Bangumi 用户名）、`avatar[]`（头像地址的候选，先是经电视转发的相对地址 `api/img?u=…`，再是原地址）。
  - `viaMirror`：是否经第三方镜像连接。
  - `mirrorCred`：登录与收藏同步是否也经过镜像。
  - `tokenPages[]`：生成个人令牌的页面。
  - `relay`：这个版本是否带有中转应用的凭据。
  - `phoneRelay`：手机授权完能否跳回电视。
  - `loginPath`：`browser` / `web` / `token`。
  - `login`：进行中的授权。`state` 为 `idle` / `waiting` / `failed`。`waiting` 时有 `url`（手机授权的链接；电视授权时为 null）、`relay`、`expiresIn`（还剩多少毫秒放弃）；`failed` 时有 `message`。
- 注意: 网页在等待授权时每 2 秒轮询一次这个接口。

### POST api/account/login
发起一次 OAuth 授权（手机授权或电视授权）。如果有上一次没完成的授权，会先放弃它。
- 参数:
  - `where`（可选）：传 `tv` 表示在电视上弹出授权页；不传表示手机授权。
  - `host`：手机授权时必填，是访问控制台用的 `IP:端口`。必须是内网 IPv4，即 10.x、172.16~31.x、192.168.x 或 100.64~127.x。
- 返回: `ok`、`message`。手机授权时另有 `url`，是授权页的链接。
- 注意:
  - 手机授权：把 `url` 交给用户，用户在能连到电视的设备上用浏览器打开。授权完，浏览器会跳到电视的 `/bgm-oauth` 页面并显示结果；这个页面不在 `api/` 下，地址里不带 token。
  - 电视授权：授权页盖在电视当前页面之上，用户用遥控器操作。
  - 发起后轮询 `GET api/account`：`loggedIn` 为 true 表示成功；`login.state` 为 `failed` 时看 `message`。10 分钟没有结果会自动放弃。
  - 常见失败：
    - "电视已经登录了"。
    - "现在经镜像连接 Bangumi…"：改用个人令牌。
    - "现在经加密直连…" 或 "这里用不了手机扫码授权…"：改用账号密码登录。
    - "这台电视打不开授权页，改用手机授权"。
    - "这个版本没有带 Bangumi 授权凭据，登录不了"。

### POST api/account/login/callback
手动补救用：授权后浏览器停在一个打不开的页面时，把这个页面的地址交给电视，由电视完成登录。
- 参数: `url`（必填）：浏览器地址栏里的完整地址，包含 `code` 和 `state` 参数。
- 返回: 返回 ok/message，成功时为 "登录成功，电视已登录"。
- 注意: 只认当前这次 `api/account/login` 发起的授权。地址过期或已经用过时，返回 "这个地址没换到登录凭据…"，需要重新发起授权。最长约 15 秒。

### POST api/account/login/cancel
放弃正在等待的授权。
- 参数: 无。
- 返回: 返回 ok/message。

### POST api/account/token
用 Bangumi 个人令牌登录。
- 参数: `token`（必填）。`days`（必填）：令牌的有效天数，取 1~3650 的整数，要与生成令牌时选的一致。令牌到期后电视会退出登录。
- 返回: 返回 ok/message。
- 注意: 调用时会放弃正在等待的授权。经第三方镜像连接（`viaMirror`）且 `mirrorCred` 为 false 时直接拒绝，要先调 `POST api/settings/bangumi/cred`（`on=1`）。常见失败 message："令牌无效或已过期，请重新生成"、"有效期不对"、"电视已经登录了"、"电视连不上 Bangumi…"、"校验令牌超时，请重试"（约 15 秒超时）。

### POST api/account/weblogin/start
开始账号密码登录，取回第一张验证码。
- 参数: 无。
- 返回: `ok` 和 `captcha`。`captcha` 是验证码图片的 data URL，形如 `data:image/…;base64,…`。失败时返回 `message`。
- 注意: 调用时会放弃正在等待的授权。验证码要给用户看，base64 解码后就是图片。最长约 40 秒。返回 "现在没有不经第三方、能连到 Bangumi 的线路…" 时，要先通过 `api/settings/bangumi` 打开加密直连。

### POST api/account/weblogin/captcha
换一张验证码。
- 参数: 无。
- 返回: `ok` 和 `captcha`。登录会话已失效时，返回 `ok: false`、`expired: true` 和 `message`，需要从 start 重新开始。

### POST api/account/weblogin/submit
提交邮箱、密码和验证码。电视会登录 Bangumi 网页、生成个人令牌，并用这个令牌登录。
- 参数: `email`、`password`、`captcha`，都是必填。
- 返回:
  - 成功：返回 ok/message，message 为 "登录成功，电视已登录"。
  - 被 Bangumi 拒绝：返回 `ok: false`、`message`，同时附带一张新的 `captcha`。旧验证码已经作废，要用新的重新填写。
  - `expired: true`：需要从 start 重新开始。
- 注意: 密码只在这一个请求里经过，电视不保存。最长约 40 秒。

### POST api/account/weblogin/cancel
放弃这次账号密码登录。
- 参数: 无。
- 返回: 返回 ok/message。

### POST api/account/logout
让电视退出 Bangumi 登录，并清掉本地保存的用户信息。
- 参数: 无。
- 返回: 返回 ok/message。
- 注意: 调用时会放弃正在等待的授权。超时会返回 "退出登录超时，请重试"。

### GET api/profiles
读取电视上的用户列表和当前用户。
- 参数: 无。
- 返回: 不支持多用户的设备只有 `ok` 和 `supported: false`。其他情况下包含：
  - `supported: true`。
  - `currentId`：当前用户的 id，各标签的数据都属于这个用户。
  - `users[]`：每项含 `id`；`name`（显示名，没起名时为 "用户 N"）；`raw`（用户自己起的名字，可能是空串）；`current`；`primary`（1 号用户，只能改名，不能删除）；`local`（本地用户：不登录 Bangumi，收藏只存在电视上）；`avatar[]`（可能没有）。
  - `nextName`：新建用户时不填名字所用的默认名。
  - `canImport`：现在能否把本地用户的收藏导入当前用户的 Bangumi 账号。
  - `importing`：是否有导入或合并正在进行。
  - `pendingLocal`（可能没有）：当前用户从本地用户改成 Bangumi 用户之前留下、还没处理的本地收藏部数。有这个字段时另有 `canMerge`。

### POST api/profiles/add
新建一个用户，不会切换过去。
- 参数: `name`（可选）：留空时使用默认名 "用户 N"。`kind`（可选）：传 `local` 表示本地用户，传其他值或不传表示登录 Bangumi 的用户。
- 返回: `ok`、`id`（新用户的 id）、`message`。

### POST api/profiles/switch
切换到指定用户。
- 参数: `id`（必填，取 `users[].id`）。
- 返回: 成功时返回 `ok`、`id`、`name`；失败时返回 ok/message。
- 注意:
  - 回应发出后约 0.5 秒，电视上的 Izuko 会以新用户身份重启，正在播放的内容会停下。
  - 控制台服务也会随之重启，要几秒到十几秒。token 不变，端口一般也不变。
  - 之后每秒轮询 `GET api/profiles`，直到 `currentId` 等于目标 id（网页最多等 60 秒），再重新读取其他数据。
  - Izuko 不在前台时，要先开启「从手机打开 Izuko」（`api/settings/front`）并完成授权，否则会返回 "电视上没有显示 Izuko，切换不了…"。
  - 有导入正在进行时，切换会被拒绝。

### POST api/profiles/rename
修改用户的名字。
- 参数: `id`（必填）；`name`：留空表示恢复默认名。
- 返回: 返回 ok/message。

### POST api/profiles/delete
删除一个用户，连同他的数据文件。
- 参数: `id`（必填）。
- 返回: 返回 ok/message。
- 注意: 删除后无法恢复。1 号用户（`primary`）和当前用户都不能删。

### GET api/profiles/import
预览把某个本地用户的收藏导入当前用户的 Bangumi 账号时，会新加哪些条目。
- 参数: `id`（必填，本地用户的 id）。
- 返回:
  - `ok`。
  - `source`：来源用户的名字。
  - `add[]`：每项含 `id`（条目 id）、`name`、`type`（收藏类型，`WISH` / `DOING` / `DONE` / `ON_HOLD` / `DROPPED`）、`score`（评分）、`episodes`（要标记看过的集数；想看为 0）。
  - `skipped`：账号里已经有、会跳过的部数。
- 注意: 要先满足 `canImport`，即当前用户已登录 Bangumi。预览要把账号的收藏全部读一遍，最长约 60 秒。

### POST api/profiles/import
按最近一次预览开始导入。
- 参数: `id`（必填，要与预览时的 id 相同）。
- 返回: 返回 ok/message，成功时为 "开始导入"。
- 注意: 导入在电视后台进行，进度看 `GET api/profiles/import/state`。预览超过 10 分钟会返回 "预览已经过期，请重新打开导入"，要重新获取预览。写进 Bangumi 账号的收藏撤不回来。同一时间只能有一个导入或合并在进行。

### GET api/profiles/import/state
查看导入或合并的进度。
- 参数: 无。
- 返回: 都有 `ok` 和 `running`。按状态另有：
  - 进行中：`source`、`done`、`total`、`waiting`（是否在等 Bangumi 的频率限制）。
  - 失败：`source`、`error`。
  - 完成：`source` 和 `result`。`result` 含 `added`（新加的部数）、`skipped`、`episodes`（标记看过的集数）、`failures[]`。`failures[]` 每项含 `name`、`added`（为 true 表示收藏已加上，只是看过的集没标上）、`message`。
  - 从没进行过：只有 `running: false`。
- 注意: 合并留下的本地收藏（`api/profiles/pending/merge`）也在这里看进度。网页每秒轮询一次。

### GET api/profiles/pending
预览把之前留下的本地收藏合并到当前账号时，会新加哪些条目。
- 参数: 无。
- 返回: `ok`、`total`、`add[]`（字段同 `GET api/profiles/import`）、`skipped`。
- 注意: 只有 `GET api/profiles` 返回了 `pendingLocal` 时才有内容，否则返回 "没有等着处理的本地收藏了"。要求已登录 Bangumi（`canMerge`）。最长约 60 秒。

### POST api/profiles/pending/merge
把留下的本地收藏合并到当前的 Bangumi 账号。
- 参数: 无。
- 返回: 返回 ok/message，成功时为 "开始合并"。
- 注意: 按 10 分钟内最近一次预览合并，在后台进行，进度看 `GET api/profiles/import/state`。电视上会同时询问，以先做出选择的一方为准。

### POST api/profiles/pending/discard
删除留下的本地收藏。
- 参数: 无。
- 返回: 返回 ok/message。
- 注意: 删除后找不回来。有导入正在进行时会被拒绝。

### GET api/profiles/export
把一个本地用户导出成文件内容。
- 参数: `id`（必填，必须是本地用户）。
- 返回:
  - `ok`。
  - `collections`：收藏条数。
  - `playback`：播放进度条数。
  - `archive`：导出的文件内容，是一个 JSON 对象，含 `format`（值为 `izuko-tv-profile`）、`version`、`exportedAt`、`profileName`、`collections[]`、`playback[]`。把整个 `archive` 存成 .json 文件即可。
- 注意: 最长约 30 秒。导出非本地用户时返回 "只有本地用户可以导出"。

### POST api/profiles/restore/check
从文件导入前先比对：文件里的条目中，哪些当前用户已经收藏了。
- 参数: `id`（必填，当前用户的 id）；`subjects`：逗号分隔的条目 id。
- 返回: `ok` 和 `collected[]`（其中已收藏的条目 id）。
- 注意: 从文件导入的完整顺序是：先用本接口比对，再对还没收藏的条目逐条调 `api/profiles/restore/collection`，最后分批调 `api/profiles/restore/playback`。三个 restore 接口的共同规则：
  - 只导入到当前用户，且当前用户必须是本地用户。
  - 只增不删，已有的收藏不会被改动。
  - `id` 与电视上的当前用户不一致，或当前用户不是本地用户时，返回 `ok: false` 和 `stop: true`。这时应停止整个导入。
  - 请求体上限 64 KiB，条目多时要分批发（网页每批 2000 个）。

### POST api/profiles/restore/collection
把一条收藏导入当前用户。
- 参数: `id`（必填，当前用户的 id）；`entry`（必填）：导出文件 `archive.collections[]` 里的一项，以 JSON 字符串原样传。
- 返回: `ok`、`added`（为 false 表示已经收藏过，没有改动）、`episodes`（标记看过的集数）。
- 注意: 一次只导一条。电视库里没有这个条目时，要去 Bangumi 取条目信息，每条最长约 30 秒。

### POST api/profiles/restore/playback
导入一批播放进度。
- 参数: `id`（必填，当前用户的 id）；`records`（必填）：由导出文件 `archive.playback[]` 中若干项组成的 JSON 数组（网页每批 40 条）。
- 返回: `ok` 和 `restored`（实际写入的条数）。
- 注意: 电视上的进度比文件里的新时，不会被覆盖。

## 维护

本章管电视上的 Izuko TV 本身: 检查并安装应用更新 (或把手机 / 电脑上的安装包分块传上去装)、写故障反馈并查看处理进度、列出与下载日志、性能诊断 (设备体检 / 录制 / 方法采样)、换电视时从旧电视搬数据。
- 更新: `POST api/update/check` → 轮询 `GET api/update` 到 `checked=true` → `POST api/update/install` → 轮询 `job`。安装时要有人在电视上用遥控器点系统确认框; 装自己的更新成功后 Izuko 进程会被系统结束, 控制台断开一会儿。
- 反馈: 草稿按 `target` 区分, `0` = 新工单, 其它值 = 那张工单的 issue 号 (给它的补充)。新工单: `POST api/feedback/record` (`action=start` → 有人在电视上复现问题 → `action=stop`) → 可选附报告 / 照片 → `POST api/feedback/ticket`。
- 性能诊断: `POST api/diag/record` → 轮询 `GET api/diag` 到 `status.state=idle` → `GET api/diag/report/{id}`。
- 换电视: 在新电视上 `POST api/migrate/preview` → `POST api/migrate/start` → 轮询 `GET api/migrate/status`; 新电视自己去旧电视的 `api/migrate/export/…` 取数据。
- `api/update/…` 处理中出异常时返回 ok=false、message「操作失败：…」; 进程刚起来还没就绪时返回「电视上的 Izuko TV 还没准备好，稍后再试」。

### GET api/update
读「应用更新」的状态: 当前版本、检查结果、正在进行的下载 / 安装、最近上传的安装包。
- 参数: 无。
- 返回:
  - `current`: 已装的版本号; `canInstall`: 有没有「安装未知应用」授权; `permPending`: 授权页在等 Izuko 回前台再打开。
  - `checking` / `checked`: 正在检查 / 检查过; `checkText`: 正在逐个查镜像时的进度文字 (只在检查中出现); `checkError`: 检查失败的原因。
  - `latest{name, notes[]}`: 查到的新版本; `checked=true` 而没有 `latest` 表示已是最新。
  - `job{phase, busy, progress, text}`: 当前任务。`phase` 为 `idle`、`downloading`、`verifying`、`preparing`、`confirm` (等电视上点安装确认)、`waiting_front` (等 Izuko 回前台再弹确认)、`failed` (原因在 `text`)、`success`; `busy=true` 时不接新的安装; `progress` 是 0~1 的小数, 只在知道总量时有; `text` 是给人看的一行状态。
  - `lines[{host, official, note}]`: 新版本的下载线路, 不止一条时才有; `official=true` 的是原地址, `note` 是上次测出的速度或没下成的原因。
  - `upload{name, size, version, otherPkg}`: 最近上传的安装包, 核对后才有 `version`, 是另一个包时才有 `otherPkg` (它的包名)。
  - `offer{name, issue}`: 工单回复里交过来的测试包; `retry{version}`: 有可以再装一次的包; `last{ok, text}`: 上次装自己的更新、重启后核对出的结果。
- 注意: 网页每秒轮询一次。

### POST api/update/check
在后台检查有没有新版本 (先查 GitHub, 连不上再逐个试镜像, 每个最长约 20 秒)。
- 参数: 无。
- 返回 ok/message, 立即返回 (ok=true, message 为空); 结果看 `GET api/update` 的 `checked`、`latest`、`checkError`。
- 注意: 正在检查时再调也回 ok, 不重复查。

### POST api/update/install
下载检查到的新版本并安装, 只装本应用自己的更新。
- 参数: `line`, 可选, 下载线路的域名 (取 `lines[].host`), 空 = 自动 (各线路一起测速, 从最快的下, 失败换下一条); `anyway`, 可选, `1` = 没有安装授权也直接提交, 由系统询问。
- 返回 ok/message, 立即返回; 进度看 `GET api/update` 的 `job`。没有授权且没带 `anyway=1` 时返回 `{"ok":false,"needPermission":true,"message":"要先在电视上允许 Izuko TV 安装应用"}`。
- 注意: 没先查到新版本回「先检查更新」; 有任务在跑回「正在安装，等这次完成后再试」; 线路不在列表里回「这条下载线路用不了了，换一条再试」。下载后核对签名, 必须与已装的一致。之后要有人在电视上点系统确认框的「安装」或「更新」。

### POST api/update/permission
在电视上打开 Izuko TV 的「安装未知应用」授权页。
- 参数: 无。
- 返回 ok/message。已经授权时 ok=true「已经允许了」; Izuko 不在前台又叫不回来时 ok=true, 等它回到前台再打开 (`permPending=true`); 这台电视打不开授权页时 ok=false, 这时各安装接口带 `anyway=1` 直接装。
- 注意: 要有人在电视上把 Izuko TV 的「允许」打开。

### POST api/update/upload/start
开始上传一个安装包 (APK)。
- 参数: `size`, 必填, 文件字节数, 1 ~ 314572800 (300 MiB); `name`, 可选, 文件名, 只用于显示 (最多 200 字); `anyway`, 可选, 同 `api/update/install`。
- 返回: `{ok, id, chunk}`: `id` 是这次上传的编号, `chunk` 是每块字节数 (1048576)。失败时返回 ok/message (如「文件为空或太大」), 没授权时同 `api/update/install` 返回 `needPermission`。
- 注意: 新的上传会作废之前没传完的那次; 有任务在跑时拒绝。之后按顺序调 `api/update/upload/chunk`, 传完调 `api/update/upload/finish`。

### POST api/update/upload/chunk
上传安装包的一块。
- 参数: 查询串 `id` (start 给的编号) 和 `offset` (这块在文件里的起始字节); 请求体是这块的原始字节, 不是表单 (网页用 `Content-Type: application/octet-stream`)。每块最多 `chunk` 字节; 请求体超过 1 MiB + 64 KiB 回 413。
- 返回: `{ok, received}`, `received` 是电视已收到的总字节数, 下一块从这里接着发。
- 注意: 必须按顺序发: `offset` 等于已收字节数时追加; 整块都已收过 (超时重发) 时直接回 ok; 其它情况回「上传出错，请重新选择文件」。编号对不上 (被新的上传替换、进程重启过) 回「上传已失效，请重新选择文件」。

### POST api/update/upload/finish
收齐后核对安装包, 开始安装。
- 参数: `id`, 必填; `force`, 可选, `1` = 跳过下面的确认。
- 返回: 核对通过返回 ok=true, 安装在后台进行 (看 `job`)。装的是另一个 Izuko TV 包、或版本比已装的旧, 且没带 `force=1` 时返回 `{"ok":false,"ask":true,"message":"…确定要装吗？"}`, 问过用户后带 `force=1` 再调一次。核对不过返回 ok=false 和原因 (同时记进 `job`)。
- 注意: 只收本项目的包 (包名以 `io.github.grahamzen.anime` 开头); 装自己的更新时签名必须与已装的一致。没传完回「文件没有传完整，请重试」。核对是同步做的, 要等几秒。同样要有人在电视上点安装确认。

### POST api/update/retry
把上次没装成的包 (上传的或测试包, 例如电视上点了取消) 再装一次, 不用重新上传。
- 参数: `anyway`, 可选, 同 `api/update/install`。
- 返回 ok/message, 安装在后台进行。
- 注意: 只在 `GET api/update` 有 `retry` 字段时可用; 包已经不在时回「刚才上传的安装包已经不在了，请重新选择文件」。

### POST api/update/install-offer
下载并安装工单回复里维护者给的测试包 (先用 `POST api/feedback/install` 交过来)。
- 参数: `anyway`, 可选, 同 `api/update/install`。
- 返回 ok/message, 立即返回; 进度看 `job`。
- 注意: 没有交过来的测试包回「测试包已经不在了，回到「反馈」页再点一次「装到电视」」。装另一个包或比现在旧的版本都不先问, 直接交给系统 (系统拒装时原因写进 `job`)。

### GET api/feedback
读「反馈」页的全部状态: 工单服务开没开、草稿与附件、可附的性能报告、提交过的反馈与回复。
- 参数: 无。
- 返回:
  - `device`: 本机反馈编号; `tickets`: 工单服务是否开放 (false 时不能录制和提交); `limits{recordMs, maxLead, maxPhotos, photoBytes, minSummary, minDescription}`: 各项上限。
  - `recording{target, elapsed}`: 正在录的那段 (`elapsed` 毫秒), 没在录时没有。
  - `drafts`: 以 target 为键的草稿, 每份 `{recorded{from, to}, lead, reports[], photos[{id, size}], texts{}}`; `recorded` 是录好的时间段 (毫秒时间戳), `lead` 是日志往前多截的分钟数。
  - `reports[]`: 可附的性能报告 (最多 12 份, 新的在前), 每份 `{id, kind, seconds, page, time, headline}`。
  - `checkedAt`: 上次查处理结果的时刻; `items[]`: 提交过的反馈, 每条 `kind` (`TICKET` 故障工单、`LOGO` 标题 logo、`ENTRY` 对应作品)、`issue`、`state` (`OPEN`、`ACCEPTED`、`REJECTED`、`CLOSED`)、`summary`、`at` (提交时刻, 毫秒)、`unread` (有新回复)、`replies[{maintainer, at, body, files[]}]`; 故障工单另有 `description`、`files[]`、`pending[]` (还没传上去的附件)、`uploading`、`apks[]` (回复里的测试包链接)。
- 注意: 读的时候顺带结束已录满的现场录制, 并去掉草稿里已经不在的附件。

### POST api/feedback/record
现场录制: 开始、结束、放弃, 或去掉录好的那段 (日志按录制期间截)。
- 参数: `target`, 可选, 缺省 0; `action`, 必填, `start` / `stop` / `cancel` / `remove`。`stop` 与 `cancel` 作用于正在录的那段, 不看 `target`。
- 返回 ok/message。`action` 不认识时 ok=false、message 为空。
- 注意: 工单服务没开放时回「故障反馈还没开放」; 别的 target 正在录时 `start` 回「另一条反馈正在现场录制，先结束那一段」。`start` 会丢掉这份草稿之前录好的那段。最长 5 分钟 (`limits.recordMs`), 到点自动结束。录制期间要有人在电视上把问题复现一遍。

### POST api/feedback/lead
设置日志从录制开始前几分钟截起。
- 参数: `target`, 可选, 缺省 0; `minutes`, 0 ~ 5 (`limits.maxLead`), 超出按边界算, 不是数字算 0。
- 返回 ok/message。

### POST api/feedback/text
暂存草稿里写到一半的文字, 给网页重新打开时填回去; 提交接口不读这里, 文字由提交接口自己带。
- 参数: `target`, 可选, 缺省 0; `field`, `summary` / `description` (新工单) 或 `body` (补充); `value`, 文字, 最多存 4000 字, 空串 = 删掉。
- 返回 ok/message。
- 注意: `field` 不认识时什么都不存, 照样回 ok。

### POST api/feedback/attach
把一份性能诊断报告附到草稿上, 或去掉。
- 参数: `target`, 可选, 缺省 0; `report`, 必填, 报告 id (`reports[].id`, 同 `GET api/diag` 的报告 id); `on`, `1` = 附上, 其它 = 去掉。
- 返回 ok/message。附上时报告不在了回「这份报告已经不在了」。
- 注意: 一份草稿最多附 4 份, 再附会挤掉最早附上的。

### POST api/feedback/photo
给草稿附一张照片。
- 参数: 查询串 `target`, 可选, 缺省 0; 请求体是图片的原始字节, 不是表单。只收 JPEG、PNG、WebP (按文件头认), 最大 5 MiB (`limits.photoBytes`); 请求体超过 5 MiB + 64 KiB 时直接回 413。
- 返回 ok/message。
- 注意: 一份草稿最多 4 张 (`limits.maxPhotos`), 多了回「一条最多附 4 张照片」; 格式不对回「只能附 JPEG、PNG 或 WebP 图片」。

### GET api/feedback/photo/{id}
取草稿里一张照片的原图。
- 参数: 路径里的 `{id}` 取 `drafts.<target>.photos[].id`。
- 返回: 图片文件, `image/jpeg`、`image/png` 或 `image/webp`; 不存在时回 404。

### POST api/feedback/photo/remove
从草稿里去掉一张照片, 并删掉文件。
- 参数: `target`, 可选, 缺省 0; `id`, 必填, 照片 id。
- 返回 ok/message。

### POST api/feedback/ticket
提交新的故障工单, 附件取 target 0 的草稿。
- 参数: `summary`, 必填, 一句话说明问题, 4 ~ 80 字; `description`, 必填, 怎么操作会出问题、看到了什么, 5 ~ 4000 字。
- 返回: 成功时 `{"ok":true,"issue":<工单号>,"message":"已提交，工单 #N。附件正在上传。"}`; 失败时返回 ok/message。
- 注意: 会在维护者的工单服务里真的开一张工单, 日志 (`log.gz`) 和照片随后在后台上传 (看 `items[].pending` / `uploading`), 不要为了试接口提交。草稿 0 必须有一段录好的现场录制, 否则回「新反馈要带一段现场录制…」; 还在录时回「还在现场录制，先点「结束」」。最多等 30 秒。其它常见失败: 「连不上工单服务，检查电视的网络后再试」、「提交太频繁，过一会儿再试」、「还有 N 张工单在处理中，处理完再提交新的」、「这台电视暂时不能提交故障反馈」、「故障反馈还没开放」。

### POST api/feedback/reply
给一张工单补充文字和附件, 附件取 target = issue 号的那份草稿。
- 参数: `issue`, 必填; `body`, 补充说明, 最多 4000 字, 可以为空 (这时草稿里要有附件)。
- 返回 ok/message, 成功时 message 为「已补充」。
- 注意: 同样发到工单服务, 最多等 30 秒。文字和附件都没有时回「先写点什么，或者附上东西」; 工单已结束回「这张工单已经结束，不能再补充」; 还在给它录时回「还在现场录制，先点「结束」」。

### POST api/feedback/retry
在后台重新上传某张工单没传上去的附件。
- 参数: `issue`, 必填。
- 返回 ok/message: 「正在重新上传附件」, 或 ok=false「这张工单没有要上传的附件」。

### POST api/feedback/sync
马上向工单服务查一次各条反馈的处理结果。
- 参数: 无。
- 返回: 与 `GET api/feedback` 相同的字段; 有的没查到时另带 `message`。
- 注意: 距上次手动查不到一分钟时不会真的再查, 直接返回当前状态。最多等 30 秒。

### POST api/feedback/read
把所有反馈标成已读, 清掉 `items[].unread`。
- 参数: 无。
- 返回 ok/message。

### POST api/feedback/install
把工单回复里的测试包交给「应用更新」; 只登记, 不下载也不安装。
- 参数: `issue`, 必填; `url`, 必填, 必须是那张工单 `items[].apks` 里的链接。
- 返回 ok/message。链接不在那张单的回复里时回「这个测试包已经不在工单的回复里了」。
- 注意: 之后用 `POST api/update/install-offer` 下载并安装。

### POST api/feedback/delete
删掉这台电视上一条已结束的反馈记录。
- 参数: `kind`, 必填, `TICKET` / `LOGO` / `ENTRY`; `issue`, 必填。
- 返回 ok/message。还在处理中 (`state=OPEN`) 或找不到时回「只能删除已结束的反馈」。
- 注意: 故障工单连同给它的补充草稿一起删。

### GET api/logs
列出电视上的日志文件, 新的在前。
- 参数: 无。
- 返回: `{ok, supported, items[{name, size, time}]}`。`supported=false` 表示拿不到日志目录; `size` 是给人看的大小文字; `time` 是最后修改时间 (`MM-dd HH:mm`, 电视本地时间)。
- 注意: 只列 `.log` 文件。

### GET api/logs/{name}
下载一个日志文件。
- 参数: 路径里的 `{name}` 取 `items[].name` (URL 编码)。
- 返回: 纯文本文件 (`text/plain; charset=utf-8`), 只给到请求那一刻的文件长度; 名字不在列表里回 404。

### GET api/diag
读性能诊断的状态与报告列表。
- 参数: 无。
- 返回:
  - `status.state`: `idle`、`checking` (体检中) 或 `recording`; 录制中另有 `seconds` (时长)、`remaining` (剩余秒数)、`auto` (是不是自动触发的录制)。
  - `trace{running, remaining, size, time}`: 方法采样, `remaining` 只在采样中有, 有上次的结果文件时才有 `size` (字节) 与 `time`。
  - `reports[]`: 新的在前, 每份 `{id, kind, time, seconds, page, headline, findings}`。`kind` 为 `health` (体检)、`recording` (手动录制) 或 `auto` (自动录制); `headline` 是第一条结论, `findings` 是全部结论。
- 注意: 手动的报告留最近 12 份, 自动的留 5 份。

### POST api/diag/health
跑一次设备体检, 跑完才返回。
- 参数: 无。
- 返回: `{"ok":true,"report":{…}}`, 整份报告 (同时存进报告列表); 不能体检时返回 ok/message。
- 注意: 电视上 Izuko 还没打开界面、或正在录制时不能体检。

### POST api/diag/record
开始一段性能录制; 录制期间要有人在电视上照常操作要测的界面。
- 参数: `seconds`, 必填, 5 ~ 120 秒, 超出按边界算。
- 返回 ok/message, 立即返回。
- 注意: 到时间后自动分析并存成报告, 轮询 `GET api/diag` 等 `status.state` 回到 `idle` 再看 `reports`。失败原因: 「电视上的 Izuko 还没打开界面」、「正在录制中」、「电视当前没有显示 Izuko，切回 Izuko 再录制」, `seconds` 不是数字时回「无效的时长」。

### POST api/diag/stop
提前结束手动录制, 已录到的照样分析。
- 参数: 无。
- 返回 ok/message, 总是 ok=true。
- 注意: 自动录制停不了; 没在录时什么也不做。

### POST api/diag/delete
删掉一份诊断报告。
- 参数: `id`, 必填, 报告 id。
- 返回 ok/message。不存在时回「这份报告已经不在了」。

### POST api/diag/trace
开始方法采样 (Android 采样式方法跟踪), 看主线程卡在哪段代码。
- 参数: `seconds`, 可选, 3 ~ 30 秒, 缺省 10; `us`, 可选, 采样间隔 (微秒), 500 ~ 10000, 缺省 1000。超出都按边界算。
- 返回 ok/message; 进度看 `GET api/diag` 的 `trace`。
- 注意: 同一时间只采一份, 正在采时回「正在采样」; 界面没打开时回「电视上的 Izuko 还没打开界面」。每次覆盖上一份结果。采样本身很重, 结果里的耗时不可信, 只用来看卡在哪。

### GET api/diag/trace/file
下载上一次方法采样的结果。
- 参数: 无。
- 返回: `.trace` 文件 (`application/octet-stream`, Android Studio 的 Profiler 能打开); 没有时回 404。

### GET api/diag/report/{id}
取一份完整的诊断报告。
- 参数: 路径里的 `{id}` 取 `reports[].id` (小写字母、数字与 `-`)。
- 返回: JSON 文件, 含 `id`、`kind`、`time`、`seconds`、`page`、`app{version, package, debug}`、`device`、`settings{}` 与各项分析结果 (含 `findings`); 不存在时回 404。

### GET api/migrate/export/manifest
旧电视一侧: 这台电视上有什么, 新电视先取这一份给用户确认。
- 参数: 无。
- 返回: `{format, version, appVersion, databaseVersion, profiles[], mediaSources, subscriptions}`。`format` 为 `izuko-tv-device`; `profiles[]` 每项 `{id, name, kind, loggedIn, collections, playbackRecords}`, `kind` 为 `BANGUMI` 或 `LOCAL`, `name` 为空表示没起过名; `mediaSources` / `subscriptions` 是数据源与订阅的个数。没有 ok 字段。

### GET api/migrate/export/shared
旧电视一侧: 整机共用的数据 (设置、数据源、订阅、弹幕屏蔽词等)。
- 参数: 无。
- 返回: JSON `{preferences[], subjectPreferences[], mediaSources, subscriptions[], peerFilterSubscriptions[], danmakuFilters[], manualBrowseMemories, nsfwSubjects[], appLanguage}`; `preferences` 每项 `{key, type, value, values}`。没有 ok 字段。
- 注意: 含网盘、PikPak 等账号凭据, 要当密钥对待, 不要打印、保存或转发。

### GET api/migrate/export/profile
旧电视一侧: 一个用户的资料与按人的配置。
- 参数: 查询串 `id`, 必填, 用户编号 (`profiles[].id`)。
- 返回: JSON `{profile, scopedStores{}, hiddenHistory[]}`; 缺 `id` 回 400, 没有这个用户回 404。
- 注意: `scopedStores` 里有 Bangumi 登录凭据, 要当密钥对待。

### GET api/migrate/export/database
旧电视一侧: 一个用户的库文件快照。
- 参数: 查询串 `id`, 必填, 用户编号。
- 返回: 二进制文件 (`application/octet-stream`); 缺 `id` 回 400, 没有这个用户回 404, 拍快照失败回 500。
- 注意: 先拍快照再发, 要等几秒。

### POST api/migrate/preview
新电视一侧: 连上旧电视取清单, 给出搬运前的说明。
- 参数: `url`, 必填, 旧电视控制台的完整地址 (`http://IP:端口/<token>/`), 夹在别的文字里也行, 取其中第一个地址。
- 返回: `{ok, profiles[{name, detail}], notes[]}`, `detail` 说明每个用户怎么搬, `notes` 是给人看的几条说明; 失败时返回 ok/message。
- 注意: 同步请求旧电视 (连接超时 5 秒)。常见失败: 「请粘贴旧电视控制台的完整地址」、「这是这台电视自己的地址…」、「连不上旧电视…」、「这个地址打不开，可能旧电视控制台的地址变了…」(旧电视回 404)、「旧电视上的 Izuko 没有换电视功能…」(旧电视回 405)、「旧电视上的 Izuko 比这台新…」。

### POST api/migrate/start
新电视一侧: 开始从旧电视搬数据, 在后台进行。
- 参数: `url`, 必填, 同 `api/migrate/preview`。
- 返回 ok/message, 成功时 message 为「开始搬了」; 进度看 `GET api/migrate/status`。
- 注意: 会改这台电视的数据: 设置换成旧电视的, 数据源与订阅两边合并, 旧电视的用户作为新用户加进来 (这台还没人用过时, 旧电视的 1 号用户直接放进这台的 1 号), 然后应用自动重启。旧电视上的数据不动, 缓存的视频不搬。先全部下载完才写入, 下载阶段失败或停止时这台电视什么都没改。Izuko 必须能叫到电视前台, 否则回「电视上没有显示 Izuko。先在电视上打开 Izuko 再试」; 正在搬时回「正在搬，搬完再试」, 正在导入收藏时回「正在导入收藏，导完再搬」。

### GET api/migrate/status
新电视一侧: 读搬运进度。
- 参数: 无。
- 返回: `state` 为 `idle`、`running`、`done` 或 `failed`。`running` 时另有 `text` (当前步骤)、`done` / `total` (正在下载的库文件已收 / 总字节数, 不在下载库文件时为 0)、`cancellable` (还能不能停); `done` / `failed` 时另有 `message`。没有 ok 字段。
- 注意: 搬完约 2.5 秒后应用重启, 控制台会断开一会儿; 重启后状态回到 `idle`。

### POST api/migrate/cancel
新电视一侧: 停下正在下载的搬运。
- 参数: 无。
- 返回 ok/message: 「已停止，这台电视上什么都没改」, 或 ok=false「现在停不了」(没在搬, 或已经开始写入)。

## 调试接口 (只有 debug 包)

本章接口给测试脚本用: 查改应用的库、拦截或统计网络请求、看开播时间线与线程栈、清搜索记录、操作电视设置页、读屏上的界面与焦点并按遥控器的键 (`api/debug/ui…`, 哪一页都能用)、读播放链路探针、改调试开关。
只有带调试区的包收这些接口: debug 包, 以及用 `-Pani.devConsole=true` 编的 release 包 (性能对比包)。其它包里, `api/debug/` 下任何路径和方法都回 HTTP 200 的 `{"ok":false,"message":"只有 debug 包能用调试操作"}`; `GET api/settings/debug/probes` 回 ok=false「只有 debug 包能看调试探针」; `POST api/settings/debug/set` 回 ok=false「只有 debug 包能改调试项」。
调试区里的临时开关与登记的设置项由 `GET api/settings` 的 `debug` 数组列出 (只有带调试区的包才有这个字段), 每项 `{key, title, description, editor, value}`, `editor` 为 `toggle` / `number` / `choice` / `text` / `lines`, 数字项另有 `min` / `max`, 选择项另有 `choices[{value, label}]`; 代码里的临时开关排在前面, `key` 带 `dev.` 前缀。改值用 `POST api/settings/debug/set`。
调试区开着时, `api/debug/` 下不认识的路径或方法回 405; 处理中抛出异常时返回 ok=false, message 是异常信息。

### GET api/debug/db
只读查库: 执行一条查询语句, 返回列名与结果行。
- 参数: 查询串 `sql`, 必填, 一条查询语句, 要 URL 编码 (curl 用 `-G --data-urlencode "sql=SELECT …"`)。
- 返回: `{ok, columns[], rows[[…]], truncated}`。最多 200 行, 还有更多时 `truncated=true`; 每个值是字符串 (数字也是字符串) 或 null, blob 列写成 `"<blob N bytes>"`。
- 注意: `sql` 为空时回 ok=false「sql is empty」; SQL 出错时回 ok=false 和 SQLite 的错误信息。

### POST api/debug/db
改库: 在一个事务里执行一条写语句 (UPDATE / DELETE / INSERT 等), 执行完通知 Room, 电视上开着的页面跟着刷新。
- 参数: `sql`, 必填, 一条语句。
- 返回 ok/message, 成功时 message 为 `ok`; 失败时 ok=false, message 是错误信息。
- 注意: 直接改应用数据, 没有撤销; 用来造测试状态 (如把条目或分集改成过期、删掉分集)。

### POST api/debug/collections/refresh
做一次收藏页下拉刷新的那一步: 从 Bangumi 拉某一类收藏, 与本地对账。
- 参数: `type`, 必填, `WISH` / `DOING` / `DONE` / `ON_HOLD` / `DROPPED` / `ALL`。
- 返回: `{ok, inSync}`, `inSync` 表示本地是否已与服务端一致。
- 注意: 同步等网络请求做完才返回。`type` 不认识时回 ok=false「unknown type: …」。

### POST api/debug/nav
让电视回到主界面并切到某一页, 同侧边栏切页 (不另开一个主界面)。
- 参数: `page`, 必填, `Exploration` / `Collection` / `CacheManagement`。
- 返回 ok/message, 成功时 message 为 `ok`; 切页在电视上异步进行。
- 注意: `page` 不认识时回 ok=false「unknown page: …」; 界面还没起来时回「电视还没准备好」。

### GET api/debug/net/block
读当前的网络拦截规则。
- 参数: 无。
- 返回: `{ok, rules[{pattern, hangSeconds}]}`。

### POST api/debug/net/block
设置网络拦截规则 (整组替换): 网址里含任一片段的请求直接失败, 与连不上相同; 可以先挂起几秒再失败, 模拟超时。
- 参数: `rules`, 网址片段, 用逗号或换行分隔, 空串 = 清空规则 (全部放行); `hang`, 可选, 秒数 (可带小数), 大于 0 时每条规则都先等这么久再失败, 缺省立即失败。
- 返回: 同 `GET api/debug/net/block`。
- 注意: 只存在内存里, 应用重启就没了。只拦应用自己的 HTTP 客户端发的请求, 播放器和 WebView 发的不经过这里。匹配的是 Bangumi 线路改写之后的实际地址: 只拦 `api.bgm.tv` 时应用会退到别的线路, 要让整个 Bangumi 接口都连不上, 得把各条线路的域名都写上。

### POST api/debug/requests/reset
从现在起记录应用发出的请求, 并清掉之前记的。
- 参数: 无。
- 返回 ok/message, message 为 `recording`。

### GET api/debug/requests
读记下的请求, 按接口归并计数。
- 参数: 查询串 `raw`, 可选, `1` = 另给逐条记录。
- 返回: `{ok, recording, sinceMillis, total, kept, endpoints[], requests[]}`。
  - `recording`: 是否在记录; `sinceMillis`: 开始记录的时刻 (只在记录中有); `total`: 开始记录以来的总条数; `kept`: 留着的条数 (只留最近 2000 条, 归并只算留着的)。
  - `endpoints[{endpoint, count, statuses{}}]`: 次数多的在前; `endpoint` 是「方法 主机/路径」, 不含查询串, 纯数字的路径段换成 `{n}`; `statuses` 是各状态出现的次数。
  - `requests[{at, method, url, status, ms}]`: 只在 `raw=1` 时有。
- 注意: 状态是 HTTP 状态码 (字符串), 或 `BLOCKED` (被拦截规则拦下)、`CANCELLED`、异常类名。播放器和 WebView 发的请求不在里面。

### POST api/debug/requests/stop
停止记录请求, 已记下的照样能读。
- 参数: 无。
- 返回 ok/message, message 为 `stopped`。

### GET api/debug/timeline
读最近几次开播的时间线 (从进播放页或切集到出画面的每一步)。
- 参数: 无。
- 返回: `{ok, sessions[{id, subjectId, episodeId, trigger, steps[]}]}`。新的在后, 最多 20 次; `trigger` 为 `open` (进播放页) 或 `switch` (页内切集); `steps` 每项是数组 `[序号, 距开始的毫秒数, 步骤名, 说明]`, 说明最长 200 字。
- 注意: 只在调试开关 `dev.playDiagnostics` 开着时记录 (`POST api/settings/debug/set` 带 `key=dev.playDiagnostics&value=1`); 只存在内存里。

### GET api/debug/stacks
读全部线程的栈, 查卡在哪一步。
- 参数: 无。
- 返回: `{ok, text}`。`text` 按线程名排序, 每个线程一段: 第一行 `thread <线程名> (<状态>)`, 后面最多 40 帧, 每帧一行; 段与段之间空一行。

### POST api/debug/search/clear
清搜索记录, 用来量「第一次搜」的耗时。
- 参数: `subject`, 可选, 条目 id; `memory`, 可选, `1` = 同时清掉这部番记住的数据源与播放线索 (要同时给 `subject`)。
- 返回 ok/message, 成功时 message 为 `ok`。
- 注意: 不给 `subject` 时清掉全部网页类数据源的搜索缓存和跨条目共用的站点回应记忆; 给了 `subject` 只清这一部的缓存。`memory=1` 而没给 `subject` 时回 ok=false「memory=1 needs subject」。

### POST api/debug/settings/open
打开电视的设置页并落在某一类; 设置页已经开着就切到这一类。
- 参数: `tab`, 必填, 分类名: `PROFILE`、`APPEARANCE`、`THEME`、`UPDATE`、`PLAYER`、`MEDIA_SOURCE`、`MEDIA_SELECTOR`、`PROXY`、`BT`、`STORAGE`、`SETTINGS_BACKUP`、`ABOUT`、`LOG`、`DEBUG`。
- 返回 ok/message, message 为 `opened <分类>` 或 `switched to <分类>`。
- 注意: 分类名不认识时回 ok=false `unknown tab: …`; 界面还没起来时回 ok=false `navigator not ready`。

### GET api/debug/settings/page
读电视设置页当前显示的内容。
- 参数: 无。
- 返回: `{ok, category, drill[], screenTitle, rows[], lastToast, pendingConfirm}`。
  - `category`: 当前分类; `drill`: 进到的层级; `screenTitle`: 中栏标题, 有时才有。
  - `rows[]`: 中栏各行 `{id, kind, title, value, toggle, checked, info{title, body, qrStatus}}`; `toggle` (开关状态)、`checked` (只在打勾时出现, 为 true)、`info` (这一行的说明栏) 都是有时才有。
  - `lastToast{text, agoMs}`: 设置页最近一次弹的提示与距今毫秒数; `pendingConfirm`: 有没有等确认的弹窗。
- 注意: 设置页没开时回 ok=false `settings page is not open`; 内容最多等 5 秒, 还没好时回 ok=false `settings page content not ready`。

### POST api/debug/settings/click
在设置页中栏的某一行按确定, 与用遥控器按效果相同。
- 参数: `row` (行 id) 或 `title` (中栏里的标题, 须完全相同), 二选一, 两个都给时用 `row`; `confirm`, 可选, `1` = 冒出确认框时直接确认。
- 返回: `{ok, row, confirmShown, confirmed}`: 按的是哪一行、按完有没有确认框、有没有替你确认。
- 注意: 设置页没开时回 ok=false `settings page is not open`; 按标题找不到时回 ok=false `no row titled …`。按下去会真的执行那一行的操作 (改设置、清理等)。

### POST api/debug/settings/back
设置页中栏退一层, 同按返回键。
- 参数: 无。
- 返回 ok/message。message 为 `back=true` (退了一层) 或 `back=false` (已在最上层, 什么都没做); 设置页没开时 ok=false。

### GET api/debug/ui
读电视此刻屏上的界面: 哪一页都能用, 不看截图就知道屏上有什么、焦点在哪。
- 参数: 查询串 `brief`, 可选, `1` = 每个窗口只给打头的几段字。
- 返回: `{ok, front, focus, windows[], toasts[]}`。
  - `front`: 按键此刻送不送到本应用 (应用在前台)。
  - `windows[]`: 应用的每个窗口, 最上面的在前: `{kind, title, focused, bounds, elements[]}`。`kind` 为 `activity` (主页面)、`dialog` (弹窗)、`popup` (浮层) 或 `window:<类型号>`; `focused` 只在这个窗口收按键时出现; `brief=1` 时没有 `elements`, 换成 `head[]` (打头的至多 8 段字) 与 `elementCount`。
  - `elements[]`: 窗口里看得见的东西, 从上到下、从左到右: `{text, desc, role, interactive, focused, selected, checked, disabled, tag, props, source, bounds}`。能聚焦或能点的一个算一个 (`interactive`=true), `text` 是它里面所有的字, 用 ` · ` 连起来 (一张卡的标题与副标题); 不在这类东西里面的字各算一个。`desc` 是无障碍描述 (与文字重复时不给); `role` 是 Compose 的角色 (`Button`、`Checkbox`、`Switch`、`Tab` …, 输入框为 `edit`) 或原生视图的类名; `checked` 为 `on` / `off` / `mixed`; `props` 是页面另加的语义属性; `source` 为 `compose` 或 `view`; `bounds` 是屏幕上的 `[左, 上, 右, 下]` (像素, 已裁到看得见的部分)。`interactive`、`focused`、`selected`、`disabled` 只在为 true 时出现。
  - `focus`: 焦点所在的元素 (字段同上), 另有 `window` (在第几个窗口), 同一排有别的能聚焦的元素时还有 `row[]` (这一排从左到右, 每个 `{text, focused, selected, checked, disabled}`) 与 `rowIndex` (焦点是这一排的第几个, 从 0 起)。应用不在前台或没有东西拿着焦点时没有这个字段。
  - `toasts[]`: 最近弹过的提示, 从旧到新, 每条 `{text, agoMs}`; 整份回答给最近 20 条, `brief=1` 只给 10 秒内的。
- 注意: 读不到直接画出来的东西 (海报角标的数字、进度条、聚焦高光与压暗、视频画面), 这些仍要截图; 同一个窗口里被别的东西盖住的元素照样列出。屏上带令牌的控制台地址 (启动二维码弹窗、设置里那一行) 在回答里写成 `…:<端口>/<token>`。选中状态只有写进界面语义的才读得到 (原生视图的选中、Compose 的 `selected` 语义)。

### POST api/debug/ui/key
按遥控器的键, 不必经 adb; 弹窗一样收得到。按键有两条路: 系统的输入通道 (`system`, 与真遥控器走同一条路; 安卓 12 及以前能用), 与应用里直接交给收按键的窗口 (`app`, 照系统窗口分发按键的步骤做: 先给界面, 没人要的方向键照系统的办法找下一个焦点; 安卓 13 起系统不许应用给自己送按键, 只能走这条)。不指定时能走系统通道就走它。
- 参数:
  - `keys`, 必填, 一串按键, 逗号或空格隔开。每一项是 `键名`、`键名*次数`、`键名@按住的毫秒数` (两个后缀能一起写), 或 `300ms` (停这么久)。例: `DOWN,DOWN,RIGHT*3,OK`、`OK@800` (长按确定)、`LEFT@1500,200ms,BACK`。
  - 键名不分大小写: `UP` `DOWN` `LEFT` `RIGHT`、`OK` (确定键, 也可写 `CENTER` / `SELECT`)、`ENTER`、`BACK`、`MENU`、`PLAY` (播放暂停)、`FF`、`REW`、`NEXT`、`PREV`、`STOP`; 别的写安卓的键名 (`CAPTIONS` 即 `KEYCODE_CAPTIONS`) 或键值数字。
  - `expect`, 可选, 按之前核对焦点: 此刻焦点所在元素的文字或描述里有这段字才按, 没有就一下都不按, 回 ok=false「焦点不在…上, 没有按」(带 `focus`: 焦点实际在哪)。要按确定时都该带上: 晚弹出来的提示、弹窗会把焦点抢走。
  - `gap`, 可选, 两下之间固定隔的毫秒数; 不给时每按一下等界面停稳再按下一下。
  - `path`, 可选, `system` 或 `app`, 指定走哪条路; 指定 `system` 而系统不许时回 ok=false。
  - `timeout`, 可选, 按完最多等界面多久停稳, 毫秒, 默认 2000, 最大 30000。
  - `full`, 可选, `1` = 回答里带每个窗口的全部元素。
- 返回: 同 `GET api/debug/ui?brief=1`, 另有 `sent` (按了几下)、`keyPath` (实际走的哪条路, `system` / `app`) 与 `settled` (界面在 `timeout` 内停没停稳)。
- 注意: 按住时照系统的节奏连发 (先等系统的长按时间, 之后约每 50 毫秒一发), 应用收到的与真按住遥控器一样。一次最多 200 步, 单次按住或停顿最长 10 秒, 加起来最长 60 秒。应用不在前台时回 ok=false「应用不在前台…」。`app` 那条路不经系统的输入分发与输入法: 输入法开着时真遥控器的键先给输入法, 这条路直接给界面; 界面没接的媒体键系统会转给媒体会话, 这条路不转。一直在动的界面 (播放进度、倒计时) 停不稳, `settled=false` 属正常。`keys` 写得不对时回 ok=false 和原因, 一下都不按。 按住的中途拿按键的窗口换了 (长按打开了弹窗) 时同真遥控器: 连发到此为止, 原来的窗口收到一个取消的抬起, 抬起落在新窗口上 (它没见过这一下的按下), 回 ok=true。弹窗刚出来、换内容的那几帧里没有窗口拿着按键: 先等最多 1.5 秒, 还没有才算应用不在前台。

### POST api/debug/ui/click
按文字找一个元素, 把焦点送上去再按确定。
- 参数: `text`, 必填, 元素的文字或描述; `exact`, 可选, `1` = 只认整段相同的 (默认整段相同的优先, 没有时认包含这段字的); `nth`, 可选, 找到几个时要第几个 (从 0 起, 收按键的窗口里的排前面); `press`, 可选, `0` = 只送焦点不按; `path`、`timeout`、`full` 同 `key`。
- 返回: 同 `key` 的回答, 另有 `matches` (找到几个能聚焦的) 与 `via` (`focus` = 送焦点后按的确定键, `click` = 元素不能聚焦、直接触发的点击)。
- 注意: 焦点是直接送过去的, 不是一步步走过去的: 用来摆状态, 测焦点怎么走要用 `key`。屏上没有这段字、或这段字不在能聚焦的元素里时回 ok=false 和原因; 焦点没落到那个元素上时不按确定, 回 ok=false「焦点没落到…」(带 `focus`: 焦点实际在哪)。 一排选项把焦点让给选中的那一颗时 (弹窗里的胶囊行), 直接送焦点到不了别的那几颗: 先到这一排, 再用 `key` 左右走。

### GET / POST api/debug/ui/wait
等屏上出现一段文字, 或等它消失 (弹窗关了、页面换了)。
- 参数 (GET 用查询串, POST 用表单): `text` (等它出现) 或 `gone` (等它消失), 二选一; `timeout`, 可选, 毫秒, 默认 5000, 最大 30000; `full` 同 `key`。
- 返回: 同 `GET api/debug/ui?brief=1`, 另有 `found` (等没等到) 与 `waitedMs`。
- 注意: 等出现时, 提示 (toast) 里有这段字也算 (等的这段时间里弹的与正在显示的)。

### GET api/settings/debug/probes
读播放链路探针: 把只能看屏幕确认的判定变成数据 (HLS 去广告的结果、当前视频解码器与色彩信息)。
- 参数: 无。
- 返回: `{ok, hls[], video}`。
  - `hls[{host, totalSegments, removedSegments, removedSeconds, skippedReason, atMillis}]`: 最近的在前, 最多 20 条。`skippedReason` 为 null 表示删过广告段, 否则是没动它的原因: `no_discontinuity`、`single_group`、`no_ad`、`ad_break_too_long`、`encrypted`、`byterange`、`live_or_incomplete_playlist`、`invalid_playlist`, 或其它状态名。
  - `video`: 还没播过视频时为 null, 否则 `{decoder, nvidiaWorkaround, codecs, colorSpace, colorRange, colorTransfer, colorComplete, atMillis}`; 色彩三项是整数或 null, `colorComplete=false` (三项缺一) 就是假 HDR 的成因; `nvidiaWorkaround` 表示给 NVIDIA 解码器的 Surface 补写了 dataspace。
- 注意: 记的是应用启动以来的播放, 先播一段再读。只有经应用本地 HLS 代理过滤过的播放列表才会出现在 `hls` 里。

### POST api/settings/debug/set
改调试区里的一项, 立即生效。
- 参数: `key`, 必填, 取 `GET api/settings` 的 `debug[].key`; `value`, 必填, 按这一项的 `editor` 写原文:
  - `toggle`: `1` 或 `true` (不分大小写) 为开, 其它任何值都算关。
  - `number`: 数字, 要在 `min` ~ `max` 之间; 整数字段不收小数。
  - `choice`: `choices[].value` 之一, 原样提交 (可能含中文与空格, 要 URL 编码)。
  - `text`: 原文; `lines`: 每行一项, 用换行分隔。
- 返回 ok/message。成功时为「已保存「<标题>」」; 失败如「没有这一项设置：…」、「格式不对：…」、「没有这个选项：…」、「不是有效的数字：…」、「要在 … 到 … 之间」。
- 注意: `dev.` 开头的是代码里的临时开关, 有的只在内存里 (应用重启回到默认值), 有的存在设备上, 清单里看不出是哪种; 其余是登记的正式设置项, 照常存进设置。只收 POST。
