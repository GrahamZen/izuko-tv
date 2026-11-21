/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.danmaku.imported.DanmakuImporter
import me.him188.ani.app.domain.danmaku.imported.DanmakuLinkImportResult
import me.him188.ani.app.domain.danmaku.imported.ImportedDanmakuBatch
import me.him188.ani.app.domain.danmaku.imported.ImportedDanmakuProvider
import me.him188.ani.app.domain.danmaku.imported.ImportedDanmakuStore
import me.him188.ani.app.domain.danmaku.server.DanmakuServerRegistry
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniPanelCloseButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tablet_danmaku_import
import me.him188.ani.app.ui.lang.tablet_danmaku_import_choose
import me.him188.ani.app.ui.lang.tablet_danmaku_import_count
import me.him188.ani.app.ui.lang.tablet_danmaku_import_delete
import me.him188.ani.app.ui.lang.tablet_danmaku_import_done
import me.him188.ani.app.ui.lang.tablet_danmaku_import_done_link
import me.him188.ani.app.ui.lang.tablet_danmaku_import_empty_file
import me.him188.ani.app.ui.lang.tablet_danmaku_import_file
import me.him188.ani.app.ui.lang.tablet_danmaku_import_file_hint
import me.him188.ani.app.ui.lang.tablet_danmaku_import_hint
import me.him188.ani.app.ui.lang.tablet_danmaku_import_link
import me.him188.ani.app.ui.lang.tablet_danmaku_import_link_empty
import me.him188.ani.app.ui.lang.tablet_danmaku_import_link_failed
import me.him188.ani.app.ui.lang.tablet_danmaku_import_link_invalid
import me.him188.ani.app.ui.lang.tablet_danmaku_import_link_no_server
import me.him188.ani.app.ui.lang.tablet_danmaku_import_link_placeholder
import me.him188.ani.app.ui.lang.tablet_danmaku_import_link_timeout
import me.him188.ani.app.ui.lang.tablet_danmaku_import_list
import me.him188.ani.app.ui.lang.tablet_danmaku_import_not_danmaku
import me.him188.ani.app.ui.lang.tablet_danmaku_import_read_failed
import me.him188.ani.app.ui.lang.tablet_danmaku_import_title
import me.him188.ani.app.ui.lang.tablet_danmaku_import_too_large
import me.him188.ani.app.ui.lang.tablet_danmaku_import_unknown_error
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
import me.him188.ani.danmaku.api.provider.Imported
import me.him188.ani.utils.coroutines.IO_
import org.jetbrains.compose.resources.stringResource

/**
 * 平板播放页弹幕面板栏顶的「导入」(同 Web 控制台播放器里的导入弹幕): 只给正在播的这一集 ([episodeId]), 从文件 (B 站 XML 或弹弹 play 的 JSON)
 * 或按视频页链接经弹幕服务器取 (没有能按链接取的服务器时不列这一段); 已导入的一批批列在下面, 能删。导入、删除后当场换成最新的结果显示。
 */
