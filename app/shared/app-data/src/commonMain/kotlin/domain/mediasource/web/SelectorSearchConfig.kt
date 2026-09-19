/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import io.ktor.http.URLBuilder
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import me.him188.ani.app.domain.mediasource.web.format.SelectorChannelFormat
import me.him188.ani.app.domain.mediasource.web.format.SelectorChannelFormatIndexGrouped
import me.him188.ani.app.domain.mediasource.web.format.SelectorChannelFormatNoChannel
import me.him188.ani.app.domain.mediasource.web.format.SelectorFormatConfig
import me.him188.ani.app.domain.mediasource.web.format.SelectorFormatId
import me.him188.ani.app.domain.mediasource.web.format.SelectorSubjectFormat
import me.him188.ani.app.domain.mediasource.web.format.SelectorSubjectFormatA
import me.him188.ani.app.domain.mediasource.web.format.SelectorSubjectFormatIndexed
import me.him188.ani.app.domain.mediasource.web.format.SelectorSubjectFormatJsonPathIndexed
import me.him188.ani.app.domain.mediasource.web.format.parseOrNull
import me.him188.ani.datasources.api.MediaProperties
import me.him188.ani.datasources.api.topic.Resolution
import me.him188.ani.datasources.api.topic.SubtitleLanguage
import org.intellij.lang.annotations.Language
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Immutable
@Serializable
data class SelectorSearchConfig(
    // Phase 1, search
    val searchUrl: String = "", // required
    val searchUseOnlyFirstWord: Boolean = true,
    val searchRemoveSpecial: Boolean = true,
    /**
     * 搜索时, 使用多少个 subjectName. 至少需要有 1. 排序为:
     * - 主中文名
     * - 日文原名
     * - 其他别名, 无特定顺序
     *
     * @since 4.2
     */
    val searchUseSubjectNamesCount: Int = 1,
    val rawBaseUrl: String = "", // if empty, guess
    /**
     * 两个搜索请求之间的间隔时间
     * @since 4.2
     */
    val requestInterval: @Serializable(DurationAsMillisSerializer::class) Duration = 3.seconds,
    /**
     * 播放 session 搜索缓存的有效期. 实际生效值为此值与用户设置中定义的值的较小者. 为 0 时禁用缓存.
     */
    val searchCacheTtl: @Serializable(DurationAsMillisSerializer::class) Duration = 2.hours,
    // Phase 2, for search result, select subjects
    val subjectFormatId: SelectorFormatId = SelectorSubjectFormatA.id,
    val selectorSubjectFormatA: SelectorSubjectFormatA.Config = SelectorSubjectFormatA.Config(),
    val selectorSubjectFormatIndexed: SelectorSubjectFormatIndexed.Config = SelectorSubjectFormatIndexed.Config(),
    val selectorSubjectFormatJsonPathIndexed: SelectorSubjectFormatJsonPathIndexed.Config = SelectorSubjectFormatJsonPathIndexed.Config(),
    // Phase 3, for each subject, select channels
    val channelFormatId: SelectorFormatId = SelectorChannelFormatNoChannel.id,
    val selectorChannelFormatFlattened: SelectorChannelFormatIndexGrouped.Config = SelectorChannelFormatIndexGrouped.Config(),
    val selectorChannelFormatNoChannel: SelectorChannelFormatNoChannel.Config = SelectorChannelFormatNoChannel.Config(),
//    /**
//     * Regex. Group names:
//     * - `<ch>`: channel name
//     * - `<ep>`: episode name
//     *
//     * E.g. 用于匹配 "线路1 第1集":
//     * ```regex
//     * (?<ch>.+)\s*第(?<ep>\d+)集
//     * ```
//     *
//     * 匹配方式为 find 而不是 matchEntire.
//     * @see SelectorChannelFormat.FLATTENED
//     */
//    val matchChannelFromEpisodeText: String = "",
//    val selectNameFromEpisode: String = "",
//    val selectPlayUrlFromEpisode: String = "",

    /**
     * @see MediaProperties.resolution
     */
    val defaultResolution: Resolution = Resolution.R1080P,
    /**
     * @since 4.9
     */
    val defaultSubtitleLanguage: SubtitleLanguage = SubtitleLanguage.ChineseSimplified,
    /**
     * `mpv`, `vlc`, `exoplayer`, `avkit`
     *
     * 桌面端匹配 `mpv` 或 `vlc` (兼容旧订阅).
     *
     * @since 4.9
     */
    val onlySupportsPlayers: List<String> = emptyList(),

    // Search done. Now we should have Medias.
    val filterByEpisodeSort: Boolean = true,
    val filterBySubjectName: Boolean = true,

    // When playing a media:
    val selectMedia: SelectMediaConfig = SelectMediaConfig(),
    val matchVideo: MatchVideoConfig = MatchVideoConfig(),
    /**
     * 不开浏览器, 直接请求站点自己的取流接口. 默认关闭, 关闭时取流行为与不配置本项时完全一致.
     */
    val resolveVideo: ResolveVideoConfig = ResolveVideoConfig(),
) { // TODO: add Engine version capabilities
    val finalBaseUrl by lazy(LazyThreadSafetyMode.PUBLICATION) {
        rawBaseUrl.ifBlank { guessBaseUrl(searchUrl) }
    }

    @Serializable
    @Suppress("RegExpRedundantEscape")
    data class MatchVideoConfig(
        val enableNestedUrl: Boolean = true,
        @param:Language("regexp")
        val matchNestedUrl: String = """^.+(m3u8|vip|xigua\.php).+\?""",
        @param:Language("regexp")
        val matchVideoUrl: String = """(^http(s)?:\/\/(?!.*http(s)?:\/\/).+((\.mp4)|(\.mkv)|(m3u8)).*(\?.+)?)|(akamaized)|(bilivideo.com)""",
        val cookies: String = """quality=1080""",
        val addHeadersToVideo: VideoHeaders = VideoHeaders(),
    ) {
        val matchNestedUrlRegex by lazy {
            Regex.parseOrNull(matchNestedUrl)
        }
        val matchVideoUrlRegex by lazy {
            Regex.parseOrNull(matchVideoUrl)
        }
    }

    /**
     * **直连取流**: 不开 WebView, 按配置请求站点自己的取流接口, 从响应里取出视频地址.
     *
     * 为什么要有这个: 现代站点 (Next.js / Astro 这类) 的播放页是客户端渲染的, 地址要跑完 JS 才拿得到,
     * 于是只能开 WebView. 但那些站点的 chunk 里有 `?.` / `??`, 要 Chrome 80+ —— 系统 WebView 低于 M74
     * 的电视上解析就是 SyntaxError, 页面根本起不来, 表现为「搜得到但一播就失败」(等满超时后
     * `NO_MATCHING_RESOURCE`), 而同一个源在手机上正常. 见 memory `project-old-webview-source-fail`.
     *
     * 很多这类站点的地址其实来自一个普通接口. 配上之后走 Ktor 直接要, 绕开 WebView, 老机器也能播.
     *
     * 用法 (以稀饭动漫 Next 为例, 播放页 `https://next.xifanacg.com/anime/1360/play/21094?source=xfy2`):
     * ```
     * matchPageUrl      = /play/(?<episodeId>\d+)
     * requestUrl        = https://xxx.supabase.co/functions/v1/issue-web-playback
     * method            = POST
     * requestHeaders    = Content-Type: application/json
     * requestBody       = {"action":"fallback","episode_id":{episodeId}}
     * selectUrlJsonPath = $.url
     * ```
     *
     * [requestUrl] 为空 = 不启用, 照旧走 WebView.
     */
    @Serializable
    data class ResolveVideoConfig(
        /**
         * 取流接口地址. 空 = 不启用整个直连取流.
         *
         * 可以用 `{名字}` 引用 [matchPageUrl] 抽出的变量, 以及内置的 `{pageUrl}` (播放页完整地址).
         */
        val requestUrl: String = "",
        /**
         * 从播放页地址里抽变量, **命名分组的名字就是变量名**. 为空时只有内置的 `{pageUrl}` 可用.
         *
         * 用 find 而不是 matchEntire, 所以只写要抽的那一小段就行.
         */
        @param:Language("regexp")
        val matchPageUrl: String = "",
        /** `GET` 或 `POST`. */
        val method: String = "GET",
        /** 每行一个 `Name: value`; 同样支持 `{名字}`. */
        val requestHeaders: String = "",
        /** 请求体 (仅 POST); 同样支持 `{名字}`. */
        val requestBody: String = "",
        /**
         * 从响应 JSON 里取视频地址. 与 [selectUrlRegex] 二选一, 两个都填时先试这个.
         */
        @param:Language("jsonpath")
        val selectUrlJsonPath: String = "",
        /**
         * 从响应文本里取视频地址 (响应不是 JSON 时用). 有命名分组 `<v>` 时取它, 否则取整个匹配.
         */
        @param:Language("regexp")
        val selectUrlRegex: String = "",
    ) {
        val enabled: Boolean get() = requestUrl.isNotBlank()

        val matchPageUrlRegex by lazy {
            if (matchPageUrl.isBlank()) null else Regex.parseOrNull(matchPageUrl)
        }
        val selectUrlRegexCompiled by lazy {
            if (selectUrlRegex.isBlank()) null else Regex.parseOrNull(selectUrlRegex)
        }
    }

    @Serializable
    data class VideoHeaders(
        val referer: String = "",
        val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3",
    )

    @Serializable
    data class SelectMediaConfig(
        val distinguishSubjectName: Boolean = true,
        val distinguishChannelName: Boolean = true,
    )


//    val matchChannelFromEpisodeRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
//        matchChannelFromEpisodeText.toRegex()
//    }

    // These classes are nested to limit namespace

    @Stable
    companion object {
        @Stable
        val Empty = SelectorSearchConfig()

        fun guessBaseUrl(searchUrl: String): String {
            return kotlin.runCatching {
                URLBuilder(searchUrl).apply {
                    pathSegments = emptyList()
                    parameters.clear()
                }.toString()
            }.getOrElse {
                val schemaIndex = searchUrl.indexOf("//")
                if (schemaIndex == -1) {
                    searchUrl.removeSuffix("/")
                } else {
                    val slashIndex = searchUrl.indexOf('/', startIndex = schemaIndex + 2)
                    if (slashIndex == -1) {
                        searchUrl.removeSuffix("/")
                    } else {
                        searchUrl.substring(0, slashIndex)
                    }
                }
            }
        }
    }
}

