/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 同时在查的数据源个数的上限 ([MediaSourceSearchLimiter.Default] 每次发名额时取 [current]).
 *
 * 上限就是设置「同时搜索的数据源」([MediaSelectorSettings.searchConcurrency], 默认不限: 每个源一开始就查).
 * 发现吃不消时按阶梯 ([STEPS]: 不限 → 16 → 10 → 6) 往下调一级, 当场生效, 并交给应用把设置写成新值、告知用户 ([adjustments]):
 * - 查的时候收到系统内存告警 ([onMemoryPressure]), 或开搜时系统已报内存低 ([beforeBatch]); 系统说内存严重不足时直接调到最低一级;
 * - 播放时搜索把画面拖卡 ([onSearchStutter]);
 * - 上次运行开搜时在前台被系统杀掉 (平台启动时认出, [onKilledWhileSearching]): 从那时的上限往下一级.
 *
 * 调小后正在查的照常查完, 内存要过一会儿才降, 所以前两种至少隔 [STEP_INTERVAL] 才再调一级.
 */
class SearchConcurrency(
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    enum class Reason { MemoryLow, SearchStutter, KilledWhileSearching }

    /** 自动调成了 [limit]: 应用把设置写成它并告知用户. */
    class Adjustment(val limit: Int, val reason: Reason)

    /** 平台提供的 (Android 见 AndroidSearchConcurrency). */
    interface Platform {
        /** 系统现在是不是报内存低. */
        fun lowMemory(): Boolean

        /** 记下正在查、那时的上限 ([limit]); `null` = 没在查. 进程被杀时留在盘上, 下次启动据此认出 [onKilledWhileSearching]. */
        fun markSearching(limit: Int?)
    }

    private val lock = SynchronizedObject()
    private val limit = MutableStateFlow(UNLIMITED)
    private val busy = MutableStateFlow(false)

    /** 自动调的值还没从设置写回来: 设置流里比它大的是写之前的旧值, 不采用. */
    private var pending: Int? = null
    private var lastStep: TimeMark? = null
    private var lastMemoryCheck: TimeMark? = null

    @Volatile
    private var platform: Platform? = null

    private val adjustmentChannel = Channel<Adjustment>(Channel.UNLIMITED)

    /** 自动调整, 只给一个收的 (应用写设置并告知); 收之前的先存着. */
    val adjustments: Flow<Adjustment> = adjustmentChannel.receiveAsFlow()

    /** 现在的上限, [UNLIMITED] = 不限. */
    fun current(): Int = limit.value

    /** 设置里的值变了 (0 = 不限). */
    fun onSetting(value: Int) {
        val new = if (value <= 0) UNLIMITED else value
        synchronized(lock) {
            pending?.let { waiting ->
                if (new > waiting) return
                pending = null
            }
            limit.value = new
        }
    }

    /** 平台启动时调一次: 之后开搜 / 搜完都经 [Platform.markSearching] 记下. */
    fun install(platform: Platform, scope: CoroutineScope) {
        this.platform = platform
        scope.launch {
            combine(busy, limit) { searching, limit -> if (searching) limit else null }
                .distinctUntilChanged()
                .collect { platform.markSearching(it) }
        }
    }

    /** [MediaSourceSearchLimiter] 报在查的个数 (在它的锁里调, 只改一个值). */
    fun onInFlightChanged(count: Int) {
        busy.value = count > 0
    }

    /** [MediaSourceSearchLimiter] 报: 没有在查的时候来了要查的, 发名额之前. 系统已报内存低就先调小. */
    fun beforeBatch() {
        val platform = platform ?: return
        synchronized(lock) {
            if (lastMemoryCheck?.let { it.elapsedNow() < MEMORY_CHECK_INTERVAL } == true) return
            lastMemoryCheck = timeSource.markNow()
        }
        if (platform.lowMemory()) tighten(Reason.MemoryLow)
    }

    /** 系统内存告警; [critical] = 严重不足, 直接调到最低一级. 没在查时不算 (光播放也会收到). */
    fun onMemoryPressure(critical: Boolean) {
        if (busy.value) tighten(Reason.MemoryLow, toFloor = critical)
    }

    /** 播放时搜索把画面拖卡了. */
    fun onSearchStutter() = tighten(Reason.SearchStutter)

    /** 上次运行开搜时在前台被系统杀掉, 那时的上限是 [limitThen]. */
    fun onKilledWhileSearching(limitThen: Int) = tighten(Reason.KilledWhileSearching, from = limitThen)

    private fun tighten(reason: Reason, toFloor: Boolean = false, from: Int? = null) {
        var old = 0
        val adjustment = synchronized(lock) {
            val paced = reason != Reason.KilledWhileSearching && !toFloor
            if (paced && lastStep?.let { it.elapsedNow() < STEP_INTERVAL } == true) return
            old = limit.value
            val new = if (toFloor) FLOOR else stepDown(minOf(old, from ?: old))
            if (new >= old) return
            limit.value = new
            pending = new
            lastStep = timeSource.markNow()
            Adjustment(new, reason)
        }
        logger.info { "Search concurrency ${if (old == UNLIMITED) "unlimited" else old} -> ${adjustment.limit} ($reason)" }
        adjustmentChannel.trySend(adjustment)
    }

    companion object {
        private val logger = logger<SearchConcurrency>()

        /** 不限 */
        const val UNLIMITED = Int.MAX_VALUE

        /** 自动往下调的阶梯 (从不限起) */
        val STEPS = listOf(16, 10, 6)

        /** 自动调的最低一级 */
        val FLOOR = STEPS.last()

        /** 设置里的选项, 0 = 不限 */
        val OPTIONS = listOf(0) + STEPS

        /** 两次自动调整至少隔这么久 (杀进程、严重不足除外) */
        val STEP_INTERVAL = 10.seconds

        /** 开搜时问系统内存低不低, 至少隔这么久问一次 */
        private val MEMORY_CHECK_INTERVAL = 5.seconds

        /** [limit] 往下一级; 已在最低一级时不变. */
        fun stepDown(limit: Int): Int = STEPS.firstOrNull { it < limit } ?: minOf(limit, FLOOR)

        /** 播放与下载的搜索共用这一个. */
        val Default = SearchConcurrency()
    }
}
