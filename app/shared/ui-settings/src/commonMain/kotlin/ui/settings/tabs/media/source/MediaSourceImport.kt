/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.media.source

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveAddedShareMediaSource
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveMediaSource
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveShareSearchMediaSource
import me.him188.ani.app.domain.mediasource.codec.MediaSourceImportFailure
import me.him188.ani.app.domain.mediasource.codec.MediaSourceImportResult
import me.him188.ani.app.domain.mediasource.codec.MediaSourceImporter
import me.him188.ani.app.domain.mediasource.directapi.DirectApiMediaSource
import me.him188.ani.app.domain.mediasource.jsonapi.JsonApiMediaSource
import me.him188.ani.app.domain.mediasource.rss.RssMediaSource
import me.him188.ani.app.domain.mediasource.rule.RuleMediaSource
import me.him188.ani.app.domain.mediasource.web.SelectorMediaSource
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.getClipEntryText
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.windowSizeCap
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_media_source_cancel
import me.him188.ani.app.ui.lang.settings_media_source_import
import me.him188.ani.app.ui.lang.settings_media_source_import_clear
import me.him188.ani.app.ui.lang.settings_media_source_import_confirm
import me.him188.ani.app.ui.lang.settings_media_source_import_description
import me.him188.ani.app.ui.lang.settings_media_source_import_done
import me.him188.ani.app.ui.lang.settings_media_source_import_empty
import me.him188.ani.app.ui.lang.settings_media_source_import_failures
import me.him188.ani.app.ui.lang.settings_media_source_import_hint
import me.him188.ani.app.ui.lang.settings_media_source_import_loaded_clipboard
import me.him188.ani.app.ui.lang.settings_media_source_import_loaded_count
import me.him188.ani.app.ui.lang.settings_media_source_import_loaded_file
import me.him188.ani.app.ui.lang.settings_media_source_import_none
import me.him188.ani.app.ui.lang.settings_media_source_import_not_recognized
import me.him188.ani.app.ui.lang.settings_media_source_import_only_one
import me.him188.ani.app.ui.lang.settings_media_source_import_paste
import me.him188.ani.app.ui.lang.settings_media_source_import_pick_file
import me.him188.ani.app.ui.lang.settings_media_source_import_placeholder
import me.him188.ani.app.ui.lang.settings_media_source_import_read_failed
import me.him188.ani.app.ui.lang.settings_media_source_import_title
import me.him188.ani.app.ui.lang.settings_media_source_import_unsupported
import me.him188.ani.datasources.api.source.FactoryId
import me.him188.ani.utils.coroutines.IO_
import org.jetbrains.compose.resources.stringResource
import kotlin.coroutines.cancellation.CancellationException

/**
 * 「添加数据源」列表里不列的类型, 都走导入 ([MediaSourceImportDialog]) 或别处:
 * 写规则的几种 (网页选择器、JSON API、规则源、RSS、直链 API、网盘分享搜索) 都是导入别人写好的配置, 在设置里照空白模板写规则基本用不上;
 * 网盘与添加的分享在「网盘」那一块里加. 列表里留下要自己填的 (Jellyfin、Emby、Ikaros: 自己服务器的地址与账号) 与点一下就加的内置 BT 源.
 * 这些类型本身照常可用: 导入的配置与订阅里大多就是它们, 已有的照样显示、编辑、测试.
 */
private val ImportOnlyFactories: Set<FactoryId> = setOf(
    SelectorMediaSource.FactoryId,
    JsonApiMediaSource.FactoryId,
    RuleMediaSource.FactoryId,
    RssMediaSource.FactoryId,
    DirectApiMediaSource.FactoryId,
    CloudDriveShareSearchMediaSource.FactoryId,
    CloudDriveMediaSource.FactoryId,
    CloudDriveAddedShareMediaSource.FactoryId,
)

/** 「添加数据源」列表里列的类型, 见 [ImportOnlyFactories] */
internal fun List<MediaSourceTemplate>.addableByHand(): List<MediaSourceTemplate> =
    filterNot { it.factoryId in ImportOnlyFactories }

