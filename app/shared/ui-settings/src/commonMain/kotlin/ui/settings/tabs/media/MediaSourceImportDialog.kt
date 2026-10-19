/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.media

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.mediasource.codec.MediaSourceCodecManager
import me.him188.ani.app.domain.mediasource.codec.MediaSourceTextImport
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.animation.LocalAniMotionScheme
import me.him188.ani.app.ui.foundation.getClipEntryText
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_media_source_subscription_cancel
import me.him188.ani.app.ui.lang.settings_media_source_subscription_paste
import me.him188.ani.app.ui.lang.settings_mediasource_clipboard_empty
import me.him188.ani.app.ui.lang.touch_settings_sources_import
import me.him188.ani.app.ui.lang.touch_settings_sources_import_confirm
import me.him188.ani.app.ui.lang.touch_settings_sources_import_done
import me.him188.ani.app.ui.lang.touch_settings_sources_import_download_failed
import me.him188.ani.app.ui.lang.touch_settings_sources_import_empty
import me.him188.ani.app.ui.lang.touch_settings_sources_import_field
import me.him188.ani.app.ui.lang.touch_settings_sources_import_none
import me.him188.ani.app.ui.lang.touch_settings_sources_import_partial
import me.him188.ani.app.ui.lang.touch_settings_sources_import_single
import me.him188.ani.app.ui.lang.touch_settings_sources_import_unknown
import me.him188.ani.app.ui.lang.touch_settings_sources_import_unsupported
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * 导入规则 (平板模式的触屏设置页用; 电视上在手机控制台的「导入」里贴): 贴 AniBaka 规则、规则或规则库的地址, 或导出的数据源 JSON,
 * 每一个新建为本地源 (见 [MediaSourceTextImport]). 样子同「添加订阅」那个填地址的对话框. 导进来了就关, 一个没进来时说明原因、不关.
 */
@Composable
fun MediaSourceImportDialog(onDismissRequest: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val confirm: () -> Unit = {
        if (!importing && text.isNotBlank()) {
            importing = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.Default) { importMediaSources(text) }
                    toaster.toast(result.message)
                    if (result.imported) onDismissRequest()
                } finally {
                    importing = false
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest,
        confirmButton = {
            AnimatedContent(
                importing,
                transitionSpec = LocalAniMotionScheme.current.animatedContent.standard,
                contentAlignment = Alignment.BottomEnd,
            ) {
                if (it) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    TextButton(confirm, enabled = text.isNotBlank()) {
                        Text(stringResource(Lang.touch_settings_sources_import_confirm))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onDismissRequest) {
                Text(stringResource(Lang.settings_media_source_subscription_cancel))
            }
        },
        title = { Text(stringResource(Lang.touch_settings_sources_import)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                Modifier.focusRequester(focus),
                enabled = !importing,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                label = { Text(stringResource(Lang.touch_settings_sources_import_field)) },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val clipText = clipboard.getClipEntryText()
                                if (clipText.isNullOrBlank()) {
                                    toaster.toast(getString(Lang.settings_mediasource_clipboard_empty))
                                    return@launch
                                }
                                text = clipText
                            }
                        },
                        enabled = !importing,
                    ) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(Lang.settings_media_source_subscription_paste))
                    }
                },
            )
            SideEffect { focus.requestFocus() }
        },
    )
}

/** 导入的结果: 给用户看的一句 [message], 与有没有导进来 ([imported], 导进来了就关对话框). */
private class ImportOutcome(val message: String, val imported: Boolean)

private suspend fun importMediaSources(text: String): ImportOutcome {
    val client = GlobalKoin.get<HttpClientProvider>().get()
    val importer = MediaSourceTextImport(GlobalKoin.get<MediaSourceManager>(), GlobalKoin.get<MediaSourceCodecManager>()) { url ->
        client.use { get(url).bodyAsText() }
    }
    val result = importer.import(text.trim()) ?: return ImportOutcome(getString(Lang.touch_settings_sources_import_unknown), false)
    val reasons = result.failures.map { failure ->
        when (failure) {
            is MediaSourceTextImport.Failure.UnsupportedType -> getString(Lang.touch_settings_sources_import_unsupported, failure.factoryId)
            is MediaSourceTextImport.Failure.SingleInstance -> getString(Lang.touch_settings_sources_import_single, failure.name)
            is MediaSourceTextImport.Failure.Download -> getString(Lang.touch_settings_sources_import_download_failed, failure.message)
            is MediaSourceTextImport.Failure.Other -> failure.message
        }
    }.distinct().joinToString(" / ")
    return when {
        result.added == 0 && result.failures.isEmpty() -> ImportOutcome(getString(Lang.touch_settings_sources_import_empty), false)
        result.added == 0 -> ImportOutcome(getString(Lang.touch_settings_sources_import_none, reasons), false)
        result.failures.isEmpty() -> ImportOutcome(getString(Lang.touch_settings_sources_import_done, result.added), true)
        else -> ImportOutcome(getString(Lang.touch_settings_sources_import_partial, result.added, result.failures.size, reasons), true)
    }
}
