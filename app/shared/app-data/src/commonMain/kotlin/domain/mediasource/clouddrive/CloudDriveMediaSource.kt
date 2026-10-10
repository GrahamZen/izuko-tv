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
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import me.him188.ani.app.domain.media.fetch.SelfLimitedMediaSource
import me.him188.ani.app.domain.mediasource.codec.DefaultMediaSourceCodec
import me.him188.ani.app.domain.mediasource.codec.DontForgetToRegisterCodec
import me.him188.ani.app.domain.mediasource.codec.MediaSourceArguments
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaProperties
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
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.Resolution
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.datasources.api.topic.titles.RawTitleParser
import me.him188.ani.datasources.api.topic.titles.parse
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 「网盘」数据源的参数: 网盘的协议 ([CloudDriveProtocol]) 就写在这里, 随订阅或导入下发.
 *
 * @property name 数据源名, 空表示用协议里的网盘名
 */
@OptIn(DontForgetToRegisterCodec::class)
@Serializable
data class CloudDriveArguments(
    override val name: String = "",
    val description: String = "",
    val iconUrl: String = "",
    val protocol: CloudDriveProtocol,
    override val tier: MediaSourceTier = MediaSourceTier.Fallback,
) : MediaSourceArguments

object CloudDriveMediaSourceCodec : DefaultMediaSourceCodec<CloudDriveArguments>(
    CloudDriveMediaSource.FactoryId,
    CloudDriveArguments::class,
    currentVersion = 1,
    CloudDriveArguments.serializer(),
)

/**
 * 在用户自己的网盘里找视频. 网盘的接入方式来自参数里的协议, 登录在设置里完成 (扫码或填 Cookie); 没登录时什么也不给 (不算失败).
 *
 * 资源的 [Media.download] 是占位地址 (见 [DrivePlaceholders.fileUri]), 直链有效期有限且常要带 Cookie,
 * 所以播放时才由 [me.him188.ani.app.domain.media.resolver.CloudDriveMediaResolver] 去取.
 *
 * 除了按条目名搜到的, 还给出用户在 Web 控制台手动指定的文件与文件夹 ([CloudDriveService.picksOf]): 单独指定的文件优先,
 * 其次是指定文件夹里认出的, 再其次是自动匹配的; 同一个文件只给一次. 手动指定的算精确匹配.
 *
 * 播过的那一集所在的文件夹会记给条目 (见 [CloudDriveService.matchRememberedFolder]): 之后先列它, 里面有要的这一集就不再全盘搜索
 * (追番时第二集起只列一次文件夹); 没有 (新的一集还没放进去、文件挪走了) 时照常搜.
 */
