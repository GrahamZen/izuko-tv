/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.him188.ani.app.data.repository.media.MediaSourceSubscriptionRepository
import me.him188.ani.app.domain.danmaku.server.DanmakuServerAdmin
import me.him188.ani.app.domain.danmaku.server.DanmakuServerAdmin.Probe
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniPanelCloseButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.touch_danmaku_servers_add
import me.him188.ani.app.ui.lang.touch_danmaku_servers_add_title
import me.him188.ani.app.ui.lang.touch_danmaku_servers_added
import me.him188.ani.app.ui.lang.touch_danmaku_servers_added_probe_failed
import me.him188.ani.app.ui.lang.touch_danmaku_servers_delete
import me.him188.ani.app.ui.lang.touch_danmaku_servers_duplicate
import me.him188.ani.app.ui.lang.touch_danmaku_servers_empty
import me.him188.ani.app.ui.lang.touch_danmaku_servers_from_subscription
import me.him188.ani.app.ui.lang.touch_danmaku_servers_hint
import me.him188.ani.app.ui.lang.touch_danmaku_servers_invalid_url
import me.him188.ani.app.ui.lang.touch_danmaku_servers_name
import me.him188.ani.app.ui.lang.touch_danmaku_servers_probe_failed
import me.him188.ani.app.ui.lang.touch_danmaku_servers_probe_ok
import me.him188.ani.app.ui.lang.touch_danmaku_servers_probe_timeout
import me.him188.ani.app.ui.lang.touch_danmaku_servers_test
import me.him188.ani.app.ui.lang.touch_danmaku_servers_testing
import me.him188.ani.app.ui.lang.touch_danmaku_servers_title
import me.him188.ani.app.ui.lang.touch_danmaku_servers_url
import org.jetbrains.compose.resources.stringResource

/**
 * 触屏版设置页的「弹幕服务器」(电视上在 Web 控制台里管): 每台一行 (名字、域名、测试结果, 开关; 自己加的能删, 订阅带来的只能关),
 * 底下添加 (名字、地址, 加上后测一次)。改动当场生效 ([DanmakuServerAdmin])。有输入框, 点外面不关。
 */
@Composable
internal fun TouchDanmakuServersDialog(onDismissRequest: () -> Unit) {
    val admin = remember { DanmakuServerAdmin(GlobalKoin.get(), GlobalKoin.get(), GlobalKoin.get<MediaSourceSubscriptionRepository>().flow) }
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<DanmakuServerAdmin.Server>?>(null) }
    // 每台的测试结果 (地址 → 一句话); 测着时是「正在测试」
    val status = remember { mutableStateMapOf<String, String>() }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var addMessage by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    // 添加的表单点了「添加服务器」才展开: 一打开弹窗就落进输入框的话, 平板上键盘会直接弹出来
    var addFormShown by remember { mutableStateOf(false) }

    suspend fun reload() {
        servers = admin.list()
    }
    LaunchedEffect(admin) { reload() }

    AniCenteredPanelDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Lang.touch_danmaku_servers_title), Modifier.weight(1f))
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
            Text(
                stringResource(Lang.touch_danmaku_servers_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val list = servers
            if (list != null && list.isEmpty()) {
                Text(stringResource(Lang.touch_danmaku_servers_empty), style = MaterialTheme.typography.bodyMedium)
            }
            list?.forEachIndexed { index, server ->
                if (index > 0) HorizontalDivider()
                ServerRow(
                    server,
                    status = status[server.url],
                    onTest = {
                        scope.launch {
                            status[server.url] = getStringOffMain(Lang.touch_danmaku_servers_testing)
                            status[server.url] = admin.test(server.url)?.let { probeText(it) }.orEmpty()
                        }
                    },
                    onDelete = {
                        scope.launch {
                            admin.delete(server.url)
                            status.remove(server.url)
                            reload()
                        }
                    },
                    onEnabledChange = { on ->
                        scope.launch {
                            admin.setEnabled(server.url, on)
                            reload()
                        }
                    },
                )
            }

            HorizontalDivider()
            if (!addFormShown) {
                TextButton(onClick = { addFormShown = true }) { Text(stringResource(Lang.touch_danmaku_servers_add_title)) }
            } else {
                Text(stringResource(Lang.touch_danmaku_servers_add_title), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        name, { name = it },
                        Modifier.width(NAME_FIELD_WIDTH),
                        label = { Text(stringResource(Lang.touch_danmaku_servers_name)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        url, { url = it },
                        Modifier.weight(1f),
                        label = { Text(stringResource(Lang.touch_danmaku_servers_url)) },
                        placeholder = { Text("https://") },
                        singleLine = true,
                        // 网址键盘: 拼音输入法会把英文与句点转成中文
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    addMessage?.let {
                        Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } ?: Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            adding = true
                            scope.launch {
                                addMessage = when (val result = admin.add(name, url)) {
                                    DanmakuServerAdmin.AddResult.InvalidUrl -> getStringOffMain(Lang.touch_danmaku_servers_invalid_url)
                                    DanmakuServerAdmin.AddResult.Duplicate -> getStringOffMain(Lang.touch_danmaku_servers_duplicate)
                                    is DanmakuServerAdmin.AddResult.Added -> {
                                        name = ""
                                        url = ""
                                        reload()
                                        if (result.probe == Probe.Ok) {
                                            getStringOffMain(Lang.touch_danmaku_servers_added)
                                        } else {
                                            getStringOffMain(Lang.touch_danmaku_servers_added_probe_failed, probeText(result.probe))
                                        }
                                    }
                                }
                                adding = false
                            }
                        },
                        enabled = !adding && url.isNotBlank(),
                    ) {
                        Text(stringResource(Lang.touch_danmaku_servers_add))
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: DanmakuServerAdmin.Server,
    status: String?,
    onTest: () -> Unit,
    onDelete: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(server.name.ifEmpty { server.host }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val from = server.from?.let { stringResource(Lang.touch_danmaku_servers_from_subscription, it) }
            Text(
                // 没起名字时标题已经是域名, 这一行就不重复写
                listOfNotNull(server.host.takeIf { server.name.isNotEmpty() }, from, status?.takeIf { it.isNotEmpty() })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onTest) { Text(stringResource(Lang.touch_danmaku_servers_test)) }
        if (server.manual) {
            TextButton(onDelete) { Text(stringResource(Lang.touch_danmaku_servers_delete), color = MaterialTheme.colorScheme.error) }
        }
        Switch(server.enabled, onEnabledChange)
    }
}

private suspend fun probeText(probe: Probe): String = when (probe) {
    Probe.Ok -> getStringOffMain(Lang.touch_danmaku_servers_probe_ok)
    Probe.Timeout -> getStringOffMain(Lang.touch_danmaku_servers_probe_timeout)
    is Probe.Failed -> probe.serverMessage ?: getStringOffMain(Lang.touch_danmaku_servers_probe_failed)
}

private val DIALOG_MAX_WIDTH = 680.dp
private val NAME_FIELD_WIDTH = 180.dp
