/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/*
 * 对话框里的按钮: M3 [TextButton] / [Button] / [OutlinedButton] / [FilledTonalButton] 的替身, 参数与它们一一对应.
 *
 * 在焦点导航形态的弹窗里 (弹窗容器用 [ProvidePopupControlStyle] 打开) 一律画成弹窗动作按钮 ([AniFocusActionButton]):
 * 常态中性、聚焦才变色. M3 的文字按钮聚焦只有一层淡态层, 实底按钮又一直是主题色, 电视上都看不出焦点停在哪一颗.
 * 弹窗之外 (页面上的按钮) 与手机 / 桌面就是 M3 原样, 所以整个文件替换过来也只会改变弹窗里的按钮.
 *
 * 画成动作按钮时, [ButtonColors] 只看是不是错误色 (「删除」这类危险操作的红色): 是就当按钮的字色. 其他自定配色一律不管 ——
 * 比如按焦点换色的配色, 常态底色是灰, 拿它当字色就是灰字压灰底; 动作按钮自己有常态 / 聚焦两态.
 * 形状、阴影、描边、内边距也都用动作按钮自己的.
 */

/** 见文件开头; 由 [ProvidePopupControlStyle] 打开. */
internal val LocalDialogButtonsAsActionButtons = staticCompositionLocalOf { false }

/** M3 [TextButton] 的替身, 见文件开头. */
@Composable
fun AniTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalDialogButtonsAsActionButtons.current) {
        AniFocusActionButton(onClick, modifier, enabled = enabled, accentColor = colors.dialogAccent(), content = content)
        return
    }
    TextButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
}

/** M3 [Button] 的替身, 见文件开头. */
@Composable
fun AniButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalDialogButtonsAsActionButtons.current) {
        AniFocusActionButton(onClick, modifier, enabled = enabled, accentColor = colors.dialogAccent(), content = content)
        return
    }
    Button(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
}

/** M3 [OutlinedButton] 的替身, 见文件开头. */
@Composable
fun AniOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalDialogButtonsAsActionButtons.current) {
        AniFocusActionButton(onClick, modifier, enabled = enabled, accentColor = colors.dialogAccent(), content = content)
        return
    }
    OutlinedButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
}

/** M3 [FilledTonalButton] 的替身, 见文件开头. */
@Composable
fun AniFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.filledTonalShape,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalDialogButtonsAsActionButtons.current) {
        AniFocusActionButton(onClick, modifier, enabled = enabled, accentColor = colors.dialogAccent(), content = content)
        return
    }
    FilledTonalButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
}

/** 画成动作按钮时的字色强调: 只认错误色, 见文件开头. */
@Composable
@ReadOnlyComposable
private fun ButtonColors.dialogAccent(): Color {
    val error = MaterialTheme.colorScheme.error
    return if (contentColor == error || containerColor == error) error else Color.Unspecified
}
