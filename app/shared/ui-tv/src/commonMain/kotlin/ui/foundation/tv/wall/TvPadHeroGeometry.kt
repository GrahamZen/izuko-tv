/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.wall

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.tv.TvBackdropFade
import me.him188.ani.app.ui.foundation.tv.TvBackdropTreatment

/*
 * 平板版 hero (探索页的热门轮播与各海报墙的 hero 态) 的摆法, iPad 的 Compose 海报墙 (TvPadHeroLayer) 与安卓平板模式的原生海报墙
 * (TvNativePadHero) 共用: 一块铺到卡片区 (轮播区底边 / 聚焦行的组标题、行顶) 为止, 右上角一张清晰的横版图 (高度铺满这一块, 左缘与下缘
 * 羽化进底下的模糊底), 文字贴左下往上长.
 */

/** 海报墙 hero 态的文字块下沿离这块底边 (聚焦行的组标题 / 行顶) 多远. */
internal val TV_PAD_HERO_TEXT_BOTTOM = 20.dp

/** 简介行数: 轮播 (下面有按钮) 三行; 海报墙 hero 态没有按钮, 多放两行, 文字块照样往上长到轮播文字的高度附近. */
internal const val TV_PAD_HERO_SUMMARY_LINES = 3
internal const val TV_PAD_HERO_WALL_SUMMARY_LINES = 5

/** 标题与简介的宽度占页面宽度的比例. */
internal const val TV_PAD_HERO_TITLE_WIDTH_FRACTION = 0.46f
internal const val TV_PAD_HERO_SUMMARY_WIDTH_FRACTION = 0.40f

/** 横版图左缘羽化的起止 (占图宽), 下缘从这个高度起淡到透明. */
internal const val TV_PAD_HERO_LEFT_FADE_START = 0.02f
internal const val TV_PAD_HERO_LEFT_FADE_END = 0.35f
internal const val TV_PAD_HERO_BOTTOM_FADE_START = 0.6f

/**
 * 标题 logo 的高度上限比电视版放宽几倍: 竖版 logo 在电视版里先碰到高度上限、面积只有横版的一半不到, 平板版文字块往上长、空间够,
 * 让它也按面积放大.
 */
internal const val TV_PAD_HERO_LOGO_TALL_SCALE = 2f

/** 模糊底从这块高度的这个位置起往下渐变到墙的底色. */
internal const val TV_PAD_HERO_FLOOR_START = 0.45f

/** 轮播: 圆点、按钮、文字块的下沿离轮播区底边多远 —— 自下而上圆点 → 按钮 → 文字, 文字块往上长, 按钮的位置不随文字长短变. */
internal val TV_PAD_CAROUSEL_DOTS_BOTTOM = 18.dp
internal val TV_PAD_CAROUSEL_BUTTONS_BOTTOM = 40.dp
internal val TV_PAD_CAROUSEL_TEXT_BOTTOM = 96.dp

/** 轮播的圆点: 其余项见方, 当前项拉长的胶囊, 间距. */
internal val TV_PAD_CAROUSEL_DOT = 6.dp
internal val TV_PAD_CAROUSEL_DOT_SELECTED = 16.dp
internal val TV_PAD_CAROUSEL_DOT_GAP = 6.dp
internal const val TV_PAD_CAROUSEL_DOT_INACTIVE_ALPHA = 0.4f

/** 简介: 分段换行压成一段 (平板版文字块只放几行, 留着空行就是白占). */
internal fun tvPadHeroSummary(summary: String): String =
    summary.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

/**
 * 原生背景图层 (TvNativeBackdropView) 照平板版羽化横版图用的遮罩: 左缘从 [TV_PAD_HERO_LEFT_FADE_START] 到 [TV_PAD_HERO_LEFT_FADE_END]
 * 由透明到不透明, 下缘从 [TV_PAD_HERO_BOTTOM_FADE_START] 起淡到透明. 底下铺着模糊背景时 (原生的羽化) 这两条把图擦成透明; [fadeColor] 只在
 * 没铺的时候用得上.
 */
internal fun tvPadBackdropTreatment(fadeColor: Color): TvBackdropTreatment =
    TvBackdropTreatment(
        left = TvBackdropFade(start = TV_PAD_HERO_LEFT_FADE_START, end = TV_PAD_HERO_LEFT_FADE_END, maxAlpha = 1f, color = fadeColor),
        bottom = TvBackdropFade(
            start = TV_PAD_HERO_BOTTOM_FADE_START,
            end = 1f,
            maxAlpha = 1f,
            color = fadeColor,
            toEdge = true,
            smoothness = 1f,
        ),
    )
