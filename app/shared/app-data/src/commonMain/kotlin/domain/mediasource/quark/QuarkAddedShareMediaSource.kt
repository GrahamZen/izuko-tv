/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.quark

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.preference.QuarkAddedShare
import me.him188.ani.app.data.models.preference.QuarkAddedShareFile
import me.him188.ani.app.data.models.preference.QuarkAddedShares
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.paging.SinglePagePagedSource
import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.datasources.api.source.ConnectionStatus
import me.him188.ani.datasources.api.source.FactoryId
import me.him188.ani.datasources.api.source.MatchKind
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaMatch
import me.him188.ani.datasources.api.source.MediaSource
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.MediaSourceFactory
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.Uuid
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.time.Duration.Companion.seconds

/**
 * 用户粘贴的一个夸克分享链接.
 *
 * @property passcode 提取码, 没有为空串
 */
data class QuarkShareLink(val shareId: String, val passcode: String)

object QuarkShareLinks {
    private val LINK = Regex("""(?:https?://)?pan\.quark\.cn/s/([0-9A-Za-z]+)(\S*)""")
    private val URL_PASSCODE = Regex("""[?&#]pwd=([0-9A-Za-z]+)""")
    private val TEXT_PASSCODE = Regex("""(?:提取码|密码|访问码|pwd)\s*[:：=]?\s*([0-9A-Za-z]{4,8})(?![0-9A-Za-z])""", RegexOption.IGNORE_CASE)

    /**
     * 从一段文字 (分享站「复制链接」给的那种, 可能带标题与「提取码：xxxx」) 里取出全部夸克分享链接, 按出现顺序去重.
     * 提取码先看链接自己的 `?pwd=`, 没有就在链接后面到下一个链接之前的文字里找「提取码 / 密码 / 访问码」.
     */
    fun parse(text: String): List<QuarkShareLink> {
        val matches = LINK.findAll(text).toList()
        val links = LinkedHashMap<String, QuarkShareLink>()
        matches.forEachIndexed { index, match ->
            val shareId = match.groupValues[1]
            val tailEnd = matches.getOrNull(index + 1)?.range?.first ?: text.length
            val tail = text.substring(match.range.last + 1, tailEnd)
            val rest = match.groupValues[2]
            // 「提取码」可能紧贴在链接后面 (中间没有空白), 那样会落在 rest 里
            val passcode = URL_PASSCODE.find(rest)?.groupValues?.get(1)
                ?: TEXT_PASSCODE.find(rest + tail)?.groupValues?.get(1)
                ?: ""
            links.putIfAbsent(shareId, QuarkShareLink(shareId, passcode))
        }
        return links.values.toList()
    }
}

/**
 * 分享里的一个视频文件.
 *
 * @property folders 从分享根到文件所在文件夹的名字
 * @property episode 认出的集; 认不出或不是这个条目的为 null
 */
class QuarkShareFileInfo(
    val fid: String,
    val fileName: String,
    val folders: List<String>,
    val size: Long,
    val episode: EpisodeSort?,
)

/**
 * 打开一个分享、按当前条目对一遍的结果 (还没记下).
 *
 * @property episodes 认出的这个条目的集, 按集号排, 去重
 * @property currentFiles 正好是当前这一集的文件 (可能有几个版本)
 * @property files 分享里全部视频文件; 一集都没认出时给用户手动挑
 */
class QuarkShareInspection internal constructor(
    val link: QuarkShareLink,
    val title: String,
    val episodes: List<EpisodeSort>,
    val currentFiles: List<QuarkShareFileInfo>,
    val files: List<QuarkShareFileInfo>,
    internal val entries: Map<String, QuarkShareReader.Entry>,
)

/**
 * 这次运行里最近一次打开一个已添加的分享的结果, 控制台列出已添加的分享时显示.
 *
 * @property videos 分享里的视频文件数; 打不开时为 0
 * @property error 打不开的原因; 打开了为 null
 * @property savedCopies 分享打不开或已经空了时, 记下的剧集里自己网盘还有转存副本、照常能播的集数
 */
class QuarkShareReadStatus(val videos: Int, val error: String?, val savedCopies: Int)

