/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 替用户打开动作面板, 焦点落在顶上的后台任务那一格: 用户在电视上刚开始一件在后台跑的事 (引导里按「完成」开始移动缓存、
 * 设置里确认「移动已有缓存」), 进度就在那里, 弹一次让他看到在哪看、此刻跑到哪. 从 Web 控制台开始的不弹 (电视那头可能有人在看).
 *
 * TV 外壳收着 [taskRequests] 就打开 (同长按播放键, 当前页不让认领时不开); 没有外壳的形态没人收, 什么都不发生.
 */
object TvActionPanelRequests {
    private val logger = logger<TvActionPanelRequests>()
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val taskRequests: SharedFlow<Unit> = requests.asSharedFlow()

    /** [reason] 只进日志 (哪件事要弹) */
    fun showTasks(reason: String) {
        logger.info { "Showing background tasks in the action panel for $reason (listeners: ${requests.subscriptionCount.value})" }
        requests.tryEmit(Unit)
    }
}
