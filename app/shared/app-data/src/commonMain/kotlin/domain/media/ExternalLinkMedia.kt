/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import me.him188.ani.app.domain.media.selector.MediaSelector
import me.him188.ani.app.videoplayer.link.PlaybackLinks
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaProperties
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.currentTimeMillis
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.UriMediaData

/**
 * 一个可以直接播的地址, 连同取流要带的请求头: 设备之间交接用 (一起看的房间里互相给, 手机送给电视)。
 *
 * @property headers 取流要带的请求头, 常见的是 `Referer` 与 `User-Agent`
 * @property title 显示用的标题
 */
@Serializable
data class ExternalLink(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val title: String = "",
)

/**
 * 别处交来的播放地址 ([ExternalLink]) 对应的 [Media], 公共组件: 谁手上有一个能播的地址、想让这台设备直接播它, 都用这个。
 *
 * 与 [DroppedFileMedia] 同类: 不来自本机任何数据源, 不进 [MediaSelector] 的候选, 由交来的一方直接选上
 * ([MediaSelector.selectAutomatically] / [MediaSelector.selectTemporarily]), 随这一集的播放会话结束而失效。
 * 用它播就不需要本机有那个数据源, 也不走「开站点页面、解析出地址」那一步。
 *
 * 各处对它的处理 (都是一行钩子):
 * - 播放: `HttpStreamingMediaResolver` 取 [headersOf] 带上请求头 (这类地址常绑 Referer / User-Agent, 少了就 403);
 * - 不记成这部番的偏好源与播放线索 (`ObserveWebMediaSourcePreferenceExtension` / `PlaybackLineageExtension`): 它不是数据源, 记了下次会越过真正的源;
 * - 播放出错怎么办由交来的一方定 ([Origin.onError], 见 [recoverFromError]; `PlayerLoadErrorHandler` 里先问这里);
 *   出错的站点记一阵, 自动换上交来的地址之前问 [failedHereRecently];
 * - 界面上的数据源名按 [originIdOf] 给 (ui-mediaselect `externalLinkSourceName`, 控制台 `RemotePlayerHandle.sourceName`)。
 *
 * 反过来, 本机正在播的地址能不能交给别的设备见 [shareableOf]; 收别处交来的地址之前先问 [accepts]。
 */
object ExternalLinkMedia {
    /** 播放出错时 */
    enum class OnError {
        /** 留着报错不换: 用户点名要播这个地址, 换掉只会让人以为没生效 */
        KEEP,

        /** 回到没有这个地址时的样子: 它顶掉的那条资源 ([create] 的 `replaced`) 接着用, 没有就照常选源 */
        FALL_BACK,
    }

    /**
     * 谁交来的。
     *
     * @property id 写进 [Media.mediaSourceId] (`external-link:<id>`), 界面按它给数据源名
     */
    class Origin(val id: String, val onError: OnError)

    private class Entry(val headers: Map<String, String>, val origin: Origin, val replaced: Media?)

    /**
     * [Media] 里没有放请求头的地方, 按 mediaId 记在这里, 解析播放时取回。只留最近几条:
     * 交来的地址一般当场就播, 记着是为了播放失败、重新装资源这类会再用到的时刻。
     */
    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<String, Entry>()

    /** 播放出错过的地址的主机名 → 出错的时刻, 见 [failedHereRecently] */
    private val failedHosts = LinkedHashMap<String, Long>()

