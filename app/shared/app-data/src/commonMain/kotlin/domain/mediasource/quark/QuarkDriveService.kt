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
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
) {
    constructor(settings: Settings<QuarkConfig>) : this(settings, QuarkApi.createHttpClient())

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
        override suspend fun search(keyword: String): List<QuarkFile> = api.search(keyword).files

        override suspend fun listFolder(folderId: String): List<QuarkFile> {
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
    }

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
        emit(QuarkQrLoginState.WaitingForScan(QuarkApi.qrCodeContent(token.token)))

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
        val url = when (config.playbackMode) {
            QuarkPlaybackMode.ORIGINAL -> api.downloadUrl(fileId)
            QuarkPlaybackMode.TRANSCODED -> bestTranscodedUrl(api.transcodedVideos(fileId))
                ?: api.downloadUrl(fileId)
        }
        return QuarkPlayback(url, playbackHeaders(settings.flow.first().cookie))
    }

    companion object {
        private val logger = logger<QuarkDriveService>()

        private const val MAX_FOLDER_ITEMS = 500

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
     */
    data class WaitingForScan(val qrContent: String) : QuarkQrLoginState

    data class Success(val nickname: String) : QuarkQrLoginState

    data object Expired : QuarkQrLoginState

    data class Failed(val message: String) : QuarkQrLoginState
}
