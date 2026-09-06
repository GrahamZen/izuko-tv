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
import androidx.compose.runtime.remember
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
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TV_CARD_FADE_DISTANCE
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_COLUMN_SPACING
import me.him188.ani.app.ui.foundation.tv.tvPosterWallCardWidth
import me.him188.ani.app.ui.foundation.tv.tvPosterWallColumns

/**
 * 海报墙的一条横滑行, 单独成行 (详情页关联条目、人物预览弹窗的作品): 一屏完整放得下几张按 tvPosterWallColumns 算,
 * 卡宽铺满, 再往后那张在行尾露一截; 按需挪, 不循环; 越过行首停靠线的卡压暗着出屏. 进行落点 = 上次聚焦那张 (首次进入是行首那张),
 * 落点与行首跨导航保存. [startPadding] / [endPadding] 是两端的停靠线, 行本体全宽出血. 行首按左与行尾按右都吞掉 (两头都没有目标:
 * 放出去就交给系统按屏幕位置找, 可能跳进同一窗口里别的原生行越过行首的卡). 上下键不管, 交给页面 (页面在 Compose 里用按键预览接住).
 *
 * @param cardWidth 给了就用这个卡宽 (行比海报墙窄时, 如预览弹窗), 一屏放得下几张是自然结果, 行尾留白至少 [endPadding]、按整数张补齐;
 *   null = 按 tvPosterWallColumns 铺满.
 * @param onBind 第几张被绑定 (分页的访问提示).
 * @param holdFocusLookOnClick 点卡 (导航出去) 时按住这张的聚焦态 ([TvNativeCardAdapter.setFocusLookHeld]) 并跨导航记住: 返回时页面重建,
 *   这张排出来的第一帧就是放大的, 焦点送回来时画面不变 (同探索页, 不先按未聚焦画出来再放大一遍). 返回后焦点不回这一行的 (如人物预览弹窗:
 *   点作品先关掉弹窗再跳转) 传 false, 否则这张会一直放大着.
 */
