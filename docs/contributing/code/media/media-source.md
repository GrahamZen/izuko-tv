# MediaSource

数据源 `MediaSource` 是*资源*（[Media][Media]）的提供商。

`MediaSource` 主要提供函数 `fetch`，负责查询一个[条目](../subjects.md)的资源：

```kotlin
interface MediaSource {
    suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> // 可以理解为返回 List<Media>
}
```

查询以条目为单位。`MediaFetchRequest` 携带条目名称与 ID、条目的全部剧集（`episodes`）以及当前剧集的提示；
数据源返回该条目在本源能找到的全部资源：每一集的单集资源、每条线路（字幕组）以及合集，
不按当前剧集裁剪。按当前剧集筛选由 [MediaSelector](media-selector.md) 完成。
数据源须让每个资源的 `episodeRange` 尽量准确，并保证同一资源的 `mediaId` 在多次查询间稳定，
这样播放页切集只需重建选择器，下载可以为多集复用同一次查询。
`MatchKind.EXACT` 表示通过条目 ID 定位到了条目，`FUZZY` 表示由关键字搜索得到。

## 数据源类型

目前支持以下通用数据源和一些特别支持的数据源：

- `SelectorMediaSource`：通用 [CSS Selector][CSS Selector] 数据源；
- `RuleMediaSource`：按步骤规则抓取网站的通用数据源（规则源）；
- `RssMediaSource`：通用 RSS 订阅数据源；
- 特别支持的数据源：
    - `JellyfinMediaSource`、`EmbyMediaSource`：Jellyfin、Emby 媒体库；
    - `DmhyMediaSource`、`MikanMediaSource`：[动漫花园][dmhy]、[蜜柑计划][Mikan] 站点；
    - `IkarosMediaSource`：[Ikaros][Ikaros] 媒体库。

特别支持的数据源只是实现 `MediaSource` 接口以接入对应平台，本文不赘述。
下面我们将着重了解 `SelectorMediaSource` 和 `RssMediaSource`，以及网盘数据源。

### 网盘数据源

网盘数据源（`domain/mediasource/clouddrive`）的代码里没有任何具体的网盘：某个网盘的地址、请求参数、响应字段、状态码、
扫码登录的步骤、分享链接的格式都写在一份协议 JSON（`CloudDriveProtocol`）里，作为 `cloud-drive` 数据源的参数随订阅或导入下发。
`CloudDriveApi` 按协议解释执行一组固定的操作（搜索、列目录、取直链、转码地址、建文件夹、删除、任务轮询、打开分享、列分享、转存、扫码），
各操作是请求模板加上从响应里取值的路径（路径语法同直链 API 数据源）。

每个网盘（按协议 `id`）有一个 `CloudDriveService`，由 `CloudDriveRegistry` 从保存的数据源实例里读出全部协议后建立；
账号存在 `cloudDriveAccounts` 里，与数据源配置分开（导出配置不带账号）。三种数据源共用它：

- `cloud-drive`：在用户自己的网盘里按条目名搜视频；
- `cloud-drive-added-shares`：用户在 Web 控制台给条目添加的分享链接；
- `cloud-drive-share-search`：从固定的分享合集（按文件夹名对番名）或站点的搜索接口里找别人的分享，播放时转存到自己的网盘。

资源的 `download` 是占位地址（模板也在协议里），播放时由 `CloudDriveMediaResolver` 认出属于哪个网盘再取直链。
协议的文件字段写了视频时长（`DriveFileFields.duration`）时，生成资源时把平均码率（文件大小 ÷ 时长）按资源 id 记进
`DriveVideoBitrates`，选源面板与 Web 控制台的候选列表在大小后面显示；`Media` 本身没有码率字段，所以另记一份，只在内存里。

分享搜索用去掉季号与副标题的主标题搜站点，整个系列的分享都会搜出来，每次只打开前 `maxShares` 个。
打开前按站点剧名写的季排先后（同一档保持站点的顺序）：写了本季的在前，没写季的居中，只写了别的季的最后。
只排序不去掉，因为写着「第二季」的分享里常连第一季一起放。
协议的 `legacy` 段可以声明这个网盘以前由专门代码支持时留下的设置键与数据源类型，`CloudDriveRegistry` 第一次见到协议时把它们认领过来。

### 规则源

