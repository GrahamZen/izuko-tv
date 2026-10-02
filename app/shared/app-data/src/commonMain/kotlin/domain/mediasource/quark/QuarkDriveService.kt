/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.quark

import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.preference.QuarkConfig
import me.him188.ani.app.data.models.preference.QuarkPickedFile
import me.him188.ani.app.data.models.preference.QuarkPickedFolder
import me.him188.ani.app.data.models.preference.QuarkPickedSubtitle
import me.him188.ani.app.data.models.preference.QuarkPlaybackMode
import me.him188.ani.app.data.models.preference.QuarkSubjectPicks
import me.him188.ani.app.data.network.TmdbSubjectMapRepository
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 夸克网盘账号与文件访问. 数据源、播放解析与设置页共用一个实例.
 *
 * 登录态就是 [QuarkConfig.cookie]; 接口响应里轮换的 Cookie 在这里合并后写回设置.
 */
class QuarkDriveService internal constructor(
    private val settings: Settings<QuarkConfig>,
    httpClient: HttpClient,
    driveHosts: List<String> = QuarkApi.DRIVE_HOSTS,
    /** 按 TMDB 季集整理的文件换算成条目的集 (三个夸克数据源共用) */
    internal val episodeNumbering: TmdbEpisodeNumbering = TmdbEpisodeNumbering.None,
) {
    constructor(settings: Settings<QuarkConfig>, tmdbSubjectMap: TmdbSubjectMapRepository) :
            this(settings, QuarkApi.createHttpClient(), episodeNumbering = TmdbEpisodeNumbering.of(tmdbSubjectMap))

    private val cookieLock = Mutex()

    private val cookieStore = object : QuarkCookieStore {
        override suspend fun get(): String = settings.flow.first().cookie

        override suspend fun onServerCookies(cookies: Map<String, String>) {
            cookieLock.withLock {
                val current = settings.flow.first()
                if (!current.isLoggedIn) return
                val merged = mergeCookies(current.cookie, cookies)
                if (merged != current.cookie) settings.set(current.copy(cookie = merged))
            }
        }
    }

    internal val api = QuarkApi(httpClient, cookieStore, driveHosts)

    val config: Flow<QuarkConfig> get() = settings.flow

    val isLoggedIn: Flow<Boolean> = settings.flow.map { it.isLoggedIn }

    internal val browser = object : QuarkDriveBrowser {
        /**
         * 只取第一页, 文件夹在前: 一部番常整个放在一个文件夹里, 而名字相同的单个文件 (各季、字幕、.nfo) 能把第一页占满,
         * 文件在前的话那个文件夹会被挤到后面几页.
         */
        override suspend fun search(keyword: String): List<QuarkFile> {
            val files = api.search(keyword).files
            // 转存来的文件归「夸克分享搜索」数据源, 不在这里重复出现
            val saveFolder = settings.flow.first().shareSaveFolderId.ifEmpty { return files }
            return files.filter { it.fid != saveFolder && it.parentFid != saveFolder }
        }

        override suspend fun listFolder(folderId: String): List<QuarkFile> = listAll(folderId)
    }

    private suspend fun listAll(folderId: String): List<QuarkFile> {
        val result = mutableListOf<QuarkFile>()
        var page = 1
        while (result.size < MAX_FOLDER_ITEMS) {
            val list = api.listFolder(folderId, page)
            result += list.files
            if (list.files.size < QuarkApi.LIST_PAGE_SIZE || result.size >= list.total) break
            page++
        }
        return result
    }

    // region 手动指定 (Web 控制台「从夸克网盘挑」)

    private val pickMatcher by lazy { QuarkSubjectMatcher(browser, episodeNumbering) }

    suspend fun picksOf(subjectId: Int): QuarkSubjectPicks = settings.flow.first().subjectPicks[subjectId] ?: QuarkSubjectPicks()

    /** 记下「条目 [subjectId] 就在文件夹 [folder] 里」. */
    suspend fun pickFolder(subjectId: Int, folder: QuarkFile) {
        updatePicks(subjectId) { picks ->
            picks.copy(folders = picks.folders.filterNot { it.fid == folder.fid } + QuarkPickedFolder(folder.fid, folder.fileName))
        }
        logger.info { "Picked Quark folder ${folder.fid} (${folder.fileName}) for subject $subjectId" }
    }

    /** 记下「文件 [file] 是条目 [subjectId] 的第 [episode] 集」. */
    suspend fun pickFile(subjectId: Int, file: QuarkFile, episode: EpisodeSort) {
        val picked = QuarkPickedFile(file.fid, file.fileName, file.parentFid, file.size, episode.toString())
        updatePicks(subjectId) { picks -> picks.copy(files = picks.files.filterNot { it.fid == file.fid } + picked) }
        logger.info { "Picked Quark file ${file.fid} (${file.fileName}) as episode $episode of subject $subjectId" }
    }

    /** 忘掉条目 [subjectId] 下指定的文件夹或文件 [fid]. */
    suspend fun forgetPick(subjectId: Int, fid: String) {
        updatePicks(subjectId) { picks ->
            picks.copy(folders = picks.folders.filterNot { it.fid == fid }, files = picks.files.filterNot { it.fid == fid })
        }
    }

    private suspend fun updatePicks(subjectId: Int, update: (QuarkSubjectPicks) -> QuarkSubjectPicks) {
        // 与 Cookie 轮换写回同一份配置, 同一把锁, 免得互相覆盖
        cookieLock.withLock {
            val current = settings.flow.first()
            requireLoggedIn()
            val updated = update(current.subjectPicks[subjectId] ?: QuarkSubjectPicks())
            val all = if (updated.isEmpty) current.subjectPicks - subjectId else current.subjectPicks + (subjectId to updated)
            if (all != current.subjectPicks) settings.set(current.copy(subjectPicks = all))
        }
    }

    /** 给手动挑选用的搜索, 与数据源搜的一样 (第一页, 文件夹在前, 转存文件夹里的不列). */
    suspend fun searchForPicking(keyword: String): List<QuarkFile> {
        requireLoggedIn()
        return browser.search(keyword)
    }

    /** 给手动挑选用的列目录 (根目录是 [QuarkApi.ROOT_FOLDER_ID]), 文件夹在前. */
    suspend fun listForPicking(folderId: String): List<QuarkFile> {
        requireLoggedIn()
        return listAll(folderId)
    }

    /** 指定的文件夹 (往下几层) 里对上 [request] 这个条目的剧集, 规则同「夸克网盘」数据源读指定的文件夹. */
    internal suspend fun matchPickedFolder(request: MediaFetchRequest, folder: QuarkPickedFolder): List<QuarkSubjectMatcher.MatchedFile> =
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
    suspend fun episodesInFolder(request: MediaFetchRequest, folder: QuarkFile): List<Triple<QuarkFile, List<String>, EpisodeSort>> =
        matchPickedFolder(request, QuarkPickedFolder(folder.fid, folder.fileName)).map { Triple(it.file, it.folders, it.episode) }

    // endregion

    // region 手动挂上的字幕 (Web 控制台「从夸克网盘挑」里点字幕文件)

    /**
     * 资源 [media] 在「手动挂字幕」里的键: 自己网盘的视频按文件 id, 分享里的按分享与分享里的文件 (转存出来的副本 id 会变).
     * 不是夸克的资源时为 null.
     */
    fun subtitleKeyOf(media: Media): String? {
        val uri = (media.download as? ResourceLocation.HttpStreamingFile)?.uri ?: return null
        QuarkMediaSource.fileIdOf(uri)?.let { return fileSubtitleKey(it) }
        return QuarkShareFileRef.parse(uri)?.let { shareSubtitleKey(it) }
    }

    fun isSubtitleFile(fileName: String): Boolean = QuarkSidecarSubtitles.isSubtitle(fileName)

    /** 手动挂上字幕文件 [fileName] 后它在播放器字幕菜单里叫什么 (与别的重名时播放器里再加序号). 不是字幕文件时为 null. */
    fun subtitleLabelOf(fileName: String): String? = QuarkSidecarSubtitles.picked(Unit, fileName)?.label

    suspend fun pickedSubtitlesOf(videoKey: String): List<QuarkPickedSubtitle> =
        settings.flow.first().pickedSubtitles[videoKey].orEmpty()

    /** 给视频 [videoKey] 挂上网盘里的字幕文件 [file], 排在最前 (没有内封字幕时播放器默认显示它). */
    suspend fun pickSubtitle(videoKey: String, file: QuarkFile) {
        updatePickedSubtitles(videoKey) { list ->
            (listOf(QuarkPickedSubtitle(file.fid, file.fileName)) + list.filterNot { it.fid == file.fid }).take(QuarkSidecarSubtitles.MAX_PER_VIDEO)
        }
        logger.info { "Picked Quark subtitle ${file.fid} (${file.fileName}) for $videoKey" }
    }

    suspend fun forgetSubtitle(videoKey: String, fid: String) {
        updatePickedSubtitles(videoKey) { list -> list.filterNot { it.fid == fid } }
    }

    private suspend fun updatePickedSubtitles(videoKey: String, update: (List<QuarkPickedSubtitle>) -> List<QuarkPickedSubtitle>) {
        cookieLock.withLock {
            val current = settings.flow.first()
            requireLoggedIn()
            val updated = update(current.pickedSubtitles[videoKey].orEmpty())
            // 刚改过的挪到最后; 记的视频太多时从最早改过的删起
            val others = current.pickedSubtitles - videoKey
            val all = (if (updated.isEmpty()) others else others + (videoKey to updated))
                .entries.toList().takeLast(MAX_PICKED_SUBTITLE_VIDEOS).associate { it.key to it.value }
            if (all != current.pickedSubtitles) settings.set(current.copy(pickedSubtitles = all))
        }
    }

    // endregion

    // region 分享

    private class CachedShareToken(val token: QuarkShareToken, val time: TimeMark)

    private val shareTokenLock = Mutex()
    private val shareTokens = LinkedHashMap<String, CachedShareToken>()

    /** 分享的查看令牌, 缓存 [SHARE_TOKEN_TTL]: 列一个分享的文件夹要发好几次请求, 每次都带它. */
    private suspend fun shareToken(shareId: String, passcode: String, refresh: Boolean = false): QuarkShareToken {
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

    internal val shareBrowser = object : QuarkShareBrowser {
        override suspend fun open(shareId: String, passcode: String): String = shareToken(shareId, passcode).title

        override suspend fun listFolder(shareId: String, passcode: String, folderId: String): List<QuarkShareFile> {
            val result = mutableListOf<QuarkShareFile>()
            var page = 1
            var token = shareToken(shareId, passcode)
            var refreshed = false
            while (result.size < MAX_FOLDER_ITEMS) {
                val list = try {
                    api.listShareFolder(shareId, token.stoken, folderId, page)
                } catch (e: QuarkShareUnavailableException) {
                    // 缓存的令牌可能过期了, 换一个再试一次
                    if (refreshed) throw e
                    refreshed = true
                    token = shareToken(shareId, passcode, refresh = true)
                    continue
                }
                result += list.files
                if (list.files.size < QuarkApi.LIST_PAGE_SIZE || result.size >= list.total) break
                page++
            }
            return result
        }
    }

    private val saveLock = Mutex()

    /** 转存过的分享文件 ([QuarkShareFileRef.key]) 到转存后的文件 id. */
    private val savedFiles = HashMap<String, String>()

    /**
     * 网盘根目录下的转存文件夹 [SAVE_FOLDER_NAME], 没有就新建; 找到或建好后记进 [QuarkConfig.shareSaveFolderId].
     */
    private suspend fun findSaveFolder(): String {
        settings.flow.first().shareSaveFolderId.takeIf { it.isNotEmpty() }?.let { return it }
        val folder = listAll(QuarkApi.ROOT_FOLDER_ID).firstOrNull { it.dir && it.fileName == SAVE_FOLDER_NAME }?.fid
            ?: api.createFolder(SAVE_FOLDER_NAME).also { logger.info { "Created Quark folder $SAVE_FOLDER_NAME: $it" } }
        setSaveFolder(folder)
        return folder
    }

    /**
     * 转存文件夹 [SAVE_FOLDER_NAME] 里的文件 (从分享转存过来的副本). 没登录或还没有这个文件夹时为空, 不新建.
     */
    internal suspend fun savedShareCopies(): List<QuarkFile> {
        val config = settings.flow.first()
        if (!config.isLoggedIn) return emptyList()
        val folder = config.shareSaveFolderId.takeIf { it.isNotEmpty() }
            ?: listAll(QuarkApi.ROOT_FOLDER_ID).firstOrNull { it.dir && it.fileName == SAVE_FOLDER_NAME }?.fid
            ?: return emptyList()
        return listAll(folder).filter { !it.dir }
    }

    private suspend fun setSaveFolder(folderId: String) {
        cookieLock.withLock {
            val current = settings.flow.first()
            if (current.isLoggedIn && current.shareSaveFolderId != folderId) {
                settings.set(current.copy(shareSaveFolderId = folderId))
            }
        }
    }

    /**
     * 播放分享里的一个文件: 转存到自己网盘的 [SAVE_FOLDER_NAME] 再取地址 (分享里的文件不能直接取直链).
     * 视频旁边的外挂字幕在同一次转存里一起存过去. 同一个文件只转存一次; 文件夹里的视频超过 [MAX_SAVED_FILES] 个、
     * 字幕超过 [MAX_SAVED_SUBTITLES] 个时删掉最早转存的.
     */
    suspend fun resolveSharePlayback(ref: QuarkShareFileRef): QuarkPlayback {
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
                            QuarkSidecarSubtitles.Match(it, match.mimeType, match.label, match.language)
                        }
                    }
                }
            }
        }
        return try {
            resolvePlayback(fileId, subtitles, shareSubtitleKey(ref))
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuarkAuthException) {
            throw e
        } catch (e: QuarkApiException) {
            // 转存的文件可能被用户删了: 重新转存一次
            logger.warn { "Saved Quark file $fileId is not playable, saving again: ${e.message}" }
            saveLock.withLock {
                savedFiles.remove(ref.key)
                sidecarFiles.forEach { savedFiles.remove(shareKey(ref.shareId, it.fid)) }
            }
            resolvePlayback(saveShareFile(ref, sidecarFiles), subtitles, shareSubtitleKey(ref))
        }
    }

    /**
     * 分享里视频 [ref] 旁边的外挂字幕. 分享里没法找 (旧资源没记所在文件夹、分享打不开、超时) 时为 null.
     */
    private suspend fun shareSidecars(ref: QuarkShareFileRef): List<QuarkSidecarSubtitles.Match<QuarkShareFile>>? {
        if (ref.folderId.isEmpty()) return null
        return try {
            withTimeoutOrNull(SUBTITLE_LOOKUP_TIMEOUT) {
                val files = shareBrowser.listFolder(ref.shareId, ref.passcode, ref.folderId)
                val isVideo = { file: QuarkShareFile -> !file.dir && file.category == "video" }
                QuarkSidecarSubtitles.match(ref.fileName, files, { it.fileName }, isVideo).ifEmpty {
                    val folders = files.filter { it.dir && QuarkSidecarSubtitles.isSubtitleFolder(it.fileName) }
                    if (folders.isEmpty()) return@ifEmpty emptyList()
                    val inFolders = folders.take(MAX_SUBTITLE_FOLDERS).flatMap { shareBrowser.listFolder(ref.shareId, ref.passcode, it.fid) }
                    QuarkSidecarSubtitles.match(ref.fileName, inFolders + files.filter(isVideo), { it.fileName }, isVideo)
                }.filter { it.file.shareFidToken.isNotEmpty() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn { "Failed to look for subtitles next to ${ref.key}: $e" }
            null
        }
    }

    private suspend fun saveShareFile(ref: QuarkShareFileRef, sidecars: List<QuarkShareFile>): String = saveLock.withLock {
        try {
            saveShareFileLocked(ref, sidecars, findSaveFolder())
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuarkAuthException) {
            throw e
        } catch (e: QuarkShareUnavailableException) {
            throw e
        } catch (e: QuarkApiException) {
            // 记下的转存文件夹可能被用户删了: 重新找 (没有就新建) 再试一次
            logger.warn { "Saving to the remembered Quark folder failed, looking it up again: ${e.message}" }
            setSaveFolder("")
            saveShareFileLocked(ref, sidecars, findSaveFolder())
        }
    }

    /**
     * 转存视频 [ref] 与它的字幕 [sidecars] (已经在 [folder] 里的不再转存), 返回视频转存后的 id.
     * 字幕转存失败不影响视频.
     */
    private suspend fun saveShareFileLocked(ref: QuarkShareFileRef, sidecars: List<QuarkShareFile>, folder: String): String {
        val existing = listAll(folder).filter { !it.dir }
        val video = QuarkShareFile(fid = ref.fid, fileName = ref.fileName, size = ref.size, shareFidToken = ref.shareFidToken)
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
            api.saveFromShare(ref.shareId, token.stoken, missing, folder)
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuarkAuthException) {
            throw e
        } catch (e: QuarkShareUnavailableException) {
            throw e
        } catch (e: QuarkApiException) {
            if (missing.size == 1 && video in missing) throw e
            logger.warn { "Saving ${missing.size} files of ${ref.key} failed, saving the video alone: ${e.message}" }
            if (video !in missing) return savedFiles.getValue(ref.key)
            missing = listOf(video)
            api.saveFromShare(ref.shareId, token.stoken, missing, folder)
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
        logger.info { "Saved ${missing.size} files of Quark share ${ref.key} (${ref.fileName})" }
        val newSubtitles = missing.count { QuarkSidecarSubtitles.isSubtitle(it.fileName) }
        val (reusedSubtitles, reusedVideos) = existing.filter { it.fid in reused }.partition { QuarkSidecarSubtitles.isSubtitle(it.fileName) }
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
        return savedFiles[ref.key] ?: throw QuarkApiException("转存后没找到 ${ref.fileName}")
    }

    // endregion

    suspend fun requireLoggedIn() {
        if (!settings.flow.first().isLoggedIn) throw QuarkAuthException()
    }

    /**
     * 向服务端核对登录态, 顺便更新昵称与会员类型. 登录失效时抛 [QuarkAuthException].
     */
    suspend fun refreshAccount(): QuarkConfig {
        requireLoggedIn()
        val member = api.member()
        val nickname = try {
            api.nickname()
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuarkAuthException) {
            throw e
        } catch (e: Throwable) {
            logger.warn { "Failed to fetch Quark nickname: $e" }
            null
        }
        return cookieLock.withLock {
            val current = settings.flow.first()
            val updated = current.copy(
                memberType = member.memberType,
                nickname = nickname ?: current.nickname,
            )
            if (updated != current) settings.set(updated)
            updated
        }
    }

    /**
     * 用手动填写的 Cookie 登录: 先向服务端核对, 通过才保存.
     */
    suspend fun loginWithCookie(cookie: String): QuarkConfig {
        val trimmed = cookie.trim().removePrefix("Cookie:").trim()
        require(trimmed.contains("__pus=")) { "Cookie 里没有 __pus, 不是登录后的夸克 Cookie" }
        val previous = settings.flow.first()
        settings.set(QuarkConfig(cookie = trimmed, playbackMode = previous.playbackMode))
        return try {
            refreshAccount()
        } catch (e: Throwable) {
            settings.set(previous)
            throw e
        }
    }

    suspend fun logout() {
        cookieLock.withLock {
            val current = settings.flow.first()
            settings.set(QuarkConfig(playbackMode = current.playbackMode))
        }
    }

    suspend fun setPlaybackMode(mode: QuarkPlaybackMode) {
        cookieLock.withLock {
            val current = settings.flow.first()
            if (current.playbackMode != mode) settings.set(current.copy(playbackMode = mode))
        }
    }

    /**
     * 扫码登录. 二维码过期后流结束于 [QuarkQrLoginState.Expired], 重新收集即得到新的二维码.
     */
    fun qrLogin(pollInterval: Duration = 2.seconds, timeout: Duration = 5.minutes): Flow<QuarkQrLoginState> = flow {
        emit(QuarkQrLoginState.Loading)
        val token = try {
            api.requestQrToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to request Quark QR token" }
            emit(QuarkQrLoginState.Failed(e.message ?: e.toString()))
            return@flow
        }
        // 界面上倒数用墙上时间; 下面判超时仍用单调时钟 (不受改系统时间影响), 两者从同一刻起算
        emit(QuarkQrLoginState.WaitingForScan(QuarkApi.qrCodeContent(token.token), currentTimeMillis() + timeout.inWholeMilliseconds))

        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (deadline.hasNotPassedNow()) {
            delay(pollInterval)
            val result = try {
                api.pollQrToken(token.token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn { "Polling Quark QR token failed: $e" }
                continue
            }
            when (result) {
                QuarkQrPollResult.Waiting -> continue
                QuarkQrPollResult.Expired -> break
                is QuarkQrPollResult.Confirmed -> {
                    // 手机上确认了, 还要换票、取账号信息 (几个请求, 慢的时候十几秒): 先让界面收起二维码说一声
                    emit(QuarkQrLoginState.Confirmed)
                    try {
                        val exchange = api.exchangeServiceTicket(result.serviceTicket)
                        val cookies = token.cookies + exchange.cookies
                        if (cookies["__pus"].isNullOrBlank()) {
                            emit(QuarkQrLoginState.Failed("夸克没有返回登录 Cookie"))
                            return@flow
                        }
                        val previous = settings.flow.first()
                        settings.set(
                            QuarkConfig(
                                cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
                                nickname = exchange.nickname.orEmpty(),
                                playbackMode = previous.playbackMode,
                            ),
                        )
                        val account = try {
                            refreshAccount()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: QuarkAuthException) {
                            throw e
                        } catch (e: Throwable) {
                            // 登录已经成功, 会员信息之后再取
                            logger.warn { "Failed to refresh Quark account after login: $e" }
                            settings.flow.first()
                        }
                        logger.info { "Quark QR login succeeded" }
                        emit(QuarkQrLoginState.Success(account.nickname))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logger.warn(e) { "Quark QR login failed after confirmation" }
                        emit(QuarkQrLoginState.Failed(e.message ?: e.toString()))
                    }
                    return@flow
                }
            }
        }
        emit(QuarkQrLoginState.Expired)
    }

    /**
     * 按当前的播放方式取一个文件的播放地址, 连同视频旁边的外挂字幕. 播放器请求这些地址时必须带上 [QuarkPlayback.headers].
     */
    suspend fun resolvePlayback(fileId: String): QuarkPlayback = resolvePlayback(fileId, SubtitleLookup.InFolder, fileSubtitleKey(fileId))

    /** @param subtitleKey 手动挂字幕的键 (见 [subtitleKeyOf]) */
    private suspend fun resolvePlayback(fileId: String, subtitleLookup: SubtitleLookup, subtitleKey: String): QuarkPlayback {
        requireLoggedIn()
        val config = settings.flow.first()
        val transcoded = when (config.playbackMode) {
            QuarkPlaybackMode.ORIGINAL -> null
            QuarkPlaybackMode.TRANSCODED -> bestTranscodedUrl(api.transcodedVideos(fileId))
        }
        val download = if (transcoded == null) api.download(fileId) else null
        val url = transcoded ?: download!!.url
        val headers = playbackHeaders(settings.flow.first().cookie)
        val subtitles = subtitlesOf(fileId, download, subtitleLookup, subtitleKey)
        // 原文件直链每个连接有速度上限 (实测单连接 1.3 MB/s, 4 路 4.2 MB/s), 一个连接常常跟不上码率, 让播放器并发分块取;
        // 转码流本来就是一段段小分片, 不用
        return QuarkPlayback(
            url,
            if (transcoded == null) headers + (PlaybackRequestHints.PARALLEL_RANGE_HEADER to PARALLEL_CONNECTIONS.toString()) else headers,
            subtitles,
        )
    }

    /** 播放时到哪里找外挂字幕. */
    private sealed interface SubtitleLookup {
        /** 视频所在的文件夹 (及里面的字幕子文件夹). */
        data object InFolder : SubtitleLookup

        /** 已经知道是哪些文件 (随分享视频一起转存的). */
        class Known(val matches: suspend () -> List<QuarkSidecarSubtitles.Match<String>>) : SubtitleLookup
    }

    /**
     * 视频 [fileId] 的外挂字幕及其直链: 手动挂上的 (键 [subtitleKey]) 在前, 然后是自动找到的. 出错或超时就当没有, 不影响播放.
     *
     * @param video 已经取过的视频直链 (带着所在文件夹), 转码播放时没有
     */
    private suspend fun subtitlesOf(fileId: String, video: QuarkDownload?, lookup: SubtitleLookup, subtitleKey: String): List<QuarkSubtitle> {
        val picked = pickedSubtitlesOf(subtitleKey).mapNotNull { QuarkSidecarSubtitles.picked(it.fid, it.fileName) }
        val found = withSubtitleTimeout(fileId, "look for subtitles") {
            when (lookup) {
                is SubtitleLookup.Known -> lookup.matches()
                SubtitleLookup.InFolder -> folderSidecars(video ?: api.download(fileId))
            }
        }.orEmpty()
        val matches = QuarkSidecarSubtitles.numbered(picked + found.filter { match -> picked.none { it.file == match.file } })
        if (matches.isEmpty()) return emptyList()
        val urls = withSubtitleTimeout(fileId, "get subtitle links") {
            api.downloads(matches.map { it.file }).associate { it.fid to it.url }
        } ?: return emptyList()
        return matches.mapNotNull { match -> urls[match.file]?.let { QuarkSubtitle(it, match.mimeType, match.label, match.language) } }
            .also { subtitles -> logger.info { "Found ${subtitles.size} subtitles for Quark file $fileId: ${subtitles.map { it.label }}" } }
    }

    /** 找字幕的一步: 超过 [SUBTITLE_LOOKUP_TIMEOUT] 或出错都返回 null (只记日志). */
    private suspend fun <T : Any> withSubtitleTimeout(fileId: String, step: String, block: suspend () -> T): T? = try {
        withTimeoutOrNull(SUBTITLE_LOOKUP_TIMEOUT) { block() }
            .also { if (it == null) logger.warn { "Failed to $step of Quark file $fileId: timed out" } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn { "Failed to $step of Quark file $fileId: $e" }
        null
    }

    /** 自己网盘里视频 [video] 旁边的外挂字幕 (文件 id). 转存文件夹里放着各部番的文件, 只按名字配. */
    private suspend fun folderSidecars(video: QuarkDownload): List<QuarkSidecarSubtitles.Match<String>> {
        if (video.parentFid.isEmpty()) return emptyList()
        val inSaveFolder = video.parentFid == settings.flow.first().shareSaveFolderId
        val files = listAll(video.parentFid)
        val matches = QuarkSidecarSubtitles.match(video.fileName, files, { it.fileName }, { it.isVideo }, byEpisode = !inSaveFolder)
            .ifEmpty {
                if (inSaveFolder) return@ifEmpty emptyList()
                val folders = files.filter { it.dir && QuarkSidecarSubtitles.isSubtitleFolder(it.fileName) }
                if (folders.isEmpty()) return@ifEmpty emptyList()
                val inFolders = folders.take(MAX_SUBTITLE_FOLDERS).flatMap { listAll(it.fid) }
                QuarkSidecarSubtitles.match(video.fileName, inFolders + files.filter { it.isVideo }, { it.fileName }, { it.isVideo })
            }
        return matches.map { QuarkSidecarSubtitles.Match(it.file.fid, it.mimeType, it.label, it.language) }
    }

    companion object {
        private val logger = logger<QuarkDriveService>()

        private const val MAX_FOLDER_ITEMS = 500

        /** 播放原文件时并发几个连接. */
        const val PARALLEL_CONNECTIONS = 4

        /** 网盘根目录的文件夹 id. */
        const val ROOT_FOLDER_ID = QuarkApi.ROOT_FOLDER_ID

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

        private fun fileSubtitleKey(fileId: String) = "file:$fileId"

        private fun shareSubtitleKey(ref: QuarkShareFileRef) = "share:${ref.key}"

        private val SHARE_TOKEN_TTL = 30.minutes
        private const val MAX_CACHED_SHARE_TOKENS = 64

        /**
         * 转存文件夹里还要再放文件时, [existing] 里该删掉哪些, 才能只留 [keep] 个视频与 [keepSubtitles] 个字幕: 各自先删最早的.
         */
        internal fun filesToPrune(existing: List<QuarkFile>, keep: Int, keepSubtitles: Int): List<QuarkFile> {
            val (subtitles, others) = existing.partition { QuarkSidecarSubtitles.isSubtitle(it.fileName) }
            return others.sortedBy { it.updatedAt }.dropLast(keep.coerceAtLeast(0)) +
                    subtitles.sortedBy { it.updatedAt }.dropLast(keepSubtitles.coerceAtLeast(0))
        }

        /**
         * 请求直链、转码 m3u8 与分片都要带的请求头. 缺 Cookie 时 CDN 回 412.
         */
        fun playbackHeaders(cookie: String): Map<String, String> = mapOf(
            HttpHeaders.Cookie to cookie,
            HttpHeaders.UserAgent to QuarkApi.USER_AGENT,
            HttpHeaders.Referrer to QuarkApi.REFERER,
        )

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
        internal fun bestTranscodedUrl(videos: List<QuarkTranscodedVideo>): String? =
            videos.asSequence()
                .filter { it.accessible && !it.videoInfo?.url.isNullOrBlank() }
                .maxByOrNull { it.videoInfo?.height ?: 0 }
                ?.videoInfo?.url
    }
}

class QuarkPlayback(
    val url: String,
    val headers: Map<String, String>,
    /** 视频旁边的外挂字幕, 请求时带同样的 [headers]. */
    val subtitles: List<QuarkSubtitle> = emptyList(),
)

/**
 * 外挂字幕的直链.
 *
 * @param label 播放器字幕菜单里显示的名字
 * @param language BCP 47 语言标记, 认不出为 null
 */
class QuarkSubtitle(val url: String, val mimeType: String, val label: String, val language: String?)

sealed interface QuarkQrLoginState {
    data object Loading : QuarkQrLoginState

    /**
     * 显示二维码, 等用户用夸克 App 扫码并确认.
     *
     * @param expiresAtMillis 等到这一刻 (墙上时间, 毫秒) 还没确认就放弃, 流结束于 [Expired]; 界面据此倒数
     */
    data class WaitingForScan(val qrContent: String, val expiresAtMillis: Long) : QuarkQrLoginState

    /** 用户已在夸克 App 里确认, 正在换取登录 Cookie、读账号信息. */
    data object Confirmed : QuarkQrLoginState

    data class Success(val nickname: String) : QuarkQrLoginState

    data object Expired : QuarkQrLoginState

    data class Failed(val message: String) : QuarkQrLoginState
}