/**
 * 获取该 [SelectorSubjectFormat] 的配置 [C].
 */
fun <C : SelectorFormatConfig> SelectorSearchConfig.getFormatConfig(format: SelectorSubjectFormat<C>): C {
    @Suppress("UNCHECKED_CAST")
    return when (format) {
        SelectorSubjectFormatA -> selectorSubjectFormatA as C
        SelectorSubjectFormatIndexed -> selectorSubjectFormatIndexed as C
        SelectorSubjectFormatJsonPathIndexed -> selectorSubjectFormatJsonPathIndexed as C
    }
}

/**
 * 获取该 [SelectorChannelFormat] 的配置 [C].
 */
fun <C : SelectorFormatConfig> SelectorSearchConfig.getFormatConfig(format: SelectorChannelFormat<C>): C {
    @Suppress("UNCHECKED_CAST")
    return when (format) {
        SelectorChannelFormatIndexGrouped -> selectorChannelFormatFlattened as C
        SelectorChannelFormatNoChannel -> selectorChannelFormatNoChannel as C
    }
}


private object DurationAsMillisSerializer : KSerializer<Duration> {
    override val descriptor = PrimitiveSerialDescriptor("Duration", PrimitiveKind.LONG)
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Duration) {
        Long.serializer().serialize(encoder, value.inWholeMilliseconds)
    }

    override fun deserialize(decoder: Decoder): Duration = Long.serializer().deserialize(decoder).milliseconds
}