@Composable
internal fun TabletDanmakuImportDialog(vm: EpisodeViewModel, episodeId: Int, onDismissRequest: () -> Unit) {
    val importer = remember { GlobalKoin.get<DanmakuImporter>() }
    val store = remember { GlobalKoin.get<ImportedDanmakuStore>() }
    val provider = remember { GlobalKoin.get<ImportedDanmakuProvider>() }
    val registry = remember { GlobalKoin.get<DanmakuServerRegistry>() }
    val scope = rememberCoroutineScope()
    val subjectId = vm.subjectId
    var batches by remember { mutableStateOf<List<ImportedDanmakuBatch>>(emptyList()) }
    var canLink by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        batches = store.list(subjectId, episodeId)
        // 播放器里这一集的「导入的弹幕」换成最新的 (删光时那一项弹幕源随之消失)
        vm.onMatchingDanmakuComplete(DanmakuProviderId.Imported, provider.results(subjectId, episodeId))
    }
    LaunchedEffect(subjectId, episodeId) {
        batches = store.list(subjectId, episodeId)
        canLink = registry.providers().any { it.supportsLinkImport }
    }

    val picker = rememberFilePickerLauncher(type = FileKitType.File("xml", "json")) { file ->
        if (file == null) return@rememberFilePickerLauncher
        busy = true
        scope.launch {
            message = try {
                if (file.size() > MAX_FILE_BYTES) {
                    getStringOffMain(Lang.tablet_danmaku_import_too_large)
                } else {
                    val bytes = withContext(Dispatchers.IO_) { file.readBytes() }
                    val batch = importer.importFile(subjectId, episodeId, file.name, bytes)
                    when {
                        batch == null -> getStringOffMain(Lang.tablet_danmaku_import_not_danmaku)
                        batch.count == 0 -> getStringOffMain(Lang.tablet_danmaku_import_empty_file)
                        else -> {
                            refresh()
                            getStringOffMain(Lang.tablet_danmaku_import_done, batch.count)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                getStringOffMain(Lang.tablet_danmaku_import_read_failed)
            }
            busy = false
        }
    }

    AniCenteredPanelDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Lang.tablet_danmaku_import_title), Modifier.weight(1f))
                AniPanelCloseButton(onDismissRequest, Modifier.padding(start = 12.dp))
            }
        },
        heightFraction = null,
        maxWidth = 600.dp,
        dismissOnClickOutside = false,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(Lang.tablet_danmaku_import_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(Lang.tablet_danmaku_import_file), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { picker.launch() }, enabled = !busy) { Text(stringResource(Lang.tablet_danmaku_import_choose)) }
                Text(
                    stringResource(Lang.tablet_danmaku_import_file_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canLink) {
                Text(stringResource(Lang.tablet_danmaku_import_link), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        link, { link = it },
                        Modifier.weight(1f),
                        placeholder = { Text(stringResource(Lang.tablet_danmaku_import_link_placeholder)) },
                        singleLine = true,
                        // 网址键盘: 拼音输入法会把英文与句点转成中文
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    )
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                message = when (val r = importer.importLink(subjectId, episodeId, link)) {
                                    is DanmakuLinkImportResult.Success -> {
                                        link = ""
                                        refresh()
                                        getStringOffMain(Lang.tablet_danmaku_import_done_link, r.batch.count, r.serverName)
                                    }

                                    DanmakuLinkImportResult.InvalidLink -> getStringOffMain(Lang.tablet_danmaku_import_link_invalid)
                                    DanmakuLinkImportResult.NoServer -> getStringOffMain(Lang.tablet_danmaku_import_link_no_server)
                                    DanmakuLinkImportResult.Empty -> getStringOffMain(Lang.tablet_danmaku_import_link_empty)
                                    DanmakuLinkImportResult.Timeout -> getStringOffMain(Lang.tablet_danmaku_import_link_timeout)
                                    is DanmakuLinkImportResult.Failed -> getStringOffMain(
                                        Lang.tablet_danmaku_import_link_failed,
                                        r.message ?: getStringOffMain(Lang.tablet_danmaku_import_unknown_error),
                                    )
                                }
                                busy = false
                            }
                        },
                        enabled = !busy && link.isNotBlank(),
                    ) { Text(stringResource(Lang.tablet_danmaku_import)) }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (batches.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(Lang.tablet_danmaku_import_list), style = MaterialTheme.typography.titleSmall)
                for (batch in batches) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(batch.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(Lang.tablet_danmaku_import_count, batch.count),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    store.remove(subjectId, episodeId, batch.id)
                                    message = null
                                    refresh()
                                }
                            },
                        ) {
                            Text(stringResource(Lang.tablet_danmaku_import_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

/** 弹幕文件上限 (同 Web 控制台). */
private const val MAX_FILE_BYTES = 16L shl 20
