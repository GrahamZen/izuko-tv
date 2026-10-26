# 缓存 `Media`

用户可管理的视频下载见 [下载管理](media-downloads.md)。本文中的“缓存”指底层媒体存储机制。

Ani 目前支持缓存许多类型。主要可以分为三类：

- BT（磁力链接，种子文件）；
- HTTP 协议的视频文件（如 MP4）；
- HLS 流式传输资源（如 M3U8）。

BT 资源由 BT 引擎处理（Anitorrent），HTTP 和 HLS 资源由下载器 `HttpDownloader` 处理。

## BT 缓存的持久化

`TorrentMediaCacheEngine` 用两张表记录 BT 缓存：`torrent_cache` 以 `mediaId` 为主键保存种子数据与下载目录，
一个合集只有一行；`torrent_cache_episode` 以 `(mediaId, episodeId)` 为主键保存每集的完成状态、
种子内的文件路径与流量统计。同一合集的多集共用一行 `torrent_cache` 与一个下载会话，各自有一行 `torrent_cache_episode`；
删除某集只删除它的剧集行，合集没有剩余剧集时才删除种子行与文件。
`torrent_cache` 上的完成状态与文件路径列只用于恢复没有剧集行的旧记录（数据库版本 24 之前创建的缓存）：
恢复时按记录的剧集判断旧行的文件是否属于该集，是则沿用，否则重新从种子中选择文件，并补建剧集行。

## 缓存位置

缓存可以放在内部存储或外接盘上（`domain/media/cache/location/`）。`MediaCacheLocations` 列出此刻接着的位置
（`MediaCacheLocation`：内部存储的 `id` 为 `null`，外接盘为系统给这个卷的 UUID），并给出新缓存的位置
（`MediaCacheSettings.defaultLocation` 选的盘接着且能用时是它，否则是内部存储）。安卓实现 `AndroidMediaCacheLocations`
只用各盘上本应用的私有目录（`getExternalFilesDirs`），不用申请权限，原生库能直接按路径读写；卸载应用时系统会一起删掉。
FAT32（单个文件最大 4 GB）与只读的盘不能放新缓存。没有平台实现时只有内部存储（`InternalOnlyMediaCacheLocations`）。

- 各位置根目录下的布局相同（`anitorrent/pieces/<种子>`、`web-m3u/…`）。每条缓存只记它在哪个位置：BT 记在
  `torrent_cache.location`，网页缓存记在 `http_cache_download_state.location`（数据库版本 30 起；为空 = 内部存储，旧缓存都是）。
  换默认位置不影响已有的缓存。
- 一个种子只放一个位置：同一种子的其他剧集、别的数据源缓存的同一种子，都跟着已有的种子行。内部存储上的种子目录由引擎决定，
  别的位置上的经 `TorrentDownloader.startDownloadAt` 指定。网盘引擎（PikPak）的文件只放内部存储。
- 盘没接上时，恢复记录得到 `UnavailableMediaCache` 占位：不能播，暂停 / 继续不做什么，选源时不出现；删除只删记录，
  文件等下次带着这个盘启动时由清扫回收。绝不能把「盘不在」当成「文件丢了」去删记录或重新下载。
- 盘插拔时 `MediaCacheLocationRefresher` 让各存储重新恢复：拔掉的换成占位（网页缓存还在跑的任务先暂停），插回来的换回真的缓存。
  下完的缓存（`LocalFileMediaCache`）平时恢复一次就不再管，外接盘上的这时先用 `forgetRestoredLocalFiles` 让存储重新恢复它们。
  网页缓存下载出错时如果它所在的盘已经不在，记成暂停而不是失败，插回来后接着下。
- 启动清扫外接盘上的种子目录时，归属按数据库里的全部种子记录算（不只这次恢复出来的），恢复时盘还没接上的记录的目录不会被删。
- `DownloadSpaceGuard` 按位置分别检查剩余空间，只暂停快满的那个盘上的下载。
- 设置入口：电视设置页「存储 → 缓存位置」（`TvSettingsEntriesCacheLocation`），Web 控制台的同名卡片（`RemoteCacheLocationSetting`），
  两边用同一份选项（`mediaCacheLocationChoices`）。

### 把已有缓存搬到别的位置

`MediaCacheMover` 把不在目标位置上的缓存一份一份搬过去（电视设置「存储 → 移动已有缓存」，控制台「缓存位置」卡片的第二个按钮）。
一份（`MediaCacheMoveUnit`）是 BT 的一个种子目录（合集的各集、别的数据源缓存的同一种子一起），或在线源的一个任务的文件。
各引擎的搬法实现 `MediaCacheEngineMover`（`TorrentCacheMover`、`HttpCacheMover`），只管文件与记录：

1. 在 `MediaCacheLocations.moving` 登记，存储重新恢复：引擎认出正在搬，给「正在移动」占位（`UnavailableMediaCache.moving`），
   下载会话 / 任务随之停下；
2. 复制到目标处的临时名字（`.izuko-moving`），边写边落盘，逐个核对大小，改成正式名字，再改记录（记录在搬的期间被删了就放弃）；
3. 取消登记，存储再恢复一遍，按新位置接着用；最后删原处。

出错或取消时这一份留在原处照常用，目标处复制了的清掉；留下的临时名字由启动清扫收掉。在播的番（同一部的任何一集）跳过，
目标盘剩余空间不够时整批停下。没下完的 BT 种子不带续传数据过去，到新位置后按哈希重新校验，复制时 BT 服务还在落的数据不会因此出错。

[//]: # (TODO： 缓存)
