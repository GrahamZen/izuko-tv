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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.nameCnOrName
import me.him188.ani.app.data.repository.media.EpisodePreferencesRepository
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.media.cache.EpisodeCacheStatus
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheManager
import me.him188.ani.app.domain.media.cache.requester.CacheRequestStage
import me.him188.ani.app.domain.media.cache.requester.EpisodeCacheRequest
import me.him188.ani.app.domain.media.cache.requester.EpisodeCacheRequester
import me.him188.ani.app.domain.media.cache.requester.trySelectSingle
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.media.resolver.toEpisodeMetadata
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaSelectorFactory
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.app.domain.media.selector.blocksSelection
import me.him188.ani.app.tools.getOrZero
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.remote.RemoteCandidates.putCandidates
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.api.topic.contains
import me.him188.ani.datasources.api.topic.isSingleEpisode
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 手机控制中心的**缓存**: 选集 (可多选) → 每集挑资源 → 开始缓存. 电视上逐集点、每集在长长的资源列表里挑, 遥控器最费劲.
 *
 * 走缓存页同一台状态机 ([EpisodeCacheRequester]: 选资源 → 选存储 → `storage.cache`), 搜索名同缓存页 (按条目记住的那个).
 * 与缓存页不同的是**不记偏好**: 手机上挑的不写回数据源偏好 (没人收 `onChangePreference`), 同播放器标签的规矩.
 *
 * - **手动挑**: 手机打开某一集时建一个请求 ([Browse]), 后台一直收候选 (数据源查询要有人订阅才继续); 同一时间只开一个,
 *   [BROWSE_IDLE] 没人看就关. 点选后先同步选资源、看有没有能缓存它的存储 (没有 = 不支持缓存, 当场告诉用户), 真正的
 *   `cache()` 放后台 (BT 要先下种子信息).
 * - **自动批量**: 没有现成的批量接口, 这里逐集走: 先看有没有已缓存的季度包能复用 (`tryAutoSelectByCachedSeason`), 没有就按偏好
 *   自动选 (`tryAutoSelectByPreference`, 等数据源查完). 选不到的那集记下原因, 列表上显示, 用户再手动挑.
 * - **合集** (一个资源包含多集, 常见于 BT 的季度全集): 同电视缓存页, 已缓存的合集覆盖到的其它集不用再挑 —— 列表上直接给
 *   「用合集缓存」, 点一下就用同一个资源缓存那一集 (电视上点一集时的自动选择, `tryAutoSelectByCachedSeason`); 顶上还有
 *   「全部用合集缓存」, 走自动批量 (它本来就先找合集). 缓存按集记账, 合集只算在选它的那一集上, 所以要一集一集建.
 *
 * 缓存状态、进度读 [MediaCacheManager.cacheStatusForEpisode]; 缓存本身在存储自己的作用域里跑, 与这里无关.
 */
