/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior

/**
 * **横滑卡的示焦 modifier 链** —— 描边 + 圆角裁剪 + 按形态换示焦手段 + 文字避让, 一条链一次挂完.
 *
 * ## 为什么收编成一个原语
 *
 * 关联作品卡、出演作品卡、人物头像卡三处逐字复制过同一条链 (连注释都一样), 而复制出来的三份
 * 已经开始不一致: 其中关联作品卡**漏掉了形态门控**, 在手机/桌面上照样加描边和文字内缩 ——
 * 桌面端于是同时有涟漪的焦点状态层和一圈描边 (两层示焦), 手机端压根不会聚焦, 那 4dp 内缩纯粹
 * 白白把标题挤窄. 门控写在这里之后, 任何新卡片都不可能再漏.
 *
 * ## 各形态的行为
 *
 *  - **焦点驱动 (TV)**: 画 [tvFocusRing] 描边, 并**关掉 indication 的焦点状态层** —— 那是一块
 *    半透明高亮, 压在封面图上几乎分辨不出是哪张卡拿了焦点 (遥控器每按一下都要找焦点在哪);
 *    两者叠加则是两层特效. 按压反馈由 indication 负责的部分不受影响.
 *  - **触摸/指针**: 原样保留涟漪, 不画描边, 不留文字内缩.
 *
 * ## 用法
 *
 * 挂在卡片最外层容器上, **定尺寸之后**:
 * ```
 * Column(modifier.width(96.dp).tvFocusableCard(onClick = onClick)) { 封面; 标题 }
 * ```
 * 卡内文字的横向内缩用 [tvCardTextInset] 取值 (与本链底部留的那一份同源).
 *
 * @param cornerRadius 卡片圆角; 描边与裁剪共用, 默认 [TV_CARD_CORNER].
 * @param interactionSource 调用方需要读聚焦态 (如聚焦才跑马灯) 时传进来共用同一个, 免得两处
 *   各建一个导致状态对不上.
 * @param insetBottomForText 卡片末行文字贴着卡底时留出避让描边的下边距. 封面满宽、文字在下方
 *   的卡都要 (即默认); 纯封面无文字的卡传 false.
 */
@Composable
fun Modifier.tvFocusableCard(
    onClick: () -> Unit,
    cornerRadius: Dp = TV_CARD_CORNER,
    interactionSource: MutableInteractionSource? = null,
    insetBottomForText: Boolean = true,
    enabled: Boolean = true,
): Modifier {
    val focusDriven = LocalAniUiBehavior.current.focusDrivenNavigation
    val source = interactionSource ?: remember { MutableInteractionSource() }
    return this
        // 描边必须在 clip 之前, 否则被卡片自己的圆角裁掉一半
        .tvFocusRing(cornerRadius, enabled = focusDriven)
        .clip(RoundedCornerShape(cornerRadius))
        .clickable(
            interactionSource = source,
            indication = if (focusDriven) null else LocalIndication.current,
            enabled = enabled,
            onClick = onClick,
        )
        .padding(bottom = if (focusDriven && insetBottomForText) TvFocusRing.TextInset else 0.dp)
}

/**
 * 卡内文字的横向内缩量: 焦点驱动形态下避让描边, 其余形态为 0.
 *
 * 与 [tvFocusableCard] 底部留的那一份同源 —— 两处必须同时有或同时没有, 否则聚焦时描边压在
 * 左右两端的字上 (只留了底部) 或标题莫名比封面窄 (只留了横向).
 */
@Composable
@ReadOnlyComposable
fun tvCardTextInset(): Dp =
    if (LocalAniUiBehavior.current.focusDrivenNavigation) TvFocusRing.TextInset else 0.dp

/**
 * 横滑卡的默认圆角.
 *
 * 与 `MaterialTheme.shapes.small` 同值 —— 三处卡片原本写的就是 `MaterialTheme.shapes.small`,
 * 这里落成常量是因为 [tvFocusRing] 要的是**半径**而不是 `Shape` (描边几何要按半径算内缩, 见
 * 那里的说明), 从 Shape 里取不出来.
 */
val TV_CARD_CORNER = 8.dp
