/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import androidx.compose.runtime.Composable
import org.openani.mediamp.MediampPlayer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 播放时按视频帧率切屏幕刷新率 ([enabled] 时): 刷新率不是帧率的整数倍 (24 fps 放在 60 Hz 上) 时每帧轮流显示 2 次、3 次,
 * 平移镜头一顿一顿; 换到整数倍的刷新率 (见 [pickDisplayModeForFrameRate]) 就没有. 离开播放页时换回原来的显示模式.
 *
 * 换显示模式时电视要重新同步信号, 黑屏一两秒; 同一帧率的下一集不再换. 有的设备不让应用切 (Shield 的显示驱动回 BadConfig,
 * 系统每一帧重试), 几秒内没切成就撤回请求, 这次播放不再试; 这类设备靠系统自己的「匹配内容帧率」, 用的是 media3 按帧率给的提示.
 * 只有安卓有, 其他平台什么都不做.
 */
@Composable
expect fun ContentFrameRateEffect(player: MediampPlayer, enabled: Boolean)

/** 屏幕的一个显示模式 (分辨率与刷新率). */
data class DisplayModeOption(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * 放 [fps] 帧的视频该换到哪个显示模式: 与当前分辨率相同、刷新率是帧率整数倍的模式里, 先挑倍数准的 (23.976 fps 配 23.976 Hz),
 * 再挑倍数小的 (24 Hz 比 120 Hz 更多电视处理得好). 当前模式已经是整数倍, 或者没有合适的模式时为 null (不换).
 *
 * 23.976 fps 放在 24 Hz 上也算整数倍 (差 0.1%, 几十秒多显示一帧), 但有准的模式时用准的.
 */
fun pickDisplayModeForFrameRate(modes: List<DisplayModeOption>, current: DisplayModeOption, fps: Float): Int? {
    if (fps <= 0f) return null
    fun multipleOf(mode: DisplayModeOption): Pair<Int, Float>? {
        val ratio = mode.refreshRate / fps
        val n = ratio.roundToInt()
        if (n < 1) return null
        val error = abs(ratio - n) / n
        return if (error <= CADENCE_TOLERANCE) n to error else null
    }
    if (multipleOf(current)?.second?.let { it <= EXACT_TOLERANCE } == true) return null
    val best = modes
        .filter { it.width == current.width && it.height == current.height }
        .mapNotNull { mode -> multipleOf(mode)?.let { (n, error) -> Triple(mode, n, error) } }
        .minWithOrNull(compareBy<Triple<DisplayModeOption, Int, Float>> { it.third > EXACT_TOLERANCE }.thenBy { it.second })
        ?: return null
    // 当前模式也是整数倍、新的不比它准时不换: 换一次要黑屏
    val currentMultiple = multipleOf(current)
    if (currentMultiple != null && best.third > EXACT_TOLERANCE) return null
    return best.first.id.takeIf { it != current.id }
}

/** 算整数倍的误差上限: 23.976 fps 放 24 Hz 差 0.1%. */
private const val CADENCE_TOLERANCE = 0.002f

/** 算「准」的误差上限: 显示模式报的刷新率与标称值差万分之几 (23.976025 Hz). */
private const val EXACT_TOLERANCE = 0.0005f
