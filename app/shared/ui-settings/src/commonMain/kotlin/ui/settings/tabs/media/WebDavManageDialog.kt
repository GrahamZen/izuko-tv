/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveAuthException
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveProtocol
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveRegistry
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveService
import me.him188.ani.app.domain.mediasource.clouddrive.WebDav
import me.him188.ani.app.domain.mediasource.clouddrive.WebDavSources
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniPanelCloseButton
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_account_logout
import me.him188.ani.app.ui.lang.touch_settings_webdav_name
import me.him188.ani.app.ui.lang.touch_settings_webdav_url
import me.him188.ani.app.ui.lang.touch_settings_webdav_url_invalid
import me.him188.ani.app.ui.lang.tv_settings_delete
import me.him188.ani.app.ui.lang.webdav_manage_account
import me.him188.ani.app.ui.lang.webdav_manage_cancel
import me.him188.ani.app.ui.lang.webdav_manage_delete
import me.him188.ani.app.ui.lang.webdav_manage_delete_confirm
import me.him188.ani.app.ui.lang.webdav_manage_deleted
import me.him188.ani.app.ui.lang.webdav_manage_depth
import me.him188.ani.app.ui.lang.webdav_manage_depth_hint
import me.him188.ani.app.ui.lang.webdav_manage_edit
import me.him188.ani.app.ui.lang.webdav_manage_folder_added
import me.him188.ani.app.ui.lang.webdav_manage_folder_already
import me.him188.ani.app.ui.lang.webdav_manage_folder_choose
import me.him188.ani.app.ui.lang.webdav_manage_folder_pick_title
import me.him188.ani.app.ui.lang.webdav_manage_folder_removed
import me.him188.ani.app.ui.lang.webdav_manage_folder_up
import me.him188.ani.app.ui.lang.webdav_manage_folders
import me.him188.ani.app.ui.lang.webdav_manage_folders_add
import me.him188.ani.app.ui.lang.webdav_manage_folders_empty
import me.him188.ani.app.ui.lang.webdav_manage_folders_remove
import me.him188.ani.app.ui.lang.webdav_manage_logged_out
import me.him188.ani.app.ui.lang.webdav_manage_login
import me.him188.ani.app.ui.lang.webdav_manage_not_found
import me.him188.ani.app.ui.lang.webdav_manage_not_logged_in
import me.him188.ani.app.ui.lang.webdav_manage_save
import me.him188.ani.app.ui.lang.webdav_manage_save_hint
import me.him188.ani.app.ui.lang.webdav_manage_saved
import me.him188.ani.app.ui.lang.webdav_manage_saved_login_failed
import me.him188.ani.app.ui.lang.webdav_manage_title
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

/**
 * 管理自己加的 WebDAV 服务器 (电视上在 Web 控制台的「数据源 › 本地」里管): 改名与地址 (改了地址向服务器核对一次登录)、
 * 媒体文件夹 (添加时一层层点进服务器的文件夹, 见 [DriveBrowserState]; 移出)、往下找几层、登录 / 退出登录、删除这台服务器。
 * 每一项改的是这台服务器的数据源 ([WebDavSources]), 改完网盘列表换成新的那份, 弹窗跟着读。有输入框, 点外面不关。
 */
