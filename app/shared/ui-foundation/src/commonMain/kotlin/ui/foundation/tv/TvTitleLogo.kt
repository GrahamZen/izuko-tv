/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.runtime.Immutable
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 标题 logo 的大小规则 (hero 文字块、详情页首屏与播放器共用, 见 ThemeSettings.tvTitleLogoDisplay), 照 Prime Video 实测 (2026-10-02, Shield 上五部:
 * 宽高比 1.16 → 43×37dp, 2.45 → 91×37, 3.06 → 95×31, 5.4 → 124×23, 7.9 → 125×16): 每张 logo 面积大致相同 ([areaPx]), 越宽越矮;
 * 高不超过 [maxHeightPx], 宽不超过 [maxWidthPx]. logo 底对齐在高 [maxHeightPx] 的标题槽底边上, 槽顶就是原来文字标题的顶.
 */
@Immutable
data class TvTitleLogoBox(val maxWidthPx: Int, val maxHeightPx: Int, val areaPx: Float) {
    /** 宽高比 [aspectRatio] (宽 / 高) 的 logo 显示多大 (px): 先按面积定, 再按高、宽上限等比缩. */
    fun sizeOf(aspectRatio: Float): TvTitleLogoSize {
        if (aspectRatio <= 0f || maxWidthPx <= 0 || maxHeightPx <= 0) return TvTitleLogoSize(0, 0)
        var height = min(sqrt(areaPx / aspectRatio), maxHeightPx.toFloat())
        var width = height * aspectRatio
        if (width > maxWidthPx) {
            width = maxWidthPx.toFloat()
            height = width / aspectRatio
        }
        return TvTitleLogoSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1))
    }
}

/** [TvTitleLogoBox.sizeOf] 的结果 (px). */
@Immutable
data class TvTitleLogoSize(val widthPx: Int, val heightPx: Int)

/**
 * logo 框: 高度上限 = 两行标题 ([titleLineHeightPx] × [TV_TITLE_LOGO_SCALE], Prime 的一行标题放大一倍); 面积与宽度上限按 Prime 的比例
 * 跟着高度上限走 (面积 ≈ 2.2 × 高², 宽 ≈ 3.4 × 高), 宽度另不超过原来文字标题的宽 [titleWidthPx] (0 = 不限). 行高为 0 时 null.
 */
fun tvTitleLogoBox(titleLineHeightPx: Int, titleWidthPx: Int): TvTitleLogoBox? {
    val maxHeight = titleLineHeightPx * TV_TITLE_LOGO_SCALE
    if (maxHeight <= 0) return null
    val maxWidth = (maxHeight * TV_TITLE_LOGO_WIDTH_RATIO).roundToInt().let { if (titleWidthPx > 0) min(it, titleWidthPx) else it }
    return TvTitleLogoBox(maxWidth, maxHeight, maxHeight.toFloat() * maxHeight * TV_TITLE_LOGO_AREA_RATIO)
}

/** 比 Prime 的大小 (高度上限一行标题) 整体放大几倍. */
private const val TV_TITLE_LOGO_SCALE = 2

/** 宽度上限 / 高度上限 (Prime: 125dp / 37dp). */
private const val TV_TITLE_LOGO_WIDTH_RATIO = 3.38f

/** 面积 / 高度上限² (Prime: 约 3000dp² / 37dp²). */
private const val TV_TITLE_LOGO_AREA_RATIO = 2.19f
