/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import me.him188.ani.app.domain.player.VideoLoadingState

/**
 * 播放失败后自动换到了下一个资源 (见 [SwitchMediaOnPlayerErrorExtension]). 加载提示据此写
 * 「上一个源解析超时，正在试第 3 个（还剩 5 个）」, 否则换源时画面上只有一闪而过的红字, 看不出在试第几个.
 */
data class MediaAutoSwitchStatus(
    /** 上一个资源为什么失败; `null` = 播放器打不开 / 缓存被删这类没有细分原因的. */
    val previousFailure: VideoLoadingState.Failed?,
    /** 本集正在试的是第几个资源 (失败过、被换掉的算在内, 所以至少是 2). */
    val attempt: Int,
    /** 除了正在试的这个, 还剩几个能换的在线资源. */
    val remaining: Int,
)
