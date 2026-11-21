# 远程文件

应用从网上取的「我们自己维护的文件」：清单、加密配置、加密通知、数据表、规则、版本信息。都在 GitHub 上，经 jsDelivr、raw 或下载镜像取；取不到时用存下的或包里内置的。

## 放在哪

| 放在 | 适合 | 生效 |
|---|---|---|
| 本项目仓库的清单分支 `lists`（`GitHubFileSources.LISTS_BRANCH`） | 清单类小文件：镜像、加密直连地址与 DoH、TMDB 图片入口、GitHub 下载镜像、一起看的代理、工单服务地址、BT 屏蔽规则、加密的应用配置 | 推上去后应用下次检查时取到；jsDelivr 的 testingcf 与 gcore 节点有最长 12 小时的边缘缓存（主动刷新只有 cdn 节点认） |
| 本项目仓库的 release 附件 | 要马上生效的：加密通知（release `notices` 的 `notices.txt`） | 附件没有 jsDelivr 缓存，改了下次取就是新的（上传后 GitHub 的下载地址要几十秒才指向新附件） |
| 数据仓库（`bangumi-sequel-seasons`、`bangumi-tmdb-map`）的 main | 工作流每天生成的数据表：系列关系图、推荐目录、TMDB 对应表 | 工作流推上去后下次检查时取到 |

清单分支与代码的 `main` 分开：改清单只推 `lists`，不用连带推 `main` 上还没发布的提交。用 `scripts/lists_branch.py put 文件…` 写进本地分支再 `git push origin lists`；加密配置用 `scripts/app-config.py`，加密通知用 `scripts/release-notice.py --sealed`（两者的密文格式与钥匙见 `scripts/sealed_text.py`）。仓库根目录 `main` 上的同名清单给读 `main` 的旧版本（1.0.9-alpha04 及以前）与 10-31 发布的那版安装助手（读 `github-download-mirrors.json`）用，改清单时这几份也要跟着改、推 `main`。

安装助手（`app/installer`，不依赖共享模块）自己取两样，入口同上：`lists` 分支的 `github-download-mirrors.json`（`DownloadMirrors`）与加密的应用配置 `app-config.txt`（`AppConfig`，用编进包里的同一把钥匙解开，交流群取它，取不到用编包时那份）。

## 从哪些入口取

只有一份入口表：`GitHubFileSources`（app-data `domain/foundation/RepoHostedList.kt`）。

- 仓库文件：`GitHubFileSources.urls(仓库, 路径, 分支)` = jsDelivr 三个节点（testingcf、gcore、cdn，顺序的理由在 `JSDELIVR_HOSTS` 的注释里）再加 `raw.githubusercontent.com` 兜底。检查更新、本版说明、放送时刻（bangumi-data）也用这份节点表。
- release 附件：`GitHubFileSources.releaseAsset` 拼出原地址，再由 `GitHubDownloadMirrors.sourcesOf` 接上下载镜像（镜像清单本身是清单分支上的 `github-download-mirrors.json`）。

## 公共件（app-data `domain/foundation/RemoteFiles.kt`）

- `RemoteFetch.first` / `firstSuccessful`：按顺序试入口，每个最多等 10 秒（可改）收到回应，回应交给调用方校验（解析、解密、格式），第一份通过的就是结果；校验里 `RemoteFetch.reject(理由)` 表示这个入口给的不能用，理由写进同一行日志。被墙的入口常常连接一直挂着，不限时要等客户端自己的连接超时（还会被重试）。正文按客户端的超时读完，大文件在慢网上不会被掐断。
- `RemoteFetch.firstChanged`：大一点、多数时候没变的文件（数据表、推荐目录）带 ETag 问。ETag 是各个 CDN 各算各的，只对本机那一份来自的入口带（`Revision`）；那个入口回 304 就是没变。本机没有那一份时不带，要整份。
- `RemoteRefresh`：什么时候取。建出来先看一次，之后每 `checkEvery` 看一眼，离上次取到超过 `staleAfter` 才取；给了 `foregroundAfter` 的，应用回到前台（`AppForeground`，冷启动也算）离上次超过它也取；`refreshNow` 不管多久都取。取到没有、什么时候取到由使用方自己记（`lastFetchedAt`，本机没有那一份时返回 null 就会马上取），存在哪、怎么解析也由使用方管。取的时候抛异常只记日志，定期检查照常。
- 网络体检开始时把 `RemoteRefresh.created`（这个进程里建过的）里 `refreshOnNetworkCheck` 的都取一遍（数据表与推荐目录不取：体检限时，几 MB 的文件取到一半被掐掉就白下了），报告的「清单」一节列 `RepoHostedList` 那几份。
- `RepoHostedList`：清单类 json（`{"字段": ["条目", …]}`）的现成实现：逐条归一化、存在设置里、内置兜底。

## 现有的几样

| 文件 | 类 | 过期 / 检查 / 回到前台 |
|---|---|---|
| 清单分支上的 7 份清单 | `RepoHostedList`（`BangumiMirrorListRepository`、`TmdbImageEndpoints`、`GitHubDownloadMirrors`、`BangumiEchDohList`、`BangumiEchAddressList`、`TogetherBrokers`） | 24 小时 / 每小时 / — |
| 对应表仓库的反馈入口 `report-endpoints.json` | `RepoHostedList`（`SubjectFeedbackService`，`main` 分支） | 24 小时 / 每小时 / — |
| 工单服务地址 `ticket-endpoints.json` | `FeedbackTicketEndpoints`（内置 `FeedbackTicketService.OFFICIAL_ENDPOINTS`；网络体检测清单里的第一个） | 24 小时 / 每小时 / — |
| 加密的应用配置 `app-config.txt` | `RemoteAppConfigRepository` | 24 小时 / 每小时 / 1 小时 |
| 加密通知 `notices.txt` | `SealedNoticesRepository`（开着的电视一小时左右收到，同检查更新的频率） | 1 小时 / 10 分钟 / 1 小时 |
| BT 屏蔽规则 `peer-filter-rules.json` | `PeerFilterSubscriptionRepository`（随订阅一起更新） | 启动时 24 小时内取过就不取 |
| 放送时刻（bangumi-data 每月一份） | `BangumiScheduleSource` | 用到时，7 天 |
| 反馈候选（对应表核对页数据） | `SubjectFeedbackService.entryCandidates` | 用到时 |
| 系列关系图 `bgm-anime.tsv`（取不到退到 `bgm-series-graph.tsv`） | `SeriesGraphTableRepository`（ETag；覆盖范围比本机小、全部动画的表换成只有系列的都不用） | 20 小时 / 6 小时 / — |
| TMDB 对应表 `map/bgm-tmdb.tsv` | `TmdbSubjectMapRepository`（ETag；设置里关了 TMDB 图片就不取；条目不到本机一半当过时） | 20 小时 / 6 小时 / — |
| 推荐目录 `rec/manifest.json` 与它引用的文件 | `RecommendationCatalogRepository`（清单带 ETag；只下本机没有的文件，核对 sha256，齐了才换上） | 12 小时 / 3 小时 / — |

系列关系图与 TMDB 对应表本机还没有时（刚安装），用到它们的地方最多等第一次检查 3 秒（`RemoteRefresh.awaitFirstCheck`）；推荐目录不等，没有时推荐照旧问 Bangumi。

加一样新的：选好放在哪，用 `RemoteFetch` 取、`RemoteRefresh` 定时机（要尽快送到正在用的电视就给 `foregroundAfter`），存下的那份与内置兜底自己管。
