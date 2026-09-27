package me.him188.ani.app.ui.settings.framework.components

import androidx.compose.foundation.clickable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * 一个 [TextButton] 在最右侧
 *
 * @param headline 左边的一行, 可以写按钮所做事情的进度. 写在这里而不是按钮上, 按钮的宽度就不会跟着进度变.
 */
@SettingsDsl
@Composable
fun SettingsScope.TextButtonItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    headline: @Composable () -> Unit = {},
    title: @Composable () -> Unit,
) {
    Item(
        headlineContent = headline,
        trailingContent = {
            TextButton(
                onClick = onClick,
                enabled = enabled,
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                    title()
                }
            }
        },
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(enabled = enabled, onClick = onClick),
    )
}

/**
 * 一行字作为按钮, 对齐在左边
 */
@SettingsDsl
@Composable
fun SettingsScope.RowButtonItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    title: @Composable () -> Unit,
) {
    TextItem(
        modifier = modifier,
        description = description,
        icon = icon?.let {
            @Composable {
                CompositionLocalProvider(LocalContentColor provides color) {
                    icon()
                }
            }
        },
        action = action,
        onClick = onClick,
        onClickEnabled = enabled,
        title = {
            CompositionLocalProvider(LocalContentColor provides color) {
                title()
            }
        },
    )
}