/**
 * 用户给条目添加的夸克分享: 记在整机设置里 ([QuarkAddedShares]), 播放这个条目时由 [QuarkAddedShareMediaSource] 打开找剧集.
 *
 * 每次打开分享时记下对上的剧集 ([QuarkAddedShare.files]). 分享后来被分享者清空、被夸克屏蔽或打不开时, 记下的剧集里已经转存到
 * 自己网盘 ([savedCopies], 即「Izuko 转存」) 的照常给出: 播放时转存那一步会直接用同名同大小的副本, 不再碰分享.
 */
class QuarkAddedShareService internal constructor(
    private val settings: Settings<QuarkAddedShares>,
    browser: QuarkShareBrowser,
    numbering: TmdbEpisodeNumbering = TmdbEpisodeNumbering.None,
    private val savedCopies: suspend () -> List<QuarkFile>,
) {
    constructor(settings: Settings<QuarkAddedShares>, drive: QuarkDriveService) :
            this(settings, drive.shareBrowser, drive.episodeNumbering, drive::savedShareCopies)

    internal val reader = QuarkShareReader(browser, numbering)

    private val readStatus = MutableStateFlow<Map<String, QuarkShareReadStatus>>(emptyMap())

    suspend fun sharesOf(subjectId: Int): List<QuarkAddedShare> = settings.flow.first().of(subjectId)

    /** 这次运行里还没打开过时为 null. */
    fun readStatusOf(shareId: String): QuarkShareReadStatus? = readStatus.value[shareId]

    private fun setReadStatus(shareId: String, status: QuarkShareReadStatus) {
        readStatus.update { it + (shareId to status) }
    }

    /**
     * 打开 [link] 并按 [request] 的条目与这一集对一遍. 分享失效或提取码不对时抛 [QuarkShareUnavailableException].
     */
    suspend fun inspect(request: MediaFetchRequest, link: QuarkShareLink): QuarkShareInspection {
        val contents = try {
            reader.read(link.shareId, link.passcode)
        } catch (e: QuarkShareUnavailableException) {
            setReadStatus(link.shareId, QuarkShareReadStatus(0, e.message.orEmpty(), 0))
            throw e
        }
        setReadStatus(link.shareId, QuarkShareReadStatus(contents.videos.size, null, 0))
        val matches = reader.match(request, FoundShare(link.shareId, link.passcode, contents.title), contents.videos)
        val episodeOf = matches.associate { it.file.fid to it.episode }
        fun info(entry: QuarkShareReader.Entry) = QuarkShareFileInfo(
            fid = entry.file.fid,
            fileName = entry.file.fileName,
            folders = entry.folders,
            size = entry.file.size,
            episode = episodeOf[entry.file.fid],
        )
        val currentFids = matches.filter { it.isEpisodeOf(request) }.map { it.file.fid }.toSet()
        return QuarkShareInspection(
            link = link,
            title = contents.title,
            episodes = matches.map { it.episode }.distinct().sorted(),
            currentFiles = contents.videos.filter { it.file.fid in currentFids }.map(::info),
            files = contents.videos.map(::info),
            entries = contents.videos.associateBy { it.file.fid },
        )
    }

    /**
     * 把 [inspection] 里的文件 [fid] 做成可以直接播放的资源 (当作 [request] 这一集). 不是这个分享里的文件时返回 null.
     */
    fun mediaOf(inspection: QuarkShareInspection, fid: String, mediaSourceId: String, request: MediaFetchRequest): Media? {
        val entry = inspection.entries[fid] ?: return null
        val share = FoundShare(inspection.link.shareId, inspection.link.passcode, inspection.title)
        val subjectName = request.subjectNames.firstOrNull { it.isNotBlank() } ?: request.subjectNameCN
        return QuarkShareMatch(share, entry.file, entry.folders, request.episodeSort)
            .toShareMedia(mediaSourceId, QuarkAddedShareMediaSource.INFO.displayName, subjectName)
    }

    /** 记到条目 [subjectId] 下; 已经记过同一个分享时更新提取码与标题. */
    suspend fun add(subjectId: Int, inspection: QuarkShareInspection) {
        val share = QuarkAddedShare(
            shareId = inspection.link.shareId,
            passcode = inspection.link.passcode,
            title = inspection.title,
            addedAtMillis = currentTimeMillis(),
        )
        settings.update { plus(subjectId, share) }
        logger.info { "Added Quark share ${share.shareId} (${share.title}) to subject $subjectId: ${inspection.episodes.size} episodes" }
    }

    /**
     * 记下「分享里的文件 [fid] 是第 [episode] 集」(用户在认不出集号时手动挑的); 分享还没记到 [subjectId] 下时一起记下.
     */
    suspend fun pick(subjectId: Int, inspection: QuarkShareInspection, fid: String, episode: EpisodeSort) {
        val share = QuarkAddedShare(
            shareId = inspection.link.shareId,
            passcode = inspection.link.passcode,
            title = inspection.title,
            addedAtMillis = currentTimeMillis(),
            picks = mapOf(fid to episode.toString()),
        )
        settings.update { plus(subjectId, share) }
        logger.info { "Picked file $fid of Quark share ${share.shareId} as episode $episode of subject $subjectId" }
    }

    suspend fun remove(subjectId: Int, shareId: String) {
        settings.update { minus(subjectId, shareId) }
        logger.info { "Removed Quark share $shareId from subject $subjectId" }
    }

    /** [request] 这个条目添加过的分享里, 对得上的全部剧集. 打不开的分享跳过 (记日志). */
    internal suspend fun matches(request: MediaFetchRequest): List<QuarkShareMatch> {
        val subjectId = request.subjectId.toIntOrNull() ?: return emptyList()
        val shares = sharesOf(subjectId)
        if (shares.isEmpty()) return emptyList()
        val semaphore = Semaphore(SHARE_CONCURRENCY)
        return coroutineScope {
            shares.map { share ->
                async { semaphore.withPermit { matchesOf(subjectId, request, share) } }
            }.awaitAll().flatten()
        }
    }

    private suspend fun matchesOf(subjectId: Int, request: MediaFetchRequest, share: QuarkAddedShare): List<QuarkShareMatch> {
        val found = FoundShare(share.shareId, share.passcode, share.title)
        val videos = try {
            reader.read(share.shareId, share.passcode).videos
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuarkShareUnavailableException) {
            logger.info { "Added Quark share ${share.shareId} unavailable: ${e.message}" }
            return savedCopiesOf(found, share, e.message.orEmpty())
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to read added Quark share ${share.shareId}" }
            return savedCopiesOf(found, share, e.message ?: e.toString())
        }
        if (videos.isEmpty()) {
            logger.info { "Added Quark share ${share.shareId} has no videos now" }
            return savedCopiesOf(found, share, error = null)
        }
        setReadStatus(share.shareId, QuarkShareReadStatus(videos.size, null, 0))
        // 手动指定的盖过自动认出的
        val picked = share.picks.mapNotNull { (fid, episode) ->
            val entry = videos.firstOrNull { it.file.fid == fid } ?: return@mapNotNull null
            QuarkShareMatch(found, entry.file, entry.folders, EpisodeSort(episode))
        }
        val pickedFids = picked.mapTo(HashSet()) { it.file.fid }
        val matches = reader.match(request, found, videos).filterNot { it.file.fid in pickedFids } + picked
        remember(subjectId, share, matches)
        return matches
    }

    /** 记下对上的剧集 (有变化才写). 一集都没对上时保留原来记的. */
    private suspend fun remember(subjectId: Int, share: QuarkAddedShare, matches: List<QuarkShareMatch>) {
        val files = matches.take(MAX_REMEMBERED_FILES).map { match ->
            QuarkAddedShareFile(
                fid = match.file.fid,
                fileName = match.file.fileName,
                size = match.file.size,
                shareFidToken = match.file.shareFidToken,
                folders = match.folders,
                episode = match.episode.toString(),
                parentFid = match.file.parentFid,
            )
        }
        if (files.isEmpty() || files == share.files) return
        settings.update { withFiles(subjectId, share.shareId, files) }
    }

    /**
     * 分享打不开 ([error]) 或已经空了: 记下的剧集里, 自己网盘有同名同大小副本的照常给 (同 [QuarkDriveService.resolveSharePlayback] 复用副本的规则).
     */
    private suspend fun savedCopiesOf(found: FoundShare, share: QuarkAddedShare, error: String?): List<QuarkShareMatch> {
        val copies = if (share.files.isEmpty()) {
            emptyList()
        } else {
            try {
                savedCopies()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn { "Failed to list saved Quark copies: $e" }
                emptyList()
            }
        }
        val matches = share.files
            .filter { file -> copies.any { it.fileName == file.fileName && (file.size <= 0 || it.size == file.size) } }
            .map { file ->
                val shareFile = QuarkShareFile(
                    fid = file.fid,
                    fileName = file.fileName,
                    size = file.size,
                    category = "video",
                    shareFidToken = file.shareFidToken,
                    parentFid = file.parentFid,
                )
                QuarkShareMatch(found, shareFile, file.folders, EpisodeSort(file.episode))
            }
        if (share.files.isNotEmpty()) {
            logger.info { "Added Quark share ${share.shareId}: ${matches.size} of ${share.files.size} remembered episodes have saved copies" }
        }
        setReadStatus(share.shareId, QuarkShareReadStatus(0, error, matches.size))
        return matches
    }

    private companion object {
        private val logger = logger<QuarkAddedShareService>()
        private const val SHARE_CONCURRENCY = 2
        private const val MAX_REMEMBERED_FILES = 200
    }
}

