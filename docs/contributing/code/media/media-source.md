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
- `JsonApiMediaSource`：通用 JSON 接口数据源，用于站点内容只经 JSON 接口（通常是 POST）加载、HTML 页面里没有数据的情况；
- `RuleMediaSource`：按步骤规则抓取网站的通用数据源（规则源）；
- `RssMediaSource`：通用 RSS 订阅数据源；
- 特别支持的数据源：
    - `JellyfinMediaSource`、`EmbyMediaSource`：Jellyfin、Emby 媒体库；
    - `DmhyMediaSource`、`MikanMediaSource`：[动漫花园][dmhy]、[蜜柑计划][Mikan] 站点；
    - `IkarosMediaSource`：[Ikaros][Ikaros] 媒体库。

特别支持的数据源只是实现 `MediaSource` 接口以接入对应平台，本文不赘述。
下面我们将着重了解 `SelectorMediaSource`、`JsonApiMediaSource` 和 `RssMediaSource`，以及网盘数据源。

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
资源的条目名取 `MediaFetchRequest.catalogSubjectName`（条目服务上的名字，用户改搜索名时不变，见 `CloudDriveMediaSource.subjectNameOf`）：
文件已经按条目对上了，而选择器判条目名不匹配只认条目服务的名字。
协议的文件字段写了视频时长（`DriveFileFields.duration`）时，生成资源时把平均码率（文件大小 ÷ 时长）按资源 id 记进
`DriveVideoBitrates`，选源面板与 Web 控制台的候选列表在大小后面显示；`Media` 本身没有码率字段，所以另记一份，只在内存里。

协议的 `type` 是 `webdav` 时是一台 WebDAV 服务器（NAS 等，见 `WebDav`）：不写请求模板，`WebDavClient` 用 `PROPFIND` 列目录，
直链就是文件在服务器上的地址；账号密码登录（存在账号的 `username` / `password`），列目录、播放、取字幕都带 Basic 认证头。
这类网盘由用户在 Web 控制台添加（`WebDavSources`，默认层级 T0），协议里只有名字、地址与媒体文件夹，账号密码不随数据源导出。
没有服务端搜索时（WebDAV，或指定了媒体文件夹的接口网盘，见 `searchesLibrary`），`cloud-drive` 在媒体文件夹的索引
（`DriveLibraryIndex`：往下列 `library.depth` 层，用 `refreshMinutes` 后后台重建）里按名字找番，之后与搜索结果一样往下展开、认季认集。
WebDAV 列目录不给画面高度与时长：`cloud-drive` 给出候选前读候选视频的文件头（`DriveVideoInfoProbe`，mkv / mp4，经 `MediaStreamProbe`）
补上，才标得对分辨率、显示得出码率；结果按文件记在账号里只读一次。只等当前这一集的（最多 1 秒），其余各集在后台读；
条目级查询切集不再查，后来才读到的由 `DriveVideoInfoRefresh` 记下，切集复用查询时让这个源重查一次。

分享搜索按条目名（去掉季号与副标题的主标题，最多 `maxKeywords` 个）搜站点，前一个名字找到了分享就不再换名字。站点按标题原样找：
名字原样搜不到时先去掉装饰符号（`DriveNameParser.withoutDecorations`：`浪客剑心 －明治剑客浪漫谭－ 京都动乱`，字不删），
再去掉中日文之间的空格（`SubjectSearchPlan.withoutSpacesBetweenCjk`），然后才轮到下一个名字。
认出的剧集没覆盖每一集时，再依次搜这一部的副标题（「系列名 + 副标题」的条目，见 `DriveSeriesParts`；剧名要同时有系列名与这一部的副标题）
与系列名（`MediaFetchNameHints.seriesNames`），共三次，覆盖了就停：只写副标题（「辉夜大小姐想让我告白：究极浪漫」）或系列名加季
（「物理魔法使马修第二季」）的分享按条目名搜不到。这样搜到的多是整个系列的合集，里面的视频照样按路径认部（见下）。
每个词搜到的分享按站点剧名写的季排先后（同一档保持站点的顺序）：写了本季的在前，没写季的居中，只写了别的季的最后，打开前 `maxShares` 个；
只排序不去掉，因为写着「第二季」的分享里常连第一季一起放。打开过的分享不再打开。本季按 `DriveSubjectSeason` 定（见下）。

