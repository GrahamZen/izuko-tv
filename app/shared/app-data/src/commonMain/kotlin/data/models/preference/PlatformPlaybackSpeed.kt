/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import me.him188.ani.utils.platform.currentPlatform
import me.him188.ani.utils.platform.isIos

/**
 * 本平台倍速范围的上界. iOS 的 AVPlayer 超过 2 倍时不连续播放在线视频: 一直处于等待状态、进度跳着走、同一分片反复重下,
 * 界面就一直转圈, 所以 iOS 到 2 倍为止.
 */
val platformMaxPlaybackSpeed: Float
    get() = if (currentPlatform().isIos()) 2f else VideoScaffoldConfig.MAX_SUPPORTED_PLAYBACK_SPEED

/**
 * 把配置里的倍速范围、常驻倍速与长按倍速夹到 [cap] 以内. 默认值与已存的配置都可能超过平台上界 (默认长按 2.5 倍),
 * 所以在读取时夹, 不改存下的值.
 */
fun VideoScaffoldConfig.withinPlaybackSpeedCap(cap: Float = platformMaxPlaybackSpeed): VideoScaffoldConfig {
    val max = maxPlaybackSpeed.coerceAtMost(cap)
    return copy(
        minPlaybackSpeed = minPlaybackSpeed.coerceAtMost(max - VideoScaffoldConfig.MIN_PLAYBACK_SPEED_RANGE_WIDTH),
        maxPlaybackSpeed = max,
        playbackSpeed = playbackSpeed.coerceAtMost(cap),
        fastForwardSpeed = fastForwardSpeed.coerceAtMost(cap),
    )
}
