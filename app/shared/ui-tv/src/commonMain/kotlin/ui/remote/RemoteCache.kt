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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.nameCnOrName
import me.him188.ani.app.data.repository.media.EpisodePreferencesRepository
import me.him188.ani.app.data.repository.media.SelectorMediaSourceEpisodeCacheRepository
import me.him188.ani.app.data.repository.media.rememberSearchNames
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.media.cache.EpisodeCacheStatus
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheManager
import me.him188.ani.app.domain.media.cache.requester.CacheRequestStage
import me.him188.ani.app.domain.media.cache.requester.EpisodeCacheRequest
import me.him188.ani.app.domain.media.cache.requester.EpisodeCacheRequester
import me.him188.ani.app.domain.media.cache.requester.trySelectSingle
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.media.fetch.awaitCompletion
import me.him188.ani.app.domain.media.fetch.create
import me.him188.ani.app.domain.media.resolver.toEpisodeMetadata
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaSelectorFactory
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.app.domain.media.selector.blocksSelection
import me.him188.ani.app.tools.getOrZero
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.mediafetch.request.toEditingMediaFetchRequest
import me.him188.ani.app.ui.mediafetch.request.toMediaFetchRequestOrNull
import me.him188.ani.app.ui.remote.RemoteCandidates.putCandidates
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest
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
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台的**缓存**: 选集 (可多选) → 每集挑资源 → 开始缓存. 电视上逐集点、每集在长长的资源列表里挑, 遥控器最费劲.
 *
 * 走缓存页同一台状态机 ([EpisodeCacheRequester]: 选资源 → 选存储 → `storage.cache`), 搜索名同缓存页 (按条目记住的那个).
 * 与缓存页不同的是**不记偏好**: 手机上挑的不写回数据源偏好 (没人收 `onChangePreference`), 同播放器标签的规矩.
 *
 * - **手动挑**: 手机打开某一集时建一个请求 ([Browse]), 后台一直收候选 (数据源查询要有人订阅才继续); 同一时间只开一个,
 *   [BROWSE_IDLE] 没人看就关. 点选后先同步选资源、看有没有能缓存它的存储 (没有 = 不支持缓存, 当场告诉用户), 真正的
 *   `cache()` 放后台 (BT 要先下种子信息).
 * - **自动批量**: 没有现成的批量接口, 这里逐集走: 先看有没有已缓存的季度包能复用 (`tryAutoSelectByCachedSeason`); 没有就**沿用
 *   之前选过的源** ([pinnedSourceFor]), 只在这个源里按偏好挑, 它没有这一集就报失败、不换源 —— 在全部源里按偏好自动挑会挑到别的
 *   线路 (手机上挑的源不写偏好, 自动挑认不出来; 实测挑到一条证书过不了的线路, 整批失败). 前面一集都没选过才按偏好自动选
 *   (`tryAutoSelectByPreference`). 都等数据源查完. 选不到的那集记下原因, 列表上显示, 用户再手动挑.
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
    private val selectorCache: SelectorMediaSourceEpisodeCacheRepository get() = KoinPlatform.getKoin().get()
    private val settingsRepository: SettingsRepository get() = KoinPlatform.getKoin().get()

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

    /**
     * 这部番刚删掉的缓存原来用的源与资源, 由 [RemoteCacheList] 删之前记下. 删完马上重下时已缓存的集没了, 条目偏好又常是
     * 「本地缓存」(播过缓存的集就会记成它), 沿用源无从说起 —— 实测沿用到 LocalTorrent, 集集「没找到这一集」.
     * 有了它: 沿用同一个源、优先同一个资源 (BT 文件还在盘上的话一下就好, 同电视上重选它). 只在内存里, 重启后退回按偏好挑.
     */
    private class Recent(val sourceId: String, val mediaIds: Set<String>)

    private val recent = ConcurrentHashMap<Int, Recent>()

    fun rememberDeleted(subjectId: Int, caches: List<MediaCache>) {
        val origins = caches.map { it.origin }.filter { !isLocalSource(it.mediaSourceId) }
        val source = origins.groupingBy { it.mediaSourceId }.eachCount().maxByOrNull { it.value }?.key ?: return
        val new = Recent(source, origins.filter { it.mediaSourceId == source }.mapTo(HashSet()) { it.mediaId })
        // 一集一集删同一个源的: 累积起来
        recent.merge(subjectId, new) { old, n -> if (old.sourceId == n.sourceId) Recent(n.sourceId, old.mediaIds + n.mediaIds) else n }
    }

    /** 处理 `api/cache` 下的请求; 路径或方法不认识返回 null. */
    fun handle(request: LanHttpRequest): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return runCatching {
            when {
                request.path == "api/cache" && get -> {
                    val subjectId = request.queryParam("subject")?.toIntOrNull()
                    if (subjectId == null) result(false, tr("无效的条目")) else episodes(subjectId)
                }

                request.path == "api/cache/candidates" && get -> candidates(request)
                !post -> null
                request.path == "api/cache/pick" -> pick(request)
                request.path == "api/cache/pack" -> pack(request)
                request.path == "api/cache/auto" -> auto(request)
                request.path == "api/cache/names" -> names(request)
                request.path == "api/cache/close" -> {
                    close()
                    result(true, "")
                }

                else -> null
            }
        }.getOrElse {
            logger.warn(it) { "Remote cache request failed: ${request.method} ${request.path}" }
            result(false, tr("操作失败：{0}", it.message ?: it::class.simpleName))
        }
    }

    // ============================ 剧集列表 ============================

    private fun episodes(subjectId: Int): JsonObject {
        val info = loadSubject(subjectId) ?: return result(false, tr("读取剧集失败，请重试"))
        // 这部番的全部缓存; 顺带用来找已有的合集 (多集资源): 它覆盖到、还没缓存的集, 列表上给「用合集缓存」
        val caches = runBlocking {
            withTimeoutOrNull(STATUS_TIMEOUT) { cacheManager.listCacheForSubject(subjectId).first() }
        } ?: return result(false, tr("读取缓存状态超时，请重试"))
        // 只问有缓存的那几集的状态, 其余必然没缓存: 以前逐集各订一次状态流, 上千集的长番在限时内问不完时整张表落空,
        // 已缓存的集全显示成「未缓存」, 还给出重复缓存的勾选框. 超时宁可报错 (网页下一轮自己重试), 不给错的状态
        val cachedEpisodes = caches.mapTo(HashSet()) { it.metadata.episodeId }
        val statuses = runBlocking {
            withTimeoutOrNull(STATUS_TIMEOUT) {
                info.episodes.filter { it.episodeId.toString() in cachedEpisodes }
                    .associate { it.episodeId to cacheManager.cacheStatusForEpisode(subjectId, it.episodeId).first() }
            }
        } ?: return result(false, tr("读取缓存状态超时，请重试"))
        var packTitle: String? = null
        val b = batch?.takeIf { it.subjectId == subjectId }
        return buildJsonObject {
            put("ok", true)
            put("title", info.subjectInfo.nameCnOrName)
            putJsonArray("episodes") {
                for (ep in info.episodes) addJsonObject {
                    put("id", ep.episodeId)
                    put("label", episodeLabel(ep))
                    // 特别篇 (SP / OVA 等, 集号不是普通数字; 同「接下来播放」只认 Normal): 网页上的「全选」默认不选它
                    if (ep.episodeInfo.sort !is EpisodeSort.Normal) put("sp", true)
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
            runBlocking { autoHint(subjectId, caches) }?.let { put("autoHint", it) }
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
        val subjectId = request.queryParam("subject")?.toIntOrNull() ?: return result(false, tr("无效的条目"))
        val episodeId = request.queryParam("episode")?.toIntOrNull() ?: return result(false, tr("无效的剧集"))
        val b = ensureBrowse(subjectId, episodeId) ?: return result(false, tr("打开这一集失败，请重试"))
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
            // 选资源页的「搜索名」(见 names): 当前这次查询用的条目名, 与 Bangumi 名字不同就是改过的
            runBlocking { withTimeoutOrNull(STATUS_TIMEOUT) { b.stage.fetchSession.request.first() } }?.let { req ->
                val editing = req.toEditingMediaFetchRequest()
                put("primary", editing.primaryName)
                putJsonArray("others") { editing.complementaryNames.forEach { add(it) } }
                put("sort", editing.episodeSort)
                put("ep", editing.episodeEp)
                val d = defaultRequest(b)
                put("edited", req.subjectNames != d.subjectNames || req.episodeSort != d.episodeSort || req.episodeEp != d.episodeEp)
            }
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

    /**
     * 建请求整段串行: 建一个最长要 15 + 15 秒, 期间同一集的下一次轮询 (手机等不及重发) 或换了一集的请求要是也各建一个,
     * 后建完的把先建完的覆盖掉, 被覆盖的那个既不在 [browse] 里、也没人 cancel —— 收候选的协程和数据源查询一直挂着.
     * 串起来后, 后到的等前一个建完: 同一集直接复用, 换了集就先把它关掉.
     */
    private val createLock = Any()

    private fun ensureBrowse(subjectId: Int, episodeId: Int): Browse? = synchronized(createLock) {
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

    /** 这一集由 Bangumi 信息生成、未套用改过的搜索名的请求 (「恢复 Bangumi 名称」回到它). */
    private fun defaultRequest(b: Browse) = MediaFetchRequest.create(b.stage.request.subjectInfo, b.stage.request.episodeInfo)

    /**
     * 选资源页的「搜索名与集数」: 改数据源搜索用的条目名 (主搜索名 + 次要名), 按条目记住 —— 同播放器的编辑查询请求, 这部番以后
     * 缓存和播放都用; 集数 (系列内序号 / 条目内序号) 只作用于这一集这次查询, 不记. 缓存记录按剧集 ID 挂在这一集上, 集数只决定
     * 搜什么、以及 BT 合集里挑哪个文件 (TorrentMediaCacheEngine 按记录里的集数挑), 合集编号与 Bangumi 不同 (第二季从 13 开始)
     * 时要改它. 当前这一集立刻重搜. `reset=1` = 名字与集数都回到 Bangumi 的 (删掉名字记录).
     */
    private fun names(request: LanHttpRequest): JsonObject {
        val f = request.formFields()
        val subjectId = f["subject"]?.toIntOrNull() ?: return result(false, tr("无效的条目"))
        val episodeId = f["episode"]?.toIntOrNull() ?: return result(false, tr("无效的剧集"))
        val b = synchronized(lock) { browse }?.takeIf { it.subjectId == subjectId && it.episode.episodeId == episodeId }
            ?: return result(false, tr("列表已过期，请重新打开这一集"))
        val reset = f["reset"] == "1"
        val default = defaultRequest(b)
        val current = runBlocking { withTimeoutOrNull(STATUS_TIMEOUT) { b.stage.fetchSession.request.first() } }
            ?: return result(false, tr("操作超时，请重试"))
        val edited = if (reset) {
            default
        } else {
            val primary = f["primary"].orEmpty().trim()
            val sort = f["sort"].orEmpty().trim()
            val ep = f["ep"].orEmpty().trim()
            if (primary.isEmpty()) return result(false, tr("主搜索名不能为空"))
            if (sort.isEmpty() && ep.isEmpty()) return result(false, tr("两种集数至少要填一个"))
            current.toEditingMediaFetchRequest().copy(
                primaryName = primary,
                complementaryNames = f["others"].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() },
                episodeSort = sort,
                episodeEp = ep,
            ).toMediaFetchRequestOrNull() ?: return result(false, tr("请求无效，请检查"))
        }
        runBlocking {
            withTimeoutOrNull(STATUS_TIMEOUT) {
                episodePreferences.rememberSearchNames(subjectId, edited, default)
                // 同播放页: 各源要按新名字重搜, 本条目旧的在线源搜索缓存作废
                selectorCache.clearByRequestedSubject(subjectId)
            }
        } ?: return result(false, tr("操作超时，请重试"))
        b.stage.fetchSession.setFetchRequest(edited)
        logger.info {
            "Remote cache search request for subject $subjectId episode $episodeId: names=${edited.subjectNames}, " +
                "sort=${edited.episodeSort}, ep=${edited.episodeEp} (reset=$reset)"
        }
        val message = when {
            reset -> tr("已恢复 Bangumi 名称和集数，正在重新搜索")
            edited.subjectNames != default.subjectNames -> tr("已保存，这部番以后缓存和播放都用这个搜索名，正在重新搜索")
            else -> tr("已按新的集数重新搜索（只影响这一集）")
        }
        return result(true, message)
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
        val subjectId = f["subject"]?.toIntOrNull() ?: return result(false, tr("无效的条目"))
        val episodeId = f["episode"]?.toIntOrNull() ?: return result(false, tr("无效的剧集"))
        val mediaId = f["id"].orEmpty()
        val b = synchronized(lock) { browse?.takeIf { it.subjectId == subjectId && it.episode.episodeId == episodeId } }
            ?: return result(false, tr("列表已过期，请重新打开这一集"))
        val entry = b.candidates.firstOrNull { it.original.mediaId == mediaId }
            ?: return result(false, tr("这个资源已不在列表里，请刷新"))
        if (entry.exclusionReason?.blocksSelection == true) return result(false, tr("这个资源现在不能选"))
        // 先选资源, 看有哪些存储能缓存它: 一个都没有就是不支持 (比如没开 PikPak 的磁链走不了 HTTP 缓存), 当场说
        val selectStorage = runBlocking { withTimeoutOrNull(REQUEST_TIMEOUT) { b.stage.select(entry.original) } }
            ?: return result(false, tr("操作超时，请重试"))
        if (selectStorage.storages.isEmpty()) {
            scope.launch { runCatching { selectStorage.cancel() } }
            return result(false, tr("这个资源不支持缓存，换一个试试"))
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
                errors[key] = tr("缓存失败：{0}", e.message ?: e::class.simpleName)
            }
        }
        // 合集: 缓存建好后列表上它覆盖的其它集会出现「用合集缓存」, 先说一声
        val isPack = entry.original.episodeRange?.isSingleEpisode() == false
        return result(
            true,
            tr("已开始缓存「{0}」", episodeLabel(b.episode)) + if (isPack) tr("。这是合集，其它集可以在列表里直接用合集缓存") else "",
        )
    }

    /** 列表上的「用合集缓存」: 用已缓存的合集缓存这一集, 同电视缓存页点一集时的自动选择, 不用再挑资源. */
    private fun pack(request: LanHttpRequest): JsonObject {
        val f = request.formFields()
        val subjectId = f["subject"]?.toIntOrNull() ?: return result(false, tr("无效的条目"))
        val episodeId = f["episode"]?.toIntOrNull() ?: return result(false, tr("无效的剧集"))
        val info = loadSubject(subjectId) ?: return result(false, tr("读取剧集失败，请重试"))
        val ep = info.episodes.firstOrNull { it.episodeId == episodeId } ?: return result(false, tr("没有找到这一集"))
        val done = runBlocking { withTimeoutOrNull(REQUEST_TIMEOUT) { selectFromSeasonPack(info, ep) } }
            ?: return result(false, tr("已缓存的合集里没有这一集，请点「选资源」自己挑"))
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
                errors[key] = tr("缓存失败：{0}", e.message ?: e::class.simpleName)
            }
        }
        return result(true, tr("已用合集开始缓存「{0}」", episodeLabel(ep)))
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
        val subjectId = f["subject"]?.toIntOrNull() ?: return result(false, tr("无效的条目"))
        val ids = f["episodes"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        if (ids.isEmpty()) return result(false, tr("先勾选要缓存的剧集"))
        if (batch?.running == true) return result(false, tr("上一批还在进行，稍等一下"))
        val info = loadSubject(subjectId) ?: return result(false, tr("读取剧集失败，请重试"))
        val targets = info.episodes.filter { it.episodeId in ids }
        if (targets.isEmpty()) return result(false, tr("没有找到这些剧集"))
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
        return result(true, tr("开始为 {0} 集自动挑资源缓存", targets.size))
    }

    /**
     * 一集: 复用已缓存的季度包; 否则沿用之前选过的源 ([pinnedSourceFor]), 一次都没选过才按偏好自动选.
     * @return 失败原因; 成功 (或本来就缓存了) 为 null
     */
    private suspend fun autoCacheOne(info: SubjectCollectionInfo, ep: EpisodeCollectionInfo): String? {
        val subjectId = info.subjectInfo.subjectId
        val key = subjectId to ep.episodeId
        return try {
            if (cacheManager.cacheStatusForEpisode(subjectId, ep.episodeId).first() !is EpisodeCacheStatus.NotCached) return null
            val requester = newRequester(subjectId)
            val stage = requester.request(EpisodeCacheRequest(info.subjectInfo, ep.episodeInfo))
            val existing = cacheManager.listCacheForSubject(subjectId).first()
            val pinned = pinnedSourceFor(subjectId, ep.episodeInfo, existing)
            val started = System.currentTimeMillis()
            var why: String? = null
            val done = (
                    stage.tryAutoSelectByCachedSeason(existing)
                        ?: if (pinned == null) {
                            (withTimeoutOrNull(AUTO_SELECT_TIMEOUT) { selectByOrder(stage, subjectId) }
                                ?: (null to tr("查询超时，请点「选资源」手动选择。")))
                                .let { (selected, reason) -> why = reason; selected }
                        } else {
                            // 整段 (等源 + 挑 + 选中) 限时: 里面有些等待没有尽头, 不能让一集卡死整批
                            (withTimeoutOrNull(AUTO_SELECT_TIMEOUT) { selectFromPinned(stage, subjectId, pinned) }
                                ?: (null to tr("来源「{0}」查询超时，请点「选资源」更换。", sourceName(pinned))))
                                .let { (selected, reason) -> why = reason; selected }
                        }
                    ).toDone()
            if (done == null) {
                // 以前这条路一行日志都没有, 挑不出来只能猜; 带上各源当时的状态和等了多久
                logger.info {
                    "Remote auto cache subject $subjectId episode ${ep.episodeId}: nothing selected (pinned=$pinned, reason=$why, " +
                            "took ${System.currentTimeMillis() - started}ms, sources=" +
                            stage.fetchSession.mediaSourceResults.joinToString { "${it.mediaSourceId}:${it.state.value::class.simpleName}" } + ")"
                }
                runCatching { requester.cancelRequest() }
                (why ?: tr("没有找到可缓存的资源，请点「选资源」手动选择。")).also { errors[key] = it }
            } else {
                logger.info { "Remote auto cache subject $subjectId episode ${ep.episodeId}: source ${done.media.mediaSourceId} (pinned=$pinned)" }
                done.storage.cache(done.media, done.metadata, ep.episodeInfo.toEpisodeMetadata())
                errors.remove(key)
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Remote auto cache failed for subject $subjectId episode ${ep.episodeId}" }
            tr("缓存失败：{0}", e.message ?: e::class.simpleName).also { errors[key] = it }
        }
    }

    /**
     * 只在沿用的源里挑, 不换源. 以前每集都等满 [AUTO_SELECT_TIMEOUT] 再报「这个源没找到这一集」(其实它几秒就查到了,
     * 手动「选资源」里看得见; 逐集串行的批量于是一直「进行中」), 两处等待:
     * - `fetchSession.awaitCompletion()` 等**全部**数据源, 别的源卡在验证码 / 连不上就一直等 → 改成只等这一个源;
     * - `trySelectFromMediaSources` 里按偏好挑要等选择上下文**全部字段**到齐 (`allFieldsLoaded`: 系列信息 / 完结与否等),
     *   在这里有字段一直是 null, 于是永远挂着 → 不走它, 直接从手动列表用的同一份筛选结果里取: 先取合偏好 (分辨率 / 字幕) 的,
     *   偏好读不出来或没有合的就按筛选排序取第一条.
     * @return 选好的资源; 挑不出来时第二项是给用户看的原因
     */
    private suspend fun selectFromPinned(
        stage: CacheRequestStage.SelectMedia,
        subjectId: Int,
        pinned: String,
    ): Pair<CacheRequestStage.MediaSelected?, String> {
        val name = sourceName(pinned)
        val source = stage.fetchSession.mediaSourceResults.firstOrNull { it.mediaSourceId == pinned }
        if (source == null || source.state.value is MediaSourceFetchState.Disabled) {
            return null to tr("上次使用的来源「{0}」已停用，请点「选资源」更换。", name)
        }
        source.awaitCompletion()
        if (source.state.value !is MediaSourceFetchState.Succeed) {
            return null to tr("来源「{0}」查询失败，请点「选资源」更换。", name)
        }
        return pickFrom(stage, subjectId) { it.mediaSourceId == pinned }?.let { stage.select(it) to "" }
            ?: (null to tr("来源「{0}」没有这一集，请点「选资源」更换。", name))
    }

    /**
     * 没得沿用时按偏好挑. 不走 `tryAutoSelectByPreference`: 它同样要等选择上下文全部字段到齐 (见 [selectFromPinned]), 这里等不到,
     * 每集干等 [AUTO_SELECT_TIMEOUT] 再失败. 改成等网络源都查完 —— 最多 [NO_PIN_WAIT], 有源卡在验证码 / 连不上也不干等,
     * 用已经查到的 —— 再按 [pickFrom] 取.
     */
    private suspend fun selectByOrder(
        stage: CacheRequestStage.SelectMedia,
        subjectId: Int,
    ): Pair<CacheRequestStage.MediaSelected?, String> {
        val remote = stage.fetchSession.mediaSourceResults.filter { it.kind != MediaSourceKind.LocalCache }
        withTimeoutOrNull(NO_PIN_WAIT) { coroutineScope { remote.map { async { it.awaitCompletion() } }.awaitAll() } }
        return pickFrom(stage, subjectId) { true }?.let { stage.select(it) to "" }
            ?: (null to tr("没有找到可缓存的资源，请点「选资源」手动选择。"))
    }

    /**
     * 从手动列表用的同一份筛选结果里取一条 (只看没被排除的, 不要本地缓存): 刚删掉的缓存原来用的那个资源优先 ([recent]),
     * 其次合偏好 (分辨率 / 字幕) 的, 再其次按筛选排序的第一条.
     */
    private suspend fun pickFrom(stage: CacheRequestStage.SelectMedia, subjectId: Int, accept: (Media) -> Boolean): Media? {
        fun List<MaybeExcludedMedia>.usable() = filterIsInstance<MaybeExcludedMedia.Included>().map { it.original }
            .filter { it.kind != MediaSourceKind.LocalCache && accept(it) }
        val all = stage.mediaSelector.filteredCandidates.first().usable()
        val again = recent[subjectId]?.mediaIds.orEmpty()
        return all.firstOrNull { it.mediaId in again }
            ?: withTimeoutOrNull(STATUS_TIMEOUT) { stage.mediaSelector.preferredCandidates.first().usable() }?.firstOrNull()
            ?: all.firstOrNull()
    }

    /**
     * 自动批量要沿用的源: 这部番已缓存的集里, 离这一集最近的那集用的源 (优先它前面的; 集数不是数字的排最后) —— 中途换过源的,
     * 各集跟着离自己最近的那段走. 一集都没缓存过 (或刚全删了) 就看刚删掉的缓存用的源 ([recent]), 再看播放时手动选过的源
     * ([preferredSource]). 都没有 = null, 按偏好自动挑.
     */
    private suspend fun pinnedSourceFor(subjectId: Int, target: EpisodeInfo, caches: List<MediaCache>): String? {
        val t = target.sort.number
        val nearest = usableCaches(caches).minWithOrNull(
            compareBy<MediaCache>(
                { c -> c.metadata.episodeSort.number.let { n -> if (n == null || t == null) 2 else if (n <= t) 0 else 1 } },
                { c -> c.metadata.episodeSort.number.let { n -> if (n == null || t == null) 0f else abs(t - n) } },
            ),
        )
        return nearest?.origin?.mediaSourceId ?: recent[subjectId]?.sourceId ?: preferredSource(subjectId)
    }

    /**
     * 能拿来定沿用源的缓存: 已标记删除 (物理清理还没跑完, 列表里还在) 的不算; 来源是本地缓存源的也不算 ——
     * 本地源只列本机已有的, 沿用它必然找不到新的集.
     */
    private fun usableCaches(caches: List<MediaCache>) =
        caches.filter { !it.isDeleted.value && !isLocalSource(it.origin.mediaSourceId) }

    /** 本地缓存源 (LocalTorrent / LocalWebM3u 共用这个 id). */
    private fun isLocalSource(mediaSourceId: String) = mediaSourceId == MediaCacheManager.LOCAL_FS_MEDIA_SOURCE_ID

    /**
     * 这部番自己「播放时手动选过的源」(手机上挑的不写偏好): 每次手动选都会写进条目偏好; 选的是网页源时另记一份
     * (PreferredWebMediaSource). **条目偏好没存过时 `mediaPreferenceFlow` 读到的是全局默认偏好** —— 那不是这部番选过的,
     * 所以跟全局默认一样的源不认, 再看单记的网页源; 都没有 = 没选过. 播过缓存的集会把本地缓存源记成偏好, 同样不认.
     */
    private suspend fun preferredSource(subjectId: Int): String? = withTimeoutOrNull(STATUS_TIMEOUT) {
        val global = settingsRepository.defaultMediaPreference.flow.first().mediaSourceId
        episodePreferences.mediaPreferenceFlow(subjectId).first().mediaSourceId
            ?.takeIf { it.isNotBlank() && it != MediaPreference.ANY_FILTER && it != global && !isLocalSource(it) }
            ?: episodePreferences.getPreferredWebMediaSource(subjectId).first()?.takeIf { it.isNotBlank() && !isLocalSource(it) }
    }

    private suspend fun sourceName(mediaSourceId: String): String =
        withTimeoutOrNull(STATUS_TIMEOUT) { sourceManager.infoFlowByMediaSourceId(mediaSourceId).first()?.displayName } ?: mediaSourceId

    /** 缓存面板上说一句自动批量会沿用哪个源 (规则见 [pinnedSourceFor]); 没得沿用返回 null, 网页照旧写「按偏好自动挑」. */
    private suspend fun autoHint(subjectId: Int, caches: List<MediaCache>): String? {
        val used = usableCaches(caches).map { it.origin.mediaSourceId }.distinct()
        return when {
            used.size == 1 -> tr("否则沿用之前用的「{0}」，它没有的集不会换源", sourceName(used.single()))
            used.size > 1 -> tr("否则每集沿用离它最近的已缓存集用的源，没有的集不会换源")
            // 网页上接在「有已缓存的合集先用合集，」后面, 后面还跟「；也可以…」: 不带句号
            else -> recent[subjectId]?.let { tr("否则继续使用上次缓存的来源「{0}」；如果该来源没有某一集，不会自动切换到其他来源", sourceName(it.sourceId)) }
                ?: preferredSource(subjectId)?.let { tr("否则继续使用播放时选择的来源「{0}」；如果该来源没有某一集，不会自动切换到其他来源", sourceName(it)) }
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

    /** 没得沿用、按偏好挑时最多等网络源多久 (之后用已经查到的). */
    private val NO_PIN_WAIT = 30.seconds

    /** 手机上打开的那一集多久没人看就关掉请求 (停止查询). */
    private val BROWSE_IDLE = 3.minutes
}
