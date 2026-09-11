/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.runtime.Composable
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings

/**
 * TV 上跑马灯 (`basicMarquee`) 的滚动次数: 视觉效果完整档无限滚, 其余档滚 [TV_REDUCED_MARQUEE_ITERATIONS] 次后停住.
 *
 * 一直在滚的跑马灯让页面进不了静止态 (每帧都要出一帧), 属于"一直在动的装饰", 与占位脉动同一档 (见 TvVisualEffectsLevel).
 * 播放器片尾「接下来播放」自动展开选集条时尤其要紧: 焦点落在长集名的卡上, 无限滚会把叠在视频上的界面一直顶到
 * 60fps (最长 ~90s), 而倒计时环本身只要 ~10fps.
 */
@Composable
fun tvAmbientMarqueeIterations(): Int =
    if (LocalThemeSettings.current.visualEffects.ambient) Int.MAX_VALUE else TV_REDUCED_MARQUEE_ITERATIONS

private const val TV_REDUCED_MARQUEE_ITERATIONS = 3
