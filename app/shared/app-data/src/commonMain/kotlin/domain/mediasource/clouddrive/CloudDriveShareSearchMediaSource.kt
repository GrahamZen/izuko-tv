/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.client.plugins.contentnegotiation.exclude
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.models.preference.DriveRememberedShare
import me.him188.ani.app.domain.media.fetch.SelfLimitedMediaSource
import me.him188.ani.app.domain.mediasource.codec.DefaultMediaSourceCodec
import me.him188.ani.app.domain.mediasource.codec.DontForgetToRegisterCodec
import me.him188.ani.app.domain.mediasource.codec.MediaSourceArguments
import me.him188.ani.app.domain.mediasource.directapi.ResponseFormat
import me.him188.ani.app.domain.mediasource.directapi.parseResponse
import me.him188.ani.app.domain.mediasource.directapi.selectByPath
import me.him188.ani.app.domain.mediasource.directapi.stringByPath
import me.him188.ani.app.domain.foundation.DeviceBrowserUserAgentHolder
import me.him188.ani.app.domain.foundation.RequestUserAgentAttribute
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
import me.him188.ani.utils.xml.QueryParser
import me.him188.ani.utils.xml.Html
import me.him188.ani.utils.xml.parseSelectorOrNull
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 「分享搜索」的配置: 去哪里找网盘分享. 两种来源可以单用, 也可以一起用 (先查固定的分享, 当前这一集找到了就不再搜站点):
 * - 固定的分享 ([shares]): 一组整理好的分享 (常是按番分好文件夹的合集), 按番名对文件夹名;
 * - 站点搜索 ([searchUrl]): 用条目名调站点的搜索接口, 从结果里取出分享链接.
 *
 * 只描述「去哪找」, 分享里的文件怎么对到剧集与自己网盘的数据源相同 (见 [DriveSubjectMatcher]).
 */
@Serializable
data class DriveShareSearchConfig(
    /**
     * 固定的分享, 每项是一段含分享链接的文字 (可以带「提取码：xxxx」). 列出每个分享前 [shareIndexDepth] 层的文件夹名 (缓存几个小时),
     * 名字对得上番名的文件夹里找剧集.
     */
    val shares: List<String> = emptyList(),
    /** [shares] 往下列几层文件夹来对番名. */
    val shareIndexDepth: Int = 2,
    /**
     * 搜索地址. `{keyword}` 换成条目名 (会做 URL 编码). 空表示不搜站点.
     */
    val searchUrl: String = "",
    val format: ResponseFormat = ResponseFormat.Json,
    /** 结果列表所在的路径, 为空表示响应本身就是列表. 路径语法同直链 API 数据源. */
    val itemsPath: String = "",
    /** 结果条目里剧名的路径, 用来核对是不是要找的番 (分享本身的标题常被打乱). */
    val titlePath: String = "",
    /** 结果条目里放分享链接的字段, 可以有几个; 每个字段里按网盘的分享链接格式找出全部链接. */
    val linkPaths: List<String> = emptyList(),
    /**
     * 搜索结果是网页时填: 结果里指向详情页的链接 (CSS 选择器; 地址取 `href`, 剧名取 `title` 属性, 没有就取文字),
     * 打开剧名对得上的详情页, 取出页面里全部分享链接. 这时 [format]、[itemsPath]、[titlePath]、[linkPaths] 不用.
     */
    val detailLinkSelector: String = "",
    /** 最多用前几个条目名去搜. */
    val maxKeywords: Int = 3,
    /** 每次查询最多打开几个分享. */
    val maxShares: Int = 4,
    /** 请求站点时用的 User-Agent, 留空用本机浏览器的. */
    val userAgent: String = "",
)

/**
 * @property drive 分享在哪个网盘 (网盘 id, 见 [CloudDriveProtocol.id]); 播放时转存到这个网盘
 */