/** 这个文件是不是 [request] 要的这一集 (集号按条目内的序号或系列绝对集号). */
internal fun QuarkShareMatch.isEpisodeOf(request: MediaFetchRequest): Boolean =
    episode == request.episodeSort || (request.episodeEp != null && episode == request.episodeEp)

/**
 * 「我添加的分享」: 用户在 Web 控制台给条目添加的夸克分享链接 (见 [QuarkAddedShareService]).
 *
 * 查询时只读地打开这个条目的分享、对出剧集 (不需要登录); 播放时转存到用户自己的夸克网盘再取地址, 与分享搜索源相同.
 */
class QuarkAddedShareMediaSource(
    override val mediaSourceId: String,
    private val service: QuarkAddedShareService,
) : MediaSource {
    companion object {
        val FactoryId = FactoryId("quark-added-share")

        val INFO = MediaSourceInfo(
            displayName = "我添加的分享",
            description = "在 Web 控制台的播放器页给番添加的夸克分享链接, 播放时转存到自己的夸克网盘",
            iconUrl = "https://pan.quark.cn/favicon.ico",
        )
    }

    override val kind: MediaSourceKind get() = MediaSourceKind.WEB
    override val location: MediaSourceLocation get() = MediaSourceLocation.Online
    override val info: MediaSourceInfo get() = INFO

    override suspend fun checkConnection(): ConnectionStatus = ConnectionStatus.SUCCESS

    override suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> {
        val subjectName = query.subjectNames.firstOrNull { it.isNotBlank() } ?: query.subjectNameCN
        // 分享是用户亲手加给这个条目的
        val medias = service.matches(query)
            .map { MediaMatch(it.toShareMedia(mediaSourceId, INFO.displayName, subjectName), MatchKind.EXACT) }
        return SinglePagePagedSource { medias.asFlow() }
    }

    class Factory(
        private val service: QuarkAddedShareService,
    ) : MediaSourceFactory {
        override val factoryId: FactoryId get() = FactoryId
        override val allowMultipleInstances: Boolean get() = false
        override val info: MediaSourceInfo get() = INFO

        override fun create(
            mediaSourceId: String,
            config: MediaSourceConfig,
            client: ScopedHttpClient,
        ): MediaSource = QuarkAddedShareMediaSource(mediaSourceId, service)
    }
}

