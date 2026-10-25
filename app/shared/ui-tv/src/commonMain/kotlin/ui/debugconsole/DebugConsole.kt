/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.debugconsole

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.ui.settings.tv.TvDebugConsoleEntry
import me.him188.ani.app.ui.settings.tv.TvSettingsPlatform
import me.him188.ani.app.ui.subject.episode.RetainedPlaybackSessionHolder
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.writeText
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** 调试接口要的平台能力 (见 [DebugConsole.start]). */
class DebugConsoleConfig(
    /** 端口与口令存在这里 (一行 `端口 口令`), 重启后地址不变. */
    val stateFile: SystemPath,
    /** 日志目录 (`app.<日期>.log`). */
    val logsDir: SystemPath,
    /** 把还在缓冲里的日志写进文件. */
    val flushLogs: () -> Unit,
    /** 当前画面的 PNG, 在主线程上调; 取不到为 null. */
    val screenshotPng: suspend () -> ByteArray?,
    /** 放进系统剪贴板. */
    val copyText: (String) -> Unit,
)

/**
 * debug 包的调试接口: 开发机经局域网连进来读状态、驱动播放器与导航、取日志与截图. 给没有 Web 控制台的端 (iOS) 用,
 * 接口的样子照 Web 控制台 (同名接口返回同样的字段), 开发机上的控制台脚本两边都能用. 接口列表见 [handleDebugConsoleRequest].
 *
 * 地址 `http://<本机 IP>:<端口>/<口令>/`; 口令之外的路径一律 404. 设置 → 调试里列出地址 (点一下复制, 见 [withDebugConsole]).
 * 请求逐个在主线程上处理 (播放器与导航都只能在主线程上碰).
 */
object DebugConsole {
    private val logger = logger("DebugConsole")

    private val _url = MutableStateFlow<String?>(null)

    /** 完整地址; 没在监听或没拿到本机地址时为 null. */
    val url: StateFlow<String?> = _url.asStateFlow()

    @Volatile
    internal var config: DebugConsoleConfig? = null
        private set

    /** 界面根部登记的导航 (见 [DebugConsoleTargets]). */
    @Volatile
    internal var navigator: AniNavigator? = null

    /** 界面根部登记的保留会话: 播放器接口经它拿当前的 EpisodeViewModel (保留会话关着时没有). */
    @Volatile
    internal var playback: RetainedPlaybackSessionHolder? = null

