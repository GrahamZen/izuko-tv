/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import androidx.compose.ui.text.intl.Locale
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveService
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_settings_drive_not_logged_in
import me.him188.ani.app.ui.lang.webdav_manage_row_description
import me.him188.ani.app.ui.settings.tabs.media.WebDavManageDialog

/**
 * 触屏版设置「网盘」里自己加的 WebDAV 服务器: 每台一行 (登录的账号), 点一下打开「管理 WebDAV」([WebDavManageDialog]:
 * 改名与地址、媒体文件夹、往下找几层、登录 / 退出、删除). 电视上这几项在 Web 控制台里管, 不列。
 */
internal fun TvSettingsItemsBuilder.touchWebDavServers() {
    val drives = TvSourceSettingKeys.drives
    val touchPage = TvSettingKeys.touchPage
    entries(
        listOf(drives, touchPage),
        entries = { v ->
            if (v[touchPage] != true) emptyList() else v[drives].orEmpty().filter { it.service.isManagedWebDav() }
        },
        key = { it.service.driveId },
        title = { tvText(it.service.protocol.displayName(Locale.current.toLanguageTag())) },
        value = { _, drive ->
            if (drive.account.isLoggedIn) {
                tvText(drive.account.nickname.ifBlank { drive.account.username.ifBlank { "✓" } })
            } else {
                tvText(Lang.tv_settings_drive_not_logged_in)
            }
        },
        description = { _, _ -> tvText(Lang.webdav_manage_row_description) },
    ) { drive ->
        listOf(
            TvSettingsEdit.Run { env ->
                env.show(TvSettingsOverlay.Dialog { dismiss -> WebDavManageDialog(drive.service.driveId, onDismissRequest = dismiss) })
            },
        )
    }
}

/** 自己加的 WebDAV 服务器 (订阅带来的不在这里改). */
internal fun CloudDriveService.isManagedWebDav(): Boolean =
    protocol.isWebDav && protocol.isUserAdded