/**
 * 确保有一个启用着的「我添加的分享」数据源 (第一次添加分享时调用).
 *
 * 新建或重新启用后, 等到 [MediaSourceManager.allInstances] 里真有它才返回 (最多 [INSTANCE_VISIBLE_TIMEOUT]):
 * 写入要等实例列表重建完才看得见, 返回后马上重建搜索会话的话, 新会话取到的数据源列表里还没有它.
 *
 * @return 数据源的 instance id 与这次是否新建或重新启用了它 (是的话, 正在进行的搜索里没有它)
 */
suspend fun ensureAddedShareMediaSource(manager: MediaSourceManager): Pair<String, Boolean> {
    val existing = manager.allInstances.first().firstOrNull { it.factoryId == QuarkAddedShareMediaSource.FactoryId }
    if (existing != null && existing.isEnabled) return existing.instanceId to false
    val instanceId = existing?.instanceId ?: Uuid.randomString()
    if (existing != null) {
        manager.setEnabled(instanceId, true)
    } else {
        manager.addInstance(instanceId, instanceId, QuarkAddedShareMediaSource.FactoryId, MediaSourceConfig.Default)
    }
    withTimeoutOrNull(INSTANCE_VISIBLE_TIMEOUT) {
        manager.allInstances.first { list -> list.any { it.instanceId == instanceId && it.isEnabled } }
    }
    return instanceId to true
}

private val INSTANCE_VISIBLE_TIMEOUT = 5.seconds
