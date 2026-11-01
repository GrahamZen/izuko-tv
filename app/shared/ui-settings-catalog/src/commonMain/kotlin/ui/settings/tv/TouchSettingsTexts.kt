/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_player_up_next_tip_description
import me.him188.ani.app.ui.lang.touch_settings_drive_logout_hint
import me.him188.ani.app.ui.lang.touch_settings_drive_webdav_add_description
import me.him188.ani.app.ui.lang.touch_settings_drive_webdav_login_hint
import me.him188.ani.app.ui.lang.touch_settings_legacy_hint
import me.him188.ani.app.ui.lang.touch_settings_link_description
import me.him188.ani.app.ui.lang.touch_settings_log_send_description
import me.him188.ani.app.ui.lang.touch_settings_perf_diag_dialog_hint
import me.him188.ani.app.ui.lang.touch_settings_sorter_hint
import me.him188.ani.app.ui.lang.touch_settings_up_next_tip_description
import me.him188.ani.app.ui.lang.touch_settings_update_download_failed
import me.him188.ani.app.ui.lang.touch_settings_update_downloaded
import me.him188.ani.app.ui.lang.touch_settings_update_new_hint
import me.him188.ani.app.ui.lang.tv_perf_diag_dialog_hint
import me.him188.ani.app.ui.lang.tv_settings_drive_logout_hint
import me.him188.ani.app.ui.lang.tv_settings_drive_webdav_add_description
import me.him188.ani.app.ui.lang.tv_settings_drive_webdav_login_hint
import me.him188.ani.app.ui.lang.tv_settings_legacy_hint
import me.him188.ani.app.ui.lang.tv_settings_link_description
import me.him188.ani.app.ui.lang.tv_settings_log_send_description
import me.him188.ani.app.ui.lang.tv_settings_sorter_hint
import me.him188.ani.app.ui.lang.tv_settings_update_download_failed
import me.him188.ani.app.ui.lang.tv_settings_update_downloaded
import me.him188.ani.app.ui.lang.tv_settings_update_new_hint
import org.jetbrains.compose.resources.StringResource

/**
 * 设置清单里说「按确定」「扫右边的码」「在电视上」、在触屏设备上会误导的句子, 换成触屏的说法 (读文案时替换, 清单不动).
 * 触屏页没有右边的二维码, 要打字的项就地改 (原来设置页的那一块或表单, 见 TouchSettingsPage); 网盘 Cookie 仍指向 Web 控制台.
 */
internal val TouchSettingsTextOverrides: Map<StringResource, StringResource> = mapOf(
    Lang.tv_settings_link_description to Lang.touch_settings_link_description,
    Lang.tv_settings_sorter_hint to Lang.touch_settings_sorter_hint,
    Lang.settings_player_up_next_tip_description to Lang.touch_settings_up_next_tip_description,
    Lang.tv_perf_diag_dialog_hint to Lang.touch_settings_perf_diag_dialog_hint,
    Lang.tv_settings_legacy_hint to Lang.touch_settings_legacy_hint,
    Lang.tv_settings_drive_logout_hint to Lang.touch_settings_drive_logout_hint,
    Lang.tv_settings_drive_webdav_add_description to Lang.touch_settings_drive_webdav_add_description,
    Lang.tv_settings_drive_webdav_login_hint to Lang.touch_settings_drive_webdav_login_hint,
    Lang.tv_settings_update_downloaded to Lang.touch_settings_update_downloaded,
    Lang.tv_settings_update_download_failed to Lang.touch_settings_update_download_failed,
    Lang.tv_settings_update_new_hint to Lang.touch_settings_update_new_hint,
    Lang.tv_settings_log_send_description to Lang.touch_settings_log_send_description,
)