规则源（`domain/mediasource/rule`，类型 `rule`）用一份 JSON 规则（`RuleConfig`）抓取网站，适合 `SelectorMediaSource`
固定的「搜索页 → 条目页 → 播放页」形状表达不了的站点：剧集表写在脚本或一段文本里、播放地址要再请求一个接口、
要在几种取法之间回落。规则分搜索、详情、播放三段，每段是一串步骤（`RuleStep`），由 `RuleEngine` 依次执行：

- 每段从输入开始（关键词、条目页地址、剧集页地址），步骤改写「当前值」或存取变量：请求（`fetch`）、CSS 取值（`select`）、
  正则（`regex`）、JSON 取值（`json`，路径语法同直链 API 数据源）、模板与变量（`template`、`set`、`query`）、
  字符串变换（`transform`）、苹果 CMS 播放页（`maccmsPlayer`）、播放请求头（`mediaHeaders`）；`first` 依次尝试几个分支。
- 搜索段以条目列表步骤结束（`subjects`、`jsonSubjects`），详情段以剧集列表步骤结束（`episodes`、`jsonEpisodes`、`regexEpisodes`）；
  播放段结束时的当前值就是视频地址，以 `sniff`（`goal = video`）结束表示交给 WebView 嗅探。

站点上的东西取到之后，挑出当前这一集、搜索缓存、生成 `Media`、浏览协议都复用 `SelectorMediaSource` 的实现，
匹配相关的配置（`autoMatch`、`matchEpisodeSortFromName`、`matchVideo`）含义相同。资源的 `download` 是剧集页（`WebVideo`），
播放时 `WebVideoDirectResolver.resolveDirectly` 执行播放段；取不到或要求嗅探时，播放器按 `matchVideo` 用 WebView 打开剧集页。
GET 请求被站点验证挡住时改由 `WebSessionManager` 加载，与 `SelectorMediaSource` 共用验证码会话。

`AniBakaRuleImporter` 把 AniBaka 的规则（`anx-rule/2`）转换成规则源：两者都是「当前值 + 变量」的步骤流水线，
多数步骤一一对应；用到规则源没有的步骤（加解密、站点专用步骤、HLS 清单处理、XPath）的规则不转换并说明原因。
Web 控制台的导入接受 AniBaka 规则 JSON，或规则 / 规则库索引的地址（由电视下载）。
订阅地址也可以直接填 AniBaka 规则库索引（`AniBakaSubscription`，挂在 `MediaSourceSubscriptionRequesterImpl` 下载之后）：
每次更新订阅都重新下载并转换，之后与普通订阅一样按名字增删改；有规则文件没下载下来时这次更新算失败、原有的源不动，
否则缺了的源会被删掉再新建，用户对它的启停设置随之丢失。

### `SelectorMediaSource`

`SelectorMediaSource` 会根据配置，使用 [CSS Selector][CSS Selector] 和正则表达式，从 HTML
页面中提取资源信息及其播放方式。

#### 列表模式与自动匹配

> 自 Animeko v6.2。

数据源像一个网站：搜索得到条目列表，打开条目得到线路与剧集列表，选中一集得到可播放的资源。
这是数据源的基础形态，只要求把站点上的东西列出来，不要求判断条目是否属于请求、剧集是不是第几集，
由用户决定哪一条对应正在观看的剧集。自动匹配是这之上的可选一层。

Selector 配置据此分两层：

- **列表规则**：搜索结果怎么列、条目页的线路和剧集怎么列、播放页怎么提取视频。只写这一层，
  数据源就可以浏览和手动选集。
- **自动匹配**（`searchConfig.autoMatch`，以及 `tier` / `channelTiers`）：在列表之上让 `fetch`
  自动搜索并筛选出当前剧集的资源。全部可选，缺省时按默认策略自动匹配；`enabled: false`
  表示该源只供浏览，`fetch` 不发起任何请求。这类源在自动匹配页没有结果，在手动查找中照常可用。

拆分的原因：自动选择要在十几个数据源之间挑出正确的条目和正确的一集，需要条目名过滤、集号正则、
线路名正则、阶级等一整套判断规则；把它们与列表规则混在一起，编写门槛高，而站点上名字不含集号的资源
（OVA、特典）无论怎么配也自动选不出来。列表规则独立后，这些资源可以通过浏览手动选中。

运行时协议上，浏览是 `MediaSource` 本身的一部分：按关键字搜索条目、打开条目得到线路与剧集、
把一集转换为 `Media`。它不做任何匹配，关键字由调用方给出，结果原样返回，同名线路也不合并；
`fetch` 是建立在它之上的自动模式。所有数据源都应当是这种形态；尚未迁移的数据源（RSS、BT、媒体库）
以“搜索不到任何条目”的默认实现过渡，新数据源必须实现浏览。手动选中一集时由调用方指定它对应哪一集，
`Media.episodeRange` 由此而来。同一集不论从哪条路径得到，`mediaId` 相同，下载去重与偏好记忆因此不区分来源。