@OptIn(DontForgetToRegisterCodec::class)
@Serializable
data class CloudDriveShareSearchArguments(
    override val name: String,
    val drive: String,
    val description: String = "",
    val iconUrl: String = "",
    val config: DriveShareSearchConfig = DriveShareSearchConfig(),
    override val tier: MediaSourceTier = MediaSourceTier.Fallback,
) : MediaSourceArguments {
    companion object {
        /**
         * 配置示例, 也是添加数据源时给出的模板. 网盘 id 与地址都要照着换: 网盘 id 是已配置的网盘 (见 [CloudDriveProtocol.id]),
         * 例子是苹果 CMS 的公开接口 (`?ac=detail&wd=`), 网盘资源站常开着它, 分享链接在 `vod_down_url` 或 `vod_content` 里.
         */
        val Example = CloudDriveShareSearchArguments(
            name = "网盘分享搜索",
            drive = "",
            description = "示例配置, 填上网盘 id, 把地址换成目标站点的",
            config = DriveShareSearchConfig(
                searchUrl = "https://api.example.com/api.php/provide/vod?ac=detail&wd={keyword}",
                itemsPath = "list",
                titlePath = "vod_name",
                linkPaths = listOf("vod_down_url", "vod_play_url", "vod_content"),
            ),
        )
    }
}

object CloudDriveShareSearchMediaSourceCodec : DefaultMediaSourceCodec<CloudDriveShareSearchArguments>(
    CloudDriveShareSearchMediaSource.FactoryId,
    CloudDriveShareSearchArguments::class,
    currentVersion = 1,
    CloudDriveShareSearchArguments.serializer(),
)

/**
 * 按 [DriveShareSearchConfig] 查固定的分享、搜站点、打开分享、对出剧集. 不含任何站点或网盘特有的逻辑.
 */
