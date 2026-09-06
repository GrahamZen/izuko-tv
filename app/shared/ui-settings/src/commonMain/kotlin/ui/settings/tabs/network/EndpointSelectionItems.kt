/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.network

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import me.him188.ani.app.data.models.preference.EndpointSelection
import me.him188.ani.app.data.models.preference.EndpointSelectionMode
import me.him188.ani.app.data.models.preference.EndpointUrls
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_network_endpoint_auto
import me.him188.ani.app.ui.lang.settings_network_endpoint_auto_description
import me.him188.ani.app.ui.lang.settings_network_endpoint_custom
import me.him188.ani.app.ui.lang.settings_network_endpoint_custom_url
import me.him188.ani.app.ui.lang.settings_network_endpoint_custom_url_description
import me.him188.ani.app.ui.lang.settings_network_endpoint_custom_url_invalid
import me.him188.ani.app.ui.lang.settings_network_endpoint_fixed_description
import me.him188.ani.app.ui.settings.framework.SettingsState
import me.him188.ani.app.ui.settings.framework.components.DropdownItem
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import me.him188.ani.app.ui.settings.framework.components.TextFieldItem
import org.jetbrains.compose.resources.stringResource

/**
 * 一项可换入口的服务 (见 `AlternativeEndpoints`) 用哪个入口: 自动 / 清单里的某一个 / 自己填的. 摆进各服务自己的设置组里.
 *
 * @param candidates 仓库维护的候选清单 (每天拉一次, 拉不到用内置的)
 * @param customExample 自定义地址的示例, 显示在输入框里与说明中
 * @param customTemplateExample 模板写法的示例 (见 `EndpointUrls`), 显示在说明中
 */
@Composable
internal fun SettingsScope.EndpointSelectionItems(
    state: SettingsState<EndpointSelection>,
    candidates: List<String>,
    title: String,
    customExample: String,
    customTemplateExample: String,
) {
    val selection by state
    val options = remember(candidates, selection) {
        buildList {
            add(EndpointOption.Auto)
            candidates.forEach { add(EndpointOption.Fixed(it)) }
            // 选定的那个后来被清单去掉了: 照样列出来, 那是用户选的, 照用
            if (selection.mode == EndpointSelectionMode.FIXED && selection.fixedBaseUrl.isNotEmpty() &&
                selection.fixedBaseUrl !in candidates
            ) {
                add(EndpointOption.Fixed(selection.fixedBaseUrl))
            }
            add(EndpointOption.Custom)
        }
    }
    // 自定义还没填好时实际按自动走 (见 EndpointSelection.baseUrls), 说明照实写
    val effectivelyAuto = when (selection.mode) {
        EndpointSelectionMode.AUTO -> true
        EndpointSelectionMode.FIXED -> false
        EndpointSelectionMode.CUSTOM -> EndpointUrls.normalizeBaseUrl(selection.customBaseUrl) == null
    }
    DropdownItem(
        selected = { selection.option() },
        values = { options },
        itemText = { Text(it.label()) },
        onSelect = { state.update(it.applyTo(selection)) },
        title = { Text(title) },
        description = {
            Text(
                if (effectivelyAuto) {
                    stringResource(
                        Lang.settings_network_endpoint_auto_description,
                        candidates.joinToString("、") { EndpointUrls.displayName(it) },
                    )
                } else {
                    stringResource(Lang.settings_network_endpoint_fixed_description)
                },
            )
        },
        enabled = !state.isLoading,
    )

    if (selection.mode == EndpointSelectionMode.CUSTOM) {
        val description = stringResource(Lang.settings_network_endpoint_custom_url_description, customExample, customTemplateExample)
        val invalid = stringResource(Lang.settings_network_endpoint_custom_url_invalid)
        TextFieldItem(
            value = selection.customBaseUrl,
            title = { Text(stringResource(Lang.settings_network_endpoint_custom_url)) },
            description = { Text(description) },
            placeholder = { Text(customExample) },
            // 填不成样子时红一下: 认不出的地址按自动处理, 用户看不出是自己填错了
            isErrorProvider = { it.isNotBlank() && EndpointUrls.normalizeBaseUrl(it) == null },
            textFieldDescription = {
                Text(if (it.isNotBlank() && EndpointUrls.normalizeBaseUrl(it) == null) invalid else description)
            },
            onValueChangeCompleted = { state.update(selection.copy(customBaseUrl = it)) },
        )
    }
}

private sealed interface EndpointOption {
    data object Auto : EndpointOption
    data class Fixed(val baseUrl: String) : EndpointOption
    data object Custom : EndpointOption
}

private fun EndpointSelection.option(): EndpointOption = when (mode) {
    EndpointSelectionMode.AUTO -> EndpointOption.Auto
    EndpointSelectionMode.FIXED -> EndpointOption.Fixed(fixedBaseUrl)
    EndpointSelectionMode.CUSTOM -> EndpointOption.Custom
}

private fun EndpointOption.applyTo(selection: EndpointSelection): EndpointSelection = when (this) {
    EndpointOption.Auto -> selection.copy(mode = EndpointSelectionMode.AUTO)
    is EndpointOption.Fixed -> selection.copy(mode = EndpointSelectionMode.FIXED, fixedBaseUrl = baseUrl)
    EndpointOption.Custom -> selection.copy(mode = EndpointSelectionMode.CUSTOM)
}

@Composable
private fun EndpointOption.label(): String = when (this) {
    EndpointOption.Auto -> stringResource(Lang.settings_network_endpoint_auto)
    is EndpointOption.Fixed -> EndpointUrls.displayName(baseUrl)
    EndpointOption.Custom -> stringResource(Lang.settings_network_endpoint_custom)
}
