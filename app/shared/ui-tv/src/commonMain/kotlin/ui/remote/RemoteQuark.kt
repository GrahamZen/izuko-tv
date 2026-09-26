/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.data.models.preference.QuarkPlaybackMode
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.mediasource.quark.QuarkAuthException
import me.him188.ani.app.domain.mediasource.quark.QuarkDriveService
import me.him188.ani.app.domain.mediasource.quark.QuarkMediaSource
import me.him188.ani.app.domain.mediasource.quark.QuarkQrLoginState
import me.him188.ani.app.domain.mediasource.quark.ensureQuarkMediaSourceAdded
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.foundation.lan.LanHttpResponse
import me.him188.ani.app.ui.foundation.lan.encodeQrCode
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台「数据源」页里的夸克网盘卡片: 登录状态、扫码登录、填 Cookie、退出、转码开关、添加数据源.
 * 与电视设置页的「夸克网盘」分组做的是同一件事, 登录态同存在 [QuarkDriveService] 里.
 *
 * 扫码登录在电视这边跑 ([QuarkDriveService.qrLogin]), 网页轮询 `api/quark` 拿当前状态与二维码:
 * 手机上可以直接点开二维码里的链接 (装了夸克 App 会跳过去确认), 也可以用另一台设备扫 `api/quark/qr.svg`.
 */
internal object RemoteQuark {
    private val logger = logger<RemoteQuark>()

    private val service: QuarkDriveService get() = KoinPlatform.getKoin().get()
    private val manager: MediaSourceManager get() = KoinPlatform.getKoin().get()

    @Volatile
    private var qrState: QuarkQrLoginState? = null

    @Volatile
    private var qrJob: Job? = null

    /**
     * 最近一次向服务端核对时登录已失效. 失效时仍保留旧 Cookie, 等重新登录覆盖.
     */
    @Volatile
    private var expired = false

