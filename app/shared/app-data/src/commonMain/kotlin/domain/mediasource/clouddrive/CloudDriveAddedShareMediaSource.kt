/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

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
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.models.preference.CloudDriveAddedShares
import me.him188.ani.app.data.models.preference.DriveAddedShare
import me.him188.ani.app.data.models.preference.DriveAddedShareFile
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.media.fetch.SelfLimitedMediaSource
import me.him188.ani.app.domain.mediasource.codec.DefaultMediaSourceCodec
import me.him188.ani.app.domain.mediasource.codec.DontForgetToRegisterCodec
import me.him188.ani.app.domain.mediasource.codec.MediaSourceArguments
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
import me.him188.ani.datasources.api.source.MediaSourceTier
import me.him188.ani.datasources.api.source.deserializeArgumentsOrNull
import me.him188.ani.datasources.api.source.serializeArguments
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.Uuid
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.time.Duration.Companion.seconds

/**
 * 「我添加的分享」数据源的参数.
 *
 * @property drive 哪个网盘的分享 (网盘 id, 见 [CloudDriveProtocol.id])
 */
@OptIn(DontForgetToRegisterCodec::class)
@Serializable
data class CloudDriveAddedShareArguments(
    val drive: String,
    override val name: String = CloudDriveAddedShareMediaSource.DISPLAY_NAME,
    override val tier: MediaSourceTier = MediaSourceTier.Fallback,
) : MediaSourceArguments

object CloudDriveAddedShareMediaSourceCodec : DefaultMediaSourceCodec<CloudDriveAddedShareArguments>(
    CloudDriveAddedShareMediaSource.FactoryId,
    CloudDriveAddedShareArguments::class,
    currentVersion = 1,
    CloudDriveAddedShareArguments.serializer(),
)

/**
 * 分享里的一个视频文件.
 *
 * @property folders 从分享根到文件所在文件夹的名字
 * @property episode 认出的集; 认不出或不是这个条目的为 null
 */
