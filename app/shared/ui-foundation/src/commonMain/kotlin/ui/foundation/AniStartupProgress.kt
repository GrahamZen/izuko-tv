/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 冷启动时首屏在启动页 (logo + 进度条, 见电视端的 TvStartupLogo) 背后加载到哪了.
 *
 * 两段: 图片磁盘缓存打开 ([bindImageCacheOpenProgress]), 然后首屏的封面一张张加载出来 ([coverStarted] / [coverFinished]).
 * 启动页拿 [fraction] 画进度条, 等 [awaitFirstScreenReady] 返回就撤掉.
 *
 * 只在启动页盖着的那段时间记数: [stop] 之后各上报点都是空操作.
 */
class StartupProgressTracker {
    private data class Covers(val started: Int = 0, val finished: Int = 0)

    private val tracking = atomic(true)
    private val coldStartClaimed = atomic(false)
    private val covers = MutableStateFlow(Covers())
    private val noCoversExpected = MutableStateFlow(false)
    private val imageCacheOpenProgress = MutableStateFlow<StateFlow<Float>?>(null)

    /** 本进程第一次问返回 true. 启动页只在冷启动出现: Activity 重建时图片都在内存里, 没有可等的. */
    fun claimColdStart(): Boolean = coldStartClaimed.compareAndSet(expect = false, update = true)

    /** 图片磁盘缓存打开的进度 (0..1). 没接上时那一段算已完成. */
    fun bindImageCacheOpenProgress(progress: StateFlow<Float>) {
        imageCacheOpenProgress.value = progress
    }

    /** 还在记数 (启动页还盖着). 上报点据此决定要不要挂监听. */
    val isTracking: Boolean get() = tracking.value

    /** 首屏的一张封面开始加载. */
    fun coverStarted() {
        if (tracking.value) covers.update { it.copy(started = it.started + 1) }
    }

    /** 一张封面加载结束 (成功、失败或取消). */
    fun coverFinished() {
        if (tracking.value) covers.update { it.copy(finished = it.finished + 1) }
    }

    /**
     * 首屏这会儿没有封面要加载 (比如新用户的推荐还在现算, 要十几秒): 等首屏的一方不必干等到 noCoversTimeout,
     * 再给一小会儿就算好了 (见 [awaitFirstScreenReady]).
     */
    fun expectNoCovers() {
        noCoversExpected.value = true
    }

    /** 已加载完 / 已开始的封面数 (日志用). */
    val coverCounts: Pair<Int, Int> get() = covers.value.let { it.finished to it.started }

    /** 启动页撤掉 (或这次启动不出启动页): 之后不再记数. */
    fun stop() {
        tracking.value = false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val cacheOpen: Flow<Float> = imageCacheOpenProgress.flatMapLatest { it ?: flowOf(1f) }

    /** 整体进度 0..1, 只增不减: 前 [CACHE_WEIGHT] 是打开图片缓存, 其余是封面加载完的比例. */
    val fraction: Flow<Float> = combine(cacheOpen, covers, ::fractionOf).runningReduce { shown, next -> maxOf(shown, next) }

    /**
     * 此刻的整体进度 (同 [fraction] 的算法), 给进度条的第一帧用: 启动页一出来就该画出已经走到的地方 —— 从 [fraction] 收到第一个值
     * 要等一轮调度, 而那之后主线程常常紧接着做整个界面的第一次组合, 进度条就空着停在那一帧.
     */
    val currentFraction: Float get() = fractionOf(imageCacheOpenProgress.value?.value ?: 1f, covers.value)

    private fun fractionOf(cache: Float, loaded: Covers): Float {
        val coverFraction = if (loaded.started == 0) 0f else loaded.finished.toFloat() / loaded.started
        return CACHE_WEIGHT * cache.coerceIn(0f, 1f) + (1 - CACHE_WEIGHT) * coverFraction.coerceIn(0f, 1f)
    }

    /**
     * 挂起到首屏可以亮出来:
     *  - 图片缓存打开了, 并且
     *  - 已经开始的封面都加载完、且 [settle] 内没有新的封面开始 (后面的行可能晚一拍才有数据); 或者加载完过几张之后 [quiet] 内再没有
     *    新的加载完 —— 剩下的在等网络, 不值得让整屏陪着等.
     *
     * 一张封面都没开始 (首屏没有封面、数据还在算) 最多等 [noCoversTimeout]; 页面说了暂时没有封面要加载 ([expectNoCovers]) 就只再等
     * [noCoversGrace], 这期间开始加载的照常等. 总共最多等 [timeout].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun awaitFirstScreenReady(
        timeout: Duration = 8.seconds,
        noCoversTimeout: Duration = 4.seconds,
        noCoversGrace: Duration = 500.milliseconds,
        settle: Duration = 250.milliseconds,
        quiet: Duration = 500.milliseconds,
    ) {
        withTimeoutOrNull(timeout) {
            cacheOpen.first { it >= 1f }
            withTimeoutOrNull(noCoversTimeout) {
                combine(covers, noCoversExpected) { loaded, none -> loaded.started > 0 || none }.first { it }
            } ?: return@withTimeoutOrNull
            if (covers.value.started == 0) {
                withTimeoutOrNull(noCoversGrace) { covers.first { it.started > 0 } } ?: return@withTimeoutOrNull
            }
            covers.transformLatest { loaded ->
                when {
                    loaded.finished >= loaded.started -> {
                        delay(settle)
                        emit(Unit)
                    }

                    loaded.finished > 0 -> {
                        delay(quiet)
                        emit(Unit)
                    }
                }
            }.first()
        }
    }

    private companion object {
        const val CACHE_WEIGHT = 0.2f
    }
}

/** 本进程的启动进度. */
val AniStartupProgress = StartupProgressTracker()
