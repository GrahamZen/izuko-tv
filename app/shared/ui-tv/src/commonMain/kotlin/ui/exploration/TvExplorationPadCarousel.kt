/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHeroSource
import me.him188.ani.app.ui.foundation.tv.tvTouchFocusOnTap
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_CAROUSEL_DOT
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_CAROUSEL_DOTS_BOTTOM
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_CAROUSEL_DOT_GAP
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_CAROUSEL_DOT_INACTIVE_ALPHA
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_CAROUSEL_DOT_SELECTED
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_CAROUSEL_TEXT_BOTTOM
import me.him188.ani.app.ui.foundation.tv.wall.TvPadHeroLayer
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.exploration_schedule
import me.him188.ani.app.ui.lang.exploration_tv_watch_now
import org.jetbrains.compose.resources.stringResource

/*
 * 探索页热门轮播的平板版画法 (触屏上用, 画法见 TvPadHeroLayer): 文字、按钮 (横排)、圆点贴在轮播区左下.
 * 触屏上只有「新番时间表」一颗按钮 (点轮播画面就是进这一部的详情页, 见 TvExplorationComposeWall); 平板接着键盘 / 遥控器按键时两颗都在,
 * 焦点照电视版轮播走.
 */

/**
 * 轮播区的画面与文字 (压在卡片列表底下): [source] 是轮播这一部; 文字块下面留出按钮那一排 (按钮另起一层, 见 [TvPadCarouselButtons]).
 * 换一部的过渡固定一种 (同自动翻页), 手势不拖着画面走. [backdropShift] 见 [TvPadHeroLayer].
 */
@Composable
internal fun TvPadCarouselLayer(
    source: TvNativeHeroSource?,
    count: Int,
    selected: Int,
    textStart: Dp,
    pageWidth: Dp,
    modifier: Modifier = Modifier,
    backdropShift: () -> Float = { 0f },
) {
    TvPadHeroLayer(
        source,
        textStart = textStart,
        textBottom = TV_PAD_CAROUSEL_TEXT_BOTTOM,
        pageWidth = pageWidth,
        modifier = modifier,
        backdropShift = backdropShift,
    ) {
        if (count > 1) {
            TvPadCarouselDots(
                count,
                selected,
                Modifier.align(Alignment.BottomStart).padding(start = textStart, bottom = TV_PAD_CAROUSEL_DOTS_BOTTOM),
            )
        }
    }
}

/**
 * 轮播的按钮 (横排, 压在卡片列表上面): 主按钮「立即观看」是正文色实底 ([watchNow] 为 false 时不放, 触屏上点画面就是它), 次按钮「新番时间表」
 * 是正文色淡底. [touchFocusOnPress] = 触屏上按下即聚焦 (见 tvTouchFocusOnTap); 为 false 时点一下只是点击.
 */
@Composable
internal fun TvPadCarouselButtons(
    primaryRequester: FocusRequester,
    scheduleRequester: FocusRequester,
    onClick: (button: Int) -> Unit,
    onFocused: (button: Int) -> Unit,
    modifier: Modifier = Modifier,
    watchNow: Boolean = true,
    touchFocusOnPress: Boolean = true,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (watchNow) {
            Button(
                onClick = { onClick(0) },
                modifier = Modifier.focusRequester(primaryRequester)
                    .onFocusChanged { if (it.isFocused) onFocused(0) }
                    .then(if (touchFocusOnPress) Modifier.tvTouchFocusOnTap() else Modifier),
                colors = ButtonDefaults.buttonColors(containerColor = contentColor, contentColor = MaterialTheme.colorScheme.surface),
            ) {
                Text(stringResource(Lang.exploration_tv_watch_now), fontWeight = FontWeight.SemiBold)
            }
        }
        FilledTonalButton(
            onClick = { onClick(1) },
            modifier = Modifier.focusRequester(scheduleRequester)
                .onFocusChanged { if (it.isFocused) onFocused(1) }
                .then(if (touchFocusOnPress) Modifier.tvTouchFocusOnTap() else Modifier),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = contentColor.copy(alpha = TV_PAD_CAROUSEL_SECONDARY_BUTTON_ALPHA),
                contentColor = contentColor,
            ),
        ) {
            Text(stringResource(Lang.exploration_schedule), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TvPadCarouselDots(count: Int, selected: Int, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(TV_PAD_CAROUSEL_DOT_GAP), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            Box(
                Modifier
                    .size(width = if (index == selected) TV_PAD_CAROUSEL_DOT_SELECTED else TV_PAD_CAROUSEL_DOT, height = TV_PAD_CAROUSEL_DOT)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (index == selected) 1f else TV_PAD_CAROUSEL_DOT_INACTIVE_ALPHA)),
            )
        }
    }
}

internal const val TV_PAD_CAROUSEL_SECONDARY_BUTTON_ALPHA = 0.16f