    /**
     * @param replaced 选上它时顶掉的那条资源 (还没装好的自动选择); 它出错而 [Origin.onError] 是 [OnError.FALL_BACK] 时换回去
     */
    fun create(origin: Origin, link: ExternalLink, replaced: Media? = null): Media {
        val mediaId = "$MEDIA_SOURCE_ID_PREFIX${origin.id}:${link.url}"
        synchronized(lock) {
            entries.remove(mediaId)
            if (entries.size >= MAX_REMEMBERED) entries.keys.firstOrNull()?.let { entries.remove(it) }
            entries[mediaId] = Entry(link.headers.filterValues { it.isNotBlank() }, origin, replaced)
        }
        return DefaultMedia(
            mediaId = mediaId,
            mediaSourceId = MEDIA_SOURCE_ID_PREFIX + origin.id,
            originalUrl = link.url,
            download = ResourceLocation.HttpStreamingFile(link.url),
            originalTitle = link.title.ifBlank { titleOf(link.url) },
            publishedTime = 0,
            properties = MediaProperties(
                subjectName = null,
                episodeName = null,
                subtitleLanguageIds = emptyList(),
                resolution = "",
                alliance = "",
                size = FileSize.Unspecified,
                subtitleKind = null,
            ),
            // 交来的就是要播的那一集, 不参与按集筛选
            episodeRange = null,
            location = MediaSourceLocation.Online,
            kind = MediaSourceKind.WEB,
        )
    }

    fun isExternalLink(media: Media): Boolean = media.mediaSourceId.startsWith(MEDIA_SOURCE_ID_PREFIX)

    /** [Origin.id]; 不是交来的地址时为 null */
    fun originIdOf(media: Media): String? =
        media.mediaSourceId.takeIf { it.startsWith(MEDIA_SOURCE_ID_PREFIX) }?.removePrefix(MEDIA_SOURCE_ID_PREFIX)

    /** 取流要带的请求头; 不是交来的地址时为空 */
    fun headersOf(media: Media): Map<String, String> = entryOf(media)?.headers.orEmpty()

    /**
     * 播放交来的地址 [failed] 出错了 (见 [OnError]): [OnError.FALL_BACK] 时把它顶掉的那条资源换回来, 没有就清掉选择、[selectAsUsual] 照常选源。
     * 途中选择已经变了 (用户自己选了别的) 就不动。
     */
    suspend fun recoverFromError(failed: Media, selector: MediaSelector, selectAsUsual: suspend () -> Unit) {
        if (isExternalLink(failed)) rememberFailure(failed.originalUrl, currentTimeMillis())
        val entry = entryOf(failed)
        if (entry?.origin?.onError != OnError.FALL_BACK) {
            logger.info { "Player errored on an external link (${originIdOf(failed)}), keeping it" }
            return
        }
        val replaced = entry.replaced
        if (replaced != null) {
            if (selector.selectAutomatically(replaced, expectedSelection = failed) != null) {
                logger.info { "Player errored on an external link (${entry.origin.id}), back to ${replaced.mediaId}" }
            }
            return
        }
        if (selector.selected.value !== failed) return
        logger.info { "Player errored on an external link (${entry.origin.id}), selecting as usual" }
        selector.unselect()
        selectAsUsual()
    }

    /**
     * 本机正在播的 [media] (交给播放器的是 [data]) 能交给别的设备直接播的地址; 不能交的为 null:
     * - 只交网页类数据源解析出来的地址 ([ResourceLocation.WebVideo]: 站点公开的页面), 以及别处交来、本机正在播的 (可以再转交)。
     *   网盘 (地址绑着账号, 多个地方同时取还可能被风控)、自建的媒体服务器 (地址里带访问凭据)、BT、本地与缓存的文件都不交;
     * - 地址要是公网的 http(s): 本机代理 (HLS 去广告)、局域网里的别人够不着;
     * - 带 `Cookie` / `Authorization` 的不交 (登录信息);
     * - 太长的不交 (房间消息有大小上限)。
     */
    fun shareableOf(media: Media, data: MediaData?): ExternalLink? {
        if (media.download !is ResourceLocation.WebVideo && !isExternalLink(media)) return null
        val uriData = data as? UriMediaData ?: return null
        val link = ExternalLink(
            PlaybackLinks.currentUriOf(uriData.uri),
            uriData.headers.filterValues { it.isNotBlank() },
            media.originalTitle.take(MAX_TITLE_LENGTH),
        )
        return link.takeIf { accepts(it) }
    }

