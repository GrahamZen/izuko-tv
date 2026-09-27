/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.graphics.Rect
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TV_CARD_FADE_DISTANCE
import me.him188.ani.app.ui.foundation.tv.tvPosterWallCardWidth
import me.him188.ani.app.ui.foundation.tv.tvPosterWallColumns

/**
 * 海报墙的一条横滑行, 单独成行 (详情页关联条目): 一屏完整放得下几张按 tvPosterWallColumns 算,
 * 卡宽铺满, 再往后那张在行尾露一截; 按需挪, 不循环; 越过行首停靠线的卡压暗着出屏. 进行落点 = 上次聚焦那张 (首次进入是行首那张),
 * 落点与行首跨导航保存. [startPadding] / [endPadding] 是两端的停靠线, 行本体全宽出血. 上下键不管, 交给页面 (页面在 Compose 里用按键
 * 预览接住).
 */
@Composable
fun TvNativePosterStrip(
    cards: List<TvNativeCard?>,
    onClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    startPadding: Dp = 0.dp,
    endPadding: Dp = 0.dp,
    onFocused: (index: Int) -> Unit = {},
) {
    val sketch = LocalSketch.current
    val animatedScroll = LocalThemeSettings.current.visualEffects.animatedScroll
    var focusedIndex by rememberSaveable { mutableIntStateOf(-1) }
    var leftIndex by rememberSaveable { mutableIntStateOf(0) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnFocused by rememberUpdatedState(onFocused)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val available = (maxWidth - startPadding - endPadding).coerceAtLeast(0.dp)
        val columns = with(density) { tvPosterWallColumns(available) }
        val cardWidth = tvPosterWallCardWidth(available, columns).coerceAtLeast(0.dp)
        val style = rememberTvNativeWallStyle(cardWidth, columns)
        val rowHeight = with(density) { style.cardBlockHeightPx.toDp() }
        val bleedPx = with(density) { TV_NATIVE_STRIP_BLEED.roundToPx() }
        val startPx = with(density) { startPadding.roundToPx() }
        val endPx = with(density) { endPadding.roundToPx() }
        val fadeDistancePx = with(density) { TV_CARD_FADE_DISTANCE.toPx() }
        TvNativeRowHost(
            factory = { context ->
                TvNativeRowView(
                    context, style, sketch, pool = null,
                    startPx = startPx, endPx = endPx, bottomPx = bleedPx, topPx = bleedPx,
                    fadeDistancePx = fadeDistancePx,
                ).also { row ->
                    // 从行外进来落到上次聚焦那张 (还在屏上时)
                    row.entryIndex = { focusedIndex }
                }
            },
            update = { row ->
                // 跨导航恢复行首与上次聚焦那张: 等数据到了才做 (返回时页面重建, 这一行先建出来、条目后到; 空列表上定行首会落回 0)
                if (row.tag == null && cards.isNotEmpty()) {
                    row.tag = TV_NATIVE_STRIP_RESTORED
                    row.bind(cards, { it.toLong() }, leftIndex, columns, focusIndex = focusedIndex)
                }
                row.animatedScroll = animatedScroll
                row.cards.listener = object : TvNativeCardListener {
                    override fun onFocused(index: Int) {
                        focusedIndex = index
                        // 按需挪之后的行首
                        leftIndex = tvStripLeftIndex(leftIndex, index, cards.size, columns)
                        currentOnFocused(index)
                    }

                    override fun onClick(index: Int) = currentOnClick(index)

                    override fun onLongPress(index: Int, anchor: Rect) = Unit
                }
                row.cards.submit(cards) { it.toLong() }
            },
            height = rowHeight,
            bleedVertical = TV_NATIVE_STRIP_BLEED,
        )
    }
}

/** 单独成行时视图上下多出的一截: 聚焦卡放大 (1.12 倍) 伸出行外的部分与投影都画在里面. */
private val TV_NATIVE_STRIP_BLEED = 32.dp

/** 行视图的 tag: 行首与落点已按保存的位置恢复过. */
private const val TV_NATIVE_STRIP_RESTORED = "restored"
