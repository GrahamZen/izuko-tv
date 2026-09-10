/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.log

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.app.ui.foundation.lan.QrCodeImage
import me.him188.ani.app.ui.foundation.lan.findLanAddress
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_log_send_to_phone
import me.him188.ani.app.ui.lang.settings_log_send_to_phone_close
import me.him188.ani.app.ui.lang.settings_log_send_to_phone_failed
import me.him188.ani.app.ui.lang.settings_log_send_to_phone_hint
import me.him188.ani.app.ui.lang.settings_log_send_to_phone_no_network
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.jetbrains.compose.resources.stringResource
import java.io.File

private val logger = logger("LogLanShareDialog")

private sealed class LanShareState {
    data object Starting : LanShareState()
    data object NoNetwork : LanShareState()
    data object Failed : LanShareState()
    data class Ready(val url: String) : LanShareState()
}

/**
 * 「扫码传到手机」弹窗: 起 [LogLanShareServer], 把地址画成二维码.
 *
 * 服务与弹窗同生共死 —— 弹窗一关 (返回键 / 关闭按钮) 就 [LogLanShareServer.close], 链接失效.
 */
@Composable
internal fun LogLanShareDialog(
    logsDir: File,
    onDismissRequest: () -> Unit,
) {
    var state by remember { mutableStateOf<LanShareState>(LanShareState.Starting) }
    val holder = remember { ServerHolder() }

    LaunchedEffect(Unit) {
        // 建 socket 与枚举网卡都在 IO 线程: 前者在主线程会触发 NetworkOnMainThread 检查, 后者要走一遍 netlink
        val server = withContext(Dispatchers.IO) {
            runCatching { LogLanShareServer(logsDir).also(holder::attach) }
        }.getOrElse {
            logger.warn(it) { "Failed to start log LAN share server" }
            state = LanShareState.Failed
            return@LaunchedEffect
        }
        val host = withContext(Dispatchers.IO) { findLanAddress() }
        if (host == null) {
            state = LanShareState.NoNetwork
            return@LaunchedEffect
        }
        state = LanShareState.Ready(server.pageUrl(host))
        server.serve()
    }
    DisposableEffect(Unit) {
        onDispose { holder.close() }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Lang.settings_log_send_to_phone)) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (val s = state) {
                    LanShareState.Starting -> CircularProgressIndicator(Modifier.padding(24.dp))
                    LanShareState.NoNetwork -> Text(stringResource(Lang.settings_log_send_to_phone_no_network))
                    LanShareState.Failed -> Text(stringResource(Lang.settings_log_send_to_phone_failed))
                    is LanShareState.Ready -> {
                        QrCodeImage(s.url, Modifier.size(QR_SIZE))
                        Spacer(Modifier.height(16.dp))
                        // 地址也印出来: 扫不了码 (相机没网络权限之类) 还能手敲
                        Text(s.url, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(Lang.settings_log_send_to_phone_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onDismissRequest) { Text(stringResource(Lang.settings_log_send_to_phone_close)) }
        },
    )
}

/**
 * 服务器的生命周期把手: 服务器在 IO 线程上建, 弹窗销毁在主线程上发生, 两者谁先谁后不定 ——
 * 先销毁后建好的那种, 建好那刻就得直接关掉, 不能留一个没人管的监听端口.
 */
private class ServerHolder {
    private var closed = false
    private var server: LogLanShareServer? = null

    @Synchronized
    fun attach(server: LogLanShareServer) {
        if (closed) server.close() else this.server = server
    }

    @Synchronized
    fun close() {
        closed = true
        server?.close()
        server = null
    }
}

private val QR_SIZE = 240.dp
