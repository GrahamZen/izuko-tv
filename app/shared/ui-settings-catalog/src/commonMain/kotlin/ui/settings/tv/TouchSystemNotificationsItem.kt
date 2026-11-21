/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.platform.SystemNotificationAuthorization
import me.him188.ani.app.platform.SystemNotifier
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_system_notifications
import me.him188.ani.app.ui.lang.settings_system_notifications_denied
import me.him188.ani.app.ui.lang.settings_system_notifications_description

/**
 * 设置「系统通知」(接在「有新通知时弹出提示」后面): 只有有系统通知的平台列 (Koin 里有 [SystemNotifier], 现在只有 iOS).
 * 打开时向系统要权限: 系统不允许 (用户拒绝过) 时不打开, 提示去系统设置里开.
 */
internal fun TvSettingsItemsBuilder.systemNotificationsItem() {
    val theme = TvSettingKeys.theme
    val notifier = TvSettingKey.Platform("systemNotifier") { _ -> systemNotifier() }
    toggleOf(
        listOf(theme, notifier), Lang.settings_system_notifications,
        description = { tvText(Lang.settings_system_notifications_description) },
        visible = { it[notifier] != null },
        read = { it[theme]?.systemNotifications ?: false },
        write = { on ->
            if (!on) {
                listOf(TvSettingsEdit.Update(theme) { it.copy(systemNotifications = false) })
            } else {
                listOf(
                    TvSettingsEdit.Run { env ->
                        val n = systemNotifier() ?: return@Run
                        val granted = when (n.authorization()) {
                            SystemNotificationAuthorization.Granted -> true
                            SystemNotificationAuthorization.NotDetermined -> n.requestAuthorization()
                            SystemNotificationAuthorization.Denied -> false
                        }
                        if (granted) {
                            env.repository.themeSettings.update { copy(systemNotifications = true) }
                        } else {
                            env.toast(getStringOffMain(Lang.settings_system_notifications_denied))
                        }
                    },
                )
            }
        },
    )
}

private fun systemNotifier(): SystemNotifier? = runCatching { GlobalKoin.get<SystemNotifier>() }.getOrNull()
