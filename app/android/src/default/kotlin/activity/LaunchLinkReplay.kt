/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.activity

import android.content.Intent
import android.os.Bundle

/**
 * [MainActivity.onCreate] 要处理的启动 intent: 系统重建 Activity 时交回来的那条去掉链接 (extras 照留).
 *
 * 任务由深链接 (主屏卡片、屏保) 冷启动时, Activity 记录里一直记着那条链接 (singleTask, 之后的启动都走 onNewIntent, 不换记录里的 intent).
 * 进程在后台被回收后从桌面 / 最近任务重开、界面缩放等配置变更重建时, 系统都拿这条旧 intent 再建一次 Activity: 返回栈已经按保存的状态恢复,
 * 再按旧链接跳转会凭空多压一层页面, 去的还是当初点的那一集. 所以只有全新的一次启动 (没有保存的状态, 也不是从最近任务恢复) 才处理链接.
 */
internal fun Intent.withoutReplayedLink(savedInstanceState: Bundle?): Intent {
    val fresh = savedInstanceState == null && flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
    return if (fresh || data == null) this else Intent(this).setData(null)
}
