/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.navigation.SettingsTab

/**
 * 打开设置页并直接进某一组 (按组的锚点, 见 TvSettingItem.Group.anchor): 如通知里的反馈回复直接进「我的反馈」.
 * 跳过去之前登记一次, 设置页组合起来时取走 ([take]) 并进那一组 (TvSettingsState.openGroup).
 */
object TvSettingsEntryGroup {
    private val pending = MutableStateFlow<String?>(null)
    private val pendingRow = MutableStateFlow<String?>(null)

    fun open(navigator: AniNavigator, tab: SettingsTab, anchor: String) {
        pending.value = anchor
        navigator.navigateSettings(tab)
    }

    internal fun take(): String? = pending.getAndUpdate { null }

    /**
     * 下一次打开设置页时焦点落在锚点为 [anchor] 的那一行上 (分类最外一层的行, 见 TvSettingItem.anchor), 跳过去由调用方做:
     * 如通知里的应用内链接 `ani://settings/about/chat-groups` 落在关于页交流群那一行. 设置页组合起来时取走 ([takeRow]).
     */
    fun focusRow(anchor: String) {
        pendingRow.value = anchor
    }

    internal fun takeRow(): String? = pendingRow.getAndUpdate { null }
}
