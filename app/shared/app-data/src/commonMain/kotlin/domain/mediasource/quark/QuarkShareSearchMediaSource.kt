/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.quark

import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.foundation.DeviceBrowserUserAgentHolder
import me.him188.ani.app.domain.foundation.RequestUserAgentAttribute
import me.him188.ani.app.domain.mediasource.codec.DefaultMediaSourceCodec
import me.him188.ani.app.domain.mediasource.codec.DontForgetToRegisterCodec
import me.him188.ani.app.domain.mediasource.codec.MediaSourceArguments
import me.him188.ani.app.domain.mediasource.codec.MediaSourceTier
import me.him188.ani.app.domain.mediasource.directapi.ResponseFormat
import me.him188.ani.app.domain.mediasource.directapi.parseResponse
import me.him188.ani.app.domain.mediasource.directapi.selectByPath
import me.him188.ani.app.domain.mediasource.directapi.stringByPath
import me.him188.ani.datasources.api.EpisodeSort
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
import me.him188.ani.datasources.api.source.deserializeArgumentsOrNull
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 「搜别人分享的夸克链接」的站点配置: 用条目名调站点的搜索接口, 从结果里取出夸克分享链接.
 *
 * 只描述「去哪搜、从哪取链接」, 分享里的文件怎么对到剧集与夸克网盘数据源相同 (见 [QuarkSubjectMatcher]).
 */
@Serializable
data class QuarkShareSearchConfig(
    /**
     * 搜索地址. `{keyword}` 换成条目名 (会做 URL 编码).
     */
    val searchUrl: String = "",
    val format: ResponseFormat = ResponseFormat.Json,
    /** 结果列表所在的路径, 为空表示响应本身就是列表. 路径语法同直链 API 数据源. */
    val itemsPath: String = "",
    /** 结果条目里剧名的路径, 用来核对是不是要找的番 (分享本身的标题常被打乱). */
    val titlePath: String = "",
    /** 结果条目里放分享链接的字段, 可以有几个; 每个字段里按 `pan.quark.cn/s/...` 找出全部链接. */
    val linkPaths: List<String> = emptyList(),
    /** 最多用前几个条目名去搜. */
    val maxKeywords: Int = 3,
    /** 每次查询最多打开几个分享. */
    val maxShares: Int = 4,
    /** 请求站点时用的 User-Agent, 留空用本机浏览器的. */
    val userAgent: String = "",
)

@OptIn(DontForgetToRegisterCodec::class)
@Serializable
data class QuarkShareSearchArguments(
    override val name: String,
    val description: String = "",
    val iconUrl: String = "",
    val config: QuarkShareSearchConfig = QuarkShareSearchConfig(),
    override val tier: MediaSourceTier = MediaSourceTier.Fallback,
) : MediaSourceArguments {
    companion object {
        val Default = QuarkShareSearchArguments(name = "夸克分享搜索")

        /**
         * 配置示例, 也是添加数据源时给出的模板. 地址是 example.com, 照着换成目标站点的.
         * 例子是苹果 CMS 的公开接口 (`?ac=detail&wd=`), 网盘资源站常开着它, 分享链接在 `vod_down_url` 或 `vod_content` 里.
         */
        val Example = QuarkShareSearchArguments(
            name = "夸克分享搜索",
            description = "示例配置, 把地址换成目标站点的",
            config = QuarkShareSearchConfig(
                searchUrl = "https://api.example.com/api.php/provide/vod?ac=detail&wd={keyword}",
                itemsPath = "list",
                titlePath = "vod_name",
                linkPaths = listOf("vod_down_url", "vod_play_url", "vod_content"),
            ),
        )
    }
}

object QuarkShareSearchMediaSourceCodec : DefaultMediaSourceCodec<QuarkShareSearchArguments>(
    QuarkShareSearchMediaSource.FactoryId,
    QuarkShareSearchArguments::class,
    currentVersion = 1,
    QuarkShareSearchArguments.serializer(),
)

/**
 * 分享里的一个文件, 编在资源的占位地址里: 播放时要凭它转存 (见 [QuarkDriveService.resolveSharePlayback]).
 *
 * 地址形如 `https://pan.quark.cn/s/<分享 id>#izuko-share&fid=..&token=..&pwd=..&name=..&size=..`, 误打开就是分享页本身.
 */
