/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.log

import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.foundation.lan.LanHttpResponse
import me.him188.ani.app.ui.foundation.lan.LanHttpServer
import me.him188.ani.app.ui.foundation.lan.escapeHtml
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把日志目录经局域网 HTTP 暴露给手机, 供「扫码传到手机」用.
 *
 * 电视上没有任何能接收文件的分享目标 (见 `AniUiBehavior.supportsFileSharing`), 插 U 盘之外
 * 唯一不需要电脑的取法就是让手机直接从电视下载: 电视起一个只活在弹窗期间的 HTTP 服务, 二维码
 * 里是它的地址, 手机连同一个 Wi-Fi 扫码即可. 通用部分 (随机端口 / token / 一问一答) 见 [LanHttpServer].
 *
 * 首页列出日志目录里全部 `*.log` (今天的 `app.log` 加按天滚动的历史), 用户在手机上挑哪天的下载.
 * 扫出来先落到一个网页而不是直接下载文件: 微信/支付宝的内置浏览器对直接下载常常不给提示,
 * 落到网页上至少能看到文件列表, 也能长按「在浏览器打开」.
 */
internal class LogLanShareServer(private val logsDir: File) : AutoCloseable {
    private val server = LanHttpServer(::handle)

    /** 二维码里放的地址: 落到文件列表页. */
    fun pageUrl(host: String): String = server.rootUrl(host)

    suspend fun serve() = server.serve()

    override fun close() = server.close()

    private fun handle(request: LanHttpRequest): LanHttpResponse {
        if (request.method != "GET" && request.method != "HEAD") {
            return LanHttpResponse.status(405, "Method Not Allowed")
        }
        if (request.path.isEmpty()) {
            return LanHttpResponse.html(buildPage(listLogFiles()))
        }
        // 只认目录列表里有的名字, 不拿用户给的字符串去拼路径 —— 顺手堵掉 `..` 之类
        val file = listLogFiles().firstOrNull { it.name == request.path }
            ?: return LanHttpResponse.status(404, "Not Found")
        // app.log 边传边在追加 (我们自己处理请求也在往里写): 按此刻的长度送, 见 fileSnapshot
        return LanHttpResponse.fileSnapshot(file, "text/plain; charset=utf-8")
    }

    private fun listLogFiles(): List<File> =
        logsDir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    private fun buildPage(files: List<File>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        val items = files.joinToString("\n") { file ->
            val name = file.name.escapeHtml()
            "<li><a href=\"$name\" download>$name</a>" +
                    "<span>${formatSize(file.length())} · ${dateFormat.format(Date(file.lastModified()))}</span></li>"
        }
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Animeko 日志</title>
            <style>
            body { font-family: system-ui, sans-serif; margin: 0; padding: 24px 20px; background: #fafafa; color: #1c1b1f; }
            h1 { font-size: 22px; margin: 0 0 8px; }
            p { color: #49454f; font-size: 14px; line-height: 1.5; margin: 0 0 20px; }
            ul { list-style: none; padding: 0; margin: 0; }
            li { background: #fff; border-radius: 12px; padding: 14px 16px; margin-bottom: 10px; box-shadow: 0 1px 3px rgba(0,0,0,.08); }
            a { display: block; font-size: 17px; font-weight: 600; color: #6750a4; text-decoration: none; word-break: break-all; }
            span { display: block; font-size: 13px; color: #79747e; margin-top: 4px; }
            </style>
            </head>
            <body>
            <h1>Animeko 日志</h1>
            <p>点击文件名下载。若下载没有开始，请用系统浏览器打开本页。关闭电视上的窗口后此页面即失效。</p>
            <ul>
        """.trimIndent() + "\n" + items + "\n" + """
            </ul>
            </body>
            </html>
        """.trimIndent()
    }

    private companion object {
        private fun formatSize(bytes: Long): String = when {
            bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
            bytes >= 1024 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
