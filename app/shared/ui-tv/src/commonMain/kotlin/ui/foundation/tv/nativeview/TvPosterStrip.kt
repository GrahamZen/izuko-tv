/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
expect fun TvNativePosterStrip(
    cards: List<TvNativeCard?>,
    onClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    startPadding: Dp = 0.dp,
    endPadding: Dp = 0.dp,
    onFocused: (index: Int) -> Unit = {},
    cardWidth: Dp? = null,
    onBind: (index: Int) -> Unit = {},
    holdFocusLookOnClick: Boolean = true,
)
