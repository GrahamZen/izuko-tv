/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import me.him188.ani.app.data.models.preference.ThemeSettings
import kotlin.math.max
import kotlin.math.min

/** 电视界面按这么大的画布 (dp) 设计. */
const val TV_DESIGN_CANVAS_WIDTH_DP = 960
const val TV_DESIGN_CANVAS_HEIGHT_DP = 540

/** 同设置里「界面缩放」的档距 (百分比). */
private const val UI_SCALE_STEP_PERCENT = 10

/**
 * 屏幕 [widthPx] × [heightPx] 像素、系统给的 [density] 下, 让画布至少 [TV_DESIGN_CANVAS_WIDTH_DP] × [TV_DESIGN_CANVAS_HEIGHT_DP]
 * 的界面缩放; 画布本来就够时 null. 长边对宽、短边对高; 按设置里 10% 一档往小取 (宁可画布略大于设计尺寸, 也不溢出),
 * 最小 [ThemeSettings.UI_SCALE_MIN].
 *
 * 用于 densityDpi 报大的盒子: 像素数是准的, 只是每英寸多少像素报大了, 画布因此比设计尺寸小.
 */
fun tvUiScaleToFitDesignCanvas(widthPx: Int, heightPx: Int, density: Float): Float? {
    if (widthPx <= 0 || heightPx <= 0 || !(density > 0f)) return null
    val longPx = max(widthPx, heightPx)
    val shortPx = min(widthPx, heightPx)
    // 画布刚好够大时的 density
    val fitDensity = min(longPx.toFloat() / TV_DESIGN_CANVAS_WIDTH_DP, shortPx.toFloat() / TV_DESIGN_CANVAS_HEIGHT_DP)
    val percent = (fitDensity / density * 100f + 0.001f).toInt()
    if (percent >= 100) return null
    val stepped = percent / UI_SCALE_STEP_PERCENT * UI_SCALE_STEP_PERCENT
    return (stepped / 100f).coerceAtLeast(ThemeSettings.UI_SCALE_MIN)
}
