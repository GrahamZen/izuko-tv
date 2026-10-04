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
import kotlinx.coroutines.flow.asFlow
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
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.Resolution
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.datasources.api.topic.titles.RawTitleParser
import me.him188.ani.datasources.api.topic.titles.parse
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 在用户自己的夸克网盘里找视频. 登录在设置里完成 (扫码或填 Cookie), 数据源本身没有参数.
 *
 * 资源的 [Media.download] 是 `https://pan.quark.cn/#/file/<fid>` 形式的占位地址, 直链有效期有限且必须带 Cookie,
 * 所以播放时才由 [me.him188.ani.app.domain.media.resolver.QuarkMediaResolver] 去取.
 *
 * 除了按条目名搜到的, 还给出用户在 Web 控制台手动指定的文件与文件夹 ([QuarkDriveService.picksOf]): 单独指定的文件优先,
 * 其次是指定文件夹里认出的, 再其次是自动匹配的; 同一个文件只给一次. 手动指定的算精确匹配.
 *
 * 播过的那一集所在的文件夹会记给条目 (见 [QuarkDriveService.matchRememberedFolder]): 之后先列它, 里面有要的这一集就不再全盘搜索
 * (追番时第二集起只列一次文件夹); 没有 (新的一集还没放进去、文件挪走了) 时照常搜.
 */
class QuarkMediaSource(
    private val service: QuarkDriveService,
) : MediaSource {
    override val mediaSourceId: String get() = ID
    override val kind: MediaSourceKind get() = MediaSourceKind.WEB
    override val location: MediaSourceLocation get() = MediaSourceLocation.Online
    override val info: MediaSourceInfo get() = INFO

    private val matcher = QuarkSubjectMatcher(service.browser, service.episodeNumbering)

    override suspend fun checkConnection(): ConnectionStatus {
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
        service.requireLoggedIn()
        val subjectName = query.subjectNames.firstOrNull { it.isNotBlank() } ?: query.subjectNameCN
        val subjectId = query.subjectId.toIntOrNull()
        val picked = pickedMatches(query)
        val remembered = subjectId?.let { service.matchRememberedFolder(query, it) }.orEmpty()
        val auto = if (remembered.any { it.episode == query.episodeSort || it.episode == query.episodeEp }) {
            logger.info { "Quark drive: episode ${query.episodeSort} of subject ${query.subjectId} is in the remembered folder, skipping search" }
            emptyList()
        } else {
            try {
                matcher.match(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuarkAuthException) {
                throw e
            } catch (e: Throwable) {
                // 搜索失败时手动指定的与记下的文件夹里的照样给; 都没有才算这个源失败
                if (picked.isEmpty() && remembered.isEmpty()) throw e
                logger.warn(e) { "Quark drive search failed, returning picked and remembered files only" }
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
    private suspend fun pickedMatches(query: MediaFetchRequest): List<QuarkSubjectMatcher.MatchedFile> {
        val subjectId = query.subjectId.toIntOrNull() ?: return emptyList()
        val picks = service.picksOf(subjectId)
        if (picks.isEmpty) return emptyList()
        val files = picks.files.map { picked ->
            val file = QuarkFile(fid = picked.fid, fileName = picked.fileName, parentFid = picked.parentFid, size = picked.size, category = "video")
            QuarkSubjectMatcher.MatchedFile(file, emptyList(), EpisodeSort(picked.episode))
        }
        return files + picks.folders.flatMap { service.matchPickedFolder(query, it) }
    }

    private fun QuarkSubjectMatcher.MatchedFile.toMedia(subjectName: String?): Media = mediaFor(file, episode, subjectName, folders)

    class Factory(
        private val service: QuarkDriveService,
    ) : MediaSourceFactory {
        override val factoryId: FactoryId get() = FACTORY_ID
        override val info: MediaSourceInfo get() = INFO
        override val allowMultipleInstances: Boolean get() = false

        override fun create(
            mediaSourceId: String,
            config: MediaSourceConfig,
            client: ScopedHttpClient,
        ): MediaSource = QuarkMediaSource(service)
    }

    companion object {
        const val ID = "quark-drive"
        val FACTORY_ID = FactoryId(ID)

        /**
         * 占位地址的前缀. `HttpStreamingFile` 只收 http(s) 地址; 用页面锚点, 万一被直接打开也只是夸克首页.
         */
        private const val URI_PREFIX = "https://pan.quark.cn/#/file/"

        val INFO = MediaSourceInfo(
            displayName = "夸克网盘",
            description = "在你的夸克网盘里找视频, 先在设置里登录夸克",
            websiteUrl = "https://pan.quark.cn",
            iconUrl = "https://pan.quark.cn/favicon.ico",
        )

        fun uriOf(fileId: String): String = URI_PREFIX + fileId

        /**
         * [uriOf] 的逆运算, 不是夸克资源时返回 null.
         */
        fun fileIdOf(uri: String): String? = uri.takeIf { it.startsWith(URI_PREFIX) }?.removePrefix(URI_PREFIX)

        private val logger = logger<QuarkMediaSource>()

        /**
         * 网盘里的视频 [file] 做成这个数据源的资源 (当作第 [episode] 集). 资源 id 只看文件, 所以同一个文件不论怎么对上的都是同一条.
         *
         * @param folders 文件所在的文件夹名 (从外到里), 标题里带上最里面那层
         */
        fun mediaFor(file: QuarkFile, episode: EpisodeSort, subjectName: String?, folders: List<String> = emptyList()): Media {
            val details = RawTitleParser.getDefault().parse((folders + file.fileName).joinToString(" "))
            val resolution = details.resolution
                ?: Resolution.entries.lastOrNull { it.size <= file.videoHeight && file.videoHeight > 0 }
                ?: Resolution.R1080P
            return DefaultMedia(
                mediaId = "$ID.${file.fid}",
                mediaSourceId = ID,
                originalUrl = "https://pan.quark.cn/list#/list/all/${file.parentFid}",
                download = ResourceLocation.HttpStreamingFile(uriOf(file.fid)),
                // 很多文件名只有集号 (01.mp4), 带上所在文件夹才看得出是什么
                originalTitle = folders.lastOrNull()?.let { "$it / ${file.fileName}" } ?: file.fileName,
                publishedTime = file.updatedAt,
                properties = MediaProperties(
                    // 已经按条目名与季匹配过 (或是用户指定的), 与 Jellyfin 数据源一样直接标成当前条目
                    subjectName = subjectName,
                    episodeName = null,
                    subtitleLanguageIds = details.subtitleLanguages.map { it.id }.ifEmpty { listOf("CHS") },
                    resolution = resolution.toString(),
                    alliance = INFO.displayName,
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