/** 「添加数据源」列表最上面一行: 导入配置 (JSON), 点开 [MediaSourceImportDialog] */
@Composable
internal fun MediaSourceImportItem(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = {
            Text(stringResource(Lang.settings_media_source_import), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier.clickable(onClick = onClick),
        leadingContent = {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null)
            }
        },
        supportingContent = { Text(stringResource(Lang.settings_media_source_import_description)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** 从文件或剪贴板读进来、不放进输入框的一段 (大的订阅内容放进输入框里排版会卡), 见 [MediaSourceImportDialog] */
private class LoadedContent(val fileName: String?, val text: String) {
    val count: Int? = MediaSourceImporter.parse(text)?.size
}

/** 剪贴板里的内容超过这么长就不放进输入框, 只写读到了几个 */
private const val IMPORT_FIELD_MAX_CHARS = 20_000

/**
 * 导入配置: 粘贴 (输入框、「从剪贴板粘贴」) 或「选择文件」, 认的格式同控制台的「导入 JSON」(见 [MediaSourceImporter]).
 * 导入了至少一个就关掉并提示结果; 一个都没导入时结果写在弹窗里, 可以改了再导.
 */
@Composable
internal fun MediaSourceImportDialog(onDismissRequest: () -> Unit) {
    val importer = remember {
        MediaSourceImporter(GlobalKoin.get(), GlobalKoin.get()) { url ->
            GlobalKoin.get<HttpClientProvider>().get().use { get(url).bodyAsText() }
        }
    }
    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf<LoadedContent?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current

    val picker = rememberFilePickerLauncher(type = FileKitType.File("json", "txt")) { file ->
        if (file == null) return@rememberFilePickerLauncher
        scope.launch {
            val content = try {
                withContext(Dispatchers.IO_) { file.readBytes().decodeToString() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                message = getStringOffMain(Lang.settings_media_source_import_read_failed)
                return@launch
            }
            loaded = LoadedContent(file.name, content)
            message = null
        }
    }

    val content = loaded?.text ?: text
    AlertDialog(
        onDismissRequest = { if (!importing) onDismissRequest() },
        modifier = Modifier.windowSizeCap(), // iOS 转屏 (见 windowSizeCap)
        title = { Text(stringResource(Lang.settings_media_source_import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Lang.settings_media_source_import_hint), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val pasted = clipboard.getClipEntryText().orEmpty()
                                if (pasted.length > IMPORT_FIELD_MAX_CHARS) {
                                    loaded = LoadedContent(null, pasted)
                                } else {
                                    loaded = null
                                    text = pasted
                                }
                                message = null
                            }
                        },
                        enabled = !importing,
                    ) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = null, Modifier.size(18.dp))
                        Text(stringResource(Lang.settings_media_source_import_paste), Modifier.padding(start = 8.dp))
                    }
                    TextButton(onClick = { picker.launch() }, enabled = !importing) {
                        Icon(Icons.Rounded.FileOpen, contentDescription = null, Modifier.size(18.dp))
                        Text(stringResource(Lang.settings_media_source_import_pick_file), Modifier.padding(start = 8.dp))
                    }
                }
                val current = loaded
                if (current != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val source = current.fileName?.let { stringResource(Lang.settings_media_source_import_loaded_file, it) }
                            ?: stringResource(Lang.settings_media_source_import_loaded_clipboard)
                        Text(
                            current.count?.let { stringResource(Lang.settings_media_source_import_loaded_count, source, it) } ?: source,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { loaded = null; message = null }, enabled = !importing) {
                            Text(stringResource(Lang.settings_media_source_import_clear))
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            message = null
                        },
                        placeholder = { Text(stringResource(Lang.settings_media_source_import_placeholder)) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        minLines = 4,
                        maxLines = 8,
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    importing = true
                    scope.launch {
                        try {
                            val result = importer.import(content)
                            val summary = describeImport(result)
                            if (result is MediaSourceImportResult.Done && result.added > 0) {
                                toaster.toast(summary)
                                onDismissRequest()
                            } else {
                                message = summary
                            }
                        } finally {
                            importing = false
                        }
                    }
                },
                enabled = !importing && content.isNotBlank(),
            ) {
                if (importing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(Lang.settings_media_source_import_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest, enabled = !importing) {
                Text(stringResource(Lang.settings_media_source_cancel))
            }
        },
    )
}

/** 导入结果的一句话: 导入了几个, 有导不进的接着写几个与原因 (同控制台) */
private suspend fun describeImport(result: MediaSourceImportResult): String = when (result) {
    MediaSourceImportResult.NotRecognized -> getStringOffMain(Lang.settings_media_source_import_not_recognized)
    MediaSourceImportResult.Empty -> getStringOffMain(Lang.settings_media_source_import_empty)
    is MediaSourceImportResult.Done -> {
        val head = if (result.added > 0) {
            getStringOffMain(Lang.settings_media_source_import_done, result.added)
        } else {
            getStringOffMain(Lang.settings_media_source_import_none)
        }
        if (result.failures.isEmpty()) {
            head
        } else {
            val reasons = result.failures.map { failure ->
                when (failure) {
                    is MediaSourceImportFailure.UnsupportedType -> getStringOffMain(Lang.settings_media_source_import_unsupported, failure.factoryId)
                    is MediaSourceImportFailure.OnlyOneAllowed -> getStringOffMain(Lang.settings_media_source_import_only_one, failure.name)
                    is MediaSourceImportFailure.Other -> failure.message
                }
            }.distinct()
            getStringOffMain(Lang.settings_media_source_import_failures, head, result.failures.size, reasons.joinToString("；"))
        }
    }
}
