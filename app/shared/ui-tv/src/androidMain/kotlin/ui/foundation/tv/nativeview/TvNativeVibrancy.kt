/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.widget.TextView
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_SECONDARY_LABEL_ALPHA
import kotlin.math.roundToInt

/*
 * tvOS 的 vibrancy (Apple TV 安卓版文字格式里 vibrancyEffectStyle 的深色那一档): 字以加法混合画在底下已经画好的背景上, 不是半透明盖上去 ——
 * 模糊背景上的字带出底图的颜色, 中等亮度的底上也清楚. 次要文字 (卡片下面那行小字、hero 的信息行 / 下一集行 / 简介) 用白 50%
 * ([tvVibrancySecondary]); 主要文字 (标题) 是白色, 加上去照样是白. 新番时间表的卡片番名、hero 态铺模糊背景时的卡片与 hero 文字共用这一份.
 *
 * 只给浅色字 (深色主题) 用: 浅色主题要的是相减 (Apple 用 Subtract), 画笔没有这种混合模式, 深色字照常画. 字所在的视图与上面各层都不能开离屏层
 * (透明度逐绘制指令乘, 见 TvNativeTextView 等的 hasOverlappingRendering), 否则字加在离屏层的透明底上, 看着与普通半透明字一样.
 */

/** 这个 TextView 的字照 vibrancy 画 ([enabled]) 或照常画. */
internal fun TextView.setTvVibrancy(enabled: Boolean) {
    val mode = if (enabled) TV_NATIVE_VIBRANCY else null
    if (paint.xfermode === mode) return
    paint.xfermode = mode
    invalidate()
}

/** vibrancy 次要文字的颜色: 主要文字的颜色 [primary] (ARGB) 降到次要那一档透明度 ([TV_POSTER_WALL_SECONDARY_LABEL_ALPHA]). */
internal fun tvVibrancySecondary(primary: Int): Int =
    (primary and 0xFFFFFF) or ((TV_POSTER_WALL_SECONDARY_LABEL_ALPHA * 255f).roundToInt() shl 24)

private val TV_NATIVE_VIBRANCY = PorterDuffXfermode(PorterDuff.Mode.ADD)
