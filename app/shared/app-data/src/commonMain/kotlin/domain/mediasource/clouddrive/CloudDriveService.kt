/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.app.data.models.preference.CloudDriveAccounts
import me.him188.ani.app.data.models.preference.CloudDrivePlaybackMode
import me.him188.ani.app.data.models.preference.DrivePickedFile
import me.him188.ani.app.data.models.preference.DrivePickedFolder
import me.him188.ani.app.data.models.preference.DrivePickedSubtitle
import me.him188.ani.app.data.models.preference.DriveRememberedFolder
import me.him188.ani.app.data.models.preference.DriveRememberedShare
import me.him188.ani.app.data.models.preference.DriveSubjectPicks
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.platform.PlaybackRequestHints
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 一个网盘的账号与文件访问. 这个网盘的三种数据源、播放解析与设置页共用一个实例 (见 [CloudDriveRegistry]).
 *
 * 登录态就是账号的 Cookie ([CloudDriveAccount.cookie]); 接口响应里轮换的 Cookie 在这里合并后写回.
 *
 * @param protocol 创建时的协议; 订阅更新后由 [updateProtocol] 换掉, [driveId] 不变
 */
class CloudDriveService internal constructor(
    protocol: CloudDriveProtocol,
    private val accounts: Settings<CloudDriveAccounts>,
    httpClient: HttpClient,
    /** 按 TMDB 季集整理的文件换算成条目的集 (这个网盘的三种数据源共用) */
    internal val episodeNumbering: TmdbEpisodeNumbering = TmdbEpisodeNumbering.None,
) {
    @Volatile
    var protocol: CloudDriveProtocol = protocol
        private set

    @Volatile
    var placeholders: DrivePlaceholders = DrivePlaceholders(protocol)
        private set

    @Volatile
    var shareLinks: DriveShareLinks = DriveShareLinks(protocol)
        private set

    val driveId: String = protocol.id

    /** 换成新的协议 (同一个网盘, 例如订阅更新了接口). */
    fun updateProtocol(protocol: CloudDriveProtocol) {
        require(protocol.id == driveId) { "Cloud drive id changed from $driveId to ${protocol.id}" }
        if (protocol == this.protocol) return
        this.protocol = protocol
        placeholders = DrivePlaceholders(protocol)
        shareLinks = DriveShareLinks(protocol)
        logger.info { "Cloud drive $driveId protocol updated" }
    }

    private val accountLock = Mutex()

    val account: Flow<CloudDriveAccount> = accounts.flow.map { it.of(driveId) }.distinctUntilChanged()

    val isLoggedIn: Flow<Boolean> = account.map { it.isLoggedIn }

    private suspend fun currentAccount(): CloudDriveAccount = account.first()

    private suspend fun setAccount(value: CloudDriveAccount) = accounts.update { with(driveId, value) }

    /** 读改写账号, 与 Cookie 轮换写回同一把锁, 免得互相覆盖. [loggedInOnly] 时没登录就什么都不做. */
    private suspend fun updateAccount(loggedInOnly: Boolean = true, update: (CloudDriveAccount) -> CloudDriveAccount) {
        accountLock.withLock {
            val current = currentAccount()
            if (loggedInOnly && !current.isLoggedIn) return
            val updated = update(current)
            if (updated != current) setAccount(updated)
        }
    }

    private val cookieStore = object : CloudDriveCookieStore {
        override suspend fun get(): String = currentAccount().cookie

        override suspend fun onServerCookies(cookies: Map<String, String>) {
            updateAccount { it.copy(cookie = mergeCookies(it.cookie, cookies)) }
        }
    }

    internal val api = CloudDriveApi(httpClient, { this.protocol }, cookieStore)

    internal val browser = object : DriveBrowser {
        /**
         * 只取第一页, 文件夹在前 (排序由协议的请求参数决定): 一部番常整个放在一个文件夹里, 而名字相同的单个文件 (各季、字幕、.nfo)
         * 能把第一页占满, 文件在前的话那个文件夹会被挤到后面几页.
         */
        override suspend fun search(keyword: String): List<DriveFile> {
            val files = api.search(keyword).files
            // 转存来的文件归「分享搜索」数据源, 不在这里重复出现
            val saveFolder = currentAccount().shareSaveFolderId.ifEmpty { return files }
            return files.filter { it.fid != saveFolder && it.parentFid != saveFolder }
        }

        override suspend fun listFolder(folderId: String): List<DriveFile> = listAll(folderId)
    }

    private suspend fun listAll(folderId: String): List<DriveFile> {
        val result = mutableListOf<DriveFile>()
        var page = 1
        val pageSize = api.listPageSize
        while (result.size < MAX_FOLDER_ITEMS) {
            val list = api.listFolder(folderId, page)
            result += list.files
            if (list.files.size < pageSize || (list.total != null && result.size >= list.total)) break
            page++
        }
        return result
    }

    // region 手动指定 (Web 控制台「从网盘挑」)

    private val pickMatcher by lazy { DriveSubjectMatcher(browser, episodeNumbering) }

    suspend fun picksOf(subjectId: Int): DriveSubjectPicks = currentAccount().subjectPicks[subjectId] ?: DriveSubjectPicks()

    /** 记下「条目 [subjectId] 就在文件夹 [folder] 里」. */
    suspend fun pickFolder(subjectId: Int, folder: DriveFile) {
        updatePicks(subjectId) { picks ->
            picks.copy(folders = picks.folders.filterNot { it.fid == folder.fid } + DrivePickedFolder(folder.fid, folder.fileName))
        }
        logger.info { "Picked $driveId folder ${folder.fid} (${folder.fileName}) for subject $subjectId" }
    }

    /** 记下「文件 [file] 是条目 [subjectId] 的第 [episode] 集」. */
    suspend fun pickFile(subjectId: Int, file: DriveFile, episode: EpisodeSort) {
        val picked = DrivePickedFile(file.fid, file.fileName, file.parentFid, file.size, episode.toString())
        updatePicks(subjectId) { picks -> picks.copy(files = picks.files.filterNot { it.fid == file.fid } + picked) }
        logger.info { "Picked $driveId file ${file.fid} (${file.fileName}) as episode $episode of subject $subjectId" }
    }

    /** 忘掉条目 [subjectId] 下指定的文件夹或文件 [fid]. */
    suspend fun forgetPick(subjectId: Int, fid: String) {
        updatePicks(subjectId) { picks ->
            picks.copy(folders = picks.folders.filterNot { it.fid == fid }, files = picks.files.filterNot { it.fid == fid })
        }
    }

    private suspend fun updatePicks(subjectId: Int, update: (DriveSubjectPicks) -> DriveSubjectPicks) {
        requireLoggedIn()
        updateAccount { current ->
            val updated = update(current.subjectPicks[subjectId] ?: DriveSubjectPicks())
            current.copy(subjectPicks = if (updated.isEmpty) current.subjectPicks - subjectId else current.subjectPicks + (subjectId to updated))
        }
    }

    /** 给手动挑选用的搜索, 与数据源搜的一样 (第一页, 文件夹在前, 转存文件夹里的不列). */
    suspend fun searchForPicking(keyword: String): List<DriveFile> {
        requireLoggedIn()
        return browser.search(keyword)
    }

    /** 给手动挑选用的列目录 (根目录是 [rootFolderId]), 文件夹在前. */
    suspend fun listForPicking(folderId: String): List<DriveFile> {
        requireLoggedIn()
        return listAll(folderId)
    }

    val rootFolderId: String get() = protocol.rootFolderId

    /** 指定的文件夹 (往下几层) 里对上 [request] 这个条目的剧集, 规则同自己网盘的数据源读指定的文件夹. */
    internal suspend fun matchPickedFolder(request: MediaFetchRequest, folder: DrivePickedFolder): List<DriveSubjectMatcher.MatchedFile> =
        pickMatcher.matchPickedFolder(request, folder.fid, folder.name)

    /** 文件名里认出的 (季, 集), 季没写为 null; 花絮或认不出集号时为 null. 控制台列文件时标在旁边. */
    fun episodeInFileName(fileName: String): Pair<Int?, EpisodeSort>? {
        val parsed = DriveNameParser.parseFile(fileName)
        if (parsed.isExtra) return null
        return parsed.episode?.let { parsed.season to it }
    }

    /**
     * 指定文件夹 [folder] 的话会认出这个条目的哪些文件、各是哪一集 (控制台记下之前给用户看, 记下后直接播当前这一集).
     *
     * @return 文件、所在文件夹名 (从外到里)、集
     */
    suspend fun episodesInFolder(request: MediaFetchRequest, folder: DriveFile): List<Triple<DriveFile, List<String>, EpisodeSort>> =
        matchPickedFolder(request, DrivePickedFolder(folder.fid, folder.fileName)).map { Triple(it.file, it.folders, it.episode) }

    // endregion

    // region 自动记下的条目文件夹 (见 CloudDriveAccount.rememberedFolders)

    /** 最近搜到的文件属于哪个条目、在哪个文件夹 (文件 id → 记录), 播放时据此记下文件夹. 只在内存里. */
    private val recentMatches = LinkedHashMap<String, RecentMatch>()
    private val recentMatchesLock = Mutex()

    private class RecentMatch(val subjectId: Int, val folder: DriveRememberedFolder)

    /**
     * 记下这次给条目 [subjectId] 认出的文件. 只记在按名字搜到的文件夹里找到的 ([DriveSubjectMatcher.MatchedFile.folders] 不空):
     * 文件本身被搜到时, 它所在的文件夹可能是放着各种视频的「下载」之类, 记下来之后会把别的番当成这一部.
     */
    internal suspend fun noteMatches(subjectId: Int, matches: List<DriveSubjectMatcher.MatchedFile>) {
        val inFolders = matches.filter { it.folders.isNotEmpty() && it.file.parentFid.isNotEmpty() }
        if (inFolders.isEmpty()) return
        recentMatchesLock.withLock {
            for (match in inFolders) {
                recentMatches.remove(match.file.fid)
                recentMatches[match.file.fid] = RecentMatch(subjectId, DriveRememberedFolder(match.file.parentFid, match.folders))
            }
            while (recentMatches.size > MAX_RECENT_MATCHES) recentMatches.remove(recentMatches.keys.first())
        }
    }

    /**
     * 条目 [subjectId] 记下的文件夹里认出的文件; 没记过时为 null. 文件夹打不开 (被删了、挪走了) 时忘掉它, 也返回 null.
     */
    internal suspend fun matchRememberedFolder(request: MediaFetchRequest, subjectId: Int): List<DriveSubjectMatcher.MatchedFile>? {
        val folder = currentAccount().rememberedFolders[subjectId] ?: return null
        val matches = pickMatcher.matchRememberedFolder(request, folder.fid, folder.path)
        if (matches == null) updateAccount { it.copy(rememberedFolders = it.rememberedFolders - subjectId) }
        return matches
    }

    /** 播了文件 [fileId]: 它是搜到的哪个条目的, 就把它所在的文件夹记给那个条目. */
    private suspend fun rememberFolderOf(fileId: String) {
        val match = recentMatchesLock.withLock { recentMatches[fileId] } ?: return
        updateAccount { current ->
            val remembered = current.rememberedFolders
            if (remembered[match.subjectId] == match.folder) return@updateAccount current
            logger.info { "Remembered $driveId folder ${match.folder.fid} (${match.folder.path.joinToString("/")}) for subject ${match.subjectId}" }
            // 重新放到末尾 (最近的), 超出上限时去掉最早记下的
            current.copy(
                rememberedFolders = (remembered - match.subjectId + (match.subjectId to match.folder)).entries
                    .toList().takeLast(MAX_REMEMBERED_FOLDERS).associate { it.key to it.value },
            )
        }
    }

    // endregion

    // region 「分享搜索」自动记下的分享文件夹 (见 CloudDriveAccount.rememberedShares)

    /** 最近搜到的分享文件属于哪个源与条目、在分享的哪个文件夹 ([DriveShareFileRef.key] → 记录), 播放时据此记下. 只在内存里. */
    private val recentShareMatches = LinkedHashMap<String, RecentShareMatch>()

    private class RecentShareMatch(val key: String, val share: DriveRememberedShare)

    private fun rememberedShareKey(mediaSourceId: String, subjectId: Int) = "$mediaSourceId:$subjectId"

    /** 记下数据源 [mediaSourceId] 这次给条目 [subjectId] 认出的分享文件. */
    internal suspend fun noteShareMatches(mediaSourceId: String, subjectId: Int, matches: List<DriveShareMatch>) {
        if (matches.isEmpty()) return
        val key = rememberedShareKey(mediaSourceId, subjectId)
        recentMatchesLock.withLock {
            for (match in matches) {
                if (match.file.parentFid.isEmpty()) continue
                val share = DriveRememberedShare(
                    match.share.shareId, match.share.passcode, match.share.siteTitle, match.file.parentFid, match.folders,
                )
                val fileKey = shareKey(match.share.shareId, match.file.fid)
                recentShareMatches.remove(fileKey)
                recentShareMatches[fileKey] = RecentShareMatch(key, share)
            }
            while (recentShareMatches.size > MAX_RECENT_MATCHES) recentShareMatches.remove(recentShareMatches.keys.first())
        }
    }

    /** 数据源 [mediaSourceId] 给条目 [subjectId] 记下的分享文件夹. */
    internal suspend fun rememberedShareOf(mediaSourceId: String, subjectId: Int): DriveRememberedShare? =
        currentAccount().rememberedShares[rememberedShareKey(mediaSourceId, subjectId)]

    internal suspend fun forgetRememberedShare(mediaSourceId: String, subjectId: Int) {
        val key = rememberedShareKey(mediaSourceId, subjectId)
        logger.info { "Forgot remembered $driveId share of $key" }
        updateAccount { it.copy(rememberedShares = it.rememberedShares - key) }
    }

    /** 播了分享里的文件 [ref]: 它是哪个源给哪个条目搜到的, 就把它在分享里所在的文件夹记给那个源与条目. */
    private suspend fun rememberShareOf(ref: DriveShareFileRef) {
        val match = recentMatchesLock.withLock { recentShareMatches[ref.key] } ?: return
        updateAccount { current ->
            val remembered = current.rememberedShares
            if (remembered[match.key] == match.share) return@updateAccount current
            logger.info {
                "Remembered $driveId share ${match.share.shareId} folder ${match.share.folderId} (${match.share.path.joinToString("/")}) for ${match.key}"
            }
            // 重新放到末尾 (最近的), 超出上限时去掉最早记下的
            current.copy(
                rememberedShares = (remembered - match.key + (match.key to match.share)).entries
                    .toList().takeLast(MAX_REMEMBERED_SHARES).associate { it.key to it.value },
            )
        }
    }

    // endregion

    // region 手动挂上的字幕 (Web 控制台「从网盘挑」里点字幕文件)

    /**
     * 资源 [media] 在「手动挂字幕」里的键: 自己网盘的视频按文件 id, 分享里的按分享与分享里的文件 (转存出来的副本 id 会变).
     * 不是这个网盘的资源时为 null.
     */
    fun subtitleKeyOf(media: Media): String? {
        val uri = (media.download as? ResourceLocation.HttpStreamingFile)?.uri ?: return null
        placeholders.fileIdOf(uri)?.let { return fileSubtitleKey(it) }
        return placeholders.parseShareFile(uri)?.let { shareSubtitleKey(it) }
    }

    fun isSubtitleFile(fileName: String): Boolean = DriveSidecarSubtitles.isSubtitle(fileName)

    /** 手动挂上字幕文件 [fileName] 后它在播放器字幕菜单里叫什么 (与别的重名时播放器里再加序号). 不是字幕文件时为 null. */
    fun subtitleLabelOf(fileName: String): String? = DriveSidecarSubtitles.picked(Unit, fileName)?.label

    suspend fun pickedSubtitlesOf(videoKey: String): List<DrivePickedSubtitle> =
        currentAccount().pickedSubtitles[videoKey].orEmpty()

    /** 给视频 [videoKey] 挂上网盘里的字幕文件 [file], 排在最前 (没有内封字幕时播放器默认显示它). */
    suspend fun pickSubtitle(videoKey: String, file: DriveFile) {
        updatePickedSubtitles(videoKey) { list ->
            (listOf(DrivePickedSubtitle(file.fid, file.fileName)) + list.filterNot { it.fid == file.fid }).take(DriveSidecarSubtitles.MAX_PER_VIDEO)
        }
        logger.info { "Picked $driveId subtitle ${file.fid} (${file.fileName}) for $videoKey" }
    }

    suspend fun forgetSubtitle(videoKey: String, fid: String) {
        updatePickedSubtitles(videoKey) { list -> list.filterNot { it.fid == fid } }
    }

    private suspend fun updatePickedSubtitles(videoKey: String, update: (List<DrivePickedSubtitle>) -> List<DrivePickedSubtitle>) {
        requireLoggedIn()
        updateAccount { current ->
            val updated = update(current.pickedSubtitles[videoKey].orEmpty())
            // 刚改过的挪到最后; 记的视频太多时从最早改过的删起
            val others = current.pickedSubtitles - videoKey
            current.copy(
                pickedSubtitles = (if (updated.isEmpty()) others else others + (videoKey to updated))
                    .entries.toList().takeLast(MAX_PICKED_SUBTITLE_VIDEOS).associate { it.key to it.value },
            )
        }
    }

    // endregion

    // region 分享

    private class CachedShareToken(val token: DriveShareToken, val time: TimeMark)

    private val shareTokenLock = Mutex()
    private val shareTokens = LinkedHashMap<String, CachedShareToken>()

    /** 分享的令牌, 缓存 [SHARE_TOKEN_TTL]: 列一个分享的文件夹要发好几次请求, 每次都带它. */
    private suspend fun shareToken(shareId: String, passcode: String, refresh: Boolean = false): DriveShareToken {
        if (!refresh) {
            shareTokenLock.withLock {
                shareTokens[shareId]?.takeIf { it.time.elapsedNow() < SHARE_TOKEN_TTL }?.let { return it.token }
            }
        }
        val token = api.shareToken(shareId, passcode)
        shareTokenLock.withLock {
            shareTokens[shareId] = CachedShareToken(token, TimeSource.Monotonic.markNow())
            while (shareTokens.size > MAX_CACHED_SHARE_TOKENS) shareTokens.remove(shareTokens.keys.first())
        }
        return token
    }

    internal val shareBrowser = object : DriveShareBrowser {
        override val rootFolderId: String get() = protocol.share.rootFolderId

        override val needsFileToken: Boolean get() = protocol.file.shareToken.isNotBlank()

        override suspend fun open(shareId: String, passcode: String): String = shareToken(shareId, passcode).title

        override suspend fun listFolder(shareId: String, passcode: String, folderId: String): List<DriveFile> {
            val result = mutableListOf<DriveFile>()
            var page = 1
            var token = shareToken(shareId, passcode)
            var refreshed = false
            val pageSize = api.shareListPageSize
            while (result.size < MAX_FOLDER_ITEMS) {
                val list = try {
                    api.listShareFolder(shareId, passcode, token.token, folderId, page)
                } catch (e: CloudDriveShareUnavailableException) {
                    // 缓存的令牌可能过期了, 换一个再试一次
                    if (refreshed) throw e
                    refreshed = true
                    token = shareToken(shareId, passcode, refresh = true)
                    continue
                }
                result += list.files
                if (list.files.size < pageSize || (list.total != null && result.size >= list.total)) break
                page++
            }
            return result
        }
    }

    private val saveLock = Mutex()

    /** 转存过的分享文件 ([DriveShareFileRef.key]) 到转存后的文件 id. */
    private val savedFiles = HashMap<String, String>()

    /**
     * 网盘根目录下的转存文件夹 [SAVE_FOLDER_NAME], 没有就新建; 找到或建好后记进 [CloudDriveAccount.shareSaveFolderId].
     */
    private suspend fun findSaveFolder(): String {
        currentAccount().shareSaveFolderId.takeIf { it.isNotEmpty() }?.let { return it }
        val folder = listAll(rootFolderId).firstOrNull { it.dir && it.fileName == SAVE_FOLDER_NAME }?.fid
            ?: api.createFolder(SAVE_FOLDER_NAME, rootFolderId).also { logger.info { "Created $driveId folder $SAVE_FOLDER_NAME: $it" } }
        setSaveFolder(folder)
        return folder
    }

    /**
     * 转存文件夹 [SAVE_FOLDER_NAME] 里的文件 (从分享转存过来的副本). 没登录或还没有这个文件夹时为空, 不新建.
     */
    internal suspend fun savedShareCopies(): List<DriveFile> {
        val account = currentAccount()
        if (!account.isLoggedIn) return emptyList()
        val folder = account.shareSaveFolderId.takeIf { it.isNotEmpty() }
            ?: listAll(rootFolderId).firstOrNull { it.dir && it.fileName == SAVE_FOLDER_NAME }?.fid
            ?: return emptyList()
        return listAll(folder).filter { !it.dir }
    }

    private suspend fun setSaveFolder(folderId: String) {
        updateAccount { it.copy(shareSaveFolderId = folderId) }
    }

    /**
     * 播放分享里的一个文件: 转存到自己网盘的 [SAVE_FOLDER_NAME] 再取地址 (分享里的文件不能直接取直链).
     * 视频旁边的外挂字幕在同一次转存里一起存过去. 同一个文件只转存一次; 文件夹里的视频超过 [MAX_SAVED_FILES] 个、
     * 字幕超过 [MAX_SAVED_SUBTITLES] 个时删掉最早转存的.
     */
    suspend fun resolveSharePlayback(ref: DriveShareFileRef): CloudDrivePlayback {
        requireLoggedIn()
        val sidecars = shareSidecars(ref)
        val sidecarFiles = sidecars.orEmpty().map { it.file }
        val fileId = saveLock.withLock {
            savedFiles[ref.key]?.takeIf { sidecarFiles.all { savedFiles.containsKey(shareKey(ref.shareId, it.fid)) } }
        } ?: saveShareFile(ref, sidecarFiles)
        // 分享里没法找字幕 (旧资源没记文件夹, 或分享打不开) 时到转存文件夹里按名字找: 以前一起转存过的还在
        val subtitles = if (sidecars == null) {
            SubtitleLookup.InFolder
        } else {
            SubtitleLookup.Known {
                saveLock.withLock {
                    sidecars.mapNotNull { match ->
                        savedFiles[shareKey(ref.shareId, match.file.fid)]?.let {
                            DriveSidecarSubtitles.Match(it, match.mimeType, match.label, match.language)
                        }
                    }
                }
            }
        }
        // 转存的副本被删了会重新转存成另一个文件, 内容不变: 按分享里的文件标识存本机数据
        val cacheKey = "$driveId-share:${ref.key}"
        val playback = try {
            resolvePlayback(fileId, subtitles, shareSubtitleKey(ref), cacheKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudDriveAuthException) {
            throw e
        } catch (e: CloudDriveApiException) {
            // 转存的文件可能被用户删了: 重新转存一次
            logger.warn { "Saved $driveId file $fileId is not playable, saving again: ${e.message}" }
            saveLock.withLock {
                savedFiles.remove(ref.key)
                sidecarFiles.forEach { savedFiles.remove(shareKey(ref.shareId, it.fid)) }
            }
            resolvePlayback(saveShareFile(ref, sidecarFiles), subtitles, shareSubtitleKey(ref), cacheKey)
        }
        rememberShareOf(ref)
        return playback
    }

    /**
     * 分享里视频 [ref] 旁边的外挂字幕. 分享里没法找 (旧资源没记所在文件夹、分享打不开、超时) 时为 null.
     */
    private suspend fun shareSidecars(ref: DriveShareFileRef): List<DriveSidecarSubtitles.Match<DriveFile>>? {
        if (ref.folderId.isEmpty()) return null
        return try {
            withTimeoutOrNull(SUBTITLE_LOOKUP_TIMEOUT) {
                val files = shareBrowser.listFolder(ref.shareId, ref.passcode, ref.folderId)
                val isVideo = { file: DriveFile -> file.isVideo }
                DriveSidecarSubtitles.match(ref.fileName, files, { it.fileName }, isVideo).ifEmpty {
                    val folders = files.filter { it.dir && DriveSidecarSubtitles.isSubtitleFolder(it.fileName) }
                    if (folders.isEmpty()) return@ifEmpty emptyList()
                    val inFolders = folders.take(MAX_SUBTITLE_FOLDERS).flatMap { shareBrowser.listFolder(ref.shareId, ref.passcode, it.fid) }
                    DriveSidecarSubtitles.match(ref.fileName, inFolders + files.filter(isVideo), { it.fileName }, isVideo)
                }.filter { it.file.shareToken.isNotEmpty() || !shareBrowser.needsFileToken }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn { "Failed to look for subtitles next to ${ref.key}: $e" }
            null
        }
    }

    private suspend fun saveShareFile(ref: DriveShareFileRef, sidecars: List<DriveFile>): String = saveLock.withLock {
        try {
            saveShareFileLocked(ref, sidecars, findSaveFolder())
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudDriveAuthException) {
            throw e
        } catch (e: CloudDriveShareUnavailableException) {
            throw e
        } catch (e: CloudDriveCapacityException) {
            throw e
        } catch (e: CloudDriveApiException) {
            // 记下的转存文件夹可能被用户删了: 重新找 (没有就新建) 再试一次
            logger.warn { "Saving to the remembered $driveId folder failed, looking it up again: ${e.message}" }
            setSaveFolder("")
            saveShareFileLocked(ref, sidecars, findSaveFolder())
        }
    }

    /**
     * 转存视频 [ref] 与它的字幕 [sidecars] (已经在 [folder] 里的不再转存), 返回视频转存后的 id.
     * 字幕转存失败不影响视频.
     */
    private suspend fun saveShareFileLocked(ref: DriveShareFileRef, sidecars: List<DriveFile>, folder: String): String {
        val existing = listAll(folder).filter { !it.dir }
        val video = DriveFile(fid = ref.fid, fileName = ref.fileName, size = ref.size, isVideo = true, shareToken = ref.shareToken)
        val reused = HashSet<String>()
        var missing = (listOf(video) + sidecars).filter { file ->
            val copy = existing.firstOrNull { it.fileName == file.fileName && (file.size <= 0 || it.size == file.size) }
            if (copy != null) {
                savedFiles[shareKey(ref.shareId, file.fid)] = copy.fid
                reused += copy.fid
            }
            copy == null
        }
        if (missing.isEmpty()) return savedFiles.getValue(ref.key)

        val token = shareToken(ref.shareId, ref.passcode)
        val saved = try {
            try {
                api.saveFromShare(ref.shareId, ref.passcode, token.token, missing, folder)
            } catch (e: CloudDriveApiException) {
                if (!e.isCapacityLimit) throw e
                // 网盘满了: 转存文件夹里的都是之前播放时转存的副本, 清掉这次用不到的腾出地方, 再转存一次
                freeSaveFolder(existing.filter { it.fid !in reused }, ref)
                try {
                    api.saveFromShare(ref.shareId, ref.passcode, token.token, missing, folder)
                } catch (e: CloudDriveApiException) {
                    if (!e.isCapacityLimit) throw e
                    throw CloudDriveCapacityException("网盘空间不够: 清空「$SAVE_FOLDER_NAME」后仍放不下 ${ref.fileName} (${ref.size / MB} MB)")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudDriveAuthException) {
            throw e
        } catch (e: CloudDriveShareUnavailableException) {
            throw e
        } catch (e: CloudDriveCapacityException) {
            throw e
        } catch (e: CloudDriveApiException) {
            if (missing.size == 1 && video in missing) throw e
            logger.warn { "Saving ${missing.size} files of ${ref.key} failed, saving the video alone: ${e.message}" }
            if (video !in missing) return savedFiles.getValue(ref.key)
            missing = listOf(video)
            api.saveFromShare(ref.shareId, ref.passcode, token.token, missing, folder)
        }
        if (missing.size == 1) {
            savedFiles[shareKey(ref.shareId, missing.single().fid)] = saved.first()
        } else {
            // 一次转存几个文件时按名字认回各自的新 id
            val after = listAll(folder).filter { !it.dir }
            for (file in missing) {
                after.firstOrNull { it.fileName == file.fileName && (file.size <= 0 || it.size == file.size) }
                    ?.let { savedFiles[shareKey(ref.shareId, file.fid)] = it.fid }
            }
        }
        logger.info { "Saved ${missing.size} files of $driveId share ${ref.key} (${ref.fileName})" }
        val newSubtitles = missing.count { DriveSidecarSubtitles.isSubtitle(it.fileName) }
        val (reusedSubtitles, reusedVideos) = existing.filter { it.fid in reused }.partition { DriveSidecarSubtitles.isSubtitle(it.fileName) }
        val stale = filesToPrune(
            existing.filter { it.fid !in reused },
            keep = MAX_SAVED_FILES - (missing.size - newSubtitles) - reusedVideos.size,
            keepSubtitles = MAX_SAVED_SUBTITLES - newSubtitles - reusedSubtitles.size,
        )
        if (stale.isNotEmpty()) {
            try {
                api.deleteFiles(stale.map { it.fid })
                val removed = stale.mapTo(HashSet()) { it.fid }
                savedFiles.entries.removeAll { it.value in removed }
                logger.info { "Removed ${stale.size} old files from $SAVE_FOLDER_NAME" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn(e) { "Failed to remove old files from $SAVE_FOLDER_NAME" }
            }
        }
        return savedFiles[ref.key] ?: throw CloudDriveApiException("转存后没找到 ${ref.fileName}")
    }

    /** 网盘满了转存不进去时, 删掉转存文件夹里的 [files] (等删除完成) 腾出空间; 没有能删的就是空间被别的文件占着. */
    private suspend fun freeSaveFolder(files: List<DriveFile>, ref: DriveShareFileRef) {
        if (files.isEmpty()) {
            throw CloudDriveCapacityException("网盘空间不够, 「$SAVE_FOLDER_NAME」里也没有能清的, 放不下 ${ref.fileName} (${ref.size / MB} MB)")
        }
        logger.warn { "Cloud drive $driveId is full: removing ${files.size} files (${files.sumOf { it.size } / MB} MB) from $SAVE_FOLDER_NAME to save ${ref.key}" }
        api.deleteFiles(files.map { it.fid }, wait = true)
        val removed = files.mapTo(HashSet()) { it.fid }
        savedFiles.entries.removeAll { it.value in removed }
    }

    // endregion

    suspend fun requireLoggedIn() {
        if (!currentAccount().isLoggedIn) throw CloudDriveAuthException()
    }

    /**
     * 向服务端核对登录态, 顺便更新昵称与档位. 登录失效时抛 [CloudDriveAuthException].
     */
    suspend fun refreshAccount(): CloudDriveAccount {
        requireLoggedIn()
        val tier = api.tier()
        val nickname = try {
            api.nickname()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudDriveAuthException) {
            throw e
        } catch (e: Throwable) {
            logger.warn { "Failed to fetch $driveId nickname: $e" }
            null
        }
        // 协议既没有档位也没有昵称时, 列一次根目录核对登录态
        if (tier == null && protocol.api.tier == null && protocol.api.nickname == null) api.listFolder(rootFolderId)
        updateAccount { current ->
            current.copy(tier = tier ?: current.tier, nickname = nickname ?: current.nickname)
        }
        return currentAccount()
    }

    /** [cookie] 是不是登录后的 Cookie: 带着协议要求的全部名字 ([DriveLoginConfig.requiredCookies]). */
    private fun missingCookies(cookie: String): List<String> {
        val names = cookie.split(';').map { it.substringBefore('=').trim() }.toSet()
        return protocol.login.requiredCookies.filter { it !in names }
    }

    /**
     * 用手动填写的 Cookie 登录: 先向服务端核对, 通过才保存.
     */
    suspend fun loginWithCookie(cookie: String): CloudDriveAccount {
        val trimmed = cookie.trim().removePrefix("Cookie:").trim()
        val missing = missingCookies(trimmed)
        require(missing.isEmpty()) { "Cookie 里没有 ${missing.joinToString()}, 不是登录后的 Cookie" }
        val previous = currentAccount()
        accountLock.withLock { setAccount(CloudDriveAccount(cookie = trimmed, playbackMode = previous.playbackMode)) }
        return try {
            refreshAccount()
        } catch (e: Throwable) {
            accountLock.withLock { setAccount(previous) }
            throw e
        }
    }

    suspend fun logout() {
        accountLock.withLock {
            setAccount(CloudDriveAccount(playbackMode = currentAccount().playbackMode))
        }
    }

    suspend fun setPlaybackMode(mode: CloudDrivePlaybackMode) {
        updateAccount(loggedInOnly = false) { it.copy(playbackMode = mode) }
    }

    /** 协议支持扫码登录. */
    val supportsQrLogin: Boolean get() = protocol.login.qr != null

    /**
     * 扫码登录. 二维码过期后流结束于 [CloudDriveQrLoginState.Expired], 重新收集即得到新的二维码.
     */
    fun qrLogin(): Flow<CloudDriveQrLoginState> = flow {
        val qr = protocol.login.qr ?: run {
            emit(CloudDriveQrLoginState.Failed("这个网盘不支持扫码登录"))
            return@flow
        }
        val timeout = qr.timeoutSeconds.coerceAtLeast(10).seconds
        val pollInterval = qr.pollIntervalSeconds.coerceAtLeast(1).seconds
        emit(CloudDriveQrLoginState.Loading)
        val token = try {
            api.requestQrToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to request $driveId QR token" }
            emit(CloudDriveQrLoginState.Failed(e.message ?: e.toString()))
            return@flow
        }
        // 界面上倒数用墙上时间; 下面判超时仍用单调时钟 (不受改系统时间影响), 两者从同一刻起算
        emit(CloudDriveQrLoginState.WaitingForScan(api.qrCodeContent(token.token), currentTimeMillis() + timeout.inWholeMilliseconds))

        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (deadline.hasNotPassedNow()) {
            delay(pollInterval)
            val result = try {
                api.pollQrToken(token.token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn { "Polling $driveId QR token failed: $e" }
                continue
            }
            when (result) {
                DriveQrPollResult.Waiting -> continue
                DriveQrPollResult.Expired -> break
                is DriveQrPollResult.Confirmed -> {
                    // 手机上确认了, 还要换票、取账号信息 (几个请求, 慢的时候十几秒): 先让界面收起二维码说一声
                    emit(CloudDriveQrLoginState.Confirmed)
                    try {
                        val exchange = api.exchangeTicket(result.ticket)
                        val cookies = token.cookies + result.cookies + exchange.cookies
                        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                        if (cookies.isEmpty() || missingCookies(cookie).isNotEmpty()) {
                            emit(CloudDriveQrLoginState.Failed("网盘没有返回登录 Cookie"))
                            return@flow
                        }
                        val previous = currentAccount()
                        accountLock.withLock {
                            setAccount(
                                CloudDriveAccount(cookie = cookie, nickname = exchange.nickname.orEmpty(), playbackMode = previous.playbackMode),
                            )
                        }
                        val account = try {
                            refreshAccount()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: CloudDriveAuthException) {
                            throw e
                        } catch (e: Throwable) {
                            // 登录已经成功, 账号信息之后再取
                            logger.warn { "Failed to refresh $driveId account after login: $e" }
                            currentAccount()
                        }
                        logger.info { "Cloud drive $driveId QR login succeeded" }
                        emit(CloudDriveQrLoginState.Success(account.nickname))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logger.warn(e) { "Cloud drive $driveId QR login failed after confirmation" }
                        emit(CloudDriveQrLoginState.Failed(e.message ?: e.toString()))
                    }
                    return@flow
                }
            }
        }
        emit(CloudDriveQrLoginState.Expired)
    }

    /**
     * 按当前的播放方式取一个文件的播放地址, 连同视频旁边的外挂字幕. 播放器请求这些地址时必须带上 [CloudDrivePlayback.headers].
     */
    suspend fun resolvePlayback(fileId: String): CloudDrivePlayback =
        resolvePlayback(fileId, SubtitleLookup.InFolder, fileSubtitleKey(fileId), "$driveId:$fileId").also { rememberFolderOf(fileId) }

    /**
     * @param subtitleKey 手动挂字幕的键 (见 [subtitleKeyOf])
     * @param cacheKey 原文件内容的固定标识, 播放器按它把下过的数据存在本机 (见 [PlaybackRequestHints.CACHE_KEY_HEADER])
     */
    private suspend fun resolvePlayback(
        fileId: String,
        subtitleLookup: SubtitleLookup,
        subtitleKey: String,
        cacheKey: String,
    ): CloudDrivePlayback {
        requireLoggedIn()
        val account = currentAccount()
        val transcoded = when (account.playbackMode) {
            CloudDrivePlaybackMode.ORIGINAL -> null
            CloudDrivePlaybackMode.TRANSCODED -> if (protocol.supportsTranscoded) bestTranscodedUrl(api.transcodedVideos(fileId)) else null
        }
        val download = if (transcoded == null) api.download(fileId) else null
        val url = transcoded ?: download!!.url
        val headers = playbackHeaders(currentAccount().cookie)
        val subtitles = subtitlesOf(fileId, download, subtitleLookup, subtitleKey)
        // 原文件直链每个连接常有速度上限, 一个连接跟不上码率时让播放器 (与缓存下载) 并发分块取, 路数按账号档位 (见 parallelConnectionsFor);
        // 转码流本来就是一段段小分片, 不用. 档位每次都记: 设置里的值只在变了时才进日志, 用户发来的日志里要看得出是哪种账号
        val connections = parallelConnectionsFor(account.tier)
        logger.info {
            val mode = if (transcoded == null) "original, $connections connections" else "transcoded"
            "Cloud drive $driveId playback of $fileId: tier=${account.tier.ifBlank { "unknown" }}, $mode"
        }
        return CloudDrivePlayback(
            url,
            if (transcoded == null) {
                headers + listOfNotNull(
                    connections.takeIf { it > 1 }?.let { PlaybackRequestHints.PARALLEL_RANGE_HEADER to it.toString() },
                    PlaybackRequestHints.CACHE_KEY_HEADER to cacheKey,
                )
            } else {
                headers
            },
            subtitles,
        )
    }

    /** 账号档位 [tier] 播放原文件时并发几个连接 (见 [CloudDriveProtocol.tiers]). */
    internal fun parallelConnectionsFor(tier: String): Int =
        protocol.tierOf(tier)?.parallelConnections ?: protocol.playback.parallelConnections

    /** 播放器请求直链、转码地址与外挂字幕时要带的请求头 (见 [DrivePlaybackConfig.headers]). */
    fun playbackHeaders(cookie: String): Map<String, String> {
        val template = DriveTemplate(
            mapOf("cookie" to JsonPrimitive(cookie), "userAgent" to JsonPrimitive(protocol.http.userAgent)),
        )
        return protocol.playback.headers.mapValues { template.text(it.value) }.filterValues { it.isNotBlank() }
    }

    /** 播放时到哪里找外挂字幕. */
    private sealed interface SubtitleLookup {
        /** 视频所在的文件夹 (及里面的字幕子文件夹). */
        data object InFolder : SubtitleLookup

        /** 已经知道是哪些文件 (随分享视频一起转存的). */
        class Known(val matches: suspend () -> List<DriveSidecarSubtitles.Match<String>>) : SubtitleLookup
    }

    /**
     * 视频 [fileId] 的外挂字幕及其直链: 手动挂上的 (键 [subtitleKey]) 在前, 然后是自动找到的. 出错或超时就当没有, 不影响播放.
     *
     * @param video 已经取过的视频直链 (带着所在文件夹), 转码播放时没有
     */
    private suspend fun subtitlesOf(fileId: String, video: DriveDownload?, lookup: SubtitleLookup, subtitleKey: String): List<CloudDriveSubtitle> {
        val picked = pickedSubtitlesOf(subtitleKey).mapNotNull { DriveSidecarSubtitles.picked(it.fid, it.fileName) }
        val found = withSubtitleTimeout(fileId, "look for subtitles") {
            when (lookup) {
                is SubtitleLookup.Known -> lookup.matches()
                SubtitleLookup.InFolder -> folderSidecars(video ?: api.download(fileId))
            }
        }.orEmpty()
        val matches = DriveSidecarSubtitles.numbered(picked + found.filter { match -> picked.none { it.file == match.file } })
        if (matches.isEmpty()) return emptyList()
        val urls = withSubtitleTimeout(fileId, "get subtitle links") {
            api.downloads(matches.map { it.file }).associate { it.fid to it.url }
        } ?: return emptyList()
        return matches.mapNotNull { match -> urls[match.file]?.let { CloudDriveSubtitle(it, match.mimeType, match.label, match.language) } }
            .also { subtitles -> logger.info { "Found ${subtitles.size} subtitles for $driveId file $fileId: ${subtitles.map { it.label }}" } }
    }

    /** 找字幕的一步: 超过 [SUBTITLE_LOOKUP_TIMEOUT] 或出错都返回 null (只记日志). */
    private suspend fun <T : Any> withSubtitleTimeout(fileId: String, step: String, block: suspend () -> T): T? = try {
        withTimeoutOrNull(SUBTITLE_LOOKUP_TIMEOUT) { block() }
            .also { if (it == null) logger.warn { "Failed to $step of $driveId file $fileId: timed out" } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn { "Failed to $step of $driveId file $fileId: $e" }
        null
    }

    /** 自己网盘里视频 [video] 旁边的外挂字幕 (文件 id). 转存文件夹里放着各部番的文件, 只按名字配. */
    private suspend fun folderSidecars(video: DriveDownload): List<DriveSidecarSubtitles.Match<String>> {
        if (video.parentFid.isEmpty()) return emptyList()
        val inSaveFolder = video.parentFid == currentAccount().shareSaveFolderId
        val files = listAll(video.parentFid)
        val matches = DriveSidecarSubtitles.match(video.fileName, files, { it.fileName }, { it.isVideo }, byEpisode = !inSaveFolder)
            .ifEmpty {
                if (inSaveFolder) return@ifEmpty emptyList()
                val folders = files.filter { it.dir && DriveSidecarSubtitles.isSubtitleFolder(it.fileName) }
                if (folders.isEmpty()) return@ifEmpty emptyList()
                val inFolders = folders.take(MAX_SUBTITLE_FOLDERS).flatMap { listAll(it.fid) }
                DriveSidecarSubtitles.match(video.fileName, inFolders + files.filter { it.isVideo }, { it.fileName }, { it.isVideo })
            }
        return matches.map { DriveSidecarSubtitles.Match(it.file.fid, it.mimeType, it.label, it.language) }
    }

    companion object {
        private val logger = logger<CloudDriveService>()

        private const val MAX_FOLDER_ITEMS = 500

        /** 播放分享时转存到网盘根目录下的这个文件夹. 只动这个文件夹里的东西. */
        const val SAVE_FOLDER_NAME = "Izuko 转存"

        /** 转存文件夹里最多留几个视频, 多了删最早转存的. */
        const val MAX_SAVED_FILES = 20

        /** 转存文件夹里最多留几个字幕文件, 多了删最早转存的. */
        const val MAX_SAVED_SUBTITLES = 60

        /** 找外挂字幕最多等多久, 超时就不带字幕播放. */
        private val SUBTITLE_LOOKUP_TIMEOUT = 8.seconds

        /** 视频旁边没有字幕时, 最多再看几个字幕子文件夹. */
        private const val MAX_SUBTITLE_FOLDERS = 2

        private fun shareKey(shareId: String, fid: String) = "$shareId/$fid"

        /** 最多给多少个视频记手动挂上的字幕. */
        private const val MAX_PICKED_SUBTITLE_VIDEOS = 300

        private const val MB = 1024 * 1024

        /** 最多给多少个条目自动记文件夹. */
        private const val MAX_REMEMBERED_FOLDERS = 300

        /** 「分享搜索」最多记多少个 (数据源, 条目) 的分享文件夹. */
        private const val MAX_REMEMBERED_SHARES = 300

        /** 内存里最多记多少个最近搜到的文件. */
        private const val MAX_RECENT_MATCHES = 2000

        private fun fileSubtitleKey(fileId: String) = "file:$fileId"

        private fun shareSubtitleKey(ref: DriveShareFileRef) = "share:${ref.key}"

        private val SHARE_TOKEN_TTL = 30.minutes
        private const val MAX_CACHED_SHARE_TOKENS = 64

        /**
         * 转存文件夹里还要再放文件时, [existing] 里该删掉哪些, 才能只留 [keep] 个视频与 [keepSubtitles] 个字幕: 各自先删最早的.
         */
        internal fun filesToPrune(existing: List<DriveFile>, keep: Int, keepSubtitles: Int): List<DriveFile> {
            val (subtitles, others) = existing.partition { DriveSidecarSubtitles.isSubtitle(it.fileName) }
            return others.sortedBy { it.updatedAt }.dropLast(keep.coerceAtLeast(0)) +
                    subtitles.sortedBy { it.updatedAt }.dropLast(keepSubtitles.coerceAtLeast(0))
        }

        /**
         * 把服务端下发的 Cookie 合并进现有的 Cookie 头, 保留原有顺序.
         */
        fun mergeCookies(cookie: String, updates: Map<String, String>): String {
            val pairs = LinkedHashMap<String, String>()
            for (part in cookie.split(';')) {
                val trimmed = part.trim()
                val name = trimmed.substringBefore('=', missingDelimiterValue = "").trim()
                if (name.isNotEmpty()) pairs[name] = trimmed.substringAfter('=')
            }
            pairs.putAll(updates)
            return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        /**
         * 账号能用的最高一档转码.
         */
        internal fun bestTranscodedUrl(videos: List<DriveTranscodedVideo>): String? =
            videos.asSequence()
                .filter { it.accessible && !it.url.isNullOrBlank() }
                .maxByOrNull { it.height }
                ?.url
    }
}

class CloudDrivePlayback(
    val url: String,
    val headers: Map<String, String>,
    /** 视频旁边的外挂字幕, 请求时带同样的 [headers]. */
    val subtitles: List<CloudDriveSubtitle> = emptyList(),
)

/**
 * 外挂字幕的直链.
 *
 * @param label 播放器字幕菜单里显示的名字
 * @param language BCP 47 语言标记, 认不出为 null
 */
class CloudDriveSubtitle(val url: String, val mimeType: String, val label: String, val language: String?)

sealed interface CloudDriveQrLoginState {
    data object Loading : CloudDriveQrLoginState

    /**
     * 显示二维码, 等用户用网盘 App 扫码并确认.
     *
     * @param expiresAtMillis 等到这一刻 (墙上时间, 毫秒) 还没确认就放弃, 流结束于 [Expired]; 界面据此倒数
     */
    data class WaitingForScan(val qrContent: String, val expiresAtMillis: Long) : CloudDriveQrLoginState

    /** 用户已在 App 里确认, 正在换取登录 Cookie、读账号信息. */
    data object Confirmed : CloudDriveQrLoginState

    data class Success(val nickname: String) : CloudDriveQrLoginState

    data object Expired : CloudDriveQrLoginState

    data class Failed(val message: String) : CloudDriveQrLoginState
}