自动按名字找的地方（分享站的标题、固定合集的文件夹、分享与自己网盘里的视频、播放线索里的文件夹）都按 `DriveSubjectIdentity` 认部，
名字与网页类数据源的[搜索计划](#搜索计划)是同一组（搜索名与 `MediaFetchRequest.nameHints`）。本条目的名字是这一部；
同系列别的作品的名字是别的部：系列关系图（`SeriesGraphTable`）里以本条目主标题开头的、顺着前传续集走得到的，以及 `nameHints.otherWorkNames`，
本条目名字里含着的是系列名，不算。一段名字里同时有几部的名字时最长的说了算（「机动战士高达SEED」是 SEED，「XX 第三季 (附第二季)」是第三季）。
「系列名 + 副标题」的条目再按副标题认：只有这一部的是这一部，只有别的部的是别的部，都有的（「诀别谭+相克谭+死神全集」）说不清。
系列的第一部还看分享站标题写的年份，都与开播年份差一年以上的去掉（重制版除外；续作不看年份，整部的合集常按第一季的年份写）。
播放线索里的文件夹是别的作品时线索作废、照常搜：自动选错过一次的会被记下。用户添加的分享、手挑的文件夹不查。

视频按路径认部（`DrivePathAttribution`）：从文件往上（文件名、各层文件夹、分享标题），第一个点名了某一部的那一层说了算。
除了名字，只写了本季的也是这一部，名字点名的是第一季、却写着本季（第 2 季及以后）的也算（「弦音 -风舞高中弓道部- 第二季」）；
拆分季的一段，写着几段共有的季或拆分季的季名、又写了第几段的（「S04 Part2」「进击的巨人 S04 最终季 part1」），本段是这一部、别的段是别的部。
点名这一部的那一层与文件之间隔着另一套编号的文件夹时那个名字不算：写了别的季、拆分季共有的季，或本条目集号以外的集号范围（「1-40」）——
这类分享按最新一部起名，里面附着前几部。点名别的部的视频不要。
系列里后来的作品（有前传、名字含系列名或写明第 2 季及以后）路径说不清是不是这一部时只认集号上的证据：对应表对上的 `S几E几`；
拆分季按共有的季编的集号落在本段里的（Re:Zero 第二季前半的 `S02E05`，对应表却把整部算一季）；这一部的跨部连续集号（「死神 千年血战篇 / 27.mkv」之于相克谭），
要求离文件最近、提到这个系列的那一层点的是系列名，而不是集号与本条目重叠的作品（2004 年的「死神」TV 版也从 1 编到几百），
集号也是本季序号时还要所在文件夹里有只能是连续编号的集号。整个系列的合集里各部都从 01 编起，分不清的不要；系列的第一部照季的规则认。

整个系列的合集里各季、外传、剧场版的视频都从 01 编起，认集时（`DriveSubjectMatcher.matchEpisodes`）：文件夹的季也认罗马数字
（「XX I」…「XX V」、`Ⅳ`，前面是 Part / Vol 的不算），按季分好的一层只列本季的文件夹；
文件夹或文件名写明是剧场版、外传、OVA（`DriveNameParser.workKindsOf`）而条目不是这一类的不要，「TV+剧场版」「1-4季」这类合集名不算，
只有一集的条目（剧场版）与用户手挑的文件夹不查。

本条目按第几季找（`DriveSubjectSeason`）：条目名写明的季从严（中文名或原名写了的按它，都没写时别名写的季只有一个才算：别名里的季常是别的说法），
不写季的文件只认系列绝对集号；名字没写时推出来的季只去掉写明别季的，
不写季的照留。推的来源依次是 TMDB 对应表（这个条目全在同一季且是第 2 季及以后；表里记成第 1 季接着编号的续作很多，那种不推）
和系列关系图（`seriesSeasonOf`：最近的 TV 前传是系列第一部或名字写明了季，本条目又不是它拆出来的后半，就是下一季；只在有 TMDB 逐集对位时用）。
认出了季与集的文件（文件名的 `S02E05`，或「Season 1」文件夹里的 `12.mkv`）先按对应表换算；TMDB 把后来的作品单列成一部（S1E1 起）时，
网盘上的「S1」多半是系列的第一季，路径点名了这一部才查表（「堀与宫村 -piece- (2023) S01」是它自己的季，「堀与宫村 S1」与「堀与宫村 S2」并排时 S1 是前传）。
网盘把系列里的一段当成单独一部、从这一段起编季的（`S01E41`，TMDB 记成 S2E41），路径点名了这一部、集号又正是系列连续集号时照集号认。
改这些规则用 60 部续作的离线录放（`DriveSeasonReplayTest`）比改前改后认出的文件。

### BT 源查续作

按关键词搜发布标题的 BT 源（`TopicMediaSource`、`RssMediaSource`）用条目的每个名字各搜一次，查续作时再用系列名搜一次
（`fetchWithSeriesName`，系列名取自 `fallbackSearchKeywords`）：字幕组给续作的标题常不写季号，只写系列名加系列内序号
（「药屋少女的呢喃 [49]」是第三季第 1 集）或「S3」。系列名会搜出整个系列，而选择器不核对 BT 资源的名字，
所以只留下认得出是本季这一集的：集号含系列内序号且它与条目内序号不同，或者标题写明了本季且集号对得上。
集号只认解析出的具体序号，解析成「整季」的不算。条目名没写季、集号又每季从 1 编时，系列名搜到的分不出季，不多搜。
蜜柑按条目 id 找番剧页，不需要这一步。
协议的 `legacy` 段可以声明这个网盘以前由专门代码支持时留下的设置键与数据源类型，`CloudDriveRegistry` 第一次见到协议时把它们认领过来。

### RSS 源：从标题算出直链

有的视频在另一个文件站上按发布标题存放，地址哪里都不直接给，只能从种子站的标题算出来（如 ANi 的文件站）。
RSS 源配置的 `titleToLink`（`RssTitleToLink`）在解析出条目之后、生成资源之前改写条目：`pattern` 整个匹配标题，
对不上的条目丢掉；它的命名分组在 `url`、`title` 模板里用 `{名字}` 取（`url` 里做 URL 编码，`{名字:raw}` 原样）。
文件站按番开播的季度分目录时用 `{seasonYear}` / `{seasonMonth}` / `{seasonMonth2}`：开播日期取条目正片里最早的 Bangumi 播出日期
（`MediaFetchRequest.firstAirDate`，经 `RssSearchQuery.subjectAirDate` 传进来），不知道时用同一部番（命名分组 `groupBy`）里最早一条的发布日期。
种子站的搜索结果常常只有最近的几十条，而且续作常与第一季同名，所以优先用条目的日期；比它早一个月以上发布的条目是更早的季，丢掉。
`seasonRoundUpDays` 让季末几天首播的番归到下一季，`maxAgeDays` 丢掉开播太久、文件站已经删掉的。
改写出的视频地址放在条目的 enclosure 上，原来的链接留作来源页。
配置了 `titleToLink` 的 RSS 源结果全是直链，算在线源（`rssSourceKindOf`），其余 RSS 源算 BT：选源面板按数据源的类别分栏，
在线源那一排只收在线源、BT 栏只收种子，类别不对的源两边都显示不出来。

`searchFallbackKeywords` 让 RSS 源在条目名之外也用回退关键词搜，其中有对应表里的港台译名（`withRegionalNames`），
只认台湾译名的种子站要靠它。

### 规则源

规则源（`domain/mediasource/rule`，类型 `rule`）用一份 JSON 规则（`RuleConfig`）抓取网站，适合 `SelectorMediaSource`
固定的「搜索页 → 条目页 → 播放页」形状表达不了的站点：剧集表写在脚本或一段文本里、播放地址要再请求一个接口、
要在几种取法之间回落。规则分搜索、详情、播放三段，每段是一串步骤（`RuleStep`），由 `RuleEngine` 依次执行：

- 每段从输入开始（关键词、条目页地址、剧集页地址），步骤改写「当前值」或存取变量：请求（`fetch`）、CSS 取值（`select`）、
  正则（`regex`）、JSON 取值（`json`，路径语法同直链 API 数据源）、模板与变量（`template`、`set`、`query`）、
  字符串变换（`transform`）、解密与摘要（`crypto`：AES-CBC 解密、MD5）、苹果 CMS 播放页（`maccmsPlayer`）、播放请求头（`mediaHeaders`）；
  `first` 依次尝试几个分支。
- 搜索段以条目列表步骤结束（`subjects`、`jsonSubjects`），详情段以剧集列表步骤结束（`episodes`、`jsonEpisodes`、`regexEpisodes`，
  以及苹果 CMS 视频接口的 `maccmsEpisodes`：按 `vod_play_from` / `vod_play_url` 拆线路与剧集，见 `MacCmsPlayLines`）；
  播放段结束时的当前值就是视频地址，以 `sniff`（`goal = video`）结束表示交给 WebView 嗅探。

站点上的东西取到之后，挑出当前这一集、搜索缓存、生成 `Media`、浏览协议都复用 `SelectorMediaSource` 的实现，
匹配相关的配置（`autoMatch`、`matchEpisodeSortFromName`、`matchVideo`）含义相同。
关键词与打开哪些条目页同网页抓取数据源（[搜索计划](#搜索计划)），每个关键词最多打开 `maxSubjects` 个：
有的站搜索结果固定是一整页按相关度排的条目，后面是只沾一两个字的，不是这一部的不开。资源的 `download` 是剧集页（`WebVideo`），
播放时 `WebVideoDirectResolver.resolveDirectly` 执行播放段；取不到或要求嗅探时，播放器按 `matchVideo` 用 WebView 打开剧集页。
GET 请求被站点验证挡住时改由 `WebSessionManager` 加载，与 `SelectorMediaSource` 共用验证码会话。

`fetch` 可以把响应设的 Cookie 存进变量（`cookies`），交给 `mediaHeaders` 让播放器带上：有的站点取视频地址的接口同时发下载视频要的 Cookie，
不带就 403。剧集分页列出时，`episodes` 的 `nextPage` 依次打开后面的页，合成一份列表。站点只给「全部条目」的列表、没有搜索接口时，
`jsonSubjects` / `subjects` 的 `filterByKeyword` 按关键词筛（两边去掉空白与标点、繁转简后比较）；列表的每一项本身是数组时，路径用 `[0]`、`[1]` 按下标取。

播放段的 `sniff`（`goal = video`）可以写 `timeoutMillis`：站点要很久才发出视频请求时，这个源的 WebView 嗅探至少等这么久。
匹配器实现 `WebVideoSniffTimeout` 报出它，安卓的解析器取全局设置与它中较大的（它最多算 30 秒，与设置里能选的最长时长相同）。

`AniBakaRuleImporter` 把 AniBaka 的规则（`anx-rule/2`）转换成规则源：两者都是「当前值 + 变量」的步骤流水线，
多数步骤一一对应；用到规则源没有的步骤（站点专用步骤、HLS 清单处理）的规则不转换并说明原因。
XPath（`listXPath` / `roadsXPath` 等）由 `XPathToCss` 换成 CSS 选择器，只认按节点名与属性、文字挑元素的写法，换不了的照样报原因。
`fetch` 的请求体写成对象时，按 `contentType` 拼成表单字段或 JSON（变量用模板的 `{x:json}` 按 JSON 字符串转义）。
`jsonSeries` 只给条目 id（AniBaka 由详情段自己拼请求地址）时，条目地址取详情段开头那个用到 `{seriesId}` 的请求的地址，详情段的那个请求改为直接打开条目地址。
站点专用步骤里能用通用步骤表达的照样翻译：anime1 的三步翻成「番剧表按关键词筛 → 分类页翻页取文章 → 文章里的 token 换视频地址并带上接口发的 Cookie」。
`keywordMatch` 对到 `filterByKeyword`。
嗅探的选项照录进 `sniff`：等待时长生效；页面加载好 / 被挡的标志文字（`readyContains` / `rejectContains`）、加载好后再等多久、
视频地址要跟随跳转（`resolveMediaRedirects`）目前只记录，不起作用。
Web 控制台的导入接受 AniBaka 规则 JSON，或规则 / 规则库索引的地址（由电视下载）。
订阅地址也可以直接填 AniBaka 规则库索引（`AniBakaSubscription`，挂在 `MediaSourceSubscriptionRequesterImpl` 下载之后）：
每次更新订阅都重新下载并转换，之后与普通订阅一样按名字增删改；有规则文件没下载下来时这次更新算失败、原有的源不动，
否则缺了的源会被删掉再新建，用户对它的启停设置随之丢失。

订阅更新（`MediaSourceSubscriptionUpdater`）按名字对应新旧：新的加、没了的删、同名的原地换配置（实例与启停不变）。
订阅里改了名的源由 `SubscriptionRenames` 认出（同类型、同站点的一删一增配成一对，站点不看图标地址），同样原地更新。
更新后默认保留用户调过的顺序，新加的源排在最后；设置「订阅更新时恢复订阅里的顺序」
（`MediaSelectorSettings.restoreSubscriptionOrder`）打开时才把这个订阅的源排回订阅里的顺序。

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

#### 搜索计划

网页、规则、JSON API、直连 API 数据源搜什么词、站点上的条目是不是这一部，都取自同一份搜索计划
（`SubjectSearchPlan`，`domain/mediasource/search`），各源只按自己的写法（`KeywordStyle`：只取首词、去特殊字符、用几个名字）改写关键词。
它读请求里的搜索名与 `MediaFetchRequest.nameHints`（`MediaFetchNameHints`，由 `MediaFetchRequest.create` 填）：
条目服务上的全部名字、港台译名、系列关系推出的「系列名 第N季」（只拿来认部）、系列名（同系列别的作品里被本条目名字包含的，长的在前）、
同系列别的作品的名字、是不是拆分季。名字与选择器认条目用的是同一组（见[选源](media-selector.md#过滤规则列表)）。

自动匹配的搜索关键词是一条链（`SubjectSearchPlan.keywords`，链本身是共用的 `keywordSearchChain`）：
先用 `autoMatch.searchUseSubjectNamesCount` 个条目名各搜一次；它们都没搜到这一部时，依次试回退关键词，搜到为止：
请求的 `fallbackSearchKeywords`（带季度标记的别名、由系列关系推出的基础名、港台译名）、被系列名换下的名字自己的首词、
名字去掉中日文之间空格的写法（站点按字面找，「继 怪怪守护神」搜不到「继怪怪守护神」）。改写后相同的词只搜一次，截成太弱的词（一个字、很短的英文）的回退不搜。
只取首词的源本来就是想搜整个系列的短词：名字里含系列名时取系列名的首词，「继 怪怪守护神」的首词「继」什么都搜得到，系列名「怪怪守护神」才是；
没有系列名而首词太弱时用整个名字（名字本身就短的照搜，「K」）。
站点给各季起名以第一季为基础（「出包王女 第二季」），Bangumi 的中文名却沿用官方译名（「更多 出包王女」），
只用后者搜索会一无所获，而取首词后它变成「更多」，更搜不到。回退只在前面都失败时发起，本来搜得到的条目不会多发请求。

「搜到了这一部」（`SubjectSearchPlan.foundOwn`）与「站点上的条目是不是这一部」（`identify`）的判据与选择器过滤 WEB 资源的名称规则相同：
与本条目某个名字相同的是；正是本条目去掉季号的系列名、而本条目按系列内序号编集时说不准（站点把整个系列放在一页）；
季号是别的季、或与同系列别的作品同名的不是；剩下的名字对得上才是；拆分季的一段不判别季。所以这里认为是这一部的，选择器不会以名字不符或别季为由排除，
链也就不会停在只搜到第一季的那次；认为不是的，选择器也会排除。自动匹配因此只打开可能是这一部的条目页（`pagesToOpen`：是的在前，说不准的随后），
不是的不开：取首词搜出的一大片无关条目不必每个都发一次请求。
回退关键词只用于搜索，不加入名称匹配：基础名同时是第一季的名字，加入匹配会放过第一季的资源。

站点回应另有一份跨条目共用的内存记忆（`SiteSearchMemory`，挂在 `SelectorMediaSourceEpisodeCacheRepository.siteMemory`）：
搜索缓存按发起查询的条目分开记，而同系列各季多拿同一个系列名搜，换一季时站点的搜索结果与条目页都是同一份。
搜索结果最多记 10 分钟（站点新开了一季的页面，记久了就一直看不到），条目页记满搜索缓存的有效期、取出时还要含已上映的最新一集；
有效期为 0 时不记，手动重新查询时清空。

回退关键词的最后是一个港台译名（`RegionalSubjectNames`，接在 `CreateMediaFetchSelectBundleFlowUseCase` 建请求处）：
台湾、香港的站点只认当地译名，两岸译名又常常不只是简繁之别（间谍过家家 / 間諜家家酒、药屋少女的呢喃 / 藥師少女的獨語）。
它来自 TMDB 对应表（bangumi-tmdb-map）的 `names` 列，即 TMDB 条目的 zh-TW / zh-HK 翻译。TMDB 的剧集按系列登记，译名是系列名，
所以按条目名字里的季号补上（「藥師少女的獨語 第二季」）。排在请求回退关键词的最后，是因为只有别的名字都没搜到时才该用它：
排在系列名前面的话，简体站会先用台湾的系列名（「排球少年！！」）命中第一季。只给一个，免得对同一个站反复搜。
请求认部用的名字同样带上港台的本季与别季名字，这条链认得出繁体页面已经是这一部。
选择器那边把港台译名作为条目别名（同样补上季号；后面的季推不出季号时不加，否则系列名会让第一季的资源也算作本季），
站点上的繁体条目名才对得上；同时把别的季的港台名字（译名补上它们各自的季号，第一季是译名本身）加进系列信息，
别季的繁体页面才会按「页名是系列里别的季」排除 —— 只差「第三季」三个字的长名字（「SPY×FAMILY間諜家家酒」）模糊匹配率能过线，光有别名会被当成本季。BT 查续作从回退关键词里挑系列名时只挑条目自己某个名字里含有的，不会拿港台译名去搜：BT 资源不核对名字。
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

### `JsonApiMediaSource`

`JsonApiMediaSource` 面向把搜索、剧集列表与播放地址都放在 JSON 接口后面的站点。配置
（`JsonApiSearchConfig`）按三个阶段描述接口：搜索条目、取条目的剧集列表、为一集签发播放地址。
每个阶段是一个请求模板（地址、方法、请求头、请求体，变量用 `{name}` 代入）加几个
JSONPath 表达式；公共请求头（如 `apikey`）与地址前缀在三个阶段间共用。

#### 设计目标与取舍

- **与 Selector 源共用自动匹配层。** `JsonApiSearchConfig.autoMatch` 就是 `SelectorAutoMatchConfig`，
  搜索缓存也写进同一张 `WebSearchSessionCache` 表（剧集的站点 id 从播放页地址按模板反解，
  不单独存列）。关键字链（主名、回退关键字、记忆关键字的顺序与节流）是两种源共用的 `keywordSearchChain`。
- **请求节流是源级的。** 三个阶段的请求共用一个最小间隔（`requestInterval`），而不是像 Selector 源那样
  只在搜索之间等待：接口站点对剧集与播放请求同样限流，而且自动匹配一次会连着发搜索、剧集与播放请求。
- **线路是声明出来的。** 有的站点剧集列表里没有线路，线路只在播放响应的候选数组里以一个
  标识字段出现。因此线路格式 `declared` 让用户按 `名称=匹配值` 列出线路，每条线路列出全部剧集；
  播放时按资源所在线路的匹配值挑候选。资源 id 总是带线路（`源.条目id-线路-剧集id`）：
  同一集的各线路只差线路这一个维度，合并它们会让自动选择与失败换线路无从区分。
  声明的线路对每个条目都存在，但站点未必给每个条目的每条线路候选（例如新番与旧番走不同线路），
  所以默认在线路没有候选时退用第一条候选，让自动选中的资源总能播放；代价是播的可能不是所选线路，
  编辑器的播放详情页会标出这种退用。
- **搜索结果先认部再取剧集**（[搜索计划](#搜索计划)）。不是这一部的条目选择器也会排除，不值得为它们各发一次剧集请求。
  站点给出别名时（`selectAliases`）条目名与别名里最像这一部的作资源与搜索缓存里的条目名：
  选择器下游用同一判据按资源的条目名再过滤一次，保留站点标题会让只有别名对上的条目在那里被排除。
- **播放地址在播放时签发。** 签发的地址有效期短（通常几十分钟），不能写进 `Media`。
  资源的 `download` 是 `ResourceLocation.WebVideo(页面地址, apiParameters = 播放请求的变量)`；
  `apiParameters` 非空表示"由接口解析"，播放与缓存前 `ApiVideoMediaResolver` 从 `MediaSourceManager`
  找到同 id 的源（它实现 `ApiVideoResolver`）当场签发，平台 WebView resolver 不打开页面。
  同一集的播放响应在源内短暂记忆（60 秒），同一集换线路不重复请求。
  用 `WebVideo` 的可选字段而不是新的 `ResourceLocation` 子类，是因为下载记录的反序列化遇到未知
  子类时会丢弃整个列表，旧版本客户端打开新版本写入的下载列表就会丢掉全部记录。
  已知限制：缓存下载中途地址过期后，下载器没有换地址重试的接口，与 Selector 源带签名的直链相同。
- **接口的请求头不发给视频主机。** 视频请求只带 User-Agent、Referer 与播放阶段单独配置的 `videoHeaders`，
  站点的 API key 不会流向 CDN；反过来视频主机要求的 Origin 一类请求头也不混进接口请求。
- **剧集列表自带的地址也在播放时重取。** 有的站点剧集列表就给出可播放地址，不需要签发请求
  （`JsonApiEpisodeFields.selectVideoUrl`，此时播放阶段的请求可留空）。这类地址同样不写进 `Media`：
  列表里的直链也可能带签名有时效，而播放时按条目 id 重取一次剧集列表读这一集的地址，代价与签发请求相同。
  资源仍走 `ApiVideoMediaResolver` 而不是 `HttpStreamingFile`，因为后者的 resolver 不带 Referer。
  同时配置了签发请求时，只对列表里没有地址的剧集发它。
- **编辑器的测试面板只在请求变化时重新请求。** 测试器缓存每个阶段的原始响应，只改 JSONPath 时
  用缓存重新解析。请求体代入变量后必须是合法 JSON（字符串位置自动转义），编辑器在发送前就能发现模板错误。

浏览方法与自动匹配产出相同的 `Media`（同一集两种途径得到相同的 `mediaId`），浏览时条目与剧集的站点 id
从页面地址按模板反解，模板对不上的地址视为不属于本源。

## 数据源阶级

> 自 Animeko v4.8。Channel 级阶级自 v4.9。

每个数据源拥有一个阶级 [`MediaSourceTier`][MediaSourceTier]。阶级值越低表示质量越高：`0`
为最高阶级。阶级影响 [MediaSelector](media-selector.md) 的两个环节：

- **排序**：有效阶级低的资源排在前面，详见[排序阶段](media-selector.md#排序阶段)；
- **快速选择**：阶级不超过阈值（目前为 `0`）的 WEB 数据源查询完成且有精确匹配结果后会被立即选择，
  无需等待其他数据源。超过阈值的数据源只能在等待一段时间、或 WEB 数据源全部查询结束后通过兜底逻辑被选择。
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

## 数据源标签

订阅可以在数据源条目上写 `tags`，告诉客户端这个数据源需要特殊处理。标签写在条目上而不是 `arguments` 里，
因此对所有数据源类型通用。标签随订阅更新覆盖到本地配置 `MediaSourceConfig.tags`，用户不能修改；
本地添加的数据源没有标签。

```json
{
  "schemaVersion": 2,
  "mediaSources": [
    { "id": "example", "tags": ["hls-ads"], "factoryId": "web-selector", "version": 2, "arguments": { } }
  ]
}
```

已知的标签见 `MediaSourceTags`：

- `hls-ads`：这个数据源的 HLS 播放列表可能插有广告。过滤插播广告要在起播前探测时间戳，会推迟开播，
  所以默认的「智能」模式只对带这个标签的数据源过滤，见 `HlsAdFilteringMode`。

客户端忽略不认识的标签，旧客户端忽略整个 `tags` 字段，所以新增标签不需要提升 `schemaVersion`。
订阅文件由第三方维护，已发布的标签不能改名。

## 扩展数据源支持

有以下多种方法扩展数据源支持：

- （最简单）编写通用的数据源的配置。可以在 APP 内“设置-数据源管理”中添加 `Selector`、`JSON API` 和 `RSS`
  类型数据源。只需编写一些 CSS Selector 或 JSONPath 配置即可使用。
- 实现新的 `MediaSelector`。参考 `IkarosMediaSource`（位于 `datasource/ikaros`）。通常需要为 Izuko TV
  仓库提交代码，增加一个新的模块。

[Media]: ../../../../datasource/api/src/commonMain/kotlin/Media.kt

[MediaSource]: ../../../../datasource/api/src/commonMain/kotlin/source/MediaSource.kt

[MediaSourceTier]: ../../../../datasource/api/src/commonMain/kotlin/source/MediaSource.kt

[dmhy]: http://www.dmhy.org/

[Mikan]: https://mikanani.me/

[Ikaros]: https://ikaros.run/

[CSS Selector]: https://developer.mozilla.org/zh-CN/docs/Web/CSS/CSS_selectors