class DriveShareFileInfo(
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
class DriveShareInspection internal constructor(
    val link: DriveShareLink,
    val title: String,
    val episodes: List<EpisodeSort>,
    val currentFiles: List<DriveShareFileInfo>,
    val files: List<DriveShareFileInfo>,
    internal val entries: Map<String, DriveShareReader.Entry>,
)

/**
 * 这次运行里最近一次打开一个已添加的分享的结果, 控制台列出已添加的分享时显示.
 *
 * @property videos 分享里的视频文件数; 打不开时为 0
 * @property error 打不开的原因; 打开了为 null
 * @property savedCopies 分享打不开或已经空了时, 记下的剧集里自己网盘还有转存副本、照常能播的集数
 */
class DriveShareReadStatus(val videos: Int, val error: String?, val savedCopies: Int)

/**
 * 用户给条目添加的一个网盘 ([drive]) 的分享: 记在整机设置里 ([CloudDriveAddedShares]), 播放这个条目时由 [CloudDriveAddedShareMediaSource] 打开找剧集.
 *
 * 每次打开分享时记下对上的剧集 ([DriveAddedShare.files]). 分享后来被分享者清空、被网盘屏蔽或打不开时, 记下的剧集里已经转存到
 * 自己网盘 ([savedCopies], 即「Izuko 转存」) 的照常给出: 播放时转存那一步会直接用同名同大小的副本, 不再碰分享.
 */
class CloudDriveAddedShareService internal constructor(
    private val settings: Settings<CloudDriveAddedShares>,
    val drive: CloudDriveService,
    browser: DriveShareBrowser = drive.shareBrowser,
    numbering: TmdbEpisodeNumbering = drive.episodeNumbering,
    private val savedCopies: suspend () -> List<DriveFile> = drive::savedShareCopies,
) {
    private val driveId get() = drive.driveId

    internal val reader = DriveShareReader(browser, numbering)

    private val readStatus = MutableStateFlow<Map<String, DriveShareReadStatus>>(emptyMap())

    suspend fun sharesOf(subjectId: Int): List<DriveAddedShare> = settings.flow.first().of(driveId).of(subjectId)

    /** 这次运行里还没打开过时为 null. */
    fun readStatusOf(shareId: String): DriveShareReadStatus? = readStatus.value[shareId]

    private fun setReadStatus(shareId: String, status: DriveShareReadStatus) {
        readStatus.update { it + (shareId to status) }
    }

    private suspend fun updateShares(update: me.him188.ani.app.data.models.preference.DriveAddedShares.() -> me.him188.ani.app.data.models.preference.DriveAddedShares) {
        settings.update { with(driveId, of(driveId).update()) }
    }

    /**
     * 打开 [link] 并按 [request] 的条目与这一集对一遍. 分享失效或提取码不对时抛 [CloudDriveShareUnavailableException].
     */
    suspend fun inspect(request: MediaFetchRequest, link: DriveShareLink): DriveShareInspection {
        val contents = try {
            reader.read(link.shareId, link.passcode)
        } catch (e: CloudDriveShareUnavailableException) {
            setReadStatus(link.shareId, DriveShareReadStatus(0, e.message.orEmpty(), 0))
            throw e
        }
        setReadStatus(link.shareId, DriveShareReadStatus(contents.videos.size, null, 0))
        val matches = reader.match(request, FoundShare(link.shareId, link.passcode, contents.title), contents.videos)
        val episodeOf = matches.associate { it.file.fid to it.episode }
        fun info(entry: DriveShareReader.Entry) = DriveShareFileInfo(
            fid = entry.file.fid,
            fileName = entry.file.fileName,
            folders = entry.folders,
            size = entry.file.size,
            episode = episodeOf[entry.file.fid],
        )
        val currentFids = matches.filter { it.isEpisodeOf(request) }.map { it.file.fid }.toSet()
        return DriveShareInspection(
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
    fun mediaOf(inspection: DriveShareInspection, fid: String, mediaSourceId: String, request: MediaFetchRequest): Media? {
        val entry = inspection.entries[fid] ?: return null
        val share = FoundShare(inspection.link.shareId, inspection.link.passcode, inspection.title)
        val subjectName = request.subjectNames.firstOrNull { it.isNotBlank() } ?: request.subjectNameCN
        return DriveShareMatch(share, entry.file, entry.folders, request.episodeSort)
            .toShareMedia(drive.placeholders, mediaSourceId, CloudDriveAddedShareMediaSource.DISPLAY_NAME, subjectName)
    }

    /** 记到条目 [subjectId] 下; 已经记过同一个分享时更新提取码与标题. */
    suspend fun add(subjectId: Int, inspection: DriveShareInspection) {
        val share = DriveAddedShare(
            shareId = inspection.link.shareId,
            passcode = inspection.link.passcode,
            title = inspection.title,
            addedAtMillis = currentTimeMillis(),
        )
        updateShares { plus(subjectId, share) }
        logger.info { "Added $driveId share ${share.shareId} (${share.title}) to subject $subjectId: ${inspection.episodes.size} episodes" }
    }

    /**
     * 记下「分享里的文件 [fid] 是第 [episode] 集」(用户在认不出集号时手动挑的); 分享还没记到 [subjectId] 下时一起记下.
     */
    suspend fun pick(subjectId: Int, inspection: DriveShareInspection, fid: String, episode: EpisodeSort) {
        val share = DriveAddedShare(
            shareId = inspection.link.shareId,
            passcode = inspection.link.passcode,
            title = inspection.title,
            addedAtMillis = currentTimeMillis(),
            picks = mapOf(fid to episode.toString()),
        )
        updateShares { plus(subjectId, share) }
        logger.info { "Picked file $fid of $driveId share ${share.shareId} as episode $episode of subject $subjectId" }
    }

    suspend fun remove(subjectId: Int, shareId: String) {
        updateShares { minus(subjectId, shareId) }
        logger.info { "Removed $driveId share $shareId from subject $subjectId" }
    }

    /**
     * [request] 这个条目添加过的分享里, 对得上的全部剧集. 打不开的分享跳过 (记日志).
     *
     * 播过的那一集所在的分享文件夹会记给数据源 [mediaSourceId] 与条目 (同分享搜索, 见 [CloudDriveService.rememberedShareOf]):
     * 之后先只列它, 有要的这一集就不再把各个分享整个列一遍 (加的是大合集时每集从头列很慢); 没有 (新的一集还没更新) 时照常全列.
     */
    internal suspend fun matches(request: MediaFetchRequest, mediaSourceId: String): List<DriveShareMatch> {
        val subjectId = request.subjectId.toIntOrNull() ?: return emptyList()
        val shares = sharesOf(subjectId)
        if (shares.isEmpty()) return emptyList()
        val remembered = rememberedMatches(subjectId, request, shares, mediaSourceId)
        val matches = if (remembered != null && remembered.any { it.isEpisodeOf(request) }) {
            logger.info { "Added $driveId share: episode ${request.episodeSort} of subject $subjectId is in the remembered folder, skipping full listing" }
            remembered
        } else {
            val semaphore = Semaphore(SHARE_CONCURRENCY)
            coroutineScope {
                shares.map { share ->
                    async { semaphore.withPermit { matchesOf(subjectId, request, share) } }
                }.awaitAll().flatten()
            }
        }
        // 播放时据此记下所在的文件夹 (见 CloudDriveService.resolveSharePlayback)
        drive.noteShareMatches(mediaSourceId, subjectId, matches)
        return matches
    }

    /**
     * 记下的分享文件夹里对得上的剧集. 没记过、记的分享已不在这个条目下、文件夹打不开 (分享失效的同时忘掉) 时为 null;
     * 当前这一集有手动指定的文件而它不在这个文件夹里时也为 null —— 手动指定的盖过自动认出的, 要全列才找得到它.
     */
    private suspend fun rememberedMatches(
        subjectId: Int,
        request: MediaFetchRequest,
        shares: List<DriveAddedShare>,
        mediaSourceId: String,
    ): List<DriveShareMatch>? {
        val remembered = drive.rememberedShareOf(mediaSourceId, subjectId) ?: return null
        val share = shares.firstOrNull { it.shareId == remembered.shareId } ?: return null
        val entries = try {
            reader.readFolder(share.shareId, share.passcode, remembered.folderId, remembered.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudDriveShareUnavailableException) {
            logger.info { "Remembered folder of added $driveId share ${share.shareId} unavailable: ${e.message}" }
            drive.forgetRememberedShare(mediaSourceId, subjectId)
            return null
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to read the remembered folder of added $driveId share ${share.shareId}" }
            return null
        }
        val found = FoundShare(share.shareId, share.passcode, share.title)
        val listed = entries.associateBy { it.file.fid }
        val pickedElsewhere = share.picks.any { (fid, episode) ->
            fid !in listed && EpisodeSort(episode).let { it == request.episodeSort || (request.episodeEp != null && it == request.episodeEp) }
        }
        if (pickedElsewhere) return null
        val picked = share.picks.mapNotNull { (fid, episode) ->
            val entry = listed[fid] ?: return@mapNotNull null
            DriveShareMatch(found, entry.file, entry.folders, EpisodeSort(episode))
        }
        val pickedFids = picked.mapTo(HashSet()) { it.file.fid }
        val matches = reader.match(request, found, entries).filterNot { it.file.fid in pickedFids } + picked
        // 只列了一个文件夹: 记下的剧集并进去, 不冲掉别的文件夹里的 (分享清空后靠它们找转存副本)
        remember(subjectId, share, matches, merge = true)
        return matches
    }

    private suspend fun matchesOf(subjectId: Int, request: MediaFetchRequest, share: DriveAddedShare): List<DriveShareMatch> {
        val found = FoundShare(share.shareId, share.passcode, share.title)
        val videos = try {
            reader.read(share.shareId, share.passcode).videos
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudDriveShareUnavailableException) {
            logger.info { "Added $driveId share ${share.shareId} unavailable: ${e.message}" }
            return savedCopiesOf(found, share, e.message.orEmpty())
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to read added $driveId share ${share.shareId}" }
            return savedCopiesOf(found, share, e.message ?: e.toString())
        }
        if (videos.isEmpty()) {
            logger.info { "Added $driveId share ${share.shareId} has no videos now" }
            return savedCopiesOf(found, share, error = null)
        }
        setReadStatus(share.shareId, DriveShareReadStatus(videos.size, null, 0))
        // 手动指定的盖过自动认出的
        val picked = share.picks.mapNotNull { (fid, episode) ->
            val entry = videos.firstOrNull { it.file.fid == fid } ?: return@mapNotNull null
            DriveShareMatch(found, entry.file, entry.folders, EpisodeSort(episode))
        }
        val pickedFids = picked.mapTo(HashSet()) { it.file.fid }
        val matches = reader.match(request, found, videos).filterNot { it.file.fid in pickedFids } + picked
        remember(subjectId, share, matches)
        return matches
    }

    /** 记下对上的剧集 (有变化才写). 一集都没对上时保留原来记的; [merge] = 并进原来记的 (只列了分享的一部分时). */
    private suspend fun remember(subjectId: Int, share: DriveAddedShare, matches: List<DriveShareMatch>, merge: Boolean = false) {
        val found = matches.map { match ->
            DriveAddedShareFile(
                fid = match.file.fid,
                fileName = match.file.fileName,
                size = match.file.size,
                shareToken = match.file.shareToken,
                folders = match.folders,
                episode = match.episode.toString(),
                parentFid = match.file.parentFid,
            )
        }
        val foundFids = found.mapTo(HashSet()) { it.fid }
        val files = (if (merge) found + share.files.filterNot { it.fid in foundFids } else found).take(MAX_REMEMBERED_FILES)
        if (found.isEmpty() || files == share.files) return
        updateShares { withFiles(subjectId, share.shareId, files) }
    }

    /**
     * 分享打不开 ([error]) 或已经空了: 记下的剧集里, 自己网盘有同名同大小副本的照常给 (同 [CloudDriveService.resolveSharePlayback] 复用副本的规则).
     */
    private suspend fun savedCopiesOf(found: FoundShare, share: DriveAddedShare, error: String?): List<DriveShareMatch> {
        val copies = if (share.files.isEmpty()) {
            emptyList()
        } else {
            try {
                savedCopies()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn { "Failed to list saved $driveId copies: $e" }
                emptyList()
            }
        }
        val matches = share.files
            .filter { file -> copies.any { it.fileName == file.fileName && (file.size <= 0 || it.size == file.size) } }
            .map { file ->
                val shareFile = DriveFile(
                    fid = file.fid,
                    fileName = file.fileName,
                    size = file.size,
                    isVideo = true,
                    shareToken = file.shareToken,
                    parentFid = file.parentFid,
                )
                DriveShareMatch(found, shareFile, file.folders, EpisodeSort(file.episode))
            }
        if (share.files.isNotEmpty()) {
            logger.info { "Added $driveId share ${share.shareId}: ${matches.size} of ${share.files.size} remembered episodes have saved copies" }
        }
        setReadStatus(share.shareId, DriveShareReadStatus(0, error, matches.size))
        return matches
    }

    private companion object {
        private val logger = logger<CloudDriveAddedShareService>()
        private const val SHARE_CONCURRENCY = 2
        private const val MAX_REMEMBERED_FILES = 200
    }
}

/**
 * 「我添加的分享」: 用户在 Web 控制台给条目添加的某个网盘的分享链接 (见 [CloudDriveAddedShareService]).
 *
 * 查询时只读地打开这个条目的分享、对出剧集 (不需要登录); 播放时转存到用户自己的网盘再取地址, 与分享搜索源相同.
 */
class CloudDriveAddedShareMediaSource(
    override val mediaSourceId: String,
    private val driveId: String,
    private val registry: CloudDriveRegistry,
) : MediaSource, SelfLimitedMediaSource {
    override val kind: MediaSourceKind get() = MediaSourceKind.WEB
    override val location: MediaSourceLocation get() = MediaSourceLocation.Online
    /** 各网盘的「我添加的分享」都用同一个 [INFO]: 播放时据此认出它 (见 PauseMediaFetchWhilePlayingExtension). */
    override val info: MediaSourceInfo get() = INFO

    override suspend fun checkConnection(): ConnectionStatus = ConnectionStatus.SUCCESS

    override suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> {
        val service = registry.awaitAddedShareService(driveId) ?: return SinglePagePagedSource { emptyList<MediaMatch>().asFlow() }
        val subjectName = query.subjectNames.firstOrNull { it.isNotBlank() } ?: query.subjectNameCN
        // 分享是用户亲手加给这个条目的
        val medias = service.matches(query, mediaSourceId)
            .map { MediaMatch(it.toShareMedia(service.drive.placeholders, mediaSourceId, DISPLAY_NAME, subjectName), MatchKind.EXACT) }
        return SinglePagePagedSource { medias.asFlow() }
    }

    class Factory(
        private val registry: CloudDriveRegistry,
    ) : MediaSourceFactory {
        override val factoryId: FactoryId get() = FactoryId
        override val allowMultipleInstances: Boolean get() = true
        override val info: MediaSourceInfo get() = INFO

        override fun create(
            mediaSourceId: String,
            config: MediaSourceConfig,
            client: ScopedHttpClient,
        ): MediaSource {
            // 网盘在查询时再找: 建实例时保存的数据源可能还没读完
            val driveId = config.deserializeArgumentsOrNull(CloudDriveAddedShareArguments.serializer())?.drive.orEmpty()
            return CloudDriveAddedShareMediaSource(mediaSourceId, driveId, registry)
        }
    }

    companion object {
        val FactoryId = FactoryId("cloud-drive-added-shares")

        const val DISPLAY_NAME = "我添加的分享"

        val INFO = MediaSourceInfo(
            displayName = DISPLAY_NAME,
            description = "在 Web 控制台的播放器页给番添加的网盘分享链接, 播放时转存到自己的网盘",
        )
    }
}

/**
 * 确保网盘 [driveId] 有一个启用着的「我添加的分享」数据源 (第一次添加分享时调用).
 *
 * 新建或重新启用后, 等到 [MediaSourceManager.allInstances] 里真有它才返回 (最多 [INSTANCE_VISIBLE_TIMEOUT]):
 * 写入要等实例列表重建完才看得见, 返回后马上重建搜索会话的话, 新会话取到的数据源列表里还没有它.
 *
 * @return 数据源的 instance id 与这次是否新建或重新启用了它 (是的话, 正在进行的搜索里没有它)
 */
suspend fun ensureAddedShareMediaSource(manager: MediaSourceManager, driveId: String): Pair<String, Boolean> {
    val existing = manager.allInstances.first().firstOrNull {
        it.factoryId == CloudDriveAddedShareMediaSource.FactoryId &&
                runCatching { it.config.deserializeArgumentsOrNull(CloudDriveAddedShareArguments.serializer())?.drive }.getOrNull() == driveId
    }
    if (existing != null && existing.isEnabled) return existing.instanceId to false
    val instanceId = existing?.instanceId ?: Uuid.randomString()
    if (existing != null) {
        manager.setEnabled(instanceId, true)
    } else {
        val config = MediaSourceConfig(
            serializedArguments = MediaSourceConfig.serializeArguments(
                CloudDriveAddedShareArguments.serializer(),
                CloudDriveAddedShareArguments(driveId),
            ),
        )
        manager.addInstance(instanceId, instanceId, CloudDriveAddedShareMediaSource.FactoryId, config)
    }
    awaitInstance(manager, instanceId)
    return instanceId to true
}

/**
 * 确保网盘 [driveId] 自己网盘的数据源启用着 (控制台给条目指定了网盘里的位置时调用, 没有这个源指定的就用不上).
 * 网盘没有配置 (没有这个数据源) 时返回 null.
 *
 * @return 数据源的 instance id 与这次是否重新启用了它 (是的话, 正在进行的搜索里没有它)
 */
suspend fun ensureCloudDriveMediaSourceEnabled(manager: MediaSourceManager, driveId: String): Pair<String, Boolean>? {
    val existing = manager.allInstances.first().firstOrNull {
        it.factoryId == CloudDriveMediaSource.FactoryId &&
                runCatching { it.config.deserializeArgumentsOrNull(CloudDriveArguments.serializer())?.protocol?.id }.getOrNull() == driveId
    } ?: return null
    if (existing.isEnabled) return existing.instanceId to false
    manager.setEnabled(existing.instanceId, true)
    awaitInstance(manager, existing.instanceId)
    return existing.instanceId to true
}

private suspend fun awaitInstance(manager: MediaSourceManager, instanceId: String) {
    withTimeoutOrNull(INSTANCE_VISIBLE_TIMEOUT) {
        manager.allInstances.first { list -> list.any { it.instanceId == instanceId && it.isEnabled } }
    }
}

private val INSTANCE_VISIBLE_TIMEOUT = 5.seconds
