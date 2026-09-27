/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 探索页海报墙的停位 ([centeredScroll]): 聚焦行停在视口正中, 第一行也一样 (热门轮播还露着一截, 跟着列表上移, 见
 * [tvNativeCarouselShift]), 末尾停在末行底边离视口底留一截处.
 *
 * 数字取 1080p 下的 dp 量级 (这里当像素用): hero 占位 280, 组标题 36, 一行 235 (海报 173 + 番名 42 + 行距 20),
 * 视口 516 (540 − 顶线 24), 末尾留 40.
 */
class TvNativeExploreListTest {
    // [hero 占位, 继续观看标题, 行, 行, 推荐标题, 行, 行, 行]; 各项顶: 0, 280, 316, 551, 786, 822, 1057, 1292
    private val items = listOf(
        TvNativeExploreItem.Spacer("spacer"),
        TvNativeExploreItem.Header("followed-header", ""),
        TvNativeExploreItem.Row("followed-row", emptyList()),
        TvNativeExploreItem.Row("followed-row-2", emptyList()),
        TvNativeExploreItem.Header("rec-header", ""),
        TvNativeExploreItem.Row("rec-row-0", emptyList()),
        TvNativeExploreItem.Row("rec-row-1", emptyList()),
        TvNativeExploreItem.Row("rec-row-2", emptyList()),
    )

    private val metrics = TvNativeExploreMetrics(
        pageWidthPx = 960,
        pageHeightPx = 540,
        bleedLeftPx = 0,
        columns = 6,
        listTopPx = 24,
        listTopBleedPx = 0,
        listBottomBleedPx = 0,
        spacerPx = 280,
        headerPx = 36,
        rowPx = 235,
        rowGapPx = 20,
        viewportPx = 516,
        endMarginPx = 40,
        // 原 hero 页固定标签线离卡片区顶线的距离: 268 − 24
        heroHeaderTopPx = 244,
        rowStartPx = 0,
        endPadPx = 0,
        fadeDistancePx = 64f,
        carouselBottomPx = 0f,
        overhangPx = 0,
        backdropWidthPx = 0,
        backdropHeightPx = 0,
        cardBackdropScale = 1f,
        heroStartPx = 0,
        heroTopPx = 0,
        heroEndPadPx = 0,
        heroBlockPx = 0,
        heroBlockExpandedPx = 0,
        titleWidthPx = 0,
        carouselSummaryWidthPx = 0,
        cardSummaryWidthPx = 0,
        buttonsTopGapPx = 0,
        buttonGapPx = 0,
        dotsCenterYPx = 0,
        dotPx = 0f,
        dotSelectedWidthPx = 0f,
        dotGapPx = 0f,
    )

    @Test
    fun `first row is centered too`() {
        // 316 + 107 − 258 = 165: 比 hero 占位 (280) 少, 轮播还露着一截
        assertEquals(165, metrics.centeredScroll(items, 2))
    }

    @Test
    fun `middle rows are centered`() {
        assertEquals(551 + 107 - 258, metrics.centeredScroll(items, 3))
        assertEquals(822 + 107 - 258, metrics.centeredScroll(items, 5))
        assertEquals(1057 + 107 - 258, metrics.centeredScroll(items, 6))
    }

    @Test
    fun `last row stops at the end margin`() {
        // 内容底 = 1527 − 20 = 1507, 上限 = 1507 + 40 − 516 = 1031 (居中要 1141)
        assertEquals(1031, metrics.centeredScroll(items, 7))
    }

    @Test
    fun `short content stops at the end`() {
        // 只有一组一行: 居中要 165, 上限 531 + 40 − 516 = 55
        val short = items.take(3)
        assertEquals(55, metrics.centeredScroll(short, 2))
    }

    @Test
    fun `carousel moves with the list plus the overhang until the first row is centered`() {
        // 第一行居中时滚 165, 背景图比占位长出去 58
        assertEquals(0f, tvNativeCarouselShift(0f, 165, 58))
        // 滚到一半: 跟着滚 82.5, 再多挪一半的 58
        assertEquals(82.5f + 29f, tvNativeCarouselShift(82.5f, 165, 58))
        // 第一行居中: 长出去的那截正好挪完
        assertEquals(165f + 58f, tvNativeCarouselShift(165f, 165, 58))
        // 再往下翻只跟着列表滚
        assertEquals(400f + 58f, tvNativeCarouselShift(400f, 165, 58))
    }

    @Test
    fun `hero state puts the focused row header on the fixed label line`() {
        // 首行: 组标题 (280) 往上挪到线上, 占位还露着一截
        assertEquals(316 - 36 - 244, metrics.heroScroll(items, 2))
        // 第二组的首行: 组标题 (786) 落到线上
        assertEquals(822 - 36 - 244, metrics.heroScroll(items, 5))
        // 没有组标题的行落在同一个位置, 上一行越线淡出
        assertEquals(551 - 36 - 244, metrics.heroScroll(items, 3))
        // 开头滚不到就停在顶
        assertEquals(0, metrics.copy(heroHeaderTopPx = 400).heroScroll(items, 2))
    }

    @Test
    fun `item top`() {
        assertEquals(0, metrics.itemTop(items, 0))
        assertEquals(316, metrics.itemTop(items, 2))
        assertEquals(1292, metrics.itemTop(items, 7))
    }
}