@Composable
fun TvNativePosterStrip(
    cards: List<TvNativeCard?>,
    onClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    startPadding: Dp = 0.dp,
    endPadding: Dp = 0.dp,
    onFocused: (index: Int) -> Unit = {},
    cardWidth: Dp? = null,
    onBind: (index: Int) -> Unit = {},
    holdFocusLookOnClick: Boolean = true,
) {
    val sketch = LocalSketch.current
    val animatedScroll = LocalThemeSettings.current.visualEffects.animatedScroll
    // NSFW 设为模糊时按卡片的条目 id 打码 (见 withNsfw): 调用方只管给卡片填 subjectId
    val nsfw = rememberNsfwPolicy()
    val shownCards = remember(cards, nsfw.snapshot) { cards.withNsfw(nsfw) }
    var focusedIndex by rememberSaveable { mutableIntStateOf(-1) }
    var leftIndex by rememberSaveable { mutableIntStateOf(0) }
    // 点卡导航出去时按住的那张 (见 holdFocusLookOnClick), -1 = 没有; 焦点回到这一行就清掉
    var heldIndex by rememberSaveable { mutableIntStateOf(-1) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnFocused by rememberUpdatedState(onFocused)
    val currentOnBind by rememberUpdatedState(onBind)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val startPx = with(density) { startPadding.roundToPx() }
        val columns: Int
        val resolvedCardWidth: Dp
        val endPx: Int
        if (cardWidth == null) {
            val available = (maxWidth - startPadding - endPadding).coerceAtLeast(0.dp)
            columns = with(density) { tvPosterWallColumns(available) }
            resolvedCardWidth = tvPosterWallCardWidth(available, columns).coerceAtLeast(0.dp)
            endPx = with(density) { endPadding.roundToPx() }
        } else {
            val widthPx = with(density) { maxWidth.roundToPx() }
            val minEndPx = with(density) { endPadding.roundToPx() }
            val stepPx = with(density) { (cardWidth + TV_POSTER_WALL_COLUMN_SPACING).roundToPx() }
            val spacingPx = with(density) { TV_POSTER_WALL_COLUMN_SPACING.roundToPx() }
            columns = ((widthPx - startPx - minEndPx + spacingPx) / stepPx).coerceAtLeast(1)
            resolvedCardWidth = cardWidth
            endPx = (widthPx - startPx - columns * stepPx + spacingPx).coerceAtLeast(minEndPx)
        }
        val style = rememberTvNativeWallStyle(resolvedCardWidth, columns)
        val rowHeight = with(density) { style.cardBlockHeightPx.toDp() }
        val bleedPx = with(density) { TV_NATIVE_STRIP_BLEED.roundToPx() }
        val fadeDistancePx = with(density) { TV_CARD_FADE_DISTANCE.toPx() }
        TvNativeRowHost(
            factory = { context ->
                TvNativeRowView(
                    context, style, sketch, pool = null,
                    startPx = startPx, endPx = endPx, bottomPx = bleedPx, topPx = bleedPx,
                    fadeDistancePx = fadeDistancePx,
                    standalone = true,
                ).also { row ->
                    row.startLeftExits = false
                    // 从行外进来落到上次聚焦那张 (还在屏上时), 头一回进来落到行首那张 —— 不交给 leanback: 它按来处的位置挑最近的那张
                    // (从右上角的按钮往下按会落到第四张)
                    row.entryIndex = { if (focusedIndex >= 0) focusedIndex else row.leftIndex() }
                }
            },
            update = { row ->
                // 跨导航恢复行首与上次聚焦那张: 等数据到了才做 (返回时页面重建, 这一行先建出来、条目后到; 空列表上定行首会落回 0)
                if (row.tag == null && shownCards.isNotEmpty()) {
                    row.tag = TV_NATIVE_STRIP_RESTORED
                    // 离开时按住的那张: 排出来就画成聚焦态, 焦点送回来 (适配器在它拿到焦点时放开) 画面不变
                    if (heldIndex >= 0) row.cards.setFocusLookHeld(row, heldIndex)
                    row.bind(shownCards, { it.toLong() }, leftIndex, columns, focusIndex = focusedIndex)
                }
                row.animatedScroll = animatedScroll
                row.cards.listener = object : TvNativeCardListener {
                    override fun onFocused(index: Int) {
                        heldIndex = -1
                        focusedIndex = index
                        // 按需挪之后的行首
                        leftIndex = tvStripLeftIndex(leftIndex, index, shownCards.size, columns)
                        currentOnFocused(index)
                    }

                    override fun onClick(index: Int) {
                        // 占位卡点了不会导航出去, 不按住
                        if (holdFocusLookOnClick && shownCards.getOrNull(index) != null) {
                            // 焦点交出去之后这张仍画成聚焦态, 返回重建时也按住 (见上)
                            heldIndex = index
                            row.cards.setFocusLookHeld(row, index)
                        }
                        currentOnClick(index)
                    }

                    override fun onLongPress(index: Int, anchor: Rect) = Unit
                }
                row.cards.onBind = { currentOnBind(it) }
                row.cards.submit(shownCards) { it.toLong() }
            },
            height = rowHeight,
            bleedVertical = TV_NATIVE_STRIP_BLEED,
        )
    }
}

/**
 * 演职人员的圆头像横滑行, 单独成行 (详情页角色 / 制作人员): 圆定宽 ([TvNativeMonogramStyle.sizePx]), 从 [startPadding] 排起, 屏上完整
 * 放得下几格是自然结果, 再往后那格在行尾露一截; 行尾留白至少 [endPadding], 按整数格补齐 (按需挪时恰好一格一格地挪). 行为见
 * [TvNativeMonogramRowView]: 左右两头都吞掉, 上下键不管 (交给页面).
 *
 * [items] 为 null = 数据还没到: 放一排占位格 (铺到屏幕右缘), 照样接得住焦点 (落在第一格); 数据到了原地换, 持焦的那格不拆, 焦点留在
 * 这一行. 列表里的 null 是分页还没到的那一格 (画成占位). 进行落点 = 上次聚焦那格 (首次进入是行首那格), 落点与行首跨导航保存, 等真数据到了才恢复.
 *
 * @param onLongPress 确定键按住到阈值 (放大看照片); null = 没有长按, 按住也只算点击.
 * @param repeatMillis 长按左右键时最快多久挪一格 (默认同全局上限, 高于系统连发).
 * @param onBind 第几格被绑定 (分页的访问提示).
 * @param restoreFocus 页面的进页落点会回这一行 (返回本页, 页面重建): 上次聚焦那格排出来就画成聚焦态, 焦点送回来时画面不变,
 *   不先按未聚焦画出来再放大一遍. 点圆头像开的是预览弹窗、页面不离开, 所以不能照海报行那样点击时按住; 只在建行时读.
 */