@Composable
fun WebDavManageDialog(driveId: String, onDismissRequest: () -> Unit) {
    val registry = remember { GlobalKoin.get<CloudDriveRegistry>() }
    val manager = remember { GlobalKoin.get<MediaSourceManager>() }
    val drives by registry.drives.collectAsState()
    val service = drives?.firstOrNull { it.driveId == driveId }
    if (service == null) {
        // 删掉了 (或数据源还没读出来): 没有可管的
        if (drives != null) LaunchedEffect(Unit) { onDismissRequest() }
        return
    }
    // 网盘列表换协议时沿用同一个服务对象 (原地换, 列表不重发): 当前协议由这里记着, 每次改完换成改后的那份
    var protocol by remember(driveId) { mutableStateOf(service.protocol) }
    val account by service.account.collectAsState(null)
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()

    var name by remember(driveId) { mutableStateOf(protocol.name) }
    var url by remember(driveId) { mutableStateOf(WebDav.serverUrl(protocol)?.toString().orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    // 名称与地址平时只显示, 点「修改」才出输入框 (打开弹窗时不弹键盘)
    var editing by remember { mutableStateOf(false) }
    var pickingFolder by remember { mutableStateOf(false) }
    var loggingIn by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    /** 改这台服务器的协议, 等网盘那边换上新的那份 (最多 [UPDATE_WAIT]); 找不到数据源时提示并返回 null. */
    suspend fun update(transform: (CloudDriveProtocol) -> CloudDriveProtocol): CloudDriveService? {
        var expected: CloudDriveProtocol? = null
        if (!WebDavSources.update(manager, driveId) { transform(it).also { new -> expected = new } }) {
            toaster.toast(getStringOffMain(Lang.webdav_manage_not_found))
            return null
        }
        val updated = withTimeoutOrNull(UPDATE_WAIT) {
            while (registry.drives.value?.firstOrNull { it.driveId == driveId }?.protocol != expected) delay(100)
        }
        if (updated == null) logger.warn { "WebDAV $driveId protocol not applied in $UPDATE_WAIT" }
        expected?.let { protocol = it }
        return registry.drives.value?.firstOrNull { it.driveId == driveId }
    }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    AniCenteredPanelDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Lang.webdav_manage_title), Modifier.weight(1f))
                AniPanelCloseButton(onDismissRequest, Modifier.padding(start = 12.dp))
            }
        },
        heightFraction = null,
        maxWidth = DIALOG_MAX_WIDTH,
        dismissOnClickOutside = false,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!editing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(protocol.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            WebDav.serverUrl(protocol)?.toString().orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = {
                            name = protocol.name
                            url = WebDav.serverUrl(protocol)?.toString().orEmpty()
                            editing = true
                        },
                        enabled = !busy,
                    ) { Text(stringResource(Lang.webdav_manage_edit)) }
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    Modifier.width(200.dp),
                    label = { Text(stringResource(Lang.touch_settings_webdav_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    url,
                    { url = it },
                    Modifier.weight(1f),
                    label = { Text(stringResource(Lang.touch_settings_webdav_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
            }
            if (editing) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Lang.webdav_manage_save_hint),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { editing = false }, enabled = !busy) { Text(stringResource(Lang.webdav_manage_cancel)) }
                FilledTonalButton(
                    onClick = {
                        run {
                            val updated = try {
                                update { p ->
                                    val renamed = p.copy(name = name.trim().ifEmpty { p.name })
                                    if (url.isBlank()) renamed else WebDavSources.withServer(renamed, url.trim())
                                }
                            } catch (_: IllegalArgumentException) {
                                toaster.toast(getStringOffMain(Lang.touch_settings_webdav_url_invalid))
                                return@run
                            } ?: return@run
                            // 向服务器核对一次: 地址或账号不对时当场说
                            val problem = try {
                                updated.listForPicking(updated.rootFolderId)
                                null
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                e.message ?: e::class.simpleName.orEmpty()
                            }
                            toaster.toast(
                                if (problem == null) getStringOffMain(Lang.webdav_manage_saved)
                                else getStringOffMain(Lang.webdav_manage_saved_login_failed, problem),
                            )
                            editing = false
                        }
                    },
                    enabled = !busy,
                ) { Text(stringResource(Lang.webdav_manage_save)) }
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Lang.webdav_manage_folders), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { pickingFolder = true }, enabled = !busy) { Text(stringResource(Lang.webdav_manage_folders_add)) }
            }
            if (protocol.library.folders.isEmpty()) {
                Text(
                    stringResource(Lang.webdav_manage_folders_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (fid in protocol.library.folders) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        WebDav.displayPath(protocol, fid),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = {
                            run {
                                update { p -> p.copy(library = p.library.copy(folders = p.library.folders - fid)) } ?: return@run
                                toaster.toast(getStringOffMain(Lang.webdav_manage_folder_removed))
                            }
                        },
                        enabled = !busy,
                    ) { Text(stringResource(Lang.webdav_manage_folders_remove)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Lang.webdav_manage_depth), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(Lang.webdav_manage_depth_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (depth in 1..3) {
                    FilterChip(
                        selected = protocol.library.depth == depth,
                        onClick = {
                            run { update { p -> p.copy(library = p.library.copy(depth = depth)) } }
                        },
                        label = { Text(depth.toString()) },
                        enabled = !busy,
                    )
                }
            }

            HorizontalDivider()
            val current = account
            Text(
                if (current?.isLoggedIn == true) {
                    stringResource(Lang.webdav_manage_account, current.nickname.ifBlank { current.username })
                } else {
                    stringResource(Lang.webdav_manage_not_logged_in)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                if (current?.isLoggedIn == true) {
                    TextButton(
                        onClick = {
                            run {
                                service.logout()
                                toaster.toast(getStringOffMain(Lang.webdav_manage_logged_out))
                            }
                        },
                        enabled = !busy,
                    ) { Text(stringResource(Lang.settings_account_logout)) }
                } else {
                    TextButton(onClick = { loggingIn = true }, enabled = !busy) { Text(stringResource(Lang.webdav_manage_login)) }
                }
                TextButton(onClick = { confirmingDelete = true }, enabled = !busy) {
                    Text(stringResource(Lang.webdav_manage_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (pickingFolder) {
        WebDavFolderPickDialog(
            service,
            onPick = { fid ->
                pickingFolder = false
                run {
                    if (fid in protocol.library.folders) {
                        toaster.toast(getStringOffMain(Lang.webdav_manage_folder_already))
                        return@run
                    }
                    update { p -> p.copy(library = p.library.copy(folders = (p.library.folders + fid).distinct())) } ?: return@run
                    toaster.toast(getStringOffMain(Lang.webdav_manage_folder_added))
                }
            },
            onDismissRequest = { pickingFolder = false },
        )
    }
    if (loggingIn) {
        CloudDrivePasswordLoginDialog(service, onDismissRequest = { loggingIn = false })
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            text = { Text(stringResource(Lang.webdav_manage_delete_confirm, protocol.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        run {
                            service.logout()
                            WebDavSources.remove(manager, driveId)
                            toaster.toast(getStringOffMain(Lang.webdav_manage_deleted, protocol.name))
                            onDismissRequest()
                        }
                    },
                ) { Text(stringResource(Lang.tv_settings_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(Lang.webdav_manage_cancel)) }
            },
        )
    }
}

/** 一层层点进服务器的文件夹, 「选这个文件夹」把这一层当媒体文件夹 (根也能选: 按整个服务器找)。只列文件夹。 */
@Composable
private fun WebDavFolderPickDialog(service: CloudDriveService, onPick: (String) -> Unit, onDismissRequest: () -> Unit) {
    val state = rememberDriveBrowserState(service)
    AniCenteredPanelDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.canGoUp) {
                    IconButton(onClick = { state.up() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(Lang.webdav_manage_folder_up))
                    }
                }
                Text(stringResource(Lang.webdav_manage_folder_pick_title), Modifier.weight(1f))
                AniPanelCloseButton(onDismissRequest, Modifier.padding(start = 12.dp))
            }
        },
        heightFraction = 0.85f,
        maxWidth = DIALOG_MAX_WIDTH,
        dismissOnClickOutside = false,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.path,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                FilledTonalButton(onClick = { onPick(state.current.fid) }) {
                    Text(stringResource(Lang.webdav_manage_folder_choose))
                }
            }
            DriveBrowserList(state, Modifier.weight(1f, fill = false), shown = { false })
        }
    }
}

private val DIALOG_MAX_WIDTH = 680.dp
private val UPDATE_WAIT = 5.seconds
private val logger = logger("WebDavManageDialog")