    /** 处理 `api/quark` 下的请求; 路径或方法不认识返回 null. */
    fun handle(request: LanHttpRequest, scope: CoroutineScope): LanHttpResponse? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        if (request.path == "api/quark/qr.svg" && get) return qrSvg()
        val result = runCatching {
            when {
                request.path == "api/quark" && get -> status()
                !post -> null
                request.path == "api/quark/check" -> check()
                request.path == "api/quark/qr/start" -> startQr(scope)
                request.path == "api/quark/qr/cancel" -> cancelQr()
                request.path == "api/quark/cookie" -> loginWithCookie(request.field("cookie"))
                request.path == "api/quark/logout" -> logout()
                request.path == "api/quark/transcode" -> setTranscode(request.field("on") == "1")
                request.path == "api/quark/add" -> add()
                else -> null
            }
        }.getOrElse {
            logger.warn(it) { "Remote quark request failed: ${request.method} ${request.path}" }
            result(false, tr("操作失败：{0}", it.message ?: it::class.simpleName))
        } ?: return null
        return LanHttpResponse.bytes(result.toString().toByteArray(), "application/json; charset=utf-8")
    }

    private fun status(message: String = "", ok: Boolean = true): JsonObject {
        val config = runBlocking { service.config.first() }
        val added = runBlocking { manager.allInstances.first() }.find { it.factoryId == QuarkMediaSource.FACTORY_ID }
        return buildJsonObject {
            put("ok", ok)
            put("message", message)
            put("loggedIn", config.isLoggedIn)
            put("expired", config.isLoggedIn && expired)
            put("nickname", config.nickname)
            put("member", memberLabel(config.memberType))
            put("transcode", config.playbackMode == QuarkPlaybackMode.TRANSCODED)
            put("added", added != null)
            put("enabled", added?.isEnabled == true)
            qrState?.let { state ->
                putJsonObject("qr") {
                    when (state) {
                        QuarkQrLoginState.Loading -> put("state", "loading")
                        is QuarkQrLoginState.WaitingForScan -> {
                            put("state", "waiting")
                            put("link", state.qrContent)
                        }

                        is QuarkQrLoginState.Success -> put("state", "success")
                        QuarkQrLoginState.Expired -> put("state", "expired")
                        is QuarkQrLoginState.Failed -> {
                            put("state", "failed")
                            put("message", state.message)
                        }
                    }
                }
            }
        }
    }

    /** 向夸克核对登录态 (顺便更新昵称与会员), 网页打开数据源页时调一次. */
    private fun check(): JsonObject {
        val loggedIn = runBlocking { service.config.first().isLoggedIn }
        if (!loggedIn) return status()
        runBlocking {
            withTimeoutOrNull(CHECK_TIMEOUT) {
                try {
                    service.refreshAccount()
                    expired = false
                } catch (e: CancellationException) {
                    throw e
                } catch (_: QuarkAuthException) {
                    expired = true
                } catch (e: Throwable) {
                    logger.warn { "Quark account check failed: $e" }
                }
            }
        }
        return status()
    }

    private fun startQr(scope: CoroutineScope): JsonObject {
        qrJob?.cancel()
        qrState = QuarkQrLoginState.Loading
        qrJob = scope.launch {
            service.qrLogin().collect { state ->
                qrState = state
                if (state is QuarkQrLoginState.Success) {
                    expired = false
                    val added = ensureQuarkMediaSourceAdded(manager)
                    logger.info { "Quark logged in from the web console, source added=$added" }
                }
            }
        }
        return status()
    }

    private fun cancelQr(): JsonObject {
        qrJob?.cancel()
        qrJob = null
        qrState = null
        return status()
    }

    private fun loginWithCookie(cookie: String): JsonObject {
        if (cookie.isBlank()) return status(tr("先把 Cookie 粘到框里"), ok = false)
        runBlocking {
            service.loginWithCookie(cookie)
            expired = false
            ensureQuarkMediaSourceAdded(manager)
        }
        return status(tr("已登录夸克网盘"))
    }

    private fun logout(): JsonObject {
        cancelQr()
        runBlocking { service.logout() }
        expired = false
        return status(tr("已退出夸克网盘"))
    }

    private fun setTranscode(on: Boolean): JsonObject {
        runBlocking { service.setPlaybackMode(if (on) QuarkPlaybackMode.TRANSCODED else QuarkPlaybackMode.ORIGINAL) }
        return status()
    }

    private fun add(): JsonObject {
        val added = runBlocking { ensureQuarkMediaSourceAdded(manager) }
        return status(if (added) tr("已添加") else tr("已经添加过了"))
    }

    /** 当前登录二维码的 SVG (深色码白底, 四周留 4 个模块); 不在等扫码时 404. */
    private fun qrSvg(): LanHttpResponse {
        val content = (qrState as? QuarkQrLoginState.WaitingForScan)?.qrContent
            ?: return LanHttpResponse.status(404, "Not Found")
        val matrix = encodeQrCode(content) ?: return LanHttpResponse.status(404, "Not Found")
        val quiet = 4
        val size = matrix.size + quiet * 2
        val path = buildString {
            for (y in 0 until matrix.size) for (x in 0 until matrix.size) {
                if (matrix[x, y]) append("M${x + quiet} ${y + quiet}h1v1h-1z")
            }
        }
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $size $size" shape-rendering="crispEdges">""" +
                """<rect width="$size" height="$size" fill="#fff"/><path d="$path" fill="#000"/></svg>"""
        return LanHttpResponse.bytes(svg.toByteArray(), "image/svg+xml")
    }

    private fun memberLabel(memberType: String): String = when {
        memberType.isBlank() -> ""
        memberType.startsWith("EXP_") -> tr("体验会员")
        memberType.contains("SVIP") || memberType == "SUPER_VIP" -> tr("超级会员")
        memberType.contains("VIP") -> tr("会员")
        else -> tr("普通用户")
    }

    private val CHECK_TIMEOUT = 15.seconds

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    private fun LanHttpRequest.field(name: String): String =
        formFieldList().lastOrNull { it.first == name }?.second.orEmpty()
}
