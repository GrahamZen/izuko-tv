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
import me.him188.ani.app.data.models.preference.QuarkConfig
import me.him188.ani.app.data.models.preference.QuarkPlaybackMode
import me.him188.ani.app.data.network.TmdbSubjectMapRepository
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.platform.PlaybackRequestHints
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
     * 同一个文件只转存一次; 文件夹里超过 [MAX_SAVED_FILES] 个时删掉最早转存的.
     */
    suspend fun resolveSharePlayback(ref: QuarkShareFileRef): QuarkPlayback {
        requireLoggedIn()
        val fileId = saveLock.withLock { savedFiles[ref.key] } ?: saveShareFile(ref)
        return try {
            resolvePlayback(fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuarkAuthException) {
            throw e
        } catch (e: QuarkApiException) {
            // 转存的文件可能被用户删了: 重新转存一次
            logger.warn { "Saved Quark file $fileId is not playable, saving again: ${e.message}" }
            saveLock.withLock { savedFiles.remove(ref.key) }
            resolvePlayback(saveShareFile(ref))
        }
    }

    private suspend fun saveShareFile(ref: QuarkShareFileRef): String = saveLock.withLock {
        try {
            saveShareFileLocked(ref, findSaveFolder())
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
            saveShareFileLocked(ref, findSaveFolder())
        }
    }

    private suspend fun saveShareFileLocked(ref: QuarkShareFileRef, folder: String): String {
        val existing = listAll(folder).filter { !it.dir }
        existing.firstOrNull { it.fileName == ref.fileName && (ref.size <= 0 || it.size == ref.size) }?.let {
            savedFiles[ref.key] = it.fid
            return it.fid
        }
        val token = shareToken(ref.shareId, ref.passcode)
        val shareFile = QuarkShareFile(fid = ref.fid, fileName = ref.fileName, size = ref.size, shareFidToken = ref.shareFidToken)
        val saved = api.saveFromShare(ref.shareId, token.stoken, shareFile, folder)
        logger.info { "Saved Quark share file ${ref.key} (${ref.fileName}) as $saved" }
        savedFiles[ref.key] = saved
        val stale = filesToPrune(existing, MAX_SAVED_FILES - 1)
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
        return saved
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
     * 按当前的播放方式取一个文件的播放地址. 播放器请求这个地址时必须带上 [QuarkPlayback.headers].
     */
    suspend fun resolvePlayback(fileId: String): QuarkPlayback {
        requireLoggedIn()
        val config = settings.flow.first()
        val transcoded = when (config.playbackMode) {
            QuarkPlaybackMode.ORIGINAL -> null
            QuarkPlaybackMode.TRANSCODED -> bestTranscodedUrl(api.transcodedVideos(fileId))
        }
        val url = transcoded ?: api.downloadUrl(fileId)
        val headers = playbackHeaders(settings.flow.first().cookie)
        // 原文件直链每个连接有速度上限 (实测单连接 1.3 MB/s, 4 路 4.2 MB/s), 一个连接常常跟不上码率, 让播放器并发分块取;
        // 转码流本来就是一段段小分片, 不用
        return QuarkPlayback(
            url,
            if (transcoded == null) headers + (PlaybackRequestHints.PARALLEL_RANGE_HEADER to PARALLEL_CONNECTIONS.toString()) else headers,
        )
    }

    companion object {
        private val logger = logger<QuarkDriveService>()

        private const val MAX_FOLDER_ITEMS = 500

        /** 播放原文件时并发几个连接. */
        const val PARALLEL_CONNECTIONS = 4

        /** 播放分享时转存到网盘根目录下的这个文件夹. 只动这个文件夹里的东西. */
        const val SAVE_FOLDER_NAME = "Izuko 转存"

        /** 转存文件夹里最多留几个文件, 多了删最早转存的. */
        const val MAX_SAVED_FILES = 20

        private val SHARE_TOKEN_TTL = 30.minutes
        private const val MAX_CACHED_SHARE_TOKENS = 64

        /**
         * 转存文件夹里还要再放一个文件时, [existing] 里该删掉哪些, 才能只留 [keep] 个: 先删最早的.
         */
        internal fun filesToPrune(existing: List<QuarkFile>, keep: Int): List<QuarkFile> =
            existing.sortedBy { it.updatedAt }.dropLast(keep.coerceAtLeast(0))

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
)

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