data class QuarkShareFileRef(
    val shareId: String,
    val passcode: String,
    val fid: String,
    val shareFidToken: String,
    val fileName: String,
    val size: Long,
) {
    val key: String get() = "$shareId/$fid"

    fun toUri(): String = buildString {
        append(SHARE_URL_PREFIX).append(shareId).append('#').append(MARKER)
        append("&fid=").append(fid.encodeURLParameter())
        append("&token=").append(shareFidToken.encodeURLParameter())
        append("&pwd=").append(passcode.encodeURLParameter())
        append("&name=").append(fileName.encodeURLParameter())
        append("&size=").append(size)
    }

    companion object {
        const val SHARE_URL_PREFIX = "https://pan.quark.cn/s/"
        private const val MARKER = "izuko-share"

        /** [toUri] 的逆运算; 不是这种地址 (包括普通的分享链接) 时返回 null. */
        fun parse(uri: String): QuarkShareFileRef? {
            if (!uri.startsWith(SHARE_URL_PREFIX)) return null
            val shareId = uri.removePrefix(SHARE_URL_PREFIX).substringBefore('#').substringBefore('?')
            val fragment = uri.substringAfter('#', missingDelimiterValue = "")
            val parts = fragment.split('&')
            if (shareId.isEmpty() || parts.firstOrNull() != MARKER) return null
            val values = parts.drop(1).associate { part ->
                part.substringBefore('=') to part.substringAfter('=', "").decodeURLQueryComponent()
            }
            val fid = values["fid"]?.takeIf { it.isNotEmpty() } ?: return null
            return QuarkShareFileRef(
                shareId = shareId,
                passcode = values["pwd"].orEmpty(),
                fid = fid,
                shareFidToken = values["token"].orEmpty(),
                fileName = values["name"].orEmpty(),
                size = values["size"]?.toLongOrNull() ?: 0,
            )
        }
    }
}

/**
 * 只读地查看分享 (不需要登录).
 */
internal interface QuarkShareBrowser {
    /** 打开分享, 返回分享标题. 分享失效时抛 [QuarkShareUnavailableException]. */
    suspend fun open(shareId: String, passcode: String): String

    suspend fun listFolder(shareId: String, passcode: String, folderId: String): List<QuarkShareFile>
}

/** 要打开的一个分享: 站点结果里找到的, 或用户添加的. */
internal class FoundShare(
    val shareId: String,
    val passcode: String,
    /**
     * 用来认季的名字: 站点上的剧名 (分享标题常被打乱, 靠不住); 用户添加的分享没有站点剧名, 用分享标题.
     */
    val siteTitle: String,
)

/** 分享里一个对上了集的视频文件. */
internal class QuarkShareMatch(
    val share: FoundShare,
    val file: QuarkShareFile,
    /** 分享里从根到文件所在文件夹的名字 (不含站点剧名). */
    val folders: List<String>,
    val episode: EpisodeSort,
)

/**
 * 按 [QuarkShareSearchConfig] 搜站点、打开分享、对出剧集. 不含任何站点特有的逻辑.
 */
internal class QuarkShareSearchEngine(
    private val config: QuarkShareSearchConfig,
    private val shares: QuarkShareBrowser,
    /** 请求站点搜索接口; 失败返回 null. */
    private val fetch: suspend (url: String) -> ByteArray?,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val reader = QuarkShareReader(shares)

    suspend fun search(request: MediaFetchRequest): List<QuarkShareMatch> {
        val names = QuarkSubjectMatcher.subjectNamesOf(request)
        val keywords = QuarkSubjectMatcher.keywordsOf(names).take(config.maxKeywords.coerceAtLeast(1))
        if (keywords.isEmpty() || config.searchUrl.isBlank()) return emptyList()

        // 名字都是同一部番, 前一个名字找到了就不再换名字搜: 这类站连着搜几次就会回 5xx
        val found = LinkedHashMap<String, FoundShare>()
        for (keyword in keywords) {
            for (share in searchSite(keyword)) found.putIfAbsent(share.shareId, share)
            if (found.isNotEmpty()) break
        }
        val selected = found.values.take(config.maxShares.coerceAtLeast(1))
        logger.info { "Quark share search: ${selected.size} shares for ${keywords.first()}" }

        val semaphore = Semaphore(SHARE_CONCURRENCY)
        return coroutineScope {
            selected.map { share ->
                async { semaphore.withPermit { matchShare(request, share) } }
            }.awaitAll().flatten()
        }
    }

    /** 搜一个关键词, 返回剧名对得上的结果里的全部夸克分享链接. */
    internal suspend fun searchSite(keyword: String): List<FoundShare> {
        val url = config.searchUrl.replace("{keyword}", keyword.encodeURLParameter())
        val bytes = fetch(url) ?: return emptyList()
        val root = parseResponse(bytes, config.format, json) ?: return emptyList()
        val normalizedKeyword = DriveNameParser.normalize(keyword)
        return root.selectByPath(config.itemsPath).flatMap { item ->
            val title = item.stringByPath(config.titlePath).orEmpty()
            if (!titleMatches(title, normalizedKeyword)) return@flatMap emptyList()
            config.linkPaths
                .flatMap { path -> item.selectByPath(path).mapNotNull { it.asStringOrNull() } }
                .flatMap { text -> extractShareLinks(text) }
                .map { (shareId, passcode) -> FoundShare(shareId, passcode, title) }
        }.distinctBy { it.shareId }
    }

    private suspend fun matchShare(request: MediaFetchRequest, share: FoundShare): List<QuarkShareMatch> {
        val videos = try {
            reader.read(share.shareId, share.passcode).videos
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuarkShareUnavailableException) {
            logger.info { "Quark share ${share.shareId} unavailable: ${e.message}" }
            return emptyList()
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to read Quark share ${share.shareId}" }
            return emptyList()
        }
        return reader.match(request, share, videos)
    }

    internal companion object {
        private val logger = logger<QuarkShareSearchEngine>()

        private const val SHARE_CONCURRENCY = 2

        private val SHARE_LINK = Regex("""https?://pan\.quark\.cn/s/([0-9A-Za-z]+)(?:\?pwd=([0-9A-Za-z]+))?""")

        /** 文字里的全部夸克分享链接 (分享 id 到提取码, 没有提取码时为空串). */
        fun extractShareLinks(text: String): List<Pair<String, String>> =
            SHARE_LINK.findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList()

        /**
         * 站点上的剧名是不是这个关键词对应的番: 归一化后互相包含即可 (剧名常带「第二季」之类的后缀, 关键词是主标题).
         */
        fun titleMatches(title: String, normalizedKeyword: String): Boolean {
            if (normalizedKeyword.length < 2) return false
            val normalizedTitle = DriveNameParser.normalize(title)
            if (normalizedTitle.contains(normalizedKeyword)) return true
            val base = DriveNameParser.normalize(DriveNameParser.baseTitle(title))
            return base.length >= 2 && normalizedKeyword.contains(base)
        }
    }
}

