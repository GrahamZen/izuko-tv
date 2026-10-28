/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import me.him188.ani.app.platform.DevSwitches
import kotlin.concurrent.Volatile
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 数据源查询 (解析网页、反序列化结果) 跑在哪个线程池. 开播时十几个源同时解析, 常把 `Dispatchers.Default` 的线程占满,
 * 自动选源、界面与播放器要用的协程排在后面. 开 [DevSwitches.searchLowPriority] 时查询改用 [lowPriority]:
 * Android 上是启动时 [install] 的一组低优先级线程, CPU 紧时先让给别的线程; 没装时就是 Default.
 *
 * 开 [DevSwitches.searchPrioritized] 时每个源用自己的通道 ([forSource]), 同一组线程按通道的位次取任务:
 * 等网站回应的源不占线程, 线程空出来先给开查先后里靠前的源 (见 [SourceStartOrder]) 解析, 不跟一串源排队.
 */
object SearchDispatchers {
    /** 按位次取任务的线程池里的一条通道; [rank] 越小越先. */
    fun interface Lanes {
        fun lane(rank: Int): CoroutineDispatcher
    }

    @Volatile
    var lowPriority: CoroutineDispatcher = Dispatchers.Default
        private set

    @Volatile
    private var lanes: Lanes? = null

    fun install(dispatcher: CoroutineDispatcher, lanes: Lanes? = null) {
        lowPriority = dispatcher
        this.lanes = lanes
    }

    /** 新建的查询用的线程池 */
    val current: CoroutineDispatcher
        get() = if (DevSwitches.searchLowPriority.value) lowPriority else Dispatchers.Default

    /** 开查先后里位次为 [rank] 的源的查询换到它的通道上; 没开 [DevSwitches.searchPrioritized] 或平台没有时不换. */
    fun forSource(rank: Int): CoroutineContext {
        val lanes = lanes ?: return EmptyCoroutineContext
        return if (DevSwitches.searchPrioritized.value) lanes.lane(rank) else EmptyCoroutineContext
    }

    /**
     * 数据源内部要离开调用方的线程去解析时用: 已经在查询的线程池里 (含各源的通道) 就留在原处, 保住它的位次; 否则去 Default.
     */
    suspend fun parsingContext(): CoroutineContext {
        val current = currentCoroutineContext()[ContinuationInterceptor]
        return if (current === lowPriority || current is Lane) EmptyCoroutineContext else Dispatchers.Default
    }

    /** [Lanes] 给出的通道要实现它, [parsingContext] 才认得. */
    interface Lane
}
