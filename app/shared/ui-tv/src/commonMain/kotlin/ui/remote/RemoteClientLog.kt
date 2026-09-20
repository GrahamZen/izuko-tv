/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * `POST api/client-log`: 手机上那张网页把自己这一侧的关键状态发回电视, 写进 app 日志。
 *
 * 为什么要有这条路: **网页里出的事在电视日志里本来一点痕迹都没有** —— [me.him188.ani.app.ui.foundation.lan.LanHttpServer]
 * 只记慢请求, 普通请求不写日志, 所以"日志里没有"从来不能证明手机没发出去。2026-09-20 排"全屏里总时长只有
 * 几秒"时, 手机侧整个是盲的, 只能靠电视日志里的副作用 (位置被打回 0) 反推真因, 绕了两轮。
 *
 * 用法: 网页那边的 `clientLog(...)` (见 `CONTROL_SCRIPT`), 只在关键节点发 —— 载体建了多长、元数据到没到、
 * 加载失败码、进出全屏。看的时候 `adb logcat | grep WebConsole`。
 *
 * 这条只进日志, 不改任何状态, 所以不需要额外的权限判断 (已经在令牌路径之内)。但**它是外来数据**:
 * 长度截断、频率封顶, 免得网页出了循环就把 logcat 刷爆。
 */
internal object RemoteClientLog {
    private val logger = logger<RemoteClientLog>()

    /** 单条最长, 多的截掉 —— 网页只发状态, 正常都在一百字以内. */
    private const val MAX_LENGTH = 300

    /** 每 [WINDOW_MILLIS] 最多放行这么多条; 超了就丢, 并且只提示一次. */
    private const val MAX_PER_WINDOW = 60
    private const val WINDOW_MILLIS = 60_000L

    private var windowStart = 0L
    private var countInWindow = 0
    private var mutedNoticeShown = false

    fun handle(request: LanHttpRequest): JsonObject? {
        if (request.method != "POST") return null
        val message = request.formFields()["msg"].orEmpty().trim()
        if (message.isNotEmpty() && allow()) {
            logger.warn { "[WebConsole] ${message.take(MAX_LENGTH)}" }
        }
        return buildJsonObject { put("ok", true) }
    }

    @Synchronized
    private fun allow(): Boolean {
        val now = System.currentTimeMillis()
        if (now - windowStart > WINDOW_MILLIS) {
            windowStart = now
            countInWindow = 0
            mutedNoticeShown = false
        }
        if (countInWindow < MAX_PER_WINDOW) {
            countInWindow++
            return true
        }
        if (!mutedNoticeShown) {
            mutedNoticeShown = true
            logger.warn { "[WebConsole] 这一分钟的诊断日志超过 $MAX_PER_WINDOW 条, 其余的丢掉了" }
        }
        return false
    }
}
