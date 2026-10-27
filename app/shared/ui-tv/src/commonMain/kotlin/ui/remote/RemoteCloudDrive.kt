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
import me.him188.ani.app.domain.media.cache.library.MediaLibraryAccess
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveAuthException
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveMediaSource
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveProtocol
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveQrLoginState
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveRegistry
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveService
import me.him188.ani.app.domain.mediasource.clouddrive.LocalFolderDrive
import me.him188.ani.app.domain.mediasource.clouddrive.WebDav
import me.him188.ani.app.domain.mediasource.clouddrive.WebDavSources
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
 * WebDAV 服务器 (NAS) 是用户在这里自己加的网盘 ([WebDavSources]): 卡片上是账号密码登录、媒体文件夹 (浏览服务器的文件夹选)、改名字与地址、删除;
 * 添加的表单在「新增数据源」里. 本地文件夹 ([LocalFolderDrive]) 也是在这里加的: 不用登录, 卡片上是权限状态、媒体文件夹 (浏览各块盘选)、改名字、删除.
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
        "api/drive/password", "api/drive/folders", "api/drive/library", "api/drive/webdav/edit", "api/drive/remove",
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
                request.path == "api/drive/webdav/add" -> addWebDav(request)
                request.path == "api/drive/local/add" -> addLocalFolder(request)
                request.path == "api/drive/local/grant" -> openLocalFolderGrant()
                request.path in DRIVE_ACTIONS -> {
                    val service = driveOf(request.field("drive"))
                        ?: return@runCatching status(tr("没有这个网盘，请刷新"), ok = false)
                    when (request.path) {
                        "api/drive/qr/start" -> startQr(service, scope)
                        "api/drive/qr/cancel" -> cancelQr(service.driveId)
                        "api/drive/cookie" -> loginWithCookie(service, request.field("cookie"))
                        "api/drive/logout" -> logout(service)
                        "api/drive/transcode" -> setTranscode(service, request.field("on") == "1")
                        "api/drive/password" -> loginWithPassword(service, request.field("username"), request.field("password"))
                        "api/drive/folders" -> folders(service, request.field("fid"))
                        "api/drive/library" -> library(service, request.field("action"), request.field("fid"), request.field("depth"))
                        "api/drive/webdav/edit" -> editWebDav(service, request.field("name"), request.field("url"))
                        "api/drive/remove" -> removeWebDav(service)
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
            put("pick", protocol.supportsSearch || protocol.supportsListing)
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
                            folderPath = { folderPathOf(service, it) },
                            localAccessible = service.protocol.isLocalFolder && service.localFoldersAccessible(),
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
     * @param folderPath 媒体文件夹给人看的路径
     * @param localAccessible 本地文件夹此刻能读 (见 [CloudDriveService.localFoldersAccessible])
     */
    internal fun driveJson(
        protocol: CloudDriveProtocol,
        account: CloudDriveAccount,
        sourceName: String?,
        sourceEnabled: Boolean,
        expired: Boolean,
        qr: CloudDriveQrLoginState?,
        languageTag: String,
        folderPath: (String) -> String = { WebDav.displayPath(protocol, it) },
        localAccessible: Boolean = false,
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
        // 能播别人分享的 (登录后转存): 卡片说明要讲清不只是自己存的
        put("shares", protocol.supportsShares)
        put("type", protocol.type)
        put("supportsPassword", protocol.supportsPasswordLogin)
        put("username", account.username)
        if (protocol.isWebDav) put("server", WebDav.serverUrl(protocol)?.toString().orEmpty())
        if (protocol.isLocalFolder) {
            put("accessible", localAccessible)
            put("grantAdb", localFolderAccess()?.adbGrantCommand.orEmpty())
        }
        if (protocol.isUserAdded) {
            put("depth", protocol.library.depth)
            putJsonArray("library") {
                for (fid in protocol.library.folders) addJsonObject {
                    put("id", fid)
                    put("path", folderPath(fid))
                }
            }
        }
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

    private fun loginWithPassword(service: CloudDriveService, username: String, password: String): JsonObject {
        if (username.isBlank()) return status(tr("先填用户名"), ok = false)
        runBlocking { service.loginWithPassword(username, password) }
        expired.remove(service.driveId)
        return status(tr("已登录{0}", nameOf(service)))
    }

    // region WebDAV 服务器

    /**
     * 加一台 WebDAV 服务器: 新建它的数据源, 用填的账号密码列一次根目录核对; 连不上或账号密码不对时不留下数据源, 把原因报给网页.
     */
    private fun addWebDav(request: LanHttpRequest): JsonObject {
        val url = request.field("url").trim()
        val username = request.field("username").trim()
        if (url.isEmpty()) return status(tr("先填 WebDAV 地址"), ok = false)
        if (username.isEmpty()) return status(tr("先填用户名"), ok = false)
        val protocol = try {
            WebDavSources.newProtocol(request.field("name").trim().ifEmpty { tr("NAS") }, url)
        } catch (_: IllegalArgumentException) {
            return status(tr("地址要以 http:// 或 https:// 开头"), ok = false)
        }
        runBlocking {
            WebDavSources.add(manager, protocol)
            try {
                registry.serviceFor(protocol).loginWithPassword(username, request.field("password"))
            } catch (e: Throwable) {
                WebDavSources.remove(manager, protocol.id)
                throw e
            }
        }
        awaitDrives { drives -> drives.any { it.driveId == protocol.id } }
        logger.info { "Added WebDAV drive ${protocol.id} from the web console" }
        return status(tr("已添加{0}", protocol.name))
    }

    /** 浏览 WebDAV 服务器或本地各块盘的文件夹 (选媒体文件夹用); [fid] 为空时是根. */
    private fun folders(service: CloudDriveService, fid: String): JsonObject {
        val protocol = service.protocol
        if (!protocol.isUserAdded) return result(false, tr("这个网盘不能选媒体文件夹"))
        val id = fid.ifEmpty { service.rootFolderId }
        val atLocalRoot = protocol.isLocalFolder && id == service.rootFolderId
        // 本地的根是各块盘, 按系统给的顺序 (内部存储在前)
        val children = runBlocking { service.listForPicking(id) }.filter { it.dir }
            .let { list -> if (atLocalRoot) list else list.sortedBy { it.fileName.lowercase() } }
        return buildJsonObject {
            put("ok", true)
            put("id", id)
            put("path", if (atLocalRoot) tr("设备上的存储") else folderPathOf(service, id))
            put(
                "parent",
                when {
                    id == service.rootFolderId -> ""
                    protocol.isLocalFolder -> service.localFolderParentOf(id)
                    else -> WebDav.parentIdOf(id)
                },
            )
            put("chosen", id in protocol.library.folders)
            // 本地的根 (各块盘的列表) 本身不能当媒体文件夹
            put("choosable", !atLocalRoot)
            putJsonArray("folders") {
                for (folder in children) addJsonObject {
                    put("id", folder.fid)
                    put("name", folder.fileName)
                }
            }
        }
    }

    /** 媒体文件夹: [action] 是 add / remove (文件夹 [fid]) 或 depth (往下找几层). */
    private fun library(service: CloudDriveService, action: String, fid: String, depth: String): JsonObject {
        if (!service.protocol.isUserAdded) return status(tr("这个网盘不能选媒体文件夹"), ok = false)
        val expected = updateWebDav(service) { protocol ->
            val library = protocol.library
            protocol.copy(
                library = when (action) {
                    "add" -> library.copy(folders = (library.folders + fid).filter { it.isNotEmpty() }.distinct())
                    "remove" -> library.copy(folders = library.folders - fid)
                    "depth" -> library.copy(depth = depth.toIntOrNull()?.coerceIn(1, 3) ?: library.depth)
                    else -> library
                },
            )
        } ?: return status(tr("没有找到「{0}」的数据源", nameOf(service)), ok = false)
        awaitDrives { drives -> drives.any { it.driveId == service.driveId && it.protocol == expected } }
        return status(
            when (action) {
                "add" -> tr("已加为媒体文件夹")
                "remove" -> tr("已移出媒体文件夹")
                else -> ""
            },
        )
    }

    /** 改 WebDAV 服务器的名字与地址 (本地文件夹只改名字), 改完向服务器核对一次登录. */
    private fun editWebDav(service: CloudDriveService, name: String, url: String): JsonObject {
        if (!service.protocol.isUserAdded) return status(tr("订阅来的网盘不能在这里修改"), ok = false)
        val expected = try {
            updateWebDav(service) { protocol ->
                val renamed = protocol.copy(name = name.trim().ifEmpty { protocol.name })
                if (url.isBlank() || !protocol.isWebDav) renamed else WebDavSources.withServer(renamed, url)
            }
        } catch (_: IllegalArgumentException) {
            return status(tr("地址要以 http:// 或 https:// 开头"), ok = false)
        } ?: return status(tr("没有找到「{0}」的数据源", nameOf(service)), ok = false)
        awaitDrives { drives -> drives.any { it.driveId == service.driveId && it.protocol == expected } }
        runBlocking { checkOne(service) }
        return status(tr("已保存"))
    }

    /** 删掉用户自己加的 WebDAV 服务器 (连同账号密码) 或本地文件夹 (盘上的文件不动); 订阅来的网盘会随订阅更新回来, 不在这里删. */
    private fun removeWebDav(service: CloudDriveService): JsonObject {
        if (!service.protocol.isUserAdded) return status(tr("订阅来的网盘不能在这里删除"), ok = false)
        val name = nameOf(service)
        runBlocking {
            service.logout()
            WebDavSources.remove(manager, service.driveId)
        }
        expired.remove(service.driveId)
        awaitDrives { drives -> drives.none { it.driveId == service.driveId } }
        return status(tr("已删除{0}", name))
    }

    /** 改 [service] 的协议并返回改后的协议; 没有它的数据源时为 null. */
    private fun updateWebDav(service: CloudDriveService, transform: (CloudDriveProtocol) -> CloudDriveProtocol): CloudDriveProtocol? {
        var updated: CloudDriveProtocol? = null
        val found = runBlocking { WebDavSources.update(manager, service.driveId) { transform(it).also { new -> updated = new } } }
        return if (found) updated else null
    }

    /** 改完数据源后等网盘列表跟上 (最多 [LOAD_TIMEOUT]), 回给网页的状态才是新的. */
    private fun awaitDrives(predicate: (List<CloudDriveService>) -> Boolean) {
        runBlocking { withTimeoutOrNull(LOAD_TIMEOUT) { registry.drives.first { it != null && predicate(it) } } }
    }

    // endregion

    // region 本地文件夹

    private fun localFolderAccess(): MediaLibraryAccess? = KoinPlatform.getKoin().getOrNull<MediaLibraryAccess>()?.takeIf { it.isSupported }

    /**
     * 加一个本地文件夹数据源. 平台读不了盘上的文件夹时不加; 没有「所有文件访问」时照样加上 (卡片上提示去开),
     * 加完网页打开文件夹浏览选媒体文件夹.
     */
    private fun addLocalFolder(request: LanHttpRequest): JsonObject {
        if (localFolderAccess() == null) return status(tr("这台设备不能读本地文件夹"), ok = false)
        val protocol = LocalFolderDrive.newProtocol(request.field("name").trim().ifEmpty { tr("本地文件夹") })
        runBlocking { WebDavSources.add(manager, protocol) }
        awaitDrives { drives -> drives.any { it.driveId == protocol.id } }
        logger.info { "Added local folder drive ${protocol.id} from the web console" }
        return status(tr("已添加{0}", protocol.name))
    }

    /** 在电视上打开给「所有文件访问」的系统页面 (同片库). 一个入口都没有时回 adb 命令. */
    private fun openLocalFolderGrant(): JsonObject {
        val access = localFolderAccess() ?: return status(tr("这台设备不能读本地文件夹"), ok = false)
        if (access.isGranted()) return status(tr("已经允许了"))
        return if (access.openGrantPage() != null) {
            status(tr("已在电视上打开授权页面，用遥控器打开「允许管理所有文件」后回来刷新"))
        } else {
            status(tr("电视上没有授权页面，在电脑上运行：{0}", access.adbGrantCommand.orEmpty()), ok = false)
        }
    }

    /** 媒体文件夹 [fid] 给人看的路径 */
    private fun folderPathOf(service: CloudDriveService, fid: String): String =
        if (service.protocol.isLocalFolder) service.localFolderPathOf(fid) else WebDav.displayPath(service.protocol, fid)

    // endregion

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