    /** 起服务 (只起一次). 由 debug 包的启动流程调用. */
    fun start(config: DebugConsoleConfig) {
        if (this.config != null) return
        this.config = config
        CoroutineScope(SupervisorJob() + Dispatchers.IO_ + CoroutineName("DebugConsole")).launch {
            try {
                serve(config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn(e) { "Debug console stopped" }
            }
        }
    }

    private suspend fun serve(config: DebugConsoleConfig) {
        val saved = runCatching { config.stateFile.readText() }.getOrNull()?.trim()?.split(' ')
        val savedPort = saved?.getOrNull(0)?.toIntOrNull()
        val savedToken = saved?.getOrNull(1)
        val token = savedToken?.takeIf { it.length >= TOKEN_LENGTH } ?: newToken()
        val selector = SelectorManager(Dispatchers.IO_)
        var server = listen(selector, savedPort ?: DEFAULT_PORT) ?: listen(selector, 0) ?: return
        val port = (server.localAddress as InetSocketAddress).port
        if (savedPort != port || savedToken != token) {
            runCatching { config.stateFile.writeText("$port $token") }
                .onFailure { logger.warn(it) { "Failed to save debug console address" } }
        }
        logger.info { "Debug console listening on port $port" }
        coroutineScope {
            // 换了 Wi-Fi 地址会变: 隔一会儿重新取
            launch {
                while (isActive) {
                    val host = lanAddress(selector)
                    val url = host?.let { "http://$it:$port/$token/" }
                    if (url != _url.value) {
                        _url.value = url
                        logger.info { "Debug console reachable at ${host ?: "(no LAN address)"}:$port" }
                    }
                    delay(ADDRESS_REFRESH)
                }
            }
            while (isActive) {
                val socket = try {
                    server.accept()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 应用在后台被挂起时系统会收回监听 socket: 在原端口上重新监听
                    logger.warn(e) { "Debug console listener failed, rebinding port $port" }
                    runCatching { server.close() }
                    var rebound: ServerSocket? = null
                    while (rebound == null) {
                        delay(REBIND_DELAY)
                        rebound = listen(selector, port)
                    }
                    server = rebound
                    continue
                }
                launch {
                    try {
                        respond(socket, token, config)
                    } finally {
                        socket.close()
                    }
                }
            }
        }
    }

    private suspend fun listen(selector: SelectorManager, port: Int): ServerSocket? = try {
        aSocket(selector).tcp().bind("0.0.0.0", port) { reuseAddress = true }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn(e) { "Debug console cannot listen on port $port" }
        null
    }

    private suspend fun respond(socket: Socket, token: String, config: DebugConsoleConfig) {
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = false)
        try {
            val (line, body) = withTimeoutOrNull(REQUEST_TIMEOUT) { readHead(input) } ?: return
            val request = parseDebugConsoleRequest(line, body, token)
            val response = if (request == null) {
                DebugConsoleResponse.text("not found", 404)
            } else {
                try {
                    withContext(Dispatchers.Main) { handleDebugConsoleRequest(request, config) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.warn(e) { "Debug console request ${request.path} failed" }
                    DebugConsoleResponse.json(
                        buildJsonObject {
                            put("ok", false)
                            put("message", e.toString())
                        },
                        500,
                    )
                }
            }
            write(output, response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "Debug console connection failed" }
        } finally {
            runCatching { output.flushAndClose() }
        }
    }

    /** 请求行与正文 (按 Content-Length 读); 连接没发东西就断了为 null. */
    private suspend fun readHead(input: ByteReadChannel): Pair<String, String>? {
        val line = input.readUTF8Line(MAX_LINE_LENGTH) ?: return null
        var length = 0
        while (true) {
            val header = input.readUTF8Line(MAX_LINE_LENGTH) ?: break
            if (header.isEmpty()) break
            if (header.substringBefore(':').trim().equals("content-length", ignoreCase = true)) {
                length = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val body = if (length > 0) input.readByteArray(length.coerceAtMost(MAX_BODY)).decodeToString() else ""
        return line to body
    }

    private suspend fun write(output: ByteWriteChannel, response: DebugConsoleResponse) {
        val reason = when (response.status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Error"
        }
        val head = "HTTP/1.1 ${response.status} $reason\r\n" +
                "Content-Type: ${response.contentType}\r\n" +
                "Content-Length: ${response.body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
        output.writeFully(head.encodeToByteArray())
        output.writeFully(response.body)
    }

    /**
     * 本机在局域网上的地址: 连一个 UDP socket 到外网地址 (不发包) 让系统选出出站的网卡, 读它的本机地址.
     * 目标不在局域网里, iOS 不弹本地网络权限; 别人连进来的 TCP 也不需要那个权限.
     */
    private suspend fun lanAddress(selector: SelectorManager): String? {
        val socket = try {
            aSocket(selector).udp().connect(InetSocketAddress("8.8.8.8", 53))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return null
        }
        val host = try {
            (socket.localAddress as? InetSocketAddress)?.hostname
        } finally {
            socket.close()
        }
        return host?.takeIf { it.isNotEmpty() && ':' !in it && it != "0.0.0.0" && !it.startsWith("127.") }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun newToken(): String = Uuid.random().toHexString().take(TOKEN_LENGTH)

    fun copy(text: String) {
        config?.copyText?.invoke(text)
    }

    private const val DEFAULT_PORT = 8790
    private const val TOKEN_LENGTH = 16
    private const val MAX_LINE_LENGTH = 16 * 1024
    private const val MAX_BODY = 1024 * 1024
    private val REQUEST_TIMEOUT = 30.seconds
    private val ADDRESS_REFRESH = 30.seconds
    private val REBIND_DELAY = 2.seconds
}

/** 界面根部 (触屏) 把导航与保留会话登记给调试接口; 调试接口没起时什么都不做. */
@Composable
fun DebugConsoleTargets(navigator: AniNavigator, playback: RetainedPlaybackSessionHolder) {
    if (DebugConsole.config == null) return
    DisposableEffect(navigator, playback) {
        DebugConsole.navigator = navigator
        DebugConsole.playback = playback
        onDispose {
            if (DebugConsole.navigator === navigator) DebugConsole.navigator = null
            if (DebugConsole.playback === playback) DebugConsole.playback = null
        }
    }
}

/** 设置页用的平台: 调试接口起了就在「调试」里列出它的地址 (见 [TvSettingsPlatform.debugConsole]). */
fun TvSettingsPlatform.withDebugConsole(): TvSettingsPlatform {
    if (DebugConsole.config == null) return this
    return object : TvSettingsPlatform by this {
        override val debugConsole: TvDebugConsoleEntry = object : TvDebugConsoleEntry {
            override val url: Flow<String?> get() = DebugConsole.url
            override fun copy(url: String) = DebugConsole.copy(url)
        }
    }
}
