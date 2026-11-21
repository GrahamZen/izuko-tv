/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.dateByAddingTimeInterval
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * iOS 的后台刷新 (BGAppRefreshTask): 应用进后台时向系统要一次「过一阵叫醒我」, 被叫醒时把 [BackgroundRefresh] 登记的活跑一遍,
 * 跑完再要下一次. 最早什么时候叫由 [interval] 定, 实际什么时候叫、叫不叫由系统定.
 *
 * 任务的标识要写在 Info.plist 的 BGTaskSchedulerPermittedIdentifiers 里 (UIBackgroundModes 里有 fetch), 处理函数要在应用启动完之前
 * 登记 ([install], 在 startIosApp 里调).
 */
object IosBackgroundRefresh : BackgroundRefresh.Scheduler {
    const val TASK_ID = "izuko.background-refresh"

    /** 跑完之后最早过多久再叫; 调试接口能改 (见 [setInterval]). */
    @Volatile
    override var interval: Duration = 30.minutes
        private set

    private val logger = logger<IosBackgroundRefresh>()
    private var scope: CoroutineScope? = null

    private val _lastSubmit = MutableStateFlow<Submit?>(null)

    /** 上一次向系统要叫醒: 什么时候要的、最早几点、系统收没收. */
    val lastSubmit: StateFlow<Submit?> = _lastSubmit.asStateFlow()

    data class Submit(val atMillis: Long, val earliestMillis: Long, val accepted: Boolean)

    override val lastSubmitText: String?
        get() = _lastSubmit.value?.let { "at ${it.atMillis}, earliest ${it.earliestMillis}, accepted ${it.accepted}" }

    fun install(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        BackgroundRefresh.scheduler = this
        val registered = BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(TASK_ID, null) { task ->
            if (task != null) handle(task)
        }
        logger.info { "Background refresh registered: $registered" }
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            schedule()
        }
    }

    /** 改「最早过多久再叫」, 并按新的重新要一次. */
    override fun setInterval(value: Duration) {
        interval = value
        schedule()
    }

    /** 向系统要一次叫醒 (同一个标识只留最新这一次). */
    @OptIn(ExperimentalForeignApi::class)
    override fun schedule(): Boolean {
        val now = currentTimeMillis()
        val request = BGAppRefreshTaskRequest(identifier = TASK_ID)
        request.earliestBeginDate = NSDate().dateByAddingTimeInterval(interval.inWholeSeconds.toDouble())
        val accepted = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
        _lastSubmit.value = Submit(now, now + interval.inWholeMilliseconds, accepted)
        logger.info { "Background refresh requested in $interval: accepted=$accepted" }
        return accepted
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun handle(task: BGTask) {
        // 先要下一次: 这一次被系统中途收走也接得上
        schedule()
        val scope = scope ?: run {
            task.setTaskCompletedWithSuccess(false)
            return
        }
        // 跑完与系统收走可能同时到: 只报一次
        val completed = AtomicInt(0)
        fun complete(success: Boolean) {
            if (completed.compareAndSet(0, 1)) task.setTaskCompletedWithSuccess(success)
        }
        val job = scope.launch {
            BackgroundRefresh.runAll("system")
            complete(true)
        }
        task.expirationHandler = {
            logger.info { "Background refresh expired" }
            job.cancel()
            complete(false)
        }
    }
}
