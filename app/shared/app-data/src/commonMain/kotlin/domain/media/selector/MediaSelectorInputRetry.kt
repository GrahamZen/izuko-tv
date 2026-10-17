/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.utils.coroutines.retryWithBackoffDelay
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

private val logger = logger("MediaSelectorInput")

/**
 * 选源器上下文里要联网的输入 (条目、分集、是否完结) 取不到时记一条日志并退避重试, 不往下抛.
 *
 * [DefaultMediaSelector] 把上下文 shareIn 在没有异常处理器的作用域里, 输入流抛出的异常会直接让进程崩溃;
 * 重试期间这个字段保持未加载.
 */
internal fun <T> Flow<T>.retryAsSelectorInput(name: String): Flow<T> = retryWithBackoffDelay { e, _ ->
    val wrapped = RepositoryException.wrapOrThrowCancellation(e)
    logger.warn { "Media selector failed to load $name, retrying: $wrapped" }
    true
}
