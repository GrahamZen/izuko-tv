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
import me.him188.ani.app.domain.media.cache.DeleteCacheByCacheIdUseCase
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheManager
import me.him188.ani.app.domain.media.cache.MediaCacheState
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
 * 暂停 / 继续 / 删除同缓存管理页: 按 cacheId 找缓存; 删除 (单集, 或番名那一行的整部删除) 走 [DeleteCacheByCacheIdUseCase]
 * (顺带清掉用不上的弹幕缓存).
 */
internal object RemoteCacheList {
    private val logger = logger<RemoteCacheList>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("RemoteCacheList"))

    private val cacheManager: MediaCacheManager get() = KoinPlatform.getKoin().get()
    private val sourceManager: MediaSourceManager get() = KoinPlatform.getKoin().get()
    private val playHistory: EpisodePlayHistoryRepository get() = KoinPlatform.getKoin().get()
    private val saveDirProvider: MediaSaveDirProvider get() = KoinPlatform.getKoin().get()
    private val deleteCacheById: DeleteCacheByCacheIdUseCase get() = KoinPlatform.getKoin().get()
    private val subjectRepo: SubjectCollectionRepository get() = KoinPlatform.getKoin().get()

    /** 一条缓存上次被读到时的已下载字节数、时刻, 与那时算出的速度. */
    private class Sample(val bytes: Long, val nanos: Long, val speed: Long?)

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
                request.path == "api/caches/pause" -> setPaused(request, paused = true)
                request.path == "api/caches/resume" -> setPaused(request, paused = false)
                request.path == "api/caches/delete" -> delete(request)
                request.path == "api/caches/delete-subject" -> deleteSubject(request)
                else -> null
            }
        }.getOrElse {
            logger.warn(it) { "Remote cache list request failed: ${request.method} ${request.path}" }
            result(false, "操作失败：${it.message ?: it::class.simpleName}")
        }
    }

    // ============================ 列表 ============================

    private class Row(
        val cache: MediaCache,
        /** `null` = 状态流在限时内没出值 (比如 BT 服务正在冷启动). */
        val state: MediaCacheState?,
        val stats: MediaCache.FileStats,
        val merging: Boolean,
    ) {
        val metadata: MediaCacheMetadata get() = cache.metadata
        val subjectId = metadata.subjectId.toIntOrNull() ?: 0
        val episodeId = metadata.episodeId.toIntOrNull() ?: 0
        val totalBytes: Long? = stats.totalSize.takeUnless { it.isUnspecified }?.inBytes
        val downloadedBytes: Long? = stats.downloadedBytes.takeUnless { it.isUnspecified }?.inBytes

        /** 一个资源包含多集 (常见于 BT 的季度全集); 各集的缓存共用同一个 origin.mediaId. */
        val isPack = cache.origin.episodeRange?.isSingleEpisode() == false
        val percent get() = (stats.downloadProgress.getOrZero() * 100).toInt().coerceIn(0, 100)
    }

    private class Snapshot(
        val rows: List<Row>,
        /** mediaSourceId → 数据源名. */
        val sourceNames: Map<String, String>,
        /** episodeId → 看到了百分之几 (有播放记录的集). */
        val watched: Map<Int, Int>,
    )

    private suspend fun readSnapshot(): Snapshot = coroutineScope {
        val caches = cacheManager.enabledStorages.first()
            .flatMap { it.listFlow.first() }
            .filter { !it.isDeleted.value }
            .distinctBy { it.cacheId }
        // 各条并发读、各自限时: 某一条的流迟迟不出值, 不拖住整张表
        val rows = caches.map { cache ->
            async {
                val state = async { withTimeoutOrNull(FLOW_TIMEOUT) { cache.state.first() } }
                val stats = async { withTimeoutOrNull(FLOW_TIMEOUT) { cache.fileStats.first() } }
                val merging = async { withTimeoutOrNull(FLOW_TIMEOUT) { cache.isMerging.first() } }
                Row(cache, state.await(), stats.await() ?: MediaCache.FileStats.Unspecified, merging.await() ?: false)
            }
        }.awaitAll()
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
            ?: return result(false, "读取缓存失败，请重试")
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
                    put("meta", "已完成 $done / ${g.size} 集" + if (size > 0) " · ${size.bytes}" else "")
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
                            snapshot.watched[r.episodeId]?.let { put("watched", "已看 $it%") }
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
    private fun statusOf(r: Row): Pair<String, String> = when (r.state) {
        null -> "loading" to "读取中"
        MediaCacheState.COMPLETED -> "done" to "已完成"
        MediaCacheState.FAILED -> "failed" to "下载失败"
        MediaCacheState.PAUSED -> "paused" to "已暂停 ${r.percent}%"
        MediaCacheState.IN_PROGRESS -> if (r.merging) ("merging" to "合并中") else ("run" to "下载中 ${r.percent}%")
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
                samples[cacheId] = Sample(bytes, now, speed)
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
        val cache = findCache(request) ?: return result(false, "这条缓存已经不在了，请刷新")
        val nav = navigator ?: return result(false, "电视还没准备好")
        val m = cache.metadata
        val subjectId = m.subjectId.toIntOrNull() ?: return result(false, "这条缓存没有记录是哪部番，播不了")
        val episodeId = m.episodeId.toIntOrNull() ?: return result(false, "这条缓存没有记录是哪一集，播不了")
        val done = runBlocking { withTimeoutOrNull(FLOW_TIMEOUT) { cache.state.first() } } == MediaCacheState.COMPLETED
        logger.info { "Remote cache play: subject=$subjectId ep=$episodeId cacheId=${cache.cacheId} completed=$done" }
        // 电视正在播这部番时就地换集, 不再叠一个新的播放页 (见 TvRemoteControl.playEpisode)
        val how = TvRemoteControl.playEpisode(nav, uiScope, subjectId, episodeId) {
            logger.warn(it) { "Failed to start playback from remote cache list" }
        }
        val what = "「${displayNamesOf(listOf(subjectId))[subjectId] ?: subjectTitle(m)}」${m.episodeSort}"
        // 没缓存完时电视会从别的数据源里挑一个来播 (没下完的缓存不许选), 挑到的可能是 BT 也可能是在线源, 不说死是哪种
        val msg = when (how) {
            TvRemoteControl.RemotePlayResult.AlreadyPlaying -> "电视正在播$what"
            TvRemoteControl.RemotePlayResult.Switched -> "已在电视上换到$what"
            TvRemoteControl.RemotePlayResult.Opened -> "已在电视上播放$what"
        } + if (!done && how != TvRemoteControl.RemotePlayResult.AlreadyPlaying) "：这一集还没缓存完，先从其他数据源播" else ""
        return result(true, msg, player = true)
    }

    /** 点番名 = 电视打开这部番的详情页. */
    private fun open(request: LanHttpRequest, navigator: AniNavigator?, uiScope: CoroutineScope): JsonObject {
        val subjectId = request.formFields()["subject"]?.toIntOrNull() ?: return result(false, "无效的条目")
        val nav = navigator ?: return result(false, "电视还没准备好")
        TvRemoteControl.notifyRemoteNavigation()
        uiScope.launch(Dispatchers.Main) {
            runCatching { nav.navigateSubjectDetails(subjectId, placeholder = null) }
                .onFailure { logger.warn(it) { "Failed to open subject details from remote cache list" } }
        }
        return result(true, "已在电视上打开详情页")
    }

    private fun subjectTitle(m: MediaCacheMetadata): String =
        m.subjectNameCN?.takeIf { it.isNotBlank() } ?: m.subjectNames.firstOrNull() ?: "未知条目"

    /** 暂停 / 继续 (同缓存管理页). BT 的要等句柄, 放后台做; 状态随网页下一次刷新变过来. */
    private fun setPaused(request: LanHttpRequest, paused: Boolean): JsonObject {
        val cache = findCache(request) ?: return result(false, "这条缓存已经不在了，请刷新")
        scope.launch {
            try {
                if (paused) cache.pause() else cache.resume()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Remote ${if (paused) "pause" else "resume"} failed for cache ${cache.cacheId}" }
            }
        }
        return result(true, if (paused) "已暂停" else "继续下载")
    }

    /**
     * 删除 (同缓存管理页; 不可逆, 网页上先确认). 等逻辑删除落地再回话, 刷新列表时它就不在了; 物理清理在存储自己的作用域里接着跑.
     * 删除本身在本对象的作用域里跑, 限时等不到也不会被取消.
     */
    private fun delete(request: LanHttpRequest): JsonObject {
        val cache = findCache(request) ?: return result(false, "这条缓存已经不在了，请刷新")
        val m = cache.metadata
        val subjectId = m.subjectId.toIntOrNull() ?: 0
        val episodeId = m.episodeId.toIntOrNull() ?: 0
        // 同缓存管理页留一行能还原「删了哪条」的日志, 取证词相同 (Delete cache requested)
        logger.info {
            "Delete cache requested from remote control: subject=$subjectId ep=$episodeId sort=${m.episodeSort} cacheId=${cache.cacheId}"
        }
        val deletion = scope.async { logDeleteFailure(cache.cacheId) { deleteCacheById(subjectId, episodeId, cache.cacheId) } }
        val finished = runBlocking { withTimeoutOrNull(DELETE_TIMEOUT) { deletion.await(); true } } ?: false
        return result(true, if (finished) "已删除「${episodeLabel(m)}」" else "正在删除，稍后刷新看看")
    }

    /**
     * 整部删除 (网页上番名那一行的「删除全部」, 先确认): 这部番在本机的全部缓存逐条删, 同缓存管理页的多选删除.
     * 合集的文件要等同一个种子的集都删了才整个回收, 整部删正好一起删掉.
     */
    private fun deleteSubject(request: LanHttpRequest): JsonObject {
        val subjectId = request.formFields()["subject"]?.toIntOrNull() ?: return result(false, "无效的条目")
        val caches = runBlocking {
            withTimeoutOrNull(OP_TIMEOUT) {
                cacheManager.enabledStorages.first()
                    .flatMap { it.listFlow.first() }
                    .filter { !it.isDeleted.value && it.metadata.subjectId.toIntOrNull() == subjectId }
                    .distinctBy { it.cacheId }
            }
        } ?: return result(false, "读取缓存失败，请重试")
        if (caches.isEmpty()) return result(false, "这部番已经没有缓存了，请刷新")
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
                logDeleteFailure(cache.cacheId) { deleteCacheById(subjectId, episodeId, cache.cacheId) }
            }
        }
        val finished = runBlocking { withTimeoutOrNull(DELETE_ALL_TIMEOUT) { deletion.await(); true } } ?: false
        return result(true, if (finished) "已删除 ${caches.size} 集缓存" else "正在删除 ${caches.size} 集缓存，稍后刷新看看")
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
        return runBlocking { withTimeoutOrNull(OP_TIMEOUT) { cacheManager.findFirstCache { it.cacheId == id } } }
    }

    /** @param player 电视进了播放页: 网页随后切到「播放器」标签 */
    private fun result(ok: Boolean, message: String, player: Boolean = false): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
        if (player) put("player", true)
    }

    private val LIST_TIMEOUT = 8.seconds
    private val FLOW_TIMEOUT = 2.seconds
    private val OP_TIMEOUT = 5.seconds
    private val DELETE_TIMEOUT = 10.seconds
    private val DELETE_ALL_TIMEOUT = 30.seconds
    private const val MIN_SAMPLE_GAP_NANOS = 500_000_000L
    private const val MAX_SAMPLE_GAP_NANOS = 30_000_000_000L
}
