/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache

import kotlinx.coroutines.flow.MutableStateFlow
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.time.Duration.Companion.minutes

/**
 * 从缓存页点「播放」的一次性标记: 缓存页打开播放页之前记一笔, 选源时取走 ([take]), 这一次就播这一集的缓存,
 * 没下完的 BT 也播 (边下边播), 不看「没下完的缓存先不播」的设置. 只管一次; [VALID_FOR] 内没取走就作废 (播放页没打开、用户退回去了).
 */
object CachePlayRequests {
    private data class Pending(val subjectId: Int, val episodeId: Int, val atMillis: Long)

    private val pending = MutableStateFlow<Pending?>(null)
    private val VALID_FOR = 2.minutes

    fun mark(subjectId: Int, episodeId: Int) {
        pending.value = Pending(subjectId, episodeId, currentTimeMillis())
    }

    /** 这一集有没有从缓存页点进来的标记; 有就取走. */
    fun take(subjectId: Int, episodeId: Int): Boolean {
        val current = pending.value ?: return false
        if (current.subjectId != subjectId || current.episodeId != episodeId) return false
        if (!pending.compareAndSet(current, null)) return false
        return currentTimeMillis() - current.atMillis <= VALID_FOR.inWholeMilliseconds
    }
}