/**
 * 搜别人分享的夸克链接: 站点由配置给出 (见 [QuarkShareSearchConfig]), 一个配置就是一个数据源.
 *
 * 查询时只读地打开分享、列出文件 (不需要登录); 播放时把那一集转存到用户自己的夸克网盘再取地址, 所以要先登录夸克.
 */
class QuarkShareSearchMediaSource(
    override val mediaSourceId: String,
    config: MediaSourceConfig,
    private val client: ScopedHttpClient,
    private val service: QuarkDriveService,
) : MediaSource {
    companion object {
        val FactoryId = FactoryId("quark-share-search")

        val INFO = MediaSourceInfo(
            displayName = "夸克分享搜索",
            description = "用站点的搜索接口找别人分享的夸克链接, 播放时转存到自己的夸克网盘",
            iconUrl = "https://pan.quark.cn/favicon.ico",
        )

        private val logger = logger<QuarkShareSearchMediaSource>()
    }

    private val arguments = config.deserializeArgumentsOrNull(QuarkShareSearchArguments.serializer())
        ?: QuarkShareSearchArguments.Default

    private val userAgent: String?
        get() = arguments.config.userAgent.takeIf { it.isNotBlank() } ?: DeviceBrowserUserAgentHolder.current

    private val engine = QuarkShareSearchEngine(arguments.config, service.shareBrowser, ::fetchBytes)

    override val kind: MediaSourceKind get() = MediaSourceKind.WEB
    override val location: MediaSourceLocation get() = MediaSourceLocation.Online

    override val info: MediaSourceInfo = MediaSourceInfo(
        displayName = arguments.name,
        description = arguments.description.takeIf { it.isNotBlank() },
        iconUrl = arguments.iconUrl.takeIf { it.isNotBlank() } ?: INFO.iconUrl,
    )

    override suspend fun checkConnection(): ConnectionStatus {
        val url = arguments.config.searchUrl.replace("{keyword}", "test")
        return if (url.isNotBlank() && fetchBytes(url) != null) ConnectionStatus.SUCCESS else ConnectionStatus.FAILED
    }

    override suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> {
        val subjectName = query.subjectNames.firstOrNull { it.isNotBlank() } ?: query.subjectNameCN
        val medias = engine.search(query).map { MediaMatch(it.toShareMedia(mediaSourceId, arguments.name, subjectName), MatchKind.FUZZY) }
        return SinglePagePagedSource { medias.asFlow() }
    }

    private suspend fun fetchBytes(url: String): ByteArray? = try {
        client.use {
            get(url) {
                userAgent?.let { ua -> attributes.put(RequestUserAgentAttribute, ua) }
            }.readRawBytes()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ResponseException) {
        // 异常消息里带着整个出错页面, 只记状态码
        logger.warn { "Request failed: $url: HTTP ${e.response.status.value}" }
        null
    } catch (e: Exception) {
        logger.warn { "Request failed: $url: $e" }
        null
    }

    class Factory(
        private val service: QuarkDriveService,
    ) : MediaSourceFactory {
        override val factoryId: FactoryId get() = FactoryId
        override val allowMultipleInstances: Boolean get() = true
        override val info: MediaSourceInfo get() = INFO

        override fun create(
            mediaSourceId: String,
            config: MediaSourceConfig,
            client: ScopedHttpClient,
        ): MediaSource = QuarkShareSearchMediaSource(mediaSourceId, config, client, service)
    }
}
