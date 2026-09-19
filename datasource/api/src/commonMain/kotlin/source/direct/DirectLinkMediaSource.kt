/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.api.source.direct

import kotlinx.coroutines.flow.asFlow
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.MediaExtraFiles
import me.him188.ani.datasources.api.MediaProperties
import me.him188.ani.datasources.api.SubtitleKind
import me.him188.ani.datasources.api.paging.SinglePagePagedSource
import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.datasources.api.source.HttpMediaSource
import me.him188.ani.datasources.api.source.MatchKind
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaMatch
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.Resolution
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.datasources.api.topic.guessFromUrl
import me.him188.ani.datasources.api.topic.guessHttpStreamingFromUrl
import me.him188.ani.datasources.api.topic.titles.RawTitleParser
import me.him188.ani.datasources.api.topic.titles.parse
import me.him188.ani.utils.logging.warn

/**
 * 一条视频直链. 由 [DirectLinkMediaSource.queryLinks] 返回, 由骨架转换为 [DefaultMedia].
 *
 * @param url 视频地址. 通常是 mp4/mkv/m3u8 等可直接交给播放器的地址.
 * @param title 用于展示的标题, 同时用于解析清晰度、字幕语言、字幕类型.
 * @param channel 线路名. 会显示在数据源选择器里 (对应 [MediaProperties.alliance]). 为 `null` 时使用数据源 id.
 * @param id 该资源的稳定标识, 用于拼接 [DefaultMedia.mediaId]. 为 `null` 时使用 [url].
 * @param episodeRange 该资源对应的剧集. 数据源要返回条目的全部剧集, 每条带自己的剧集号;
 * 确实判断不出来时留 `null`, **不要**填成查询请求里的那一集 —— 查询请求里的剧集只是提示,
 * 按集裁剪由数据源选择器完成, 填错会导致切集后本源的资源被整批排除.
 */
class DirectLink(
    val url: String,
    val title: String,
    val channel: String? = null,
    val id: String? = null,
    val size: FileSize = FileSize.Unspecified,
    val publishedTime: Long = 0,
    val episodeRange: EpisodeRange? = null,
    val subtitleLanguageIds: List<String>? = null,
    val resolution: String? = null,
    val subtitleKind: SubtitleKind? = null,
    val subjectName: String? = null,
    val episodeName: String? = null,
    val extraFiles: MediaExtraFiles = MediaExtraFiles.EMPTY,
)

/**
 * 提供视频直链的数据源的骨架实现.
 *
 * 子类只需实现 [queryLinks], 即"给定条目和剧集, 返回若干条视频地址",
 * 剩下的 [DefaultMedia] 组装、标题解析、集数匹配都由本类完成.
 *
 * 适用于任何"调接口拿直链"的站点. 如果站点提供的是 RSS, 直接用 `RssMediaSource` 配置即可, 无需写代码.
 */
abstract class DirectLinkMediaSource : HttpMediaSource() {
    override val kind: MediaSourceKind get() = MediaSourceKind.WEB
    override val location: MediaSourceLocation get() = MediaSourceLocation.Online

    /**
     * 当 [DirectLink.subtitleLanguageIds] 与标题解析都得不到字幕语言时使用.
     * 不能为空, 否则资源会被数据源选择器的默认偏好 (忽略无字幕资源) 过滤掉.
     */
    protected open val defaultSubtitleLanguageIds: List<String> get() = listOf("CHS")

    /**
     * 当 [DirectLink.resolution] 与标题解析都得不到清晰度时使用.
     */
    protected open val defaultResolution: String get() = Resolution.R1080P.toString()

    /**
     * 查询 [request] 对应的所有视频直链. 返回空表示该数据源没有这一集.
     */
    protected abstract suspend fun queryLinks(request: MediaFetchRequest): List<DirectLink>

    final override suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> {
        val medias = queryLinks(query).mapNotNull { link ->
            convertToMedia(link)?.let { MediaMatch(it, MatchKind.EXACT) }
        }
        return SinglePagePagedSource { medias.asFlow() }
    }

    private fun convertToMedia(link: DirectLink): DefaultMedia? {
        val download = ResourceLocation.guessFromUrl(link.url)
            ?: ResourceLocation.guessHttpStreamingFromUrl(link.url)
            ?: if (link.url.startsWith("http", ignoreCase = true)) {
                // 有的站点的直链不带扩展名, 既然数据源明确说这是视频, 就按直链处理
                ResourceLocation.HttpStreamingFile(link.url)
            } else {
                logger.warn("Ignoring unsupported url from " + mediaSourceId + ": " + link.url)
                return null
            }

        val details = RawTitleParser.getDefault().parse(link.title, null)
        return DefaultMedia(
            mediaId = "$mediaSourceId.${link.id ?: link.url}",
            mediaSourceId = mediaSourceId,
            originalUrl = link.url,
            download = download,
            originalTitle = link.title,
            publishedTime = link.publishedTime,
            properties = MediaProperties(
                subjectName = link.subjectName,
                episodeName = link.episodeName,
                subtitleLanguageIds = link.subtitleLanguageIds
                    ?: details.subtitleLanguages.map { it.id }.ifEmpty { defaultSubtitleLanguageIds },
                resolution = link.resolution ?: details.resolution?.toString() ?: defaultResolution,
                alliance = link.channel ?: mediaSourceId,
                size = link.size,
                subtitleKind = link.subtitleKind ?: details.subtitleKind,
            ),
            // 不信标题解析的集号: 按集查询的站点, 标题里常是当季集号 (第四季第 12 集对应系列第 78 集).
            // 也不回退到请求里的那一集, 见 [DirectLink.episodeRange].
            episodeRange = link.episodeRange,
            extraFiles = link.extraFiles,
            location = location,
            kind = kind,
        )
    }
}