class CloudDriveMediaSource(
    private val service: CloudDriveService,
    private val arguments: CloudDriveArguments,
) : MediaSource, SelfLimitedMediaSource {
    override val mediaSourceId: String get() = service.protocol.driveMediaSourceId
    override val kind: MediaSourceKind get() = MediaSourceKind.WEB
    override val location: MediaSourceLocation get() = MediaSourceLocation.Online
    override val info: MediaSourceInfo = infoOf(arguments)

    private val matcher = DriveSubjectMatcher(service.browser, service.episodeNumbering)

    override suspend fun checkConnection(): ConnectionStatus {
        if (!service.account.first().isLoggedIn) return ConnectionStatus.FAILED
        return try {
            service.refreshAccount()
            ConnectionStatus.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ConnectionStatus.FAILED
        }
    }

    override suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> {
        // 订阅下发后每个用户都有这个源, 没登录的不算失败
        if (!service.account.first().isLoggedIn) return SinglePagePagedSource { emptyList<MediaMatch>().asFlow() }
        val subjectName = query.subjectNames.firstOrNull { it.isNotBlank() } ?: query.subjectNameCN
        val subjectId = query.subjectId.toIntOrNull()
        val picked = pickedMatches(query)
        val remembered = subjectId?.let { service.matchRememberedFolder(query, it) }.orEmpty()
        val auto = if (remembered.any { it.episode == query.episodeSort || it.episode == query.episodeEp }) {
            logger.info { "Cloud drive ${service.driveId}: episode ${query.episodeSort} of subject ${query.subjectId} is in the remembered folder, skipping search" }
            emptyList()
        } else if (!service.protocol.supportsSearch) {
            emptyList()
        } else {
            try {
                matcher.match(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudDriveAuthException) {
                throw e
            } catch (e: Throwable) {
                // 搜索失败时手动指定的与记下的文件夹里的照样给; 都没有才算这个源失败
                if (picked.isEmpty() && remembered.isEmpty()) throw e
                logger.warn(e) { "Cloud drive ${service.driveId} search failed, returning picked and remembered files only" }
                emptyList()
            }
        }
        if (subjectId != null) service.noteMatches(subjectId, remembered + auto)
        val seen = HashSet<String>()
        val medias = picked.filter { seen.add(it.file.fid) }.map { MediaMatch(it.toMedia(subjectName), MatchKind.EXACT) } +
                (remembered + auto).filter { seen.add(it.file.fid) }.map { MediaMatch(it.toMedia(subjectName), MatchKind.FUZZY) }
        return SinglePagePagedSource { medias.asFlow() }
    }

    /** 用户手动指定的: 单独指定的文件在前 (盖过文件夹里认出的集号), 再是指定文件夹里认出的. */
    private suspend fun pickedMatches(query: MediaFetchRequest): List<DriveSubjectMatcher.MatchedFile> {
        val subjectId = query.subjectId.toIntOrNull() ?: return emptyList()
        val picks = service.picksOf(subjectId)
        if (picks.isEmpty) return emptyList()
        val files = picks.files.map { picked ->
            val file = DriveFile(fid = picked.fid, fileName = picked.fileName, parentFid = picked.parentFid, size = picked.size, isVideo = true)
            DriveSubjectMatcher.MatchedFile(file, emptyList(), EpisodeSort(picked.episode))
        }
        return files + picks.folders.flatMap { service.matchPickedFolder(query, it) }
    }

    private fun DriveSubjectMatcher.MatchedFile.toMedia(subjectName: String?): Media =
        mediaFor(service, file, episode, subjectName, folders)

    class Factory(
        private val registry: CloudDriveRegistry,
    ) : MediaSourceFactory {
        override val factoryId: FactoryId get() = FactoryId
        override val info: MediaSourceInfo get() = INFO
        override val allowMultipleInstances: Boolean get() = true

        override fun create(
            mediaSourceId: String,
            config: MediaSourceConfig,
            client: ScopedHttpClient,
        ): MediaSource {
            // 参数坏了也不能抛: 会让整个数据源列表建不出来
            val arguments = runCatching { config.deserializeArgumentsOrNull(CloudDriveArguments.serializer()) }.getOrNull()
                ?: return UnconfiguredMediaSource(mediaSourceId)
            return CloudDriveMediaSource(registry.serviceFor(arguments.protocol), arguments)
        }
    }

    /** 参数里没有可用的协议: 什么也不给. */
    private class UnconfiguredMediaSource(override val mediaSourceId: String) : MediaSource {
        override val kind: MediaSourceKind get() = MediaSourceKind.WEB
        override val location: MediaSourceLocation get() = MediaSourceLocation.Online
        override val info: MediaSourceInfo get() = INFO
        override suspend fun checkConnection(): ConnectionStatus = ConnectionStatus.FAILED
        override suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> =
            SinglePagePagedSource { emptyList<MediaMatch>().asFlow() }
    }

    companion object {
        val FactoryId = FactoryId("cloud-drive")

        /** 数据源类型本身的说明 (没有具体网盘时). */
        val INFO = MediaSourceInfo(
            displayName = "网盘",
            description = "在你的网盘里找视频. 网盘的接入方式由数据源配置给出, 登录在设置里完成",
        )

        private val logger = logger<CloudDriveMediaSource>()

        /**
         * 数据源实际报出的 [MediaSource.mediaSourceId]: 一般就是保存时的 [saveId]; 网盘源固定用协议给的 id
         * ([CloudDriveProtocol.driveMediaSourceId]), 不随订阅或导入时分配的 id 变. 按数据源 id 查层级的地方
         * (`MediaSourceManager.mediaSourceTiersFlow`) 要用它, 订阅给网盘源定的层级才对得上选源时的资源.
         */
        fun reportedMediaSourceId(arguments: MediaSourceArguments, saveId: String): String =
            (arguments as? CloudDriveArguments)?.protocol?.driveMediaSourceId ?: saveId

        internal fun infoOf(arguments: CloudDriveArguments): MediaSourceInfo {
            val protocol = arguments.protocol
            return MediaSourceInfo(
                displayName = arguments.name.ifBlank { protocol.name },
                description = arguments.description.ifBlank { null },
                websiteUrl = protocol.websiteUrl.ifBlank { null },
                iconUrl = arguments.iconUrl.ifBlank { protocol.iconUrl }.ifBlank { null },
            )
        }

        /**
         * 网盘里的视频 [file] 做成自己网盘数据源的资源 (当作第 [episode] 集). 资源 id 只看文件, 所以同一个文件不论怎么对上的都是同一条.
         *
         * @param folders 文件所在的文件夹名 (从外到里), 标题里带上最里面那层
         */
        fun mediaFor(
            service: CloudDriveService,
            file: DriveFile,
            episode: EpisodeSort,
            subjectName: String?,
            folders: List<String> = emptyList(),
        ): Media {
            val protocol = service.protocol
            val details = RawTitleParser.getDefault().parse((folders + file.fileName).joinToString(" "))
            val resolution = details.resolution
                ?: Resolution.entries.lastOrNull { it.size <= file.videoHeight && file.videoHeight > 0 }
                ?: Resolution.R1080P
            val sourceId = protocol.driveMediaSourceId
            val mediaId = "$sourceId.${file.fid}"
            DriveVideoBitrates.record(mediaId, file)
            return DefaultMedia(
                mediaId = mediaId,
                mediaSourceId = sourceId,
                originalUrl = service.placeholders.folderUrl(file.parentFid),
                download = ResourceLocation.HttpStreamingFile(service.placeholders.fileUri(file.fid)),
                // 很多文件名只有集号 (01.mp4), 带上所在文件夹才看得出是什么
                originalTitle = folders.lastOrNull()?.let { "$it / ${file.fileName}" } ?: file.fileName,
                publishedTime = file.updatedAt,
                properties = MediaProperties(
                    // 已经按条目名与季匹配过 (或是用户指定的), 与 Jellyfin 数据源一样直接标成当前条目
                    subjectName = subjectName,
                    episodeName = null,
                    subtitleLanguageIds = details.subtitleLanguages.map { it.id }.ifEmpty { listOf("CHS") },
                    resolution = resolution.toString(),
                    alliance = protocol.name,
                    size = if (file.size > 0) file.size.bytes else FileSize.Unspecified,
                    subtitleKind = details.subtitleKind,
                ),
                episodeRange = EpisodeRange.single(episode),
                location = MediaSourceLocation.Online,
                kind = MediaSourceKind.WEB,
            )
        }
    }
}
