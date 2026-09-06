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

/**
 * 在用户自己的夸克网盘里找视频. 登录在设置里完成 (扫码或填 Cookie), 数据源本身没有参数.
 *
 * 资源的 [Media.download] 是 `https://pan.quark.cn/#/file/<fid>` 形式的占位地址, 直链有效期有限且必须带 Cookie,
 * 所以播放时才由 [me.him188.ani.app.domain.media.resolver.QuarkMediaResolver] 去取.
 */
class QuarkMediaSource(
    private val service: QuarkDriveService,
) : MediaSource {
    override val mediaSourceId: String get() = ID
    override val kind: MediaSourceKind get() = MediaSourceKind.WEB
    override val location: MediaSourceLocation get() = MediaSourceLocation.Online
    override val info: MediaSourceInfo get() = INFO

    private val matcher = QuarkSubjectMatcher(service.browser)

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
        val medias = matcher.match(query).map { MediaMatch(it.toMedia(subjectName), MatchKind.FUZZY) }
        return SinglePagePagedSource { medias.asFlow() }
    }

    private fun QuarkSubjectMatcher.MatchedFile.toMedia(subjectName: String?): Media {
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
                // 已经按条目名与季匹配过, 与 Jellyfin 数据源一样直接标成当前条目
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
    }
}