支持浏览的数据源通过 `supportsBrowsing` 声明：接口上的浏览方法有默认实现（空结果 / 抛出异常），
运行时无法区分「支持但没搜到」与「不支持」，所以未声明的源不出现在手动查找的源列表中，浏览记忆也不对它回放，
默认实现只用于兼容。线路标识 `BrowseChannel.name` 在同一条目内可以重复，站点没有线路概念时为 null，
调用方以线路在列表中的位置区分，记住用户的选择时同时记录位置与标识。`BrowseEpisode.episodeSort`
是数据源对集号的解析结果，只用于手动查找时预选与当前集同号的一项；浏览记忆回放只按位置，不看它。界面层不自行解析。
手动查找与记忆回放的取舍见[选源界面](media-selector-ui.md)。

浏览不读写搜索缓存：缓存按请求条目与关键字组织，服务于切集时不重复请求；
浏览的关键字由用户给出，缓存命中率低而语义含混，不值得共享。

条目格式按页面顺序返回结果，`autoMatch.preferShorterName` 只在自动匹配阶段把名称短的条目排到前面。
集号正则 `matchEpisodeSortFromName` 允许为空：列表规则不要求解析集号，空表示整个剧集名就是集号文本。

自动匹配的搜索关键词是一条链：先用 `autoMatch.searchUseSubjectNamesCount` 个条目名各搜一次；它们都没搜到名字能对上的条目时，
再依次尝试 `MediaFetchRequest.fallbackSearchKeywords`（带季度标记的别名、由系列关系推出的基础名），搜到为止。
站点给各季起名以第一季为基础（「出包王女 第二季」），Bangumi 的中文名却沿用官方译名（「更多 出包王女」），
只用后者搜索会一无所获，而取首词后它变成「更多」，更搜不到。回退只在前面都失败时发起，本来搜得到的条目不会多发请求。
「搜到了」的判据与选择器过滤 WEB 资源的名称规则相同，所以这里认为搜到的，选择器不会再以名字不符为由排除。
回退关键词只用于搜索，不加入名称匹配：基础名同时是第一季的名字，加入匹配会放过第一季的资源。
搜索缓存同时充当这条链的记忆：缓存行按关键词写入，只有搜到过条目页的关键词才有行；下次查询把有行的关键词排到最前，
它们搜到了就不再碰其余的，所以「更多」这类搜不到的关键词只在第一次和缓存过期后各花一次请求。
有行只说明搜到过条目页，不说明名字对上了，因此记忆只决定顺序，不参与判定。

#### 配置的兼容形式

订阅 JSON 会被所有版本的客户端读取，而导出格式的版本号一旦升高，不认识该版本的客户端就会整个拒绝该源。
因此自动匹配字段除了 `autoMatch` 这一处，还接受平铺在 `searchConfig` 顶层的写法，`preferShorterName`
还接受写在各条目格式配置里的写法，导出格式版本号不变：读取时没有 `autoMatch` 键就按平铺字段组装，
两者都有时以 `autoMatch` 为准；写出时两处都写，只认识平铺写法的客户端仍能读到，
只是不认识 `enabled`，会把只供浏览的源当作普通源使用。平铺写法待这类客户端淘汰后移除。

条目格式与线路格式各有多种实现，每种都有自己的配置对象（`selectorSubjectFormat*`、`selectorChannelFormat*`）。
写出时只写当前选中的格式，未选中的格式只在被改动过时才写，否则省略；读取时缺少的格式配置即为默认值，
任何版本的客户端都如此。这样订阅文件里只剩下真正生效的选择器，而编辑器里切换格式时已填写的内容也不会丢失。

`MediaFetcher` 先将结果写入共享回放缓存，再发布成功或失败等终态。
完成标记与结果按序通过 `flatMapLatest` 的缓冲区；重试时忽略旧查询的标记，
因此观察到终态时可以读取该次查询的完整结果（失败时为已收到的部分结果）。

