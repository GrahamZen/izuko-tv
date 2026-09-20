/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepository
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.domain.media.cache.engine.TorrentEngineAccess
import me.him188.ani.app.domain.media.cache.engine.TorrentMediaCacheEngine
import me.him188.ani.app.domain.media.cache.storage.MediaSaveDirProvider
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.tools.getOrZero
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.isSingleEpisode
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台的「缓存」标签: 电视上的全部缓存按番分组列出 (同电视缓存管理页), 每集的状态、大小、速度, 可暂停 / 继续 / 删除;
 * 顶上是电视剩余空间与没下完的还差多少. 不用进播放器就能看缓存到哪了; 要给某部番再缓存几集, 网页上那部番底部的按钮打开
 * 缓存面板 (见 [RemoteCache]). 点一集 = 电视上播这一集, 点番名 = 电视打开详情页.
 *
 * 每次请求现读各条缓存的状态流 (`first()`, 并发、各自限时), 不常驻收集: 网页只在停在这个标签时每 2 秒拉一次, 不看就没有开销.
 * BT 缓存的流挂在建缓存时就定下的句柄上 (`flowOf(句柄)`), Android 上文件进度经 torrent 服务回调, 服务端从 StateFlow 收,
 * 订阅即回当前值 —— 读一次就是一次跨进程注册 + 注销, 只有没下完的 BT 缓存走这条 (下完的恢复成本地文件, 值是常量).
 * 速度按两次请求之间已下载字节数的差算: 电视缓存管理页同样按文件进度算, 不用 sessionStats (合集的那个是整个种子的).
 * 暂停 / 继续 / 删除同缓存管理页: 按 cacheId 找缓存; 删除 (单集, 或番名那一行的整部删除) 走 [MediaDownloadManager.deleteDownload]
 * (顺带清掉用不上的弹幕缓存).
 */
