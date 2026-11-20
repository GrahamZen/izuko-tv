/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_episode_close
import me.him188.ani.app.ui.lang.tv_rail_back
import org.jetbrains.compose.resources.stringResource

/*
 * 平板模式 (触屏) 下弹窗标题行的两颗圆钮, 照常见触屏应用的弹出面板: 进了下一层时左上角「返回」退一层, 右上角「关闭」关掉整个弹窗.
 * 遥控器上不画 (返回键就是这两件事), 由调用方按 LocalTvTouchInputEnabled 决定画不画.
 */

/** 弹窗标题行左边的「返回」: 退回弹窗里的上一层. */
@Composable
fun AniPanelBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    AniPanelCircleButton(
        Icons.AutoMirrored.Rounded.ArrowBack,
        stringResource(Lang.tv_rail_back),
        onClick,
        modifier.testTag(ANI_PANEL_BACK_BUTTON_TAG),
    )
}

/** 弹窗标题行右边的「关闭」: 关掉整个弹窗. */
@Composable
fun AniPanelCloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    AniPanelCircleButton(
        Icons.Rounded.Close,
        stringResource(Lang.subject_episode_close),
        onClick,
        modifier.testTag(ANI_PANEL_CLOSE_BUTTON_TAG),
    )
}

@Composable
private fun AniPanelCircleButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier.size(ANI_PANEL_NAV_BUTTON_SIZE),
        shape = CircleShape,
        color = aniGlassButtonColor(),
        border = aniGlassEdge(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, Modifier.size(20.dp))
        }
    }
}

/**
 * 平板模式下弹窗的窗外: 铺满弹窗窗口, 点面板以外的地方调 [onTap] (关掉整个弹窗), 面板 ([panel]) 居中放在上面.
 * 弹窗自己的「点外面就关」要关掉 (DialogProperties.dismissOnClickOutside = false): 那一路与返回键共用 onDismissRequest,
 * 分不出是点了外面还是按了返回, 系统返回手势仍要退一层.
 */
@Composable
fun AniTouchDialogOutside(onTap: () -> Unit, panel: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.matchParentSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            ),
        )
        panel()
    }
}

/** 返回 / 关闭钮的直径. */
val ANI_PANEL_NAV_BUTTON_SIZE = 40.dp

const val ANI_PANEL_BACK_BUTTON_TAG = "ani-panel-back"
const val ANI_PANEL_CLOSE_BUTTON_TAG = "ani-panel-close"
