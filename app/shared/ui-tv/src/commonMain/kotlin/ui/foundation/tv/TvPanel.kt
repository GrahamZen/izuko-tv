/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.widgets.centeredPanelColor

/**
 * **窗口内居中面板**: 压一层 scrim, 中央摆一块按屏比定尺寸的面板, 并把焦点锁在面板内.
 *
 * 与 `AniCenteredPanelDialog` 的区别是它**不开系统弹窗**, 就画在当前窗口里 —— 播放器上那些
 * 盖在视频之上的界面 (回复弹窗、表情选择器) 都要这个: 开真弹窗会丢掉播放器的按键路由.
 *
 * ## 焦点必须锁住
 *
 * `onExit = { cancelFocusChange() }` 挂在**焦点组**上. 不锁的话面板边缘的方向键会滑到底下
 * 仍在场的内容上 (胶囊行、进度条、评论卡), 而面板还盖着 —— 用户看不见焦点去了哪, 返回键又
 * 只会关面板, 表现就是遥控器失灵. 这层曾经在两处各写一遍, 漏一处就是一个卡死.
 *
 * @param widthFraction 面板宽占屏比 (TV 上 dp 视口约 960x540).
 * @param heightFraction 高占屏比; **null = 按内容收高** —— 内容少时撑满屏高会让按钮吊在
 *   一大片空白下面.
 * @param overlay 盖在本面板**之上**的内容 (与面板同在这一个全屏 Box 里, 因此可以
 *   `matchParentSize()` 再铺一层自己的 scrim). 回复弹窗上的表情选择器就是这么叠的.
 */
@Composable
internal fun TvInWindowPanel(
    widthFraction: Float,
    modifier: Modifier = Modifier,
    heightFraction: Float? = null,
    contentPadding: Dp = TV_PANEL_CONTENT_PADDING,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(TV_PANEL_SCRIM_COLOR)
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            Modifier
                .fillMaxWidth(widthFraction)
                .then(if (heightFraction != null) Modifier.fillMaxHeight(heightFraction) else Modifier),
            shape = RoundedCornerShape(TV_PANEL_CORNER),
            // 与其他弹窗同一个底色与内容色 (见 centeredPanelColor / AniCenteredPanelDialog)
            color = centeredPanelColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (heightFraction != null) Modifier.fillMaxHeight() else Modifier)
                    .padding(contentPadding),
                content = content,
            )
        }
        overlay()
    }
}

/**
 * 面板背后的压暗层.
 *
 * 0.38 是从 0.55 调下来的 —— 播放器上盖面板时, 太重的 scrim 会把正在播的画面压成一块死黑,
 * 看不出面板是浮在视频上. 当时两处各写一份, 只改了其中一处, 于是同一套界面里回复弹窗与表情
 * 选择器压暗程度不一样; 现在只有这一个值.
 */
private val TV_PANEL_SCRIM_COLOR = Color.Black.copy(alpha = 0.38f)

/** 面板圆角. */
private val TV_PANEL_CORNER = 20.dp

/** 面板内容默认内边距. */
private val TV_PANEL_CONTENT_PADDING = 24.dp
