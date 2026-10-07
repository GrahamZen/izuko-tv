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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.app.data.models.preference.CloudDrivePlaybackMode
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveAuthException
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveMediaSource
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveProtocol
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveQrLoginState
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveRegistry
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveService
import me.him188.ani.app.domain.mediasource.clouddrive.ensureCloudDriveMediaSourceEnabled
import me.him188.ani.app.domain.mediasource.instance.MediaSourceInstance
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.foundation.lan.LanHttpResponse
import me.him188.ani.app.ui.foundation.lan.encodeQrCode
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台「数据源」页里的网盘卡片 (每个已配置的网盘一张): 登录状态、扫码登录、填 Cookie、退出、转码开关、启用这个网盘的数据源.
 * 与电视设置页的网盘分组做的是同一件事, 登录态同存在 [CloudDriveService] 里.
 *
 * 网盘来自订阅或导入的「网盘」数据源 (见 [CloudDriveRegistry]), 卡片上的名字、扫码用的 App、手机唤起链接、Cookie 说明都取自它的协议.
 * 扫码登录在电视这边跑 ([CloudDriveService.qrLogin]), 每个网盘各有一份状态; 网页轮询 `api/drives` 拿当前状态与二维码:
 * 手机上可以按协议给的唤起链接跳到网盘 App 里确认, 也可以用另一台设备扫 `api/drive/qr.svg?drive=<id>`.
 *
 * 也给另外两处 (播放器页的「添加网盘分享」「从网盘挑」) 提供已配置的网盘.
 */
internal object RemoteCloudDrive {
    private val logger = logger<RemoteCloudDrive>()

    private val registry: CloudDriveRegistry get() = KoinPlatform.getKoin().get()
    private val manager: MediaSourceManager get() = KoinPlatform.getKoin().get()

    /** 一个网盘正在进行的扫码登录. */
    private class QrLogin {
        @Volatile
        var state: CloudDriveQrLoginState = CloudDriveQrLoginState.Loading

        @Volatile
        var job: Job? = null
    }

    /** 网盘 id → 正在进行 (或刚结束) 的扫码登录. */
    private val qrLogins = ConcurrentHashMap<String, QrLogin>()

    /** 最近一次向服务端核对时登录已失效的网盘. 失效时仍保留旧 Cookie, 等重新登录覆盖. */
    private val expired: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 卡片上的操作 (都带表单字段 `drive`). */
    private val DRIVE_ACTIONS = setOf(
        "api/drive/qr/start", "api/drive/qr/cancel", "api/drive/cookie", "api/drive/logout", "api/drive/transcode", "api/drive/enable",
    )

