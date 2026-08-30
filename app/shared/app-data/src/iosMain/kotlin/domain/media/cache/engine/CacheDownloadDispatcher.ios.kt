/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.coroutines.CoroutineDispatcher
import me.him188.ani.utils.coroutines.IO_
import kotlinx.coroutines.Dispatchers

/** iOS 上没有对应的线程优先级控制, 沿用默认 IO. */
actual fun createCacheDownloadDispatcher(): CoroutineDispatcher = Dispatchers.IO_