@Composable
fun TvNativeMonogramStrip(
    items: List<TvNativeMonogram?>?,
    style: TvNativeMonogramStyle,
    onClick: (index: Int) -> Unit,
    onLongPress: ((index: Int) -> Unit)?,
    repeatMillis: Long = TV_NATIVE_HORIZONTAL_REPEAT_MILLIS,
    modifier: Modifier = Modifier,
    startPadding: Dp = 0.dp,
    endPadding: Dp = 0.dp,
    onBind: (index: Int) -> Unit = {},
    restoreFocus: Boolean = false,
) {
    val sketch = LocalSketch.current
    val animatedScroll = LocalThemeSettings.current.visualEffects.animatedScroll
    var focusedIndex by rememberSaveable { mutableIntStateOf(-1) }
    var leftIndex by rememberSaveable { mutableIntStateOf(0) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnBind by rememberUpdatedState(onBind)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val startPx = with(density) { startPadding.roundToPx() }
        val minEndPx = with(density) { endPadding.roundToPx() }
        // 屏上完整放得下几格; 行尾留白按整数格补齐
        val columns = ((widthPx - startPx - minEndPx + style.spacingPx) / style.stepPx).coerceAtLeast(1)
        val endPx = (widthPx - startPx - columns * style.stepPx + style.spacingPx).coerceAtLeast(minEndPx)
        // 占位铺到屏幕右缘 (同 Compose 占位 TvPeopleStripPlaceholder): 最后一格被裁掉一截
        val placeholderCount = (widthPx - startPx).coerceAtLeast(0) / style.stepPx + 1
        val rowHeight = with(density) { style.cellHeightPx.toDp() }
        val bleedPx = with(density) { TV_NATIVE_STRIP_BLEED.roundToPx() }
        TvNativeRowHost(
            factory = { context ->
                TvNativeMonogramRowView(context, style, sketch, startPx = startPx, endPx = endPx, topPx = bleedPx, bottomPx = bleedPx)
                    .also { row ->
                        // 从行外进来落到上次聚焦那格 (还在屏上时), 头一回进来 (及占位时) 落到行首那格 —— 不交给 leanback: 它按来处的位置
                        // 挑最近的那格
                        row.entryIndex = { if (!row.cells.loading && focusedIndex >= 0) focusedIndex else row.leftIndex() }
                    }
            },
            update = { row ->
                if (row.paddingLeft != startPx || row.paddingRight != endPx) row.setPadding(startPx, bleedPx, endPx, bleedPx)
                row.animatedScroll = animatedScroll
                row.repeatMillis = repeatMillis
                row.cells.placeholderCount = placeholderCount
                row.cells.longPressEnabled = onLongPress != null
                row.cells.listener = object : TvNativeCardListener {
                    override fun onFocused(index: Int) {
                        // 占位格上的焦点不记: 跨导航恢复的是真数据里的位置
                        if (row.cells.loading) return
                        focusedIndex = index
                        leftIndex = tvStripLeftIndex(leftIndex, index, row.cells.itemCount, columns)
                    }

                    override fun onClick(index: Int) = currentOnClick(index)

                    override fun onLongPress(index: Int, anchor: Rect) {
                        currentOnLongPress?.invoke(index)
                    }
                }
                row.cells.onBind = { currentOnBind(it) }
                // 跨导航恢复行首与上次聚焦那格: 等真数据到了才做 (返回时页面重建, 这一行先建出来、数据后到)
                if (items != null && row.tag == null) {
                    row.tag = TV_NATIVE_STRIP_RESTORED
                    // 焦点要回这一行: 上次聚焦那格排出来就画成聚焦态 (适配器在它拿到焦点时放开). 落点先到了 (停在占位格上) 就不用了
                    if (restoreFocus && focusedIndex >= 0 && !row.hasFocus()) row.cells.setFocusLookHeld(row, focusedIndex)
                    row.bind(items, leftIndex, columns, focusIndex = focusedIndex)
                } else {
                    row.cells.submit(items)
                }
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
