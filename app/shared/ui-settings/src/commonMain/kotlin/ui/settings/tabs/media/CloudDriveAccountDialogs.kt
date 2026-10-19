/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.intl.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveMediaSource
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveRegistry
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveService
import me.him188.ani.app.domain.mediasource.clouddrive.WebDavSources
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.touch_settings_drive_logged_in
import me.him188.ani.app.ui.lang.touch_settings_drive_login_failed
import me.him188.ani.app.ui.lang.touch_settings_drive_password
import me.him188.ani.app.ui.lang.touch_settings_drive_username
import me.him188.ani.app.ui.lang.touch_settings_drive_username_missing
import me.him188.ani.app.ui.lang.touch_settings_webdav_added
import me.him188.ani.app.ui.lang.touch_settings_webdav_default_name
import me.him188.ani.app.ui.lang.touch_settings_webdav_failed
import me.him188.ani.app.ui.lang.touch_settings_webdav_name
import me.him188.ani.app.ui.lang.touch_settings_webdav_name_description
import me.him188.ani.app.ui.lang.touch_settings_webdav_url
import me.him188.ani.app.ui.lang.touch_settings_webdav_url_description
import me.him188.ani.app.ui.lang.touch_settings_webdav_url_invalid
import me.him188.ani.app.ui.lang.tv_settings_drive_webdav_add
import me.him188.ani.app.ui.settings.tabs.media.source.EditMediaSourceDialog
import me.him188.ani.app.ui.settings.tabs.media.source.EditMediaSourceMode
import me.him188.ani.app.ui.settings.tabs.media.source.EditingMediaSource
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.parameter.MediaSourceParameters
import me.him188.ani.datasources.api.source.parameter.buildMediaSourceParameters
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.coroutines.cancellation.CancellationException

/*
 * 网盘账号的两个表单 (平板模式的触屏设置页用; 电视上在手机控制台里填): 加一台 WebDAV 服务器、用账号密码登录网盘.
 * 样子同 Jellyfin 等数据源的参数表单 (EditMediaSourceDialog): 每项一个输入框, 名字当标题、说明写在下面.
 */

/**
 * 加一台 WebDAV 服务器 (NAS): 名字、地址、账号密码. 添加时用填的账号密码列一次根目录核对 (同控制台);
 * 地址不对、连不上或账号密码不对时不留下数据源, 提示原因, 表单不关. 媒体文件夹不选时按整个服务器找.
 */
@Composable
fun WebDavServerAddDialog(onDismissRequest: () -> Unit) {
    val toaster = LocalToaster.current
    val title = stringResource(Lang.tv_settings_drive_webdav_add)
    val nameField = stringResource(Lang.touch_settings_webdav_name)
    val nameDescription = stringResource(Lang.touch_settings_webdav_name_description)
    val urlField = stringResource(Lang.touch_settings_webdav_url)
    val urlDescription = stringResource(Lang.touch_settings_webdav_url_description)
    val usernameField = stringResource(Lang.touch_settings_drive_username)
    val passwordField = stringResource(Lang.touch_settings_drive_password)
    val fields = remember(nameField, urlField, usernameField, passwordField) {
        buildMediaSourceParameters {
            string(nameField, description = nameDescription)
            string(urlField, description = urlDescription, placeholder = "http://192.168.1.10:5005")
            string(usernameField)
            string(passwordField)
        }
    }
    CloudDriveFormDialog(
        title = title,
        fields = fields,
        mode = EditMediaSourceMode.Add(CloudDriveMediaSource.FactoryId),
        onConfirm = { values ->
            addWebDavServer(
                name = values[nameField].orEmpty(),
                url = values[urlField].orEmpty(),
                username = values[usernameField].orEmpty(),
                password = values[passwordField].orEmpty(),
                toast = { toaster.toast(it) },
            )
        },
        onDismissRequest = onDismissRequest,
    )
}

/** 用账号密码登录网盘 [service] (如 WebDAV 服务器); 登录失败时提示原因, 表单不关. */
@Composable
fun CloudDrivePasswordLoginDialog(service: CloudDriveService, onDismissRequest: () -> Unit) {
    val toaster = LocalToaster.current
    val title = service.protocol.displayName(Locale.current.toLanguageTag())
    val usernameField = stringResource(Lang.touch_settings_drive_username)
    val passwordField = stringResource(Lang.touch_settings_drive_password)
    val fields = remember(usernameField, passwordField) {
        buildMediaSourceParameters {
            string(usernameField)
            string(passwordField)
        }
    }
    CloudDriveFormDialog(
        title = title,
        fields = fields,
        mode = EditMediaSourceMode.Edit(service.driveId),
        onConfirm = { values ->
            val username = values[usernameField].orEmpty().trim()
            if (username.isEmpty()) {
                toaster.toast(getString(Lang.touch_settings_drive_username_missing))
                return@CloudDriveFormDialog false
            }
            try {
                service.loginWithPassword(username, values[passwordField].orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toaster.toast(getString(Lang.touch_settings_drive_login_failed, e.message ?: e::class.simpleName.orEmpty()))
                return@CloudDriveFormDialog false
            }
            toaster.toast(getString(Lang.touch_settings_drive_logged_in, title))
            true
        },
        onDismissRequest = onDismissRequest,
    )
}

/** 参数表单: [fields] 的名字当输入框标题; 点确认时按名字取值交给 [onConfirm] (在后台跑), 它返回 true 才关. */
@Composable
private fun CloudDriveFormDialog(
    title: String,
    fields: MediaSourceParameters,
    mode: EditMediaSourceMode,
    onConfirm: suspend (values: Map<String, String>) -> Boolean,
    onDismissRequest: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val state = remember(fields, title, mode) {
        EditingMediaSource(
            editingMediaSourceId = "",
            factoryId = CloudDriveMediaSource.FactoryId,
            info = MediaSourceInfo(displayName = title),
            parameters = fields,
            persistedArguments = emptyFlow(),
            editMediaSourceMode = mode,
            onSave = { editing ->
                val values = editing.arguments.associate { it.name to it.toPersisted().orEmpty() }
                if (currentOnConfirm(values)) withContext(Dispatchers.Main) { currentOnDismiss() }
            },
            parentCoroutineContext = scope.coroutineContext,
        )
    }
    DisposableEffect(state) { onDispose { state.close() } }
    EditMediaSourceDialog(state, onDismissRequest)
}

private suspend fun addWebDavServer(name: String, url: String, username: String, password: String, toast: (String) -> Unit): Boolean {
    if (username.isBlank()) {
        toast(getString(Lang.touch_settings_drive_username_missing))
        return false
    }
    val protocol = try {
        WebDavSources.newProtocol(name.trim().ifEmpty { getString(Lang.touch_settings_webdav_default_name) }, url)
    } catch (_: IllegalArgumentException) {
        toast(getString(Lang.touch_settings_webdav_url_invalid))
        return false
    }
    val manager = GlobalKoin.get<MediaSourceManager>()
    try {
        WebDavSources.add(manager, protocol)
        try {
            GlobalKoin.get<CloudDriveRegistry>().serviceFor(protocol).loginWithPassword(username.trim(), password)
        } catch (e: Throwable) {
            withContext(NonCancellable) { WebDavSources.remove(manager, protocol.id) }
            throw e
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        toast(getString(Lang.touch_settings_webdav_failed, e.message ?: e::class.simpleName.orEmpty()))
        return false
    }
    toast(getString(Lang.touch_settings_webdav_added, protocol.name))
    return true
}
