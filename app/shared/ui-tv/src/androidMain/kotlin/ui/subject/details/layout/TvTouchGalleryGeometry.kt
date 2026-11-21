/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMonogramStyle
import me.him188.ani.app.ui.subject.details.sections.VIEW_ALL_GALLERY_TOUCH_CIRCLE_TO_STEP
import kotlin.math.roundToInt

/**
 * 平板 (触屏) 的「查看全部」人物画廊照稿缩小圆: 直径为相邻两格间距的 [VIEW_ALL_GALLERY_TOUCH_CIRCLE_TO_STEP] (Compose 版同一个比例), 名字与副标题仍按原来的格宽排,
 * 圆在格里居中, 行跟着圆变矮. 遥控器上 ([touch] 为 false) 原样返回: 圆 = 格宽, 聚焦放大的那一圈要这么大才看得清.
 */
internal fun TvNativeMonogramStyle.forTouchGallery(touch: Boolean): TvNativeMonogramStyle {
    if (!touch) return this
    val circle = (stepPx * VIEW_ALL_GALLERY_TOUCH_CIRCLE_TO_STEP).roundToInt().coerceAtMost(cellWidthPx)
    return copy(sizePx = circle, cellWidthPx = cellWidthPx)
}
