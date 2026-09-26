/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository

import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.him188.ani.utils.coroutines.retryWithBackoffDelay

/**
 * 先改本地、再发请求的写操作 (看过、收藏状态). 界面读的是本地数据, 等请求回来再改的话用户点完要看着界面干等:
 * Bangumi 的写入偶尔要好几秒, 同一个人的写入服务端按顺序处理, 后台的批量请求也可能占着连接.
 *
 * 1. [writeLocal] 改本地, 返回改之前的状态;
 * 2. [send], 失败按退避重试 (4xx 是服务端的明确答复, 重试也一样, 不重试);
 * 3. 成功后 [reapplyLocal]: 请求期间落库的旧数据 (比如进页时就发出的刷新) 可能已经把本地盖回旧值, 再改一次;
 *    最终失败或被取消则 [revertLocal] 改回 (不受取消影响), 再抛出.
 *
 * [reapplyLocal] 与 [revertLocal] 都要用条件写 (本地还是预期的值才改): 期间用户又改过的, 以后面那次为准.
 */
internal suspend fun <T> writeLocalFirst(
    writeLocal: suspend () -> T,
    send: suspend () -> Unit,
    reapplyLocal: suspend (previous: T) -> Unit,
    revertLocal: suspend (previous: T) -> Unit,
) {
    val previous = writeLocal()
    try {
        suspend { send() }
            .asFlow()
            .retryWithBackoffDelay(3) { cause, _ -> cause !is ClientRequestException }
            .first()
    } catch (e: Throwable) {
        withContext(NonCancellable) { revertLocal(previous) }
        throw e
    }
    reapplyLocal(previous)
}