    /**
     * 别处交来的 [link] 能不能收 (交出去之前也按这个查): 公网的 http(s) 地址 —— 不替别人去取本机、局域网里的东西;
     * 不带 `Cookie` / `Authorization`; 地址与请求头的大小在限度内。收的一方在 [create] 之前先问。
     */
    fun accepts(link: ExternalLink): Boolean =
        link.url.length <= MAX_URL_LENGTH && isPublicHttpUrl(link.url) &&
            link.headers.size <= MAX_HEADERS && link.headers.entries.sumOf { it.key.length + it.value.length } <= MAX_HEADER_CHARS &&
            link.headers.keys.none { it.equals("Cookie", ignoreCase = true) || it.equals("Authorization", ignoreCase = true) }

    /**
     * [link] 所在的站点 (主机名) 最近 [FAILED_HOST_MEMORY_MILLIS] 内在本机播放出错过 (见 [recoverFromError])。
     * 同一个站点交来的别的地址多半也播不了 (本机的网络连不上它、地址绑了交来那一方的 IP), 想自动换上交来的地址的一方先问这里, 是就不试。
     */
    fun failedHereRecently(link: ExternalLink, nowMillis: Long = currentTimeMillis()): Boolean {
        val host = hostOf(link.url) ?: return false
        synchronized(lock) {
            val failedAt = failedHosts[host] ?: return false
            if (nowMillis - failedAt < FAILED_HOST_MEMORY_MILLIS) return true
            failedHosts.remove(host)
            return false
        }
    }

    internal fun rememberFailure(url: String, atMillis: Long) {
        val host = hostOf(url) ?: return
        synchronized(lock) {
            failedHosts.remove(host)
            if (failedHosts.size >= MAX_REMEMBERED) failedHosts.keys.firstOrNull()?.let { failedHosts.remove(it) }
            failedHosts[host] = atMillis
        }
    }

    private fun entryOf(media: Media): Entry? = if (isExternalLink(media)) synchronized(lock) { entries[media.mediaId] } else null

    /** 地址最后一段 (去掉 query) 当标题; 取不出就用主机名 */
    private fun titleOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        return path.substringAfterLast('/').ifBlank { url.substringAfter("://").substringBefore('/').ifBlank { url } }
    }

    private val logger = logger<ExternalLinkMedia>()
    private const val MEDIA_SOURCE_ID_PREFIX = "external-link:"
    private const val MAX_REMEMBERED = 16
    private const val MAX_URL_LENGTH = 2_000
    private const val MAX_HEADERS = 8
    private const val MAX_HEADER_CHARS = 1_000
    private const val MAX_TITLE_LENGTH = 120
    private const val FAILED_HOST_MEMORY_MILLIS = 30 * 60_000L
}

/** 地址里的主机名 (小写, 不带端口与用户信息); 取不出时为 null */
private fun hostOf(url: String): String? {
    val authority = url.substringAfter("://", missingDelimiterValue = "").substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@')
    val host = if (authority.startsWith("[")) authority.substringBefore(']').removePrefix("[") else authority.substringBefore(':')
    return host.lowercase().ifEmpty { null }
}

/**
 * [url] 是不是别的设备也够得着的 http(s) 地址: 主机不是本机 (回环)、不是局域网 / 链路本地的字面地址、不是 `.local` 名字, 地址里也不带用户名密码。
 * 只看字面, 不做 DNS 查询。
 */
internal fun isPublicHttpUrl(url: String): Boolean {
    val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return false
    val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isEmpty() || '@' in authority) return false
    val host = (if (authority.startsWith("[")) authority.substringBefore(']').removePrefix("[") else authority.substringBefore(':')).lowercase()
    if (host.isEmpty() || host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost")) return false
    if (':' in host) {
        // IPv6 字面: 回环、链路本地 (fe80::/10)、唯一本地 (fc00::/7)
        return !(host == "::1" || host == "::" || host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") ||
            host.startsWith("feb") || host.startsWith("fc") || host.startsWith("fd"))
    }
    val octets = host.split('.').map { it.toIntOrNull() }
    if (octets.size == 4 && octets.all { it != null && it in 0..255 }) {
        val (a, b) = octets.map { it!! }
        return !(a == 127 || a == 10 || a == 0 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254))
    }
    return true
}
