/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.diagnostics

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.platform.DevSwitches
import me.him188.ani.app.platform.perf.PerfTimeline
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.time.TimeSource

/**
 * 一次开播 (进播放页或切集) 从开始到出画面的每一步, 给开发看慢在哪 (控制台调试区 `api/debug/timeline` 取, 不上报).
 * 会话与序号的做法同上游的 `play_flow` 埋点: 一次尝试一个会话, 每一步记序号 [Entry.seq] 与距开始的毫秒数 [Entry.tMs];
 * 节点比它细: 查询会话、每个数据源的开查 / 结果 / 查完、认部、打开几页、自动选源的每一步、解析地址、交给播放器、开始播放.
 * 进程内只留最近 [KEEP] 次; 每一步也打一行日志, 并记进性能诊断的时间线 ([PerfTimeline], 录制时才收).
 * 只在开了调试开关 [DevSwitches.playDiagnostics] 时记, 关着时 [begin] 与 [mark] 什么都不做.
 */
object PlayTimeline {
    class Entry(val seq: Int, val tMs: Long, val step: String, val detail: String)

    class Session internal constructor(
        val id: Int,
        val subjectId: Int,
        val episodeId: Int,
        val trigger: String,
    ) {
        internal val start = TimeSource.Monotonic.markNow()
        internal val entries = ArrayList<Entry>()
    }

    private val logger = logger<PlayTimeline>()
    private val lock = SynchronizedObject()
    private val sessions = ArrayDeque<Session>()
    private var current: Session? = null
    private var nextId = 1

    /** 开着调试开关 [DevSwitches.playDiagnostics] */
    val enabled: Boolean get() = DevSwitches.playDiagnostics.value

    /** 开始一次开播: [trigger] 是 `open` (进播放页) 或 `switch` (页内切集). */
    fun begin(subjectId: Int, episodeId: Int, trigger: String) {
        if (!enabled) return
        val session = synchronized(lock) {
            Session(nextId++, subjectId, episodeId, trigger).also {
                current = it
                sessions.addLast(it)
                while (sessions.size > KEEP) sessions.removeFirst()
            }
        }
        record(session, "enter", "$trigger 条目 $subjectId 剧集 $episodeId")
    }

    /** 当前这次开播走到了 [step]; 还没开始过 (测试、手机版没接上) 时不记. */
    fun mark(step: String, detail: String = "") {
        if (!enabled) return
        val session = synchronized(lock) { current } ?: return
        record(session, step, detail)
    }

    private fun record(session: Session, step: String, detail: String) {
        val text = if (detail.length > MAX_DETAIL) detail.take(MAX_DETAIL) + "…" else detail
        val entry = synchronized(lock) {
            Entry(session.entries.size + 1, session.start.elapsedNow().inWholeMilliseconds, step, text).also { session.entries += it }
        }
        logger.info { "PlayTimeline #${session.id} +${entry.tMs}ms $step${if (text.isEmpty()) "" else " $text"}" }
        PerfTimeline.mark(PERF_CATEGORY, step) { text }
    }

    /** 最近几次开播, 新的在后. */
    fun toJson(): JsonArray = synchronized(lock) {
        buildJsonArray {
            for (session in sessions) addJsonObject {
                put("id", session.id)
                put("subjectId", session.subjectId)
                put("episodeId", session.episodeId)
                put("trigger", session.trigger)
                putJsonArray("steps") {
                    for (entry in session.entries) add(
                        buildJsonArray {
                            add(entry.seq)
                            add(entry.tMs)
                            add(entry.step)
                            add(entry.detail)
                        },
                    )
                }
            }
        }
    }

    private const val KEEP = 20
    private const val MAX_DETAIL = 200
    private const val PERF_CATEGORY = "开播时间线"
}
