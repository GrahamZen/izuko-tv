/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * 遥控器形态的设置页, 由 TV 包注入 ([LocalSettingsScreenVariant], 见 TvPageVariants): 整个替换 [SettingsScreen].
 * [vm] 是原来设置页的 ViewModel (状态照用), [legacy] 画原来的设置页并直接打开某一类,
 * [loadOpenSourceLibrariesJsons] 给开源许可列表.
 */
fun interface SettingsScreenVariant {
    @Composable
    fun Content(
        vm: SettingsViewModel,
        initialTab: SettingsTab?,
        legacy: @Composable (SettingsTab) -> Unit,
        loadOpenSourceLibrariesJsons: suspend () -> List<ByteArray>,
        modifier: Modifier,
    )
}

val LocalSettingsScreenVariant = staticCompositionLocalOf<SettingsScreenVariant?> { null }

/** 注入了 [SettingsScreenVariant] 就画它, 返回 true ([SettingsScreen] 据此不再画自己). */
@Composable
internal fun settingsScreenVariantShown(
    vm: SettingsViewModel,
    onNavigateToBangumiOAuth: () -> Unit,
    loadOpenSourceLibrariesJsons: suspend () -> List<ByteArray>,
    modifier: Modifier,
    initialTab: SettingsTab?,
    windowInsets: WindowInsets,
    navigationIcon: @Composable () -> Unit,
): Boolean {
    val variant = LocalSettingsScreenVariant.current ?: return false
    variant.Content(
        vm,
        initialTab,
        legacy = { tab ->
            CompositionLocalProvider(LocalSettingsScreenVariant provides null) {
                SettingsScreen(
                    vm, onNavigateToBangumiOAuth, loadOpenSourceLibrariesJsons, Modifier.fillMaxSize(),
                    initialTab = tab, windowInsets = windowInsets, navigationIcon = navigationIcon,
                )
            }
        },
        loadOpenSourceLibrariesJsons,
        modifier,
    )
    return true
}