internal object RemoteCacheList {
    private val logger = logger<RemoteCacheList>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("RemoteCacheList"))

    private val cacheManager: MediaDownloadManager get() = KoinPlatform.getKoin().get()
    private val sourceManager: MediaSourceManager get() = KoinPlatform.getKoin().get()
    private val playHistory: EpisodePlayHistoryRepository get() = KoinPlatform.getKoin().get()
    private val saveDirProvider: MediaSaveDirProvider get() = KoinPlatform.getKoin().get()
    private val subjectRepo: SubjectCollectionRepository get() = KoinPlatform.getKoin().get()
    private val engineAccess: TorrentEngineAccess get() = KoinPlatform.getKoin().get()

    /**
     * 一条缓存上次被读到时的已下载字节数、时刻, 与那时算出的速度.
     * [zeroStreak] = 连着几次采样一个字节都没进 (判「暂无来源」用).
     */
    private class Sample(val bytes: Long, val nanos: Long, val speed: Long?, val zeroStreak: Int = 0)

    /** cacheId → 上一次的样本, 算下载速度用; 已经不在列表里的每次请求时清掉. */
    private val samples = ConcurrentHashMap<String, Sample>()

    /** 处理 `api/caches` 下的请求; 路径或方法不认识返回 null. [uiScope] 用来在主线程上跳页面 (点一集播放 / 点番名开详情). */
    fun handle(request: LanHttpRequest, navigator: AniNavigator?, uiScope: CoroutineScope): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return runCatching {
            when {
                request.path == "api/caches" && get -> list()
                !post -> null
                request.path == "api/caches/play" -> play(request, navigator, uiScope)
                request.path == "api/caches/open" -> open(request, navigator, uiScope)
                request.path == "api/caches/pause" ->
                    if (request.formFields().containsKey("ids")) setPausedMany(request, paused = true)
                    else setPaused(request, paused = true)

                request.path == "api/caches/resume" ->
                    if (request.formFields().containsKey("ids")) setPausedMany(request, paused = false)
                    else setPaused(request, paused = false)
                request.path == "api/caches/delete" -> if (request.formFields().containsKey("ids")) deleteMany(request) else delete(request)
                request.path == "api/caches/delete-subject" -> deleteSubject(request)
                else -> null
            }
        }.getOrElse {
            logger.warn(it) { "Remote cache list request failed: ${request.method} ${request.path}" }
            result(false, tr("操作失败：{0}", it.message ?: it::class.simpleName))
        }
    }

    // ============================ 列表 ============================

    private class Row(
        val cache: MediaCache,
        /** `null` = 状态流在限时内没出值 (比如 BT 服务正在冷启动). */
        val state: MediaCacheState?,
        val stats: MediaCache.FileStats,
        val merging: Boolean,
        /** 非 null = 这条的状态压根没去读, 网页上显示这句 (原因见 [torrentSkipReason]). */
        val offlineText: String? = null,
    ) {
        val metadata: MediaCacheMetadata get() = cache.metadata
        val subjectId = metadata.subjectId.toIntOrNull() ?: 0
        val episodeId = metadata.episodeId.toIntOrNull() ?: 0
        val totalBytes: Long? = stats.totalSize.takeUnless { it.isUnspecified }?.inBytes
        val downloadedBytes: Long? = stats.downloadedBytes.takeUnless { it.isUnspecified }?.inBytes

        /** 一个资源包含多集 (常见于 BT 的季度全集); 各集的缓存共用同一个 origin.mediaId. */
        val isPack = cache.origin.episodeRange?.isSingleEpisode() == false
        val percent get() = (stats.downloadProgress.getOrZero() * 100).toInt().coerceIn(0, 100)

        /**
         * 进度文字: 不足 1% 时给一位小数. 取整的话冷种子下了半天还是「0%」, 看着跟卡死一样
         * (2026-09-15 用户报: 实测在以 12 KB/s 爬, 显示一直是 0%).
         */
        val percentText: String
            get() {
                val p = stats.downloadProgress.getOrZero() * 100
                return when {
                    p <= 0f -> "0"
                    p < 1f -> "0." + (p * 10).toInt().coerceAtLeast(1)
                    else -> p.toInt().coerceAtMost(100).toString()
                }
            }
    }

    private class Snapshot(
        val rows: List<Row>,
        /** mediaSourceId → 数据源名. */
        val sourceNames: Map<String, String>,
        /** episodeId → 看到了百分之几 (有播放记录的集). */
        val watched: Map<Int, Int>,
    )

    private suspend fun readSnapshot(): Snapshot = coroutineScope {
        val caches = cacheManager.downloads.first()
            .map { it.cache }
            .filter { !it.isDeleted.value }
            .distinctBy { it.cacheId }
        // 各条并发读、各自限时: 某一条的流迟迟不出值, 不拖住整张表
        val skipReason = torrentSkipReason()
        val torrentTimedOut = AtomicBoolean(false)
        val rows = caches.map { cache ->
            async {
                if (cache.needsTorrentService && skipReason != null) {
                    return@async Row(cache, null, MediaCache.FileStats.Unspecified, false, offlineText = skipReason)
                }
                val state = async { withTimeoutOrNull(FLOW_TIMEOUT) { cache.state.first() } }
                val stats = async { withTimeoutOrNull(FLOW_TIMEOUT) { cache.fileStats.first() } }
                val merging = async { withTimeoutOrNull(FLOW_TIMEOUT) { cache.isMerging.first() } }
                val stateValue = state.await()
                val statsValue = stats.await()
                if (cache.needsTorrentService && (stateValue == null || statsValue == null)) torrentTimedOut.set(true)
                Row(cache, stateValue, statsValue ?: MediaCache.FileStats.Unspecified, merging.await() ?: false)
            }
        }.awaitAll()
        if (skipReason == null) noteTorrentRead(torrentTimedOut.get())
        val sourceNames = rows.map { it.cache.origin.mediaSourceId }.distinct().map { id ->
            async { id to withTimeoutOrNull(FLOW_TIMEOUT) { sourceManager.infoFlowByMediaSourceId(id).first()?.displayName } }
        }.awaitAll().mapNotNull { (id, name) -> name?.let { id to it } }.toMap()
        val watched = withTimeoutOrNull(FLOW_TIMEOUT) { playHistory.flow.first() }.orEmpty().mapNotNull { h ->
            val duration = h.durationMillis?.takeIf { it > 0 }
            if (h.isDeleted || h.positionMillis <= 0 || duration == null) null
            else h.episodeId to (h.positionMillis * 100 / duration).toInt().coerceIn(0, 100)
        }.toMap()
        Snapshot(rows, sourceNames, watched)
    }

    /**
     * 这条缓存的状态要问 torrent 服务才知道. 下完的 BT 缓存在恢复时已经变成本地文件缓存 (`LocalFileMediaCache`),
     * 读到的是常量, 不受服务影响, 所以只有还没下完的算.
     */
    private val MediaCache.needsTorrentService: Boolean get() = this is TorrentMediaCacheEngine.TorrentMediaCache

    /** 熔断到什么时候 (见 [noteTorrentRead]); 连续读超时的次数. */
    private val torrentStuckUntil = AtomicLong(0)
    private val torrentTimeoutStrikes = AtomicInteger(0)

    /**
     * 现在不该读 BT 缓存状态流的话, 返回一句给网页显示的原因; 可以读就返回 null.
     *
     * 服务没连上时读这些流会**永久**占住一个线程: 流的上游 (`RemoteTorrentFileEntry` 等) 在等服务的通信对象,
     * 等待是阻塞式的 (`RetryRemoteObject` 里的 runBlocking), [withTimeoutOrNull] 取消不掉已经阻塞的线程。
     * 而网页停在缓存标签时每 2 秒拉一次, 每次每条都堵一个, 很快把 `Dispatchers.Default` 占满 ——
     * 之后连跟 BT 无关的请求 (读条目信息也在这个池子上) 都跑不动, 整个控制台卡死。
     */
    private fun torrentSkipReason(): String? = when {
        // BT 服务只有 Ani 在电视前台时才会起 (上游的省电策略): 在前台就是正在冷启动 (十几秒), 不在前台则是
        // 根本没开始 —— 这两种说法不能混, 后者说"正在启动"是骗人的 (2026-09-15 用户报"后台点下载没反应")
        !engineAccess.isServiceConnected.value ->
            if (TvRemoteControl.isTvForeground()) tr("正在启动 BT 服务…") else tr("电视上没打开 Ani，暂时不会下载")
        System.nanoTime() < torrentStuckUntil.get() -> tr("读取超时，正在重试")
        else -> null
    }

    private fun torrentReadable(): Boolean = torrentSkipReason() == null

    /** BT 服务还没连上 (多半正在冷启动, 十几秒): 缓存面板与缓存列表顶上会说一句, 见 [RemoteCache]. */
    internal fun torrentStarting(): Boolean = !engineAccess.isServiceConnected.value

    /**
     * 还有没下完的 BT 缓存. 网页顶上「电视没显示 Ani」那条据此加一句 —— BT 服务只在 Ani 前台时才起, 所以这时候
     * 缓存也是停着的, 不说的话用户只当是"电视没显示"而已 (见 TvRemoteControl.noticeState).
     * 只看列表的当前值, 不读各条的状态流 (那些要跨进程问服务), 所以每 2 秒问一次也不贵.
     */
    internal fun hasPendingTorrentCache(): Boolean = runBlocking {
        withTimeoutOrNull(FLOW_TIMEOUT) {
            cacheManager.downloads.first()
                .map { it.cache }
                .any { !it.isDeleted.value && it.needsTorrentService }
        }
    } ?: false

    /**
     * 记一次 BT 状态读取的结果: 连着 [STUCK_STRIKES] 次都有读不出来的才歇 [TORRENT_STUCK_BACKOFF] (服务在线却卡住的兜底)。
     * 电视忙的时候偶尔一次超时很正常, 一次就歇的话进度会莫名空掉一片 (2026-09-15 真机实测误触发过)。
     */
    private fun noteTorrentRead(timedOut: Boolean) {
        if (!timedOut) {
            torrentTimeoutStrikes.set(0)
            return
        }
        if (torrentTimeoutStrikes.incrementAndGet() < STUCK_STRIKES) return
        torrentTimeoutStrikes.set(0)
        torrentStuckUntil.set(System.nanoTime() + TORRENT_STUCK_BACKOFF.inWholeNanoseconds)
        logger.warn { "Reading torrent cache state timed out $STUCK_STRIKES times in a row, skipping torrent caches for $TORRENT_STUCK_BACKOFF." }
    }

    /**
     * subjectId → 番名, 同电视缓存管理页 (条目信息的 displayName, **离线**读, 不走网络). 缓存记录里的名字是建缓存时
     * 查询请求的主搜索名 —— 用户给这部番改过数据源搜索名的话就是改过的那个, 不是番名 (2026-09-12 用户报「缓存里的番名
     * 跟我改过的搜索名一样」). 读不到 (本地没有条目信息) 才退回记录里的. 番名不会变, 读到一次就记住.
     */
    private val displayNames = ConcurrentHashMap<Int, String>()
    private val NAME_TIMEOUT = 1.seconds

    private fun displayNamesOf(subjectIds: Collection<Int>): Map<Int, String> {
        val missing = subjectIds.filter { it > 0 && !displayNames.containsKey(it) }.distinct()
        if (missing.isNotEmpty()) runBlocking {
            withTimeoutOrNull(NAME_TIMEOUT) {
                missing.map { id ->
                    async { id to runCatching { subjectRepo.getSubjectDisplayInfoOffline(id).first()?.displayName }.getOrNull() }
                }.awaitAll().forEach { (id, name) -> if (!name.isNullOrBlank()) displayNames[id] = name }
            }
        }
        return displayNames
    }

    private fun list(): JsonObject {
        val snapshot = runBlocking { withTimeoutOrNull(LIST_TIMEOUT) { readSnapshot() } }
            ?: return result(false, tr("读取缓存失败，请重试"))
        val rows = snapshot.rows
        val now = System.nanoTime()
        samples.keys.retainAll(rows.mapTo(HashSet()) { it.cache.cacheId })
        val speeds = HashMap<String, Long>()
        for (r in rows) {
            val bytes = r.downloadedBytes ?: continue
            if (r.state != MediaCacheState.IN_PROGRESS || r.merging) continue
            speedOf(r.cache.cacheId, bytes, now)?.let { speeds[r.cache.cacheId] = it }
        }
        // 合集: 同一个种子眼下还有几集在缓存里 —— 删掉其中一集不腾空间, 要等它们都删了才整个回收 (见 TorrentMediaCacheEngine)
        val packCounts = rows.filter { it.isPack }.groupingBy { it.cache.origin.mediaId }.eachCount()
        val playing = TvRemoteControl.playingCacheProvider?.invoke()
        val space = freeSpace()
        val used = rows.sumOf { it.totalBytes ?: 0L }
        // 没下完的还差多少: 在下载的与暂停的都算 (继续下就要占这么多)
        val pending = rows.filter { it.state == MediaCacheState.IN_PROGRESS || it.state == MediaCacheState.PAUSED }
            .sumOf { r ->
                val total = r.totalBytes
                val done = r.downloadedBytes
                if (total != null && done != null) maxOf(total - done, 0L) else 0L
            }
        val downloading = rows.count { it.state == MediaCacheState.IN_PROGRESS && !it.merging }
        val speedSum = speeds.values.sum()
        // 同电视缓存管理页: 有没下完的番排前面, 其余按最近一次缓存的时间
        val groups = rows.groupBy { it.metadata.subjectId }.values.sortedWith(
            compareByDescending<List<Row>> { g -> g.any { it.state != MediaCacheState.COMPLETED } }
                .thenByDescending { g -> g.maxOf { it.metadata.creationTime ?: 0L } },
        )
        val names = displayNamesOf(groups.map { it.first().subjectId })
        return buildJsonObject {
            put("ok", true)
            put("count", rows.size)
            // 顶上说清楚现在是哪种情况: 服务正在冷启动 (十几秒), 还是电视上压根没打开 Ani (那就不会开始下)
            if (torrentStarting() && rows.any { it.cache.needsTorrentService }) {
                if (TvRemoteControl.isTvForeground()) put("btStarting", true) else put("tvBackground", true)
            }
            if (space != null) {
                put("free", space.first.bytes.toString())
                put("total", space.second.bytes.toString())
                if (pending > space.first) put("lowSpace", true)
            }
            put("used", used.bytes.toString())
            if (pending > 0) put("pending", pending.bytes.toString())
            if (downloading > 0) put("downloading", downloading)
            if (speedSum > 0) put("speed", "${speedSum.bytes}/s")
            putJsonArray("groups") {
                for (g in groups) addJsonObject {
                    val m = g.first().metadata
                    put("id", g.first().subjectId)
                    // 番名那块的竖版封面底图, 候选同搜索结果 / 播放记录 (见 remoteCoverCandidates)
                    if (g.first().subjectId > 0) {
                        putJsonArray("cover") { remoteCoverCandidates(g.first().subjectId, null).forEach { add(it) } }
                    }
                    put("title", names[g.first().subjectId] ?: subjectTitle(m))
                    val size = g.sumOf { it.totalBytes ?: 0L }
                    val done = g.count { it.state == MediaCacheState.COMPLETED }
                    put("meta", tr("已完成 {0} / {1} 集", done, g.size) + if (size > 0) " · ${size.bytes}" else "")
                    putJsonArray("items") {
                        for (r in g.sortedBy { it.metadata.episodeSort }) addJsonObject {
                            val (st, text) = statusOf(r)
                            put("cid", r.cache.cacheId)
                            put("label", episodeLabel(r.metadata))
                            put("st", st)
                            put("text", text)
                            if (st == "run" || st == "paused") put("progress", r.percent)
                            sizeText(r)?.let { put("size", it) }
                            speeds[r.cache.cacheId]?.let { put("speed", "${it.bytes}/s") }
                            snapshot.sourceNames[r.cache.origin.mediaSourceId]?.let { put("source", it) }
                            snapshot.watched[r.episodeId]?.let { put("watched", tr("已看 {0}%", it)) }
                            if (r.isPack) {
                                put("pack", true)
                                val others = (packCounts[r.cache.origin.mediaId] ?: 1) - 1
                                if (others > 0) put("packShare", others)
                            }
                            if (playing?.matches(r.subjectId, r.episodeId, r.cache.origin.mediaId) == true) put("playing", true)
                        }
                    }
                }
            }
        }
    }

    /** 网页上的状态: 类别 (决定颜色与按钮) 与文案, 文案同电视缓存页. */
    private fun statusOf(r: Row): Pair<String, String> = if (r.offlineText != null) {
        "loading" to r.offlineText
    } else when (r.state) {
        null -> "loading" to tr("读取中")
        MediaCacheState.COMPLETED -> "done" to tr("已完成")
        MediaCacheState.FAILED -> "failed" to tr("下载失败")
        MediaCacheState.PAUSED -> "paused" to tr("已暂停 {0}%", r.percentText)
        MediaCacheState.IN_PROGRESS -> when {
            r.merging -> "merging" to tr("合并中")
            // 连着几次一个字节都没进: 多半是种子没人做种, 说一句, 免得以为是卡住了
            (samples[r.cache.cacheId]?.zeroStreak ?: 0) >= NO_SOURCE_STRIKES ->
                "run" to tr("下载中 {0}%·暂无来源", r.percentText)

            else -> "run" to tr("下载中 {0}%", r.percentText)
        }
    }

    /** 下完了: 文件大小; 没下完: 已下 / 总共 (同电视缓存页). */
    private fun sizeText(r: Row): String? {
        val total = r.totalBytes ?: return null
        val done = r.downloadedBytes
        return if (r.state == MediaCacheState.COMPLETED || done == null) total.bytes.toString()
        else "${done.bytes} / ${total.bytes}"
    }

    private fun episodeLabel(m: MediaCacheMetadata): String = buildString {
        append(m.episodeSort.toString())
        if (m.episodeName.isNotBlank()) append("  ").append(m.episodeName)
    }

    /** 按上次读到的样本算这条缓存的下载速度 (字节/秒); 头一回读到或隔得太久返回 null, 等下一次. */
    private fun speedOf(cacheId: String, bytes: Long, now: Long): Long? {
        val prev = samples[cacheId]
        if (prev != null) {
            val gap = now - prev.nanos
            // 两台手机同时开着这个标签: 间隔太短算不准, 沿用上一次的
            if (gap < MIN_SAMPLE_GAP_NANOS) return prev.speed
            if (gap <= MAX_SAMPLE_GAP_NANOS) {
                val speed = (maxOf(bytes - prev.bytes, 0L).toDouble() * 1_000_000_000L / gap).toLong()
                // 连着几次一个字节都没进: 多半是没有可用来源 (种子冷), 不是卡住 —— 列表上说一句
                val zeroStreak = if (speed <= 0) prev.zeroStreak + 1 else 0
                samples[cacheId] = Sample(bytes, now, speed, zeroStreak)
                return speed
            }
        }
        samples[cacheId] = Sample(bytes, now, null)
        return null
    }

    /**
     * 缓存目录所在分区的 (剩余, 总) 字节数; 取不到为 null. 还没缓存过时目录可能还不存在, 而 `usableSpace` 对不存在的路径
     * 返回 0 —— 往上找第一个存在的目录.
     */
    internal fun freeSpace(): Pair<Long, Long>? = runCatching {
        val dir = generateSequence(File(saveDirProvider.saveDir)) { it.parentFile }.firstOrNull { it.exists() }
        dir?.takeIf { it.totalSpace > 0 }?.let { it.usableSpace to it.totalSpace }
    }.getOrNull()

    // ============================ 操作 ============================

    /**
     * 点一集 = 电视上播这一集: 进这一集的播放页, 选源时缓存优先 ([MediaSelector.trySelectCached][me.him188.ani.app.domain.media.selector.MediaSelector.trySelectCached]).
     * 没下完的缓存不会被自动选中 (选了也播不了), 播放器照常找在线源 —— 提示里说一句.
     */
    private fun play(request: LanHttpRequest, navigator: AniNavigator?, uiScope: CoroutineScope): JsonObject {
        val cache = findCache(request) ?: return result(false, tr("这条缓存已经不在了，请刷新"))
        val nav = navigator ?: return result(false, tr("电视还没准备好"))
        val m = cache.metadata
        val subjectId = m.subjectId.toIntOrNull() ?: return result(false, tr("这条缓存没有记录是哪部番，播不了"))
        val episodeId = m.episodeId.toIntOrNull() ?: return result(false, tr("这条缓存没有记录是哪一集，播不了"))
        // 读不到就都当未知 (BT 服务没连上时不读, 会永久占住线程, 见 torrentSkipReason), 未知就不多说那一句
        val readable = !cache.needsTorrentService || torrentReadable()
        val state = if (!readable) null else runBlocking { withTimeoutOrNull(FLOW_TIMEOUT) { cache.state.first() } }
        // 能不能直接拿这条缓存播, 判据同选源器 (MediaCache.canPlay): BT 缓存没下完也能边下边播, 只有 web (m3u8)
        // 缓存下载中不能播 —— 那种会被选源器排除掉 (CacheNotReady), 于是改从别的数据源找
        val canPlay = if (!readable) null else runBlocking { withTimeoutOrNull(FLOW_TIMEOUT) { cache.canPlay.first() } }
        logger.info {
            "Remote cache play: subject=$subjectId ep=$episodeId cacheId=${cache.cacheId} state=$state canPlay=$canPlay"
        }
        // 电视正在播这部番时就地换集, 不再叠一个新的播放页 (见 TvRemoteControl.playEpisode)
        val how = TvRemoteControl.playEpisode(nav, uiScope, subjectId, episodeId) {
            logger.warn(it) { "Failed to start playback from remote cache list" }
        }
        val what = "「${displayNamesOf(listOf(subjectId))[subjectId] ?: subjectTitle(m)}」${m.episodeSort}"
        // 没缓存完时电视会从别的数据源里挑一个来播 (没下完的缓存不许选), 挑到的可能是 BT 也可能是在线源, 不说死是哪种
        val msg = when (how) {
            TvRemoteControl.RemotePlayResult.AlreadyPlaying -> tr("电视正在播{0}", what)
            TvRemoteControl.RemotePlayResult.Switched -> tr("已在电视上换到{0}", what)
            TvRemoteControl.RemotePlayResult.Opened -> tr("已在电视上播放{0}", what)
        } + when {
            how == TvRemoteControl.RemotePlayResult.AlreadyPlaying -> ""
            // 这一条播不了 (下载中的 web 缓存), 电视会去别的源找
            canPlay == false -> tr("：这一集的缓存还不能播，先从其他数据源播")
            // 能播但没下完: 用的还是这条缓存, 边下边看
            state != null && state != MediaCacheState.COMPLETED -> tr("：这一集还没下完，边下边看")
            else -> ""
        }
        return result(true, msg, player = true)
    }

    /** 点番名 = 电视打开这部番的详情页. */
    private fun open(request: LanHttpRequest, navigator: AniNavigator?, uiScope: CoroutineScope): JsonObject {
        val subjectId = request.formFields()["subject"]?.toIntOrNull() ?: return result(false, tr("无效的条目"))
        val nav = navigator ?: return result(false, tr("电视还没准备好"))
        TvRemoteControl.notifyRemoteNavigation()
        uiScope.launch(Dispatchers.Main) {
            runCatching { nav.navigateSubjectDetails(subjectId, placeholder = null) }
                .onFailure { logger.warn(it) { "Failed to open subject details from remote cache list" } }
        }
        return result(true, tr("已在电视上打开详情页"))
    }

    private fun subjectTitle(m: MediaCacheMetadata): String =
        m.subjectNameCN?.takeIf { it.isNotBlank() } ?: m.subjectNames.firstOrNull() ?: tr("未知条目")

    /** 暂停 / 继续 (同缓存管理页). BT 的要等句柄, 放后台做; 状态随网页下一次刷新变过来. */
    private fun setPaused(request: LanHttpRequest, paused: Boolean): JsonObject {
        val cache = findCache(request) ?: return result(false, tr("这条缓存已经不在了，请刷新"))
        // 服务没连上时这个调用会一直阻塞 (见 torrentReadable), 直接告诉用户, 别又堵一个线程
        if (cache.needsTorrentService && !torrentReadable()) return result(false, tr("BT 服务未连接，请稍后再试"))
        scope.launch {
            try {
                if (paused) cache.pause() else cache.resume()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Remote ${if (paused) "pause" else "resume"} failed for cache ${cache.cacheId}" }
            }
        }
        return result(true, if (paused) tr("已暂停") else tr("继续下载"))
    }

    /**
     * 长按多选后一次暂停 / 继续 (`ids` = 逗号分隔的 cacheId, 可以跨番; 同多选删除). 逐条做, 一条失败不耽误后面的;
     * 都放后台, 状态随网页下一次刷新变过来 (同单条 [setPaused]).
     */
    private fun setPausedMany(request: LanHttpRequest, paused: Boolean): JsonObject {
        val ids = request.formFields()["ids"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (ids.isEmpty()) return result(false, tr("请先选择要操作的剧集。"))
        val caches = runBlocking {
            withTimeoutOrNull(OP_TIMEOUT) {
                cacheManager.downloads.first()
                    .map { it.cache }
                    .filter { !it.isDeleted.value && it.cacheId in ids }
                    .distinctBy { it.cacheId }
            }
        } ?: return result(false, tr("缓存读取失败，请重试。"))
        if (caches.isEmpty()) return result(false, tr("找不到所选缓存，请刷新列表。"))
        // 服务没连上时这些调用会一直阻塞 (见 torrentReadable), 同单条
        if (caches.any { it.needsTorrentService } && !torrentReadable()) {
            return result(false, tr("BT 服务未连接，请稍后再试"))
        }
        scope.launch {
            for (cache in caches) {
                try {
                    if (paused) cache.pause() else cache.resume()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Remote multi ${if (paused) "pause" else "resume"} failed for cache ${cache.cacheId}" }
                }
            }
        }
        return result(true, if (paused) tr("已暂停 {0} 集", caches.size) else tr("继续下载 {0} 集", caches.size))
    }

    /**
     * 删除 (同缓存管理页; 不可逆, 网页上先确认). 等逻辑删除落地再回话, 刷新列表时它就不在了; 物理清理在存储自己的作用域里接着跑.
     * 删除本身在本对象的作用域里跑, 限时等不到也不会被取消.
     */
    private fun delete(request: LanHttpRequest): JsonObject {
        val cache = findCache(request) ?: return result(false, tr("这条缓存已经不在了，请刷新"))
        val m = cache.metadata
        val subjectId = m.subjectId.toIntOrNull() ?: 0
        val episodeId = m.episodeId.toIntOrNull() ?: 0
        // 同缓存管理页留一行能还原「删了哪条」的日志, 取证词相同 (Delete cache requested)
        logger.info {
            "Delete cache requested from remote control: subject=$subjectId ep=$episodeId sort=${m.episodeSort} cacheId=${cache.cacheId}"
        }
        // 删完马上重下时自动批量要沿用它原来的源 (见 RemoteCache.rememberDeleted)
        RemoteCache.rememberDeleted(subjectId, listOf(cache))
        val deletion = scope.async { logDeleteFailure(cache.cacheId) { cacheManager.deleteDownload(cache) } }
        val finished = runBlocking { withTimeoutOrNull(DELETE_TIMEOUT) { deletion.await(); true } } ?: false
        return result(true, if (finished) tr("已删除「{0}」", episodeLabel(m)) else tr("正在删除，稍后刷新看看"))
    }

    /**
     * 长按多选删除 (`ids` = 逗号分隔的 cacheId, 可以跨番): 逐条删, 取证日志同单集删除; 一条失败不耽误后面的.
     * 限时等不到也不会被取消 (同 [delete]).
     */
    private fun deleteMany(request: LanHttpRequest): JsonObject {
        val ids = request.formFields()["ids"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (ids.isEmpty()) return result(false, tr("请先选择要删除的剧集。"))
        val caches = findCaches(ids) ?: return result(false, tr("缓存读取失败，请重试。"))
        if (caches.isEmpty()) return result(false, tr("找不到所选缓存，请刷新列表。"))
        val finished = deleteAll(caches, "multi-select")
        return result(true, if (finished) tr("已删除 {0} 集缓存", caches.size) else tr("正在删除 {0} 集，请稍后刷新。", caches.size))
    }

    /** 按 cacheId 找还在的缓存; 读不出来返回 null (同多选删除的超时口径). */
    private fun findCaches(ids: Set<String>): List<MediaCache>? = runBlocking {
        withTimeoutOrNull(OP_TIMEOUT) {
            cacheManager.downloads.first()
                .map { it.cache }
                .filter { !it.isDeleted.value && it.cacheId in ids }
                .distinctBy { it.cacheId }
        }
    }

    /**
     * 逐条删除, 取证日志同单集删除; 一条失败不耽误后面的. 限时等不到也不会被取消 (同 [delete]).
     * @return 是否在限时内删完 (没删完也在后台继续)
     */
    private fun deleteAll(caches: List<MediaCache>, why: String): Boolean {
        // 删完马上重下时自动批量要沿用它们原来的源 (见 RemoteCache.rememberDeleted)
        caches.groupBy { it.metadata.subjectId.toIntOrNull() ?: 0 }.forEach { (subjectId, list) -> RemoteCache.rememberDeleted(subjectId, list) }
        val deletion = scope.async {
            for (cache in caches) {
                val m = cache.metadata
                val subjectId = m.subjectId.toIntOrNull() ?: 0
                val episodeId = m.episodeId.toIntOrNull() ?: 0
                logger.info {
                    "Delete cache requested from remote control ($why): subject=$subjectId ep=$episodeId " +
                            "sort=${m.episodeSort} cacheId=${cache.cacheId}"
                }
                logDeleteFailure(cache.cacheId) { cacheManager.deleteDownload(cache) }
            }
        }
        return runBlocking { withTimeoutOrNull(DELETE_ALL_TIMEOUT) { deletion.await(); true } } ?: false
    }

    /** 取消自动批量时撤掉这一批已经建起来的缓存 (见 [RemoteCache]); 返回真正删掉了几条. */
    internal fun deleteByCacheIds(ids: Collection<String>): Int {
        val caches = findCaches(ids.toSet()).orEmpty()
        if (caches.isEmpty()) return 0
        deleteAll(caches, "auto batch cancelled")
        return caches.size
    }

    /**
     * 整部删除 (网页上番名那一行的「删除全部」, 先确认): 这部番在本机的全部缓存逐条删, 同缓存管理页的多选删除.
     * 合集的文件要等同一个种子的集都删了才整个回收, 整部删正好一起删掉.
     */
    private fun deleteSubject(request: LanHttpRequest): JsonObject {
        val subjectId = request.formFields()["subject"]?.toIntOrNull() ?: return result(false, tr("无效的条目"))
        val caches = runBlocking {
            withTimeoutOrNull(OP_TIMEOUT) {
                cacheManager.downloads.first()
                    .map { it.cache }
                    .filter { !it.isDeleted.value && it.metadata.subjectId.toIntOrNull() == subjectId }
                    .distinctBy { it.cacheId }
            }
        } ?: return result(false, tr("读取缓存失败，请重试"))
        if (caches.isEmpty()) return result(false, tr("这部番已经没有缓存了，请刷新"))
        RemoteCache.rememberDeleted(subjectId, caches)
        val deletion = scope.async {
            for (cache in caches) {
                val m = cache.metadata
                val episodeId = m.episodeId.toIntOrNull() ?: 0
                // 每条都留一行, 取证词同单集删除 (Delete cache requested)
                logger.info {
                    "Delete cache requested from remote control (whole subject): subject=$subjectId ep=$episodeId " +
                            "sort=${m.episodeSort} cacheId=${cache.cacheId}"
                }
                // 一条失败不耽误后面的
                logDeleteFailure(cache.cacheId) { cacheManager.deleteDownload(cache) }
            }
        }
        val finished = runBlocking { withTimeoutOrNull(DELETE_ALL_TIMEOUT) { deletion.await(); true } } ?: false
        return result(true, if (finished) tr("已删除 {0} 集缓存", caches.size) else tr("正在删除 {0} 集，请稍后刷新。", caches.size))
    }

    /**
     * 删除失败留日志: 限时内失败的会从 await 抛到 [handle] 记下, 但限时之后才失败的异常只存在没人再 await 的 Deferred 里,
     * 一行日志都没有 —— 误删 / 删不掉的取证全靠这些日志 (见 Delete cache requested).
     */
    private suspend fun logDeleteFailure(cacheId: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Remote delete failed for cache $cacheId" }
        }
    }

    private fun findCache(request: LanHttpRequest): MediaCache? {
        val id = request.formFields()["id"].orEmpty()
        if (id.isEmpty()) return null
        // 挂起着读而不用 `findDownload` (它同步读 `downloads.value`): `downloads` 是
        // stateIn(Eagerly, emptyList()) 的, app 刚起来、存储还没给出首个列表时同步读会拿到那个空初值,
        // 表现为列表里明明有、点播放却说「这条缓存已经不在了」。原来的实现也是挂起等的。
        return runBlocking {
            withTimeoutOrNull(OP_TIMEOUT) { cacheManager.downloads.first().firstOrNull { it.id == id }?.cache }
        }
    }

    /** @param player 电视进了播放页: 网页随后切到「播放器」标签 */
    private fun result(ok: Boolean, message: String, player: Boolean = false): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
        if (player) put("player", true)
    }

    private val LIST_TIMEOUT = 8.seconds
    private val FLOW_TIMEOUT = 2.seconds

    /** 连着这么多次快照都有读不出来的, 才歇 [TORRENT_STUCK_BACKOFF] 不读 (见 [noteTorrentRead]). */
    private const val STUCK_STRIKES = 3
    private val TORRENT_STUCK_BACKOFF = 30.seconds
    private val OP_TIMEOUT = 5.seconds
    private val DELETE_TIMEOUT = 10.seconds
    private val DELETE_ALL_TIMEOUT = 30.seconds
    /**
     * 连着这么多次采样 (网页 2 秒一次) 一个字节都没进, 才说「暂无来源」. 给够时间: 刚建的缓存要先连 tracker / DHT,
     * 头十几秒没速度很正常, 太早说会吓人.
     */
    private const val NO_SOURCE_STRIKES = 6

    private const val MIN_SAMPLE_GAP_NANOS = 500_000_000L
    private const val MAX_SAMPLE_GAP_NANOS = 30_000_000_000L
}
