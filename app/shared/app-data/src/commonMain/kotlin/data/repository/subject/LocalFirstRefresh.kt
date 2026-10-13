/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * 本地库这条 flow 的数据照常往下发, 同时在后台跑 [refresh] (从服务端取了写进库); 写库后本地这条 flow 自己会重发新数据.
 * 不等刷新完才开始查库: 刷新慢的时候 (网络差、要补很多数据) 本地已有的数据也能先显示出来. 下游停止收集时刷新一起取消.
 */
internal fun <T> Flow<T>.whileRefreshingInBackground(refresh: suspend () -> Unit): Flow<T> = channelFlow {
    launch { refresh() }
    collect { send(it) }
}