internal class DriveShareSearchEngine(
    private val config: DriveShareSearchConfig,
    private val shares: DriveShareBrowser,
    internal val links: DriveShareLinks,
    numbering: TmdbEpisodeNumbering = TmdbEpisodeNumbering.None,
    /** 列记下的分享文件夹 ([matchRemembered]): 每次现列, 分享取消了、新集上传了马上就知道. 搜索用 [shares]. */
    rememberedShares: DriveShareBrowser = shares,
    /** 请求站点搜索接口; 失败返回 null. */
    private val fetch: suspend (url: String) -> ByteArray?,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val reader = DriveShareReader(shares, numbering)
    private val rememberedReader = DriveShareReader(rememberedShares, numbering)

    private val fixedShares: List<DriveShareLink> = config.shares.flatMap { links.parse(it) }.distinctBy { it.shareId }

    /** 是否配置了要查的地方. */
    val isConfigured: Boolean get() = fixedShares.isNotEmpty() || config.searchUrl.isNotBlank()

    /** 先查固定的分享; 当前这一集在里面找到了就不再搜站点. */
    suspend fun search(request: MediaFetchRequest): List<DriveShareMatch> {
        val fixed = if (fixedShares.isEmpty()) emptyList() else searchFixedShares(request)
        if (fixed.any { it.isEpisodeOf(request) } || config.searchUrl.isBlank()) return fixed
        return fixed + searchSites(request)
    }

    // region 固定的分享

    /** 一个分享里列出来的文件夹 (从分享根起的名字). */
    private class IndexedFolder(val fid: String, val path: List<String>)

    private class ShareIndex(val folders: List<IndexedFolder>, val time: TimeMark)

    private val indexLock = Mutex()
    private val indexes = HashMap<String, ShareIndex>()

    private suspend fun searchFixedShares(request: MediaFetchRequest): List<DriveShareMatch> {
        val keywords = DriveSubjectMatcher.keywordsOf(DriveSubjectMatcher.subjectNamesOf(request))
            .map { DriveNameParser.normalize(it) }
            .filter { it.length >= 2 }
        if (keywords.isEmpty()) return emptyList()
        val semaphore = Semaphore(SHARE_CONCURRENCY)
        val indexed = coroutineScope {
            fixedShares.map { link ->
                async { semaphore.withPermit { indexOf(link)?.let { index -> index.folders.map { link to it } }.orEmpty() } }
            }.awaitAll().flatten()
        }
        // 有和番名完全相同的文件夹就只要它: 合集里同系列的作品挨着放 (「偶像大师」与「偶像大师 灰姑娘女孩」)
        val hits = keywords.flatMap { keyword ->
            val matched = indexed.filter { (_, folder) -> folderMatches(folder.path.last(), keyword) }
            matched.filter { (_, folder) -> folderTitle(folder.path.last()) == keyword }.ifEmpty { matched }
        }.distinctBy { (link, folder) -> link.shareId to folder.fid }
        // 外层文件夹对上了, 里层就不用再单独列
        val selected = hits.filterNot { (link, folder) ->
            hits.any { (other, outer) -> other.shareId == link.shareId && outer !== folder && folder.path.size > outer.path.size && folder.path.take(outer.path.size) == outer.path }
        }.take(MAX_FIXED_FOLDERS)
        logger.info { "Fixed shares: ${selected.size} folders for ${keywords.first()}: ${selected.map { it.second.path.joinToString("/") }}" }
        return coroutineScope {
            selected.map { (link, folder) ->
                async {
                    semaphore.withPermit {
                        try {
                            val videos = reader.readFolder(link.shareId, link.passcode, folder.fid, folder.path.drop(1))
                            reader.match(request, FoundShare(link.shareId, link.passcode, folder.path.last()), videos)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            logger.warn { "Failed to read folder ${folder.path} of share ${link.shareId}: $e" }
                            emptyList()
                        }
                    }
                }
            }.awaitAll().flatten()
        }
    }

    /** 分享 [link] 前几层的文件夹, 缓存 [SHARE_INDEX_TTL]. 打不开时为 null (下次再试). */
    private suspend fun indexOf(link: DriveShareLink): ShareIndex? {
        indexLock.withLock { indexes[link.shareId]?.takeIf { it.time.elapsedNow() < SHARE_INDEX_TTL }?.let { return it } }
        val folders = try {
            val title = shares.open(link.shareId, link.passcode)
            val result = ArrayList<IndexedFolder>()
            suspend fun walk(folderId: String, path: List<String>, depth: Int) {
                for (child in shares.listFolder(link.shareId, link.passcode, folderId)) {
                    if (!child.dir || result.size >= MAX_INDEXED_FOLDERS) continue
                    val childPath = path + child.fileName
                    result += IndexedFolder(child.fid, childPath)
                    if (depth + 1 < config.shareIndexDepth.coerceIn(1, MAX_INDEX_DEPTH)) walk(child.fid, childPath, depth + 1)
                }
            }
            walk(shares.rootFolderId, listOf(title), 0)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn { "Failed to index share ${link.shareId}: $e" }
            return null
        }
        logger.info { "Indexed share ${link.shareId}: ${folders.size} folders" }
        return ShareIndex(folders, TimeSource.Monotonic.markNow()).also { index ->
            indexLock.withLock { indexes[link.shareId] = index }
        }
    }

    // endregion

    // region 站点搜索

    private suspend fun searchSites(request: MediaFetchRequest): List<DriveShareMatch> {
        val names = DriveSubjectMatcher.subjectNamesOf(request)
        val keywords = DriveSubjectMatcher.keywordsOf(names).take(config.maxKeywords.coerceAtLeast(1))
        if (keywords.isEmpty() || config.searchUrl.isBlank()) return emptyList()

        // 名字都是同一部番, 前一个名字找到了就不再换名字搜: 这类站连着搜几次就会回 5xx
        val found = LinkedHashMap<String, FoundShare>()
        for ((index, keyword) in keywords.withIndex()) {
            // 网页站 (苹果 CMS 模板) 默认要求两次搜索隔 3 秒, 挨着搜只会拿到「系统提示」页
            if (index > 0 && config.detailLinkSelector.isNotBlank()) delay(PAGE_SEARCH_INTERVAL)
            for (share in searchSite(keyword)) found.putIfAbsent(share.shareId, share)
            if (found.isNotEmpty()) break
        }
        val selected = found.values.take(config.maxShares.coerceAtLeast(1))
        logger.info { "Share search: ${selected.size} shares for ${keywords.first()}" }

        val semaphore = Semaphore(SHARE_CONCURRENCY)
        return coroutineScope {
            selected.map { share ->
                async { semaphore.withPermit { matchShare(request, share) } }
            }.awaitAll().flatten()
        }
    }

    /**
     * 自动记下的分享文件夹里对得上的剧集 (认季的规则与名字同搜到时). 分享失效 (取消、违规) 时返回 null;
     * 别的错误 (网络) 当这次没有, 由调用方照常去搜.
     */
    suspend fun matchRemembered(request: MediaFetchRequest, remembered: DriveRememberedShare): List<DriveShareMatch>? {
        val share = FoundShare(remembered.shareId, remembered.passcode, remembered.siteTitle)
        val videos = try {
            rememberedReader.readFolder(remembered.shareId, remembered.passcode, remembered.folderId, remembered.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudDriveShareUnavailableException) {
            logger.info { "Remembered share ${remembered.shareId} unavailable: ${e.message}" }
            return null
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to read remembered share ${remembered.shareId}" }
            return emptyList()
        }
        return reader.match(request, share, videos)
    }

    /** 搜一个关键词, 返回剧名对得上的结果里的全部分享链接. */
    internal suspend fun searchSite(keyword: String): List<FoundShare> {
        val url = config.searchUrl.replace("{keyword}", keyword.encodeURLParameter())
        val bytes = fetch(url) ?: return emptyList()
        val normalizedKeyword = DriveNameParser.normalize(keyword)
        if (config.detailLinkSelector.isNotBlank()) return searchPages(url, bytes, normalizedKeyword)
        val root = parseResponse(bytes, config.format, json) ?: return emptyList()
        return root.selectByPath(config.itemsPath).flatMap { item ->
            val title = item.stringByPath(config.titlePath).orEmpty()
            if (!titleMatches(title, normalizedKeyword)) return@flatMap emptyList()
            config.linkPaths
                .flatMap { path -> item.selectByPath(path).mapNotNull { it.asStringOrNull() } }
                .flatMap { text -> links.extract(text) }
                .map { link -> FoundShare(link.shareId, link.passcode, title) }
        }.distinctBy { it.shareId }
    }

    /** 搜索结果是网页: 打开剧名对得上的详情页, 取出页面里的分享链接. */
    private suspend fun searchPages(url: String, bytes: ByteArray, normalizedKeyword: String): List<FoundShare> {
        val selector = QueryParser.parseSelectorOrNull(config.detailLinkSelector) ?: return emptyList()
        val html = bytes.decodeToString()
        val pageLinks = Html.parse(html, url).select(selector)
            .map { link -> link.attr("abs:href") to link.attr("title").ifBlank { link.text() }.trim() }
        val pages = pageLinks
            .filter { (href, title) -> href.startsWith("http") && titleMatches(title, normalizedKeyword) }
            .distinctBy { (href, _) -> href }
            .take(MAX_DETAIL_PAGES)
        logger.info {
            val title = TITLE_TAG.find(html)?.groupValues?.get(1)?.trim()?.take(40)
            "Share search page 「$title」: ${pageLinks.size} links ${pageLinks.take(3)}, opening ${pages.size}"
        }
        return pages.flatMap { (href, title) ->
            val page = fetch(href) ?: return@flatMap emptyList()
            links.extract(page.decodeToString()).map { link -> FoundShare(link.shareId, link.passcode, title) }
        }.distinctBy { it.shareId }
    }

    private suspend fun matchShare(request: MediaFetchRequest, share: FoundShare): List<DriveShareMatch> {
        val videos = try {
            reader.read(share.shareId, share.passcode).videos
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudDriveShareUnavailableException) {
            logger.info { "Share ${share.shareId} unavailable: ${e.message}" }
            return emptyList()
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to read share ${share.shareId}" }
            return emptyList()
        }
        return reader.match(request, share, videos)
    }

    // endregion

    internal companion object {
        private val logger = logger<DriveShareSearchEngine>()

        private const val SHARE_CONCURRENCY = 2

        /** 搜索结果是网页时, 最多打开几个详情页. */
        private const val MAX_DETAIL_PAGES = 3

        private val PAGE_SEARCH_INTERVAL = 3.5.seconds

        /** 固定分享的文件夹列表缓存多久: 合集更新不频繁, 每次查询都重列几十个文件夹太慢. */
        private val SHARE_INDEX_TTL = 6.hours

        /** 一个固定分享最多记多少个文件夹, 往下最多列几层. */
        private const val MAX_INDEXED_FOLDERS = 3000
        private const val MAX_INDEX_DEPTH = 4

        /** 固定分享里一次最多打开几个对上的文件夹. */
        private const val MAX_FIXED_FOLDERS = 6

        private val TITLE_TAG = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)

        /** 合集文件夹名前面的序号与画质标记 (`A 4k 番名`). */
        private val FOLDER_LEADING_TAGS = Regex("""^(?:\s*(?:[A-Za-z0-9]|[248]k|\d{3,4}p)\s+)+""", RegexOption.IGNORE_CASE)

        private val WORD_SEPARATOR = Regex("""[\s\[\]【】()（）\-_.·・/!！:：,，]+""")

        /** 合集里的文件夹名去掉前面的序号与画质标记后归一化, 用来认「完全同名」. */
        fun folderTitle(folderName: String): String = DriveNameParser.normalize(FOLDER_LEADING_TAGS.replace(folderName, ""))

        /**
         * 合集里的文件夹是不是这个番: 番名不短时同 [titleMatches]; 短的 (不到 4 个字, 如「日常」「Air」) 只认整个词,
         * 免得对上「男子高中生的日常」「Fairy Tail」.
         */
        fun folderMatches(folderName: String, normalizedKeyword: String): Boolean {
            if (normalizedKeyword.length >= 4) return titleMatches(folderName, normalizedKeyword)
            return folderTitle(folderName) == normalizedKeyword ||
                    WORD_SEPARATOR.split(folderName).any { DriveNameParser.normalize(it) == normalizedKeyword }
        }

        /**
         * 站点上的剧名 (或分享里的文件夹名) 是不是这个关键词对应的番: 归一化后互相包含即可
         * (剧名常带「第二季」「4k」之类, 关键词是主标题).
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
 * 找别人分享的网盘链接: 去哪找由配置给出 (见 [DriveShareSearchConfig]), 一个配置就是一个数据源.
 *
 * 查询时只读地打开分享、列出文件 (不需要登录); 播放时把那一集转存到用户自己的网盘再取地址, 所以没登录这个网盘时什么也不给 (不算失败).
 */
class CloudDriveShareSearchMediaSource(
    override val mediaSourceId: String,
    private val arguments: CloudDriveShareSearchArguments,
    private val client: ScopedHttpClient,
    private val registry: CloudDriveRegistry,
) : MediaSource, SelfLimitedMediaSource {
    companion object {
        val FactoryId = FactoryId("cloud-drive-share-search")

        val INFO = MediaSourceInfo(
            displayName = "网盘分享搜索",
            description = "从整理好的网盘分享合集或站点的搜索接口里找别人分享的网盘链接, 播放时转存到自己的网盘",
        )

        private val logger = logger<CloudDriveShareSearchMediaSource>()
    }

    private suspend fun userAgent(): String? =
        arguments.config.userAgent.takeIf { it.isNotBlank() } ?: DeviceBrowserUserAgentHolder.current()

    private val engineLock = Mutex()
    private var engine: Pair<CloudDriveService, DriveShareSearchEngine>? = null

    /** 网盘没有配置时为 null. 网盘的协议更新后 (分享链接的格式可能变了) 重建. */
    private suspend fun engine(): Pair<CloudDriveService, DriveShareSearchEngine>? {
        val drive = registry.awaitService(arguments.drive) ?: return null
        return engineLock.withLock {
            engine?.takeIf { it.first === drive && it.second.links === drive.shareLinks }
                ?: Pair(
                    drive,
                    DriveShareSearchEngine(
                        arguments.config, drive.shareSearchBrowser, drive.shareLinks, drive.episodeNumbering,
                        rememberedShares = drive.shareBrowser,
                        fetch = ::fetchBytes,
                    ),
                ).also { engine = it }
        }
    }

    override val kind: MediaSourceKind get() = MediaSourceKind.WEB
    override val location: MediaSourceLocation get() = MediaSourceLocation.Online

    override val info: MediaSourceInfo = MediaSourceInfo(
        displayName = arguments.name,
        description = arguments.description.takeIf { it.isNotBlank() },
        iconUrl = arguments.iconUrl.takeIf { it.isNotBlank() },
    )

    override suspend fun checkConnection(): ConnectionStatus {
        val (drive, engine) = engine() ?: return ConnectionStatus.FAILED
        if (!drive.account.first().isLoggedIn || !engine.isConfigured) return ConnectionStatus.FAILED
        val url = arguments.config.searchUrl.replace("{keyword}", "test")
        if (url.isBlank()) return ConnectionStatus.SUCCESS
        return if (fetchBytes(url) != null) ConnectionStatus.SUCCESS else ConnectionStatus.FAILED
    }

    /**
     * 播过的那一集所在的分享文件夹会记给这个源与条目 (见 [CloudDriveService.rememberedShareOf]): 之后先只列它,
     * 有要的这一集就不再去查别处 (追番时第二集起只列一个文件夹); 没有 (新的一集还没更新) 时照常查.
     */
    override suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> {
        val (drive, engine) = engine() ?: run {
            logger.warn { "${arguments.name}: cloud drive ${arguments.drive} is not configured" }
            return SinglePagePagedSource { emptyList<MediaMatch>().asFlow() }
        }
        // 订阅下发后每个用户都有这个源; 没登录网盘就播不了 (要转存), 不给结果也不算失败
        if (!drive.account.first().isLoggedIn) return SinglePagePagedSource { emptyList<MediaMatch>().asFlow() }
        val subjectName = query.subjectNames.firstOrNull { it.isNotBlank() } ?: query.subjectNameCN
        val subjectId = query.subjectId.toIntOrNull()
        val remembered = subjectId?.let { rememberedMatches(drive, engine, query, it) }.orEmpty()
        val searched = if (remembered.any { it.isEpisodeOf(query) }) {
            logger.info { "${arguments.name}: episode ${query.episodeSort} of subject ${query.subjectId} is in the remembered share, skipping search" }
            emptyList()
        } else {
            engine.search(query)
        }
        if (subjectId != null) drive.noteShareMatches(mediaSourceId, subjectId, remembered + searched)
        val seen = HashSet<String>()
        val medias = (remembered + searched)
            .filter { seen.add("${it.share.shareId}/${it.file.fid}") }
            .map { MediaMatch(it.toShareMedia(drive.placeholders, mediaSourceId, arguments.name, subjectName), MatchKind.FUZZY) }
        return SinglePagePagedSource { medias.asFlow() }
    }

    private suspend fun rememberedMatches(
        drive: CloudDriveService,
        engine: DriveShareSearchEngine,
        query: MediaFetchRequest,
        subjectId: Int,
    ): List<DriveShareMatch>? {
        val remembered = drive.rememberedShareOf(mediaSourceId, subjectId) ?: return null
        val matches = engine.matchRemembered(query, remembered)
        // 分享失效了: 忘掉, 照常查
        if (matches == null) drive.forgetRememberedShare(mediaSourceId, subjectId)
        return matches
    }

    private suspend fun fetchBytes(url: String): ByteArray? = try {
        val userAgent = userAgent()
        client.use {
            get(url) {
                userAgent?.let { ua -> attributes.put(RequestUserAgentAttribute, ua) }
                // 请求头 Accept 里有 json 时苹果 CMS (ThinkPHP) 按 JSON 请求渲染网页, 搜索结果页是坏的
                if (arguments.config.detailLinkSelector.isNotBlank()) exclude(ContentType.Application.Json)
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
            val arguments = runCatching { config.deserializeArgumentsOrNull(CloudDriveShareSearchArguments.serializer()) }.getOrNull()
                ?: CloudDriveShareSearchArguments(name = INFO.displayName, drive = "")
            return CloudDriveShareSearchMediaSource(mediaSourceId, arguments, client, registry)
        }
    }
}
