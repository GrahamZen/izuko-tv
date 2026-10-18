/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 有没有视频正在播 (在播或缓冲中; 暂停不算). 播放器那头按播放器各报一份 ([report]),
 * 播放时要避开的重活读 [isActive] —— 如后台搜索别建 / 用浏览器 (WebView 的创建与加载都在主线程上, 一卡几秒, 字幕跟着停).
 */
object PlaybackActivity {
    private val activeOwners = MutableStateFlow<Set<Any>>(emptySet())

    val isActive: Boolean get() = activeOwners.value.isNotEmpty()

    /** [owner] (一个播放器) 现在是不是在播或缓冲; 播放器释放时报 `false`. */
    fun report(owner: Any, active: Boolean) {
        activeOwners.update { if (active) it + owner else it - owner }
    }
}
