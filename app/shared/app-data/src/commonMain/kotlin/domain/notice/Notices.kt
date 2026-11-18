/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.notice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.data.models.notice.NoticeLog
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.time.Duration.Companion.days

/**
 * 列表里的一条.
 *
 * @property pending 跟着状态的 (来自 [Notices.addSource] 的来源): 状态没了它自己消失
 * @property seen 看过了: 在电视的通知弹窗或手机控制台的通知列表里列出来过, 之后时间没变晚
 */
data class NoticeEntry(
    val notice: Notice,
    val pending: Boolean,
    val seen: Boolean,
)

/**
 * 一个用户档的通知 (存在这个档的设置里: 换用户是重启进程, 一个进程只有一个档).
 *
 * 两类:
 * - **消息**: 发生过的一件事, [post] 存下, [MESSAGE_TTL] 后或超过 [MAX_MESSAGES] 条时最早的清掉;
 * - **跟着状态的**: 一个来源 ([addSource]) 从状态算出此刻还在的几条, 不存; 状态没了 (在电视上、控制台上处理掉了) 它自己消失.
 *
 * [all] 把两类合起来, 带上看没看过, 照文档的先后排好: 新的在前; 同是新的或同是看过的, 跟着状态的在前, 再按时间新的在前.
 *
 * 跟着状态的来源不知道这件事从什么时候起的 (如有待处理的本地收藏), 时间给 [UNKNOWN_TIME]: 用这件事这一次出现时 (第一次在来源里看到这个键)
 * 的时刻, 记在 [NoticeLog.firstSeen] 里 (在 [scope] 里一直看着各个来源记, 记下之前这条先不列, 免得看过的时刻与记下的对不上;
 * 没给 [scope] 时用此刻). 它从来源里消失了就忘掉, 再出现是新的一次. 只忘掉这个进程里在来源里出现过、后来没了的: 进程刚起来时来源
 * 常常先交出空的、状态算出来才交出真的 (如发版后的通知), 那时缺着的键不算状态没了, 否则看过的每次冷启动都成了新的.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Notices(
    private val log: Settings<NoticeLog>,
    scope: CoroutineScope? = null,
    private val now: () -> Long = { currentTimeMillis() },
) {
    // 设置的 update 是先读后写: 几处同时改会丢一边, 改都排队
    private val lock = Mutex()
    private val sources = MutableStateFlow<List<Flow<List<Notice>>>>(emptyList())
    private val recordsFirstSeen = scope != null

    /** 这个进程里在来源里出现过的不知道时间的键 (见 [recordFirstSeen]) */
    private val appeared = HashSet<String>()

    /**
     * 接上一个跟着状态的来源: 给出此刻还在的几条. 要马上给出第一份 (列表等所有来源都给过才出), 出错时这个来源当作没有.
     * 一个进程里每个来源只接一次.
     */
    fun addSource(source: Flow<List<Notice>>) {
        val safe = source.catch { emit(emptyList()) }
        sources.update { it + safe }
    }

    private val pending: Flow<List<Notice>> = sources.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList()) else combine(list) { parts -> parts.flatMap { it } }
    }

    init {
        scope?.launch {
            pending.collect { list -> recordFirstSeen(list) }
        }
    }

    /** 全部通知, 排好先后 (见类的说明). */
    val all: Flow<List<NoticeEntry>> = combine(log.flow, pending) { log, pending -> entriesOf(log, pending, now(), recordsFirstSeen) }
        .distinctUntilChanged()

    /**
     * 不知道时间的那几条记下第一次看到的时刻. 来源里没有的键: 这个进程里出现过、后来没了的忘掉 (状态没了, 再出现是新的一次);
     * 还没出现过的留着 (来源可能还没算出来), 只留最近的 [MAX_FIRST_SEEN] 个 (旧版本发版后的通知这类再也不会出现的键不一直攒着).
     */
    private suspend fun recordFirstSeen(pending: List<Notice>) {
        val unknown = pending.filter { it.atMillis == UNKNOWN_TIME }.map { it.key }.toSet()
        lock.withLock {
            appeared += unknown
            log.update {
                val kept = firstSeen.filterKeys { it in unknown || it !in appeared }
                    .entries.sortedByDescending { it.value }.take(MAX_FIRST_SEEN).associate { it.key to it.value }
                val added = (unknown - kept.keys).associateWith { now() }
                if (added.isEmpty() && kept == firstSeen) this else copy(firstSeen = kept + added)
            }
        }
    }

    /** 发一条消息. 同一个键已有、内容 (种类、参数) 也没变的不动; 内容变了的替换, 时间是这一条的, 算新的. */
    suspend fun post(notice: Notice) {
        lock.withLock {
            log.update {
                val old = messages.firstOrNull { it.key == notice.key }
                if (old != null && old.kind == notice.kind && old.args == notice.args) return@update this
                copy(messages = trimMessages(listOf(notice) + messages.filterNot { it.key == notice.key }, now()))
            }
        }
    }

    /** 撤回一条消息 (这件事不用再说了). */
    suspend fun retract(key: String) {
        lock.withLock {
            log.update { copy(messages = messages.filterNot { it.key == key }) }
        }
    }

    /** 这几条看过了 (列出来过). 记下每条此刻的时间: 之后时间变晚的又算新的. */
    suspend fun markSeen(notices: Collection<Notice>) {
        if (notices.isEmpty()) return
        lock.withLock {
            log.update {
                val next = seen.toMutableMap()
                var changed = false
                for (n in notices) {
                    if ((next[n.key] ?: Long.MIN_VALUE) < n.atMillis) {
                        next[n.key] = n.atMillis
                        changed = true
                    }
                }
                if (!changed) this else copy(seen = trimSeen(next))
            }
        }
    }

    companion object {
        /** 来源不知道这件事从什么时候起时给的时间 (见类的说明). */
        const val UNKNOWN_TIME = 0L

        /** 消息留多久. */
        val MESSAGE_TTL = 30.days

        /** 消息最多留几条, 再多丢最早的. */
        const val MAX_MESSAGES = 100

        /** 不知道时间的那几条, 第一次出现的时刻最多记多少个 (见 recordFirstSeen) */
        const val MAX_FIRST_SEEN = 100

        /** 看过的记录最多留几条 (只记键与时间; 再多丢时间最早的). */
        const val MAX_SEEN = 500

        internal fun entriesOf(log: NoticeLog, pending: List<Notice>, now: Long, waitFirstSeen: Boolean = false): List<NoticeEntry> {
            val byKey = LinkedHashMap<String, NoticeEntry>()
            fun seen(n: Notice) = (log.seen[n.key] ?: Long.MIN_VALUE) >= n.atMillis
            // 同一个键几个来源都给了: 取时间晚的
            for (raw in pending) {
                val n = if (raw.atMillis != UNKNOWN_TIME) raw else {
                    val first = log.firstSeen[raw.key] ?: if (waitFirstSeen) continue else now
                    raw.copy(atMillis = first)
                }
                val old = byKey[n.key]
                if (old == null || old.notice.atMillis < n.atMillis) byKey[n.key] = NoticeEntry(n, pending = true, seen = seen(n))
            }
            val cutoff = now - MESSAGE_TTL.inWholeMilliseconds
            for (n in log.messages) {
                if (n.atMillis < cutoff || n.key in byKey) continue
                byKey[n.key] = NoticeEntry(n, pending = false, seen = seen(n))
            }
            return byKey.values.sortedWith(
                compareBy<NoticeEntry> { it.seen }
                    .thenBy { !it.pending }
                    .thenByDescending { it.notice.atMillis },
            )
        }

        private fun trimMessages(messages: List<Notice>, now: Long): List<Notice> {
            val cutoff = now - MESSAGE_TTL.inWholeMilliseconds
            return messages.filter { it.atMillis >= cutoff }.sortedByDescending { it.atMillis }.take(MAX_MESSAGES)
        }

        private fun trimSeen(seen: Map<String, Long>): Map<String, Long> =
            if (seen.size <= MAX_SEEN) seen
            else seen.entries.sortedByDescending { it.value }.take(MAX_SEEN).associate { it.key to it.value }
    }
}
