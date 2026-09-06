package me.him188.ani.app.ui.settings.framework.components

import androidx.compose.foundation.clickable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.foundation.widgets.AniDropdownMenu
import me.him188.ani.app.ui.foundation.widgets.AniDropdownMenuItem

/**
 * 下来菜单, 用于显示简单的选择. 例如选择主题是深色还是浅色.
 */
@SettingsDsl
@Composable
fun <T> SettingsScope.DropdownItem(
    selected: () -> T,
    values: () -> List<T>,
    itemText: @Composable (T) -> Unit,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    itemIcon: @Composable ((T) -> Unit)? = null,
    description: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable () -> Unit,
    exposedItemText: @Composable (T) -> Unit = itemText,
    enabled: Boolean = true,
) {
    var showDropdown by rememberSaveable { mutableStateOf(false) }

    val selectedState by remember {
        derivedStateOf { selected() }
    }
    TextItem(
        modifier = modifier.clickable(onClick = { showDropdown = true }),
        description = description,
        icon = icon,
        action = {
            TextButton(onClick = { showDropdown = true }, enabled = enabled) {
                exposedItemText(selectedState)
            }
            AniDropdownMenu(
                expanded = showDropdown,
                onDismissRequest = { showDropdown = false },
            ) {
                values().forEach { value ->
                    // 当前值画成选中项 (TV 上选中色 + ✓, 其余平台主题色文字, 见 AniDropdownMenuItem)
                    AniDropdownMenuItem(
                        text = { itemText(value) },
                        leadingIcon = if (itemIcon != null) {
                            {
                                itemIcon(value)
                            }
                        } else null,
                        onClick = {
                            onSelect(value)
                            showDropdown = false
                        },
                        selected = value == selectedState,
                    )
                }
            }
        },
        title = title,
    )
}