    /** 处理 `api/drives` 与 `api/drive/` 下的请求; 路径或方法不认识返回 null. */
    fun handle(request: LanHttpRequest, scope: CoroutineScope): LanHttpResponse? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        if (request.path == "api/drive/qr.svg" && get) return qrSvg(request.queryParam("drive").orEmpty())
        val result = runCatching {
            when {
                request.path == "api/drives" && get -> status()
                !post -> null
                request.path == "api/drive/check" -> check(request.field("drive"))
                request.path in DRIVE_ACTIONS -> {
                    val service = driveOf(request.field("drive"))
                        ?: return@runCatching status(tr("没有这个网盘，请刷新"), ok = false)
                    when (request.path) {
                        "api/drive/qr/start" -> startQr(service, scope)
                        "api/drive/qr/cancel" -> cancelQr(service.driveId)
                        "api/drive/cookie" -> loginWithCookie(service, request.field("cookie"))
                        "api/drive/logout" -> logout(service)
                        "api/drive/transcode" -> setTranscode(service, request.field("on") == "1")
                        else -> enable(service)
                    }
                }

                else -> null
            }
        }.getOrElse {
            logger.warn(it) { "Remote cloud drive request failed: ${request.method} ${request.path}" }
            result(false, tr("操作失败：{0}", it.message ?: it::class.simpleName))
        } ?: return null
        return LanHttpResponse.bytes(result.toString().toByteArray(), "application/json; charset=utf-8")
    }

    // region 已配置的网盘 (另外两处也用)

    /** 已配置的网盘. 刚启动还没读出保存的数据源时最多等 [LOAD_TIMEOUT]. */
    fun configuredDrives(): List<CloudDriveService> =
        registry.drives.value
            ?: runBlocking { withTimeoutOrNull(LOAD_TIMEOUT) { registry.drives.filterNotNull().first() } }
            ?: emptyList()

    fun driveOf(driveId: String): CloudDriveService? =
        if (driveId.isEmpty()) null else configuredDrives().firstOrNull { it.driveId == driveId }

    /** 网盘按网页当前语言的名字. */
    fun nameOf(service: CloudDriveService): String = service.protocol.displayName(RemoteI18n.lang.tag)

    /** 这个网盘自己网盘的数据源 (随协议一起配置的那个); 还没建出来时为 null. */
    private fun driveSourceOf(service: CloudDriveService, instances: List<MediaSourceInstance>): MediaSourceInstance? =
        instances.firstOrNull { it.factoryId == CloudDriveMediaSource.FactoryId && it.mediaSourceId == service.protocol.driveMediaSourceId }

    /** 这个网盘自己网盘的数据源在选源列表里叫什么. */
    fun sourceNameOf(service: CloudDriveService): String {
        val instances = runBlocking { withTimeoutOrNull(LOAD_TIMEOUT) { manager.allInstances.first() } }.orEmpty()
        return driveSourceOf(service, instances)?.source?.info?.displayName ?: nameOf(service)
    }

    /**
     * 播放器页的按钮要用的网盘 (随播放状态一起给, 不等数据源读完): 能加分享的 (`shares`) 给「添加网盘分享链接」,
     * 能搜或能列目录的 (`pick`) 各给一个「从某某挑」.
     */
    fun playerDrives(): JsonArray = buildJsonArray {
        val lang = RemoteI18n.lang.tag
        for (service in registry.drives.value.orEmpty()) addJsonObject {
            val protocol = service.protocol
            put("id", service.driveId)
            put("name", protocol.displayName(lang))
            put("shares", protocol.supportsShares)
            put("pick", protocol.supportsSearch || protocol.api.listFolder != null)
        }
    }

    // endregion

    private fun status(message: String = "", ok: Boolean = true): JsonObject {
        val drives = configuredDrives()
        val instances = runBlocking { withTimeoutOrNull(LOAD_TIMEOUT) { manager.allInstances.first() } }.orEmpty()
        val lang = RemoteI18n.lang.tag
        return buildJsonObject {
            put("ok", ok)
            put("message", message)
            putJsonArray("drives") {
                for (service in drives) {
                    val account = runBlocking { service.account.first() }
                    val source = driveSourceOf(service, instances)
                    add(
                        driveJson(
                            protocol = service.protocol,
                            account = account,
                            sourceName = source?.source?.info?.displayName,
                            // 数据源还没建出来时不报「已停用」
                            sourceEnabled = source?.isEnabled != false,
                            expired = account.isLoggedIn && service.driveId in expired,
                            qr = qrLogins[service.driveId]?.state,
                            languageTag = lang,
                        ),
                    )
                }
            }
        }
    }

    /**
     * 一张网盘卡片的数据.
     *
     * @param sourceName 这个网盘自己网盘的数据源的名字, 找不到时为 null (用网盘名)
     */
    internal fun driveJson(
        protocol: CloudDriveProtocol,
        account: CloudDriveAccount,
        sourceName: String?,
        sourceEnabled: Boolean,
        expired: Boolean,
        qr: CloudDriveQrLoginState?,
        languageTag: String,
    ): JsonObject = buildJsonObject {
        val name = protocol.displayName(languageTag)
        val qrLogin = protocol.login.qr
        put("id", protocol.id)
        put("name", name)
        // 图标经电视转发 (api/source-icon), 手机不直接连图床
        put("sourceId", protocol.driveMediaSourceId)
        put("sourceName", sourceName ?: name)
        put("enabled", sourceEnabled)
        put("loggedIn", account.isLoggedIn)
        put("expired", expired)
        put("nickname", account.nickname)
        put("tier", protocol.tierOf(account.tier)?.displayLabel(languageTag).orEmpty())
        put("transcode", account.playbackMode == CloudDrivePlaybackMode.TRANSCODED)
        put("supportsTranscoded", protocol.supportsTranscoded)
        put("supportsQr", qrLogin != null)
        put("appName", qrLogin?.appName.orEmpty())
        qrLogin?.mobileOpen?.let { open ->
            putJsonObject("mobileOpen") {
                put("android", open.android)
                put("ios", open.ios)
                put("inAppUserAgent", open.inAppUserAgent)
            }
        }
        put("cookieHint", protocol.login.cookieHint)
        if (qr != null) putJsonObject("qr") {
            when (qr) {
                CloudDriveQrLoginState.Loading -> put("state", "loading")
                is CloudDriveQrLoginState.WaitingForScan -> {
                    put("state", "waiting")
                    put("link", qr.qrContent)
                    // 还剩多久过期 (毫秒): 给剩余时长而不是时刻, 手机与电视的钟不一定对得上
                    put("expiresIn", (qr.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0))
                }

                // 手机上确认了, 电视在换登录 Cookie
                CloudDriveQrLoginState.Confirmed -> put("state", "confirmed")
                is CloudDriveQrLoginState.Success -> put("state", "success")
                CloudDriveQrLoginState.Expired -> put("state", "expired")
                is CloudDriveQrLoginState.Failed -> {
                    put("state", "failed")
                    put("message", qr.message)
                }
            }
        }
    }

    /**
     * 向网盘核对登录态 (顺便更新昵称与档位), 网页打开数据源页时调一次. [driveId] 为空时核对全部已登录的网盘.
     */
    private fun check(driveId: String): JsonObject {
        val targets = if (driveId.isEmpty()) configuredDrives() else listOfNotNull(driveOf(driveId))
        runBlocking {
            targets.map { service -> async { checkOne(service) } }.awaitAll()
        }
        return status()
    }

    private suspend fun checkOne(service: CloudDriveService) {
        if (!service.account.first().isLoggedIn) return
        withTimeoutOrNull(CHECK_TIMEOUT) {
            try {
                service.refreshAccount()
                expired.remove(service.driveId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: CloudDriveAuthException) {
                expired.add(service.driveId)
            } catch (e: Throwable) {
                logger.warn { "Cloud drive ${service.driveId} account check failed: $e" }
            }
        }
    }

    private fun startQr(service: CloudDriveService, scope: CoroutineScope): JsonObject {
        val driveId = service.driveId
        qrLogins.remove(driveId)?.job?.cancel()
        val login = QrLogin()
        qrLogins[driveId] = login
        login.job = scope.launch {
            service.qrLogin().collect { state ->
                login.state = state
                if (state is CloudDriveQrLoginState.Success) {
                    expired.remove(driveId)
                    logger.info { "Cloud drive $driveId logged in from the web console" }
                }
            }
        }
        return status()
    }

    private fun cancelQr(driveId: String): JsonObject {
        qrLogins.remove(driveId)?.job?.cancel()
        return status()
    }

    private fun loginWithCookie(service: CloudDriveService, cookie: String): JsonObject {
        if (cookie.isBlank()) return status(tr("先把 Cookie 粘到框里"), ok = false)
        runBlocking { service.loginWithCookie(cookie) }
        expired.remove(service.driveId)
        return status(tr("已登录{0}", nameOf(service)))
    }

    private fun logout(service: CloudDriveService): JsonObject {
        qrLogins.remove(service.driveId)?.job?.cancel()
        runBlocking { service.logout() }
        expired.remove(service.driveId)
        return status(tr("已退出{0}", nameOf(service)))
    }

    private fun setTranscode(service: CloudDriveService, on: Boolean): JsonObject {
        runBlocking { service.setPlaybackMode(if (on) CloudDrivePlaybackMode.TRANSCODED else CloudDrivePlaybackMode.ORIGINAL) }
        return status()
    }

    /** 启用这个网盘自己网盘的数据源. */
    private fun enable(service: CloudDriveService): JsonObject {
        val enabled = runBlocking { ensureCloudDriveMediaSourceEnabled(manager, service.driveId) }
            ?: return status(tr("没有找到「{0}」的数据源，请检查订阅", nameOf(service)), ok = false)
        return status(if (enabled.second) tr("已启用") else tr("已经启用了"))
    }

    /** 网盘 [driveId] 当前登录二维码的 SVG (深色码白底, 四周留 4 个模块); 不在等扫码时 404. */
    private fun qrSvg(driveId: String): LanHttpResponse {
        val content = (qrLogins[driveId]?.state as? CloudDriveQrLoginState.WaitingForScan)?.qrContent
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

    private val CHECK_TIMEOUT = 15.seconds

    /** 刚启动时等保存的数据源读出来最多多久. */
    private val LOAD_TIMEOUT = 3.seconds

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    private fun LanHttpRequest.field(name: String): String =
        formFieldList().lastOrNull { it.first == name }?.second.orEmpty()

    private fun LanHttpRequest.queryParam(name: String): String? =
        query.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }
}
