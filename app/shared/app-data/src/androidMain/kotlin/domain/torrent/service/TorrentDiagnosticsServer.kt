/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.torrent.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.app.torrent.anitorrent.AnitorrentTorrentDownloader
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.currentTimeMillis
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 仅调试用: 在 `127.0.0.1` 上开一个极简 HTTP 端口, 输出当前所有 BT 任务的实时状态
 * (速度/进度/peer 列表), 用于排查"下载停住了"这类仅凭日志无法事后还原的问题.
 *
 * 使用方式 (设备上):
 * ```
 * adb forward tcp:6890 tcp:6890
 * curl http://127.0.0.1:6890/
 * ```
 *
 * 设计约束:
 * - 只绑定 loopback, 不对外网开放; 仅 debuggable 构建启动 (见 [AniTorrentService] 中的调用).
 * - 单个 daemon 线程阻塞在 accept 上, 无客户端连接时零开销; 请求串行处理, 不会与下载/播放抢资源.
 * - 任何异常只记日志, 绝不影响 torrent 服务本身.
 */
internal class TorrentDiagnosticsServer(
    private val downloader: AnitorrentTorrentDownloader<*, *>,
    private val port: Int = DEFAULT_PORT,
) {
    fun start() {
        thread(isDaemon = true, name = "TorrentDiagnostics") {
            val server = try {
                ServerSocket(port, 2, InetAddress.getLoopbackAddress())
            } catch (e: Exception) {
                logger.error(e) { "Failed to bind diagnostics port $port, diagnostics disabled." }
                return@thread
            }
            logger.info { "Torrent diagnostics server listening on 127.0.0.1:$port" }
            server.use {
                while (true) {
                    val client = try {
                        server.accept()
                    } catch (e: Exception) {
                        logger.error(e) { "Diagnostics server accept failed, stopping." }
                        return@thread
                    }
                    try {
                        client.use { handle(it) }
                    } catch (e: Exception) {
                        logger.error(e) { "Diagnostics request failed." }
                    }
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5_000
        // 读掉请求行即可, 路径不区分, 一律返回状态快照.
        client.getInputStream().bufferedReader().readLine()

        val body = runBlocking { buildSnapshotJson() }.toByteArray()
        client.getOutputStream().apply {
            write(
                (
                        "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json; charset=utf-8\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n"
                        ).toByteArray(),
            )
            write(body)
            flush()
        }
    }

    private suspend fun buildSnapshotJson(): String {
        val sessions = downloader.openSessions.value
        val json = buildJsonObject {
            put("time", currentTimeMillis())
            put("sessionCount", sessions.size)
            put(
                "sessions",
                buildJsonArray {
                    for ((id, session) in sessions) {
                        add(
                            buildJsonObject {
                                put("id", id)
                                put("name", withTimeoutOrNull(2_000) { session.getName() } ?: "(pending metadata)")
                                val stats = withTimeoutOrNull(2_000) { session.sessionStats.first() }
                                if (stats != null) {
                                    put("totalSizeRequested", stats.totalSizeRequested)
                                    put("downloadedBytes", stats.downloadedBytes)
                                    put("downloadSpeedBps", stats.downloadSpeed)
                                    put("uploadedBytes", stats.uploadedBytes)
                                    put("uploadSpeedBps", stats.uploadSpeed)
                                    put("downloadProgress", stats.downloadProgress)
                                    put("isDownloadFinished", stats.isDownloadFinished)
                                }
                                val peers = try {
                                    session.getPeers()
                                } catch (e: Exception) {
                                    logger.error(e) { "getPeers failed for $id" }
                                    emptyList()
                                }
                                put("peerCount", peers.size)
                                put(
                                    "peers",
                                    buildJsonArray {
                                        for (peer in peers) {
                                            add(
                                                buildJsonObject {
                                                    put("client", peer.client)
                                                    put("ip", peer.ipAddr)
                                                    put("port", peer.ipPort)
                                                    put("progress", peer.progress)
                                                    put("totalDownloadBytes", peer.totalDownload.inBytes)
                                                    put("totalUploadBytes", peer.totalUpload.inBytes)
                                                    put("flags", peer.flags)
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
        return prettyJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), json)
    }

    companion object {
        const val DEFAULT_PORT = 6890
        private val logger = logger<TorrentDiagnosticsServer>()
        private val prettyJson = Json { prettyPrint = true }
    }
}