查询可以暂停（`MediaSourceFetchResult.pause`）：进行中的请求被取消，已拿到的结果保留，
状态变为 `MediaSourceFetchState.Paused`（属于终态，等待完成的逻辑不会等它）；`restart` 从头重新查询。
播放页用它在开始播放后省资源，见 [MediaSelector](media-selector.md#web-自动选择)。

## 数据源阶级

> 自 Animeko v4.8。Channel 级阶级自 v4.9。

每个数据源拥有一个阶级 [`MediaSourceTier`][MediaSourceTier]。阶级值越低表示质量越高：`0`
为最高阶级。阶级影响 [MediaSelector](media-selector.md) 的两个环节：

- **排序**：有效阶级低的资源排在前面，详见[排序阶段](media-selector.md#排序阶段)；
- **快速选择**：阶级不超过阈值（目前为 `0`）的 WEB 数据源查询完成且有精确匹配结果后会被立即选择，
  无需等待其他数据源。超过阈值的数据源只能在等待一段时间后通过兜底逻辑被选择。
  入口为 `MediaAutoSelector.select` 的 WEB 阶段。

阶级来源于数据源配置 `MediaSourceArguments.tier`，通常由订阅提供；用户未配置时使用回退值
`MediaSourceTier.Fallback`（`2`）。

从 AniBaka 规则库导入或订阅的规则源按库里标注的广告情况定阶级：无广告 `0`、少广告 `1`、有广告 `3`
（`AniBakaRuleImporter.tierOfLabels`）。

`SourceProfiles` 记下每个数据源最近几次播放的实际表现（开播用时、分辨率与码率、有没有删到插播广告、规则源走没走 WebView、
失败），控制台据此给出建议阶级。规则源最近几次大多要 WebView 嗅探才拿到视频时，`mediaSourceTiersFlow` 给出的阶级比配置高一级；
其他数据源的阶级只取配置。

### Channel 级阶级

> 自 Animeko v4.9

`SelectorMediaSource` 支持 channel（俗称“线路”）：同一个页面上的多个播放列表。
数据源解析出的 channel 名称会写入资源的 `Media.properties.alliance` 属性。

`SelectorMediaSourceArguments.channelTiers` 可以为单个 channel 指定阶级，覆盖数据源整体的
`tier`；未列出的 channel 回退到数据源阶级。资源的**有效阶级**因此为：

```
有效阶级 = channelTiers[channel 名] ?: 数据源 tier
```

排序与快速选择都按有效阶级进行。这意味着：

- 同一数据源的不同 channel 可以与其他数据源交叉排序；
- 数据源整体阶级较高（数值大），但拥有一个 tier 0 channel 时，该 channel 的资源仍可被快速选择立即选中；
- 反之，数据源整体是 tier 0，但被降级的 channel 的资源不会被立即选中，只能走兜底。

订阅 JSON 中的配置示例（`SelectorMediaSourceArguments` 片段）：

```json
{
  "name": "示例源",
  "tier": 2,
  "channelTiers": {
    "线路A": 0,
    "线路B": 1
  }
}
```

新增字段对旧版本客户端向后兼容：解码器开启了 `ignoreUnknownKeys`，旧客户端会忽略
`channelTiers` 并继续使用数据源级阶级。

## 订阅启用状态

`MediaSourceSubscription.enabled` 持久化订阅的启用状态，默认值为 `true`。
禁用的订阅不参与自动或手动刷新。TV 设置中的订阅开关同时批量启用或禁用该订阅的所有数据源，
单个数据源仍可独立调整启用状态，不改变所属订阅状态。

`MediaSourceSubscriptionUpdater` 在订阅仓库事务之外下载订阅，在事务内检查当前启用状态并应用数据源差异。
订阅开关与差异应用共用仓库的串行更新边界，确保下载期间禁用订阅后不会应用过期响应。

## 扩展数据源支持

有以下多种方法扩展数据源支持：

- （最简单）编写通用的数据源的配置。可以在 APP 内“设置-数据源管理”中添加 `Selector` 和 `RSS`
  类型数据源。只需编写一些 CSS Selector 配置即可使用。
- 实现新的 `MediaSelector`。参考 `IkarosMediaSource`（位于 `datasource/ikaros`）。通常需要为 Izuko TV
  仓库提交代码，增加一个新的模块。

[Media]: ../../../../datasource/api/src/commonMain/kotlin/Media.kt

[MediaSource]: ../../../../datasource/api/src/commonMain/kotlin/source/MediaSource.kt

[MediaSourceTier]: ../../../../datasource/api/src/commonMain/kotlin/source/MediaSource.kt

[dmhy]: http://www.dmhy.org/

[Mikan]: https://mikanani.me/

[Ikaros]: https://ikaros.run/

[CSS Selector]: https://developer.mozilla.org/zh-CN/docs/Web/CSS/CSS_selectors
