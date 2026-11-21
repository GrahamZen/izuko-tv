/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** 照进程生命周期 (`ProcessLifecycleOwner`) 设 [AppForeground.started]: STARTED (有界面在显示) 起算前台. Application 里调一次. */
fun AppForeground.follow(processLifecycle: Lifecycle) {
    processLifecycle.addObserver(
        LifecycleEventObserver { _, _ -> started.value = processLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
    )
}