@OptIn(UnsafeOriginalMediaAccess::class)
internal object RemoteCache {
    private val logger = logger<RemoteCache>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("RemoteCache"))

    private val collectionRepository: SubjectCollectionRepository get() = KoinPlatform.getKoin().get()
    private val cacheManager: MediaCacheManager get() = KoinPlatform.getKoin().get()
    private val sourceManager: MediaSourceManager get() = KoinPlatform.getKoin().get()
    private val episodePreferences: EpisodePreferencesRepository get() = KoinPlatform.getKoin().get()

    /** 手机上正在挑资源的那一集. */
    private class Browse(
        val subjectId: Int,
        val episode: EpisodeCollectionInfo,
        val requester: EpisodeCacheRequester,
        val stage: CacheRequestStage.SelectMedia,
    ) {
        @Volatile
        var candidates: List<MaybeExcludedMedia> = emptyList()

        @Volatile
        var lastAccess = System.currentTimeMillis()

        var job: Job? = null
    }

    private val lock = Any()
    private var browse: Browse? = null
    private val watchdogStarted = AtomicBoolean(false)

    private class Batch(val subjectId: Int, val total: Int) {
        @Volatile
        var done = 0

        @Volatile
        var current: String? = null

        @Volatile
        var running = true

        val failures = CopyOnWriteArrayList<String>()
    }

    @Volatile
    private var batch: Batch? = null

    /** 各集最近一次失败的原因 ((subjectId, episodeId) → 文案), 列表上显示; 那一集成功开始缓存即清. */
    private val errors = ConcurrentHashMap<Pair<Int, Int>, String>()

    /** 处理 `api/cache` 下的请求; 路径或方法不认识返回 null. */
    fun handle(request: LanHttpRequest): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return runCatching {
            when {
                request.path == "api/cache" && get -> {
                    val subjectId = request.queryParam("subject")?.toIntOrNull()
                    if (subjectId == null) result(false, "无效的条目") else episodes(subjectId)
                }

                request.path == "api/cache/candidates" && get -> candidates(request)
                !post -> null
                request.path == "api/cache/pick" -> pick(request)
                request.path == "api/cache/pack" -> pack(request)
                request.path == "api/cache/auto" -> auto(request)
                request.path == "api/cache/close" -> {
                    close()
                    result(true, "")
                }

                else -> null
            }
        }.getOrElse {
            logger.warn(it) { "Remote cache request failed: ${request.method} ${request.path}" }
            result(false, "操作失败：${it.message ?: it::class.simpleName}")
        }
    }

    // ============================ 剧集列表 ============================

    private fun episodes(subjectId: Int): JsonObject {
        val info = loadSubject(subjectId) ?: return result(false, "读取剧集失败，请重试")
        val statuses = runBlocking {
            withTimeoutOrNull(STATUS_TIMEOUT) {
                info.episodes.associate { it.episodeId to cacheManager.cacheStatusForEpisode(subjectId, it.episodeId).first() }
            }
        }.orEmpty()
        // 已有的合集 (多集资源): 它覆盖到、还没缓存的集, 列表上给「用合集缓存」
        val caches = runBlocking {
            withTimeoutOrNull(STATUS_TIMEOUT) { cacheManager.listCacheForSubject(subjectId).first() }
        }.orEmpty()
        var packTitle: String? = null
        val b = batch?.takeIf { it.subjectId == subjectId }
        return buildJsonObject {
            put("ok", true)
            put("title", info.subjectInfo.nameCnOrName)
            putJsonArray("episodes") {
                for (ep in info.episodes) addJsonObject {
                    put("id", ep.episodeId)
                    put("label", episodeLabel(ep))
                    put("watched", ep.collectionType == UnifiedCollectionType.DONE)
                    when (val st = statuses[ep.episodeId]) {
                        is EpisodeCacheStatus.Cached -> {
                            put("status", "cached")
                            if (!st.totalSize.isUnspecified) put("size", st.totalSize.toString())
                        }

                        is EpisodeCacheStatus.Caching -> {
                            put("status", "caching")
                            put("progress", (st.progress.getOrZero() * 100).toInt())
                            if (!st.totalSize.isUnspecified) put("size", st.totalSize.toString())
                        }

                        else -> {
                            put("status", "none")
                            seasonPackFor(caches, ep.episodeInfo)?.let { pack ->
                                put("pack", true)
                                if (packTitle == null) packTitle = pack.origin.originalTitle
                            }
                        }
                    }
                    errors[subjectId to ep.episodeId]?.let { put("error", it) }
                }
            }
            packTitle?.let { put("packTitle", it) }
            // 挑几集之前心里有数 (合集按集只下那一集的文件, 但删掉一集要等同一个种子的都删了才回收)
            RemoteCacheList.freeSpace()?.let { put("free", it.first.bytes.toString()) }
            if (b != null) putJsonObject("batch") {
                put("running", b.running)
                put("done", b.done)
                put("total", b.total)
                b.current?.let { put("current", it) }
                putJsonArray("failures") { b.failures.forEach { add(it) } }
            }
        }
    }

    // ============================ 手动挑资源 ============================

    private fun candidates(request: LanHttpRequest): JsonObject {
        val subjectId = request.queryParam("subject")?.toIntOrNull() ?: return result(false, "无效的条目")
        val episodeId = request.queryParam("episode")?.toIntOrNull() ?: return result(false, "无效的剧集")
        val b = ensureBrowse(subjectId, episodeId) ?: return result(false, "打开这一集失败，请重试")
        b.lastAccess = System.currentTimeMillis()
        val filter = RemoteMediaFilter(
            resolution = request.queryParam("res")?.takeIf { it.isNotEmpty() },
            subtitle = request.queryParam("sub")?.takeIf { it.isNotEmpty() },
            alliance = request.queryParam("all")?.takeIf { it.isNotEmpty() },
            showExcluded = request.queryParam("ex") == "1",
            // 点了某个数据源胶囊后勾「显示全部」: 这个源不按每源上限截断 (同播放器标签)
            fullSource = request.queryParam("full")?.takeIf { it.isNotEmpty() },
        )
        val sources = b.stage.fetchSession.mediaSourceResults
            .filter { it.kind != MediaSourceKind.LocalCache && it.state.value != MediaSourceFetchState.Disabled }
        // 已经缓存好的 (本地缓存源) 不列: 那是缓存本身, 不能再缓存一遍
        val all = b.candidates.filter { it.original.kind != MediaSourceKind.LocalCache }
        // 胶囊上的条数: 这个源的全部候选 (含被排除的), 不随下拉筛选变
        val counts = all.groupingBy { it.original.mediaSourceId }.eachCount()
        return buildJsonObject {
            put("ok", true)
            put("loading", sources.any { it.state.value.let { st -> st == MediaSourceFetchState.Working || st == MediaSourceFetchState.Idle } })
            putJsonArray("sources") {
                for (s in sources) addJsonObject {
                    put("id", s.mediaSourceId)
                    put("name", s.sourceInfo.displayName)
                    put("state", stateLabel(s.state.value))
                    put("count", counts[s.mediaSourceId] ?: 0)
                }
            }
            putCandidates(
                all = all,
                sourceOrder = sources.map { it.mediaSourceId },
                sourceName = { id, _ -> sources.firstOrNull { it.mediaSourceId == id }?.sourceInfo?.displayName ?: id },
                filter = filter,
            )
        }
    }

    private fun ensureBrowse(subjectId: Int, episodeId: Int): Browse? {
        synchronized(lock) {
            browse?.takeIf { it.subjectId == subjectId && it.episode.episodeId == episodeId }?.let { return it }
        }
        close()
        val info = loadSubject(subjectId) ?: return null
        val ep = info.episodes.firstOrNull { it.episodeId == episodeId } ?: return null
        val requester = newRequester(subjectId)
        val stage = runBlocking {
            withTimeoutOrNull(REQUEST_TIMEOUT) { requester.request(EpisodeCacheRequest(info.subjectInfo, ep.episodeInfo)) }
        } ?: return null
        val b = Browse(subjectId, ep, requester, stage)
        // 一直收着候选: 数据源查询要有人订阅才会继续 (同缓存页的选择框)
        b.job = scope.launch { stage.mediaSelector.filteredCandidates.collect { b.candidates = it } }
        synchronized(lock) { browse = b }
        startWatchdog()
        return b
    }

    private fun close() {
        val b = synchronized(lock) { browse.also { browse = null } } ?: return
        b.job?.cancel()
        scope.launch { runCatching { b.requester.cancelRequest() } }
    }

    private fun startWatchdog() {
        if (!watchdogStarted.compareAndSet(false, true)) return
        scope.launch {
            while (true) {
                delay(30.seconds)
                val b = synchronized(lock) { browse } ?: continue
                if (System.currentTimeMillis() - b.lastAccess > BROWSE_IDLE.inWholeMilliseconds) {
                    logger.info { "Closing idle remote cache browse for episode ${b.episode.episodeId}" }
                    close()
                }
            }
        }
    }

    private fun pick(request: LanHttpRequest): JsonObject {
        val f = request.formFields()
        val subjectId = f["subject"]?.toIntOrNull() ?: return result(false, "无效的条目")
        val episodeId = f["episode"]?.toIntOrNull() ?: return result(false, "无效的剧集")
        val mediaId = f["id"].orEmpty()
        val b = synchronized(lock) { browse?.takeIf { it.subjectId == subjectId && it.episode.episodeId == episodeId } }
            ?: return result(false, "列表已过期，请重新打开这一集")
        val entry = b.candidates.firstOrNull { it.original.mediaId == mediaId }
            ?: return result(false, "这个资源已不在列表里，请刷新")
        if (entry.exclusionReason?.blocksSelection == true) return result(false, "这个资源现在不能选")
        // 先选资源, 看有哪些存储能缓存它: 一个都没有就是不支持 (比如没开 PikPak 的磁链走不了 HTTP 缓存), 当场说
        val selectStorage = runBlocking { withTimeoutOrNull(REQUEST_TIMEOUT) { b.stage.select(entry.original) } }
            ?: return result(false, "操作超时，请重试")
        if (selectStorage.storages.isEmpty()) {
            scope.launch { runCatching { selectStorage.cancel() } }
            return result(false, "这个资源不支持缓存，换一个试试")
        }
        synchronized(lock) { if (browse === b) browse = null }
        b.job?.cancel()
        val key = subjectId to episodeId
        errors.remove(key)
        // 真正开始缓存放后台: BT 要先下种子信息, 可能要一会儿; 进度看剧集列表
        scope.launch {
            try {
                val done = selectStorage.trySelectSingle() ?: selectStorage.select(selectStorage.storages.first())
                done.storage.cache(done.media, done.metadata, b.episode.episodeInfo.toEpisodeMetadata())
                logger.info { "Remote control started caching subject $subjectId episode $episodeId" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Remote cache failed for subject $subjectId episode $episodeId" }
                errors[key] = "缓存失败：${e.message ?: e::class.simpleName}"
            }
        }
        // 合集: 缓存建好后列表上它覆盖的其它集会出现「用合集缓存」, 先说一声
        val isPack = entry.original.episodeRange?.isSingleEpisode() == false
        return result(
            true,
            "已开始缓存「${episodeLabel(b.episode)}」" + if (isPack) "。这是合集，其它集可以在列表里直接用合集缓存" else "",
        )
    }

    /** 列表上的「用合集缓存」: 用已缓存的合集缓存这一集, 同电视缓存页点一集时的自动选择, 不用再挑资源. */
    private fun pack(request: LanHttpRequest): JsonObject {
        val f = request.formFields()
        val subjectId = f["subject"]?.toIntOrNull() ?: return result(false, "无效的条目")
        val episodeId = f["episode"]?.toIntOrNull() ?: return result(false, "无效的剧集")
        val info = loadSubject(subjectId) ?: return result(false, "读取剧集失败，请重试")
        val ep = info.episodes.firstOrNull { it.episodeId == episodeId } ?: return result(false, "没有找到这一集")
        val done = runBlocking { withTimeoutOrNull(REQUEST_TIMEOUT) { selectFromSeasonPack(info, ep) } }
            ?: return result(false, "已缓存的合集里没有这一集，请点「选资源」自己挑")
        val key = subjectId to episodeId
        errors.remove(key)
        // 真正开始缓存放后台 (同手动挑)
        scope.launch {
            try {
                done.storage.cache(done.media, done.metadata, ep.episodeInfo.toEpisodeMetadata())
                logger.info { "Remote control started caching subject $subjectId episode $episodeId from a cached season pack" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Remote season-pack cache failed for subject $subjectId episode $episodeId" }
                errors[key] = "缓存失败：${e.message ?: e::class.simpleName}"
            }
        }
        return result(true, "已用合集开始缓存「${episodeLabel(ep)}」")
    }

    /**
     * 按已缓存的合集为这一集选好资源与存储 (不开始缓存). 合集没覆盖这一集返回 null, 请求随即撤销.
     * 只看已有缓存, 不等数据源查询.
     */
    private suspend fun selectFromSeasonPack(info: SubjectCollectionInfo, ep: EpisodeCollectionInfo): CacheRequestStage.Done? {
        val subjectId = info.subjectInfo.subjectId
        val requester = newRequester(subjectId)
        val stage = requester.request(EpisodeCacheRequest(info.subjectInfo, ep.episodeInfo))
        val done = stage.tryAutoSelectByCachedSeason(cacheManager.listCacheForSubject(subjectId).first()).toDone()
        if (done == null) runCatching { requester.cancelRequest() }
        return done
    }

    // ============================ 自动批量 ============================

    private fun auto(request: LanHttpRequest): JsonObject {
        val f = request.formFields()
        val subjectId = f["subject"]?.toIntOrNull() ?: return result(false, "无效的条目")
        val ids = f["episodes"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        if (ids.isEmpty()) return result(false, "先勾选要缓存的剧集")
        if (batch?.running == true) return result(false, "上一批还在进行，稍等一下")
        val info = loadSubject(subjectId) ?: return result(false, "读取剧集失败，请重试")
        val targets = info.episodes.filter { it.episodeId in ids }
        if (targets.isEmpty()) return result(false, "没有找到这些剧集")
        val b = Batch(subjectId, targets.size)
        batch = b
        scope.launch {
            try {
                for (ep in targets) {
                    b.current = episodeLabel(ep)
                    autoCacheOne(info, ep)?.let { b.failures += "${episodeLabel(ep)}：$it" }
                    b.done++
                }
            } finally {
                b.current = null
                b.running = false
            }
        }
        return result(true, "开始为 ${targets.size} 集自动挑资源缓存")
    }

    /** 一集: 复用已缓存的季度包, 否则按偏好自动选. @return 失败原因; 成功 (或本来就缓存了) 为 null */
    private suspend fun autoCacheOne(info: SubjectCollectionInfo, ep: EpisodeCollectionInfo): String? {
        val subjectId = info.subjectInfo.subjectId
        val key = subjectId to ep.episodeId
        return try {
            if (cacheManager.cacheStatusForEpisode(subjectId, ep.episodeId).first() !is EpisodeCacheStatus.NotCached) return null
            val requester = newRequester(subjectId)
            val stage = requester.request(EpisodeCacheRequest(info.subjectInfo, ep.episodeInfo))
            val existing = cacheManager.listCacheForSubject(subjectId).first()
            val done = (
                    stage.tryAutoSelectByCachedSeason(existing)
                        ?: withTimeoutOrNull(AUTO_SELECT_TIMEOUT) { stage.tryAutoSelectByPreference() }
                    ).toDone()
            if (done == null) {
                runCatching { requester.cancelRequest() }
                "没有自动选到能缓存的资源，可以点「选资源」手动挑".also { errors[key] = it }
            } else {
                done.storage.cache(done.media, done.metadata, ep.episodeInfo.toEpisodeMetadata())
                errors.remove(key)
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Remote auto cache failed for subject $subjectId episode ${ep.episodeId}" }
            "缓存失败：${e.message ?: e::class.simpleName}".also { errors[key] = it }
        }
    }

    // ============================ 工具 ============================

    /** 选中资源之后定存储: 已经定了就是它; 只有一个能用的存储就选它, 再不然取第一个 (同缓存页的默认). */
    private suspend fun CacheRequestStage.MediaSelected?.toDone(): CacheRequestStage.Done? = when (this) {
        is CacheRequestStage.Done -> this
        is CacheRequestStage.SelectStorage -> trySelectSingle() ?: storages.firstOrNull()?.let { select(it) }
        null -> null
    }

    /** 已有缓存里覆盖这一集的合集 (多集资源). 判据同 `tryAutoSelectByCachedSeason`: 集数范围包含这一集的 ep 或序号. */
    private fun seasonPackFor(caches: List<MediaCache>, episode: EpisodeInfo): MediaCache? = caches.firstOrNull { cache ->
        val range = cache.origin.episodeRange
        range != null && !range.isSingleEpisode() &&
                ((episode.ep?.let { range.contains(it) } ?: false) || range.contains(episode.sort))
    }

    private fun newRequester(subjectId: Int): EpisodeCacheRequester = EpisodeCacheRequester(
        sourceManager.mediaFetcher,
        MediaSelectorFactory.withKoin(),
        cacheManager.enabledStorages,
        // 搜索名同缓存页: 用户在数据源里改过的条目搜索名按条目记住, 缓存也照用
        transformFetchRequest = { req -> episodePreferences.searchKeywordsFlow(subjectId).first()?.applyTo(req) ?: req },
    )

    private fun loadSubject(subjectId: Int): SubjectCollectionInfo? = runBlocking {
        withTimeoutOrNull(SUBJECT_TIMEOUT) { collectionRepository.subjectCollectionFlow(subjectId).first() }
    }

    private fun episodeLabel(ep: EpisodeCollectionInfo): String = buildString {
        append(ep.episodeInfo.sort.toString())
        val name = ep.episodeInfo.nameCn.ifBlank { ep.episodeInfo.name }
        if (name.isNotBlank()) append("  ").append(name)
    }

    private fun stateLabel(st: MediaSourceFetchState): String = when (st) {
        MediaSourceFetchState.Idle, MediaSourceFetchState.Working -> "loading"
        is MediaSourceFetchState.CaptchaRequired -> "captcha"
        is MediaSourceFetchState.RateLimited -> "limited"
        is MediaSourceFetchState.Failed, is MediaSourceFetchState.Abandoned -> "failed"
        else -> "done"
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    private fun LanHttpRequest.queryParam(name: String): String? =
        query.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }

    private val SUBJECT_TIMEOUT = 15.seconds
    private val STATUS_TIMEOUT = 5.seconds
    private val REQUEST_TIMEOUT = 15.seconds
    private val AUTO_SELECT_TIMEOUT = 2.minutes

    /** 手机上打开的那一集多久没人看就关掉请求 (停止查询). */
    private val BROWSE_IDLE = 3.minutes
}
