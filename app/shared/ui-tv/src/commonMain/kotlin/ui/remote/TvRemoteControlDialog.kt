/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.lan.QrCodeImage
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.search_tv_remote_connected
import me.him188.ani.app.ui.lang.search_tv_remote_host_changed
import me.him188.ani.app.ui.lang.search_tv_remote_unavailable
import me.him188.ani.app.ui.lang.search_tv_remote_waiting
import me.him188.ani.app.ui.lang.tv_remote_control_close
import me.him188.ani.app.ui.lang.tv_remote_control_desc
import me.him188.ani.app.ui.lang.tv_remote_control_title
import org.jetbrains.compose.resources.stringResource

/**
 * 「手机控制中心」二维码弹窗 (侧边栏点头像弹出): 与搜索页右侧面板是**同一个地址**, 只是入口不同 ——
 * 搜索页面板只在搜索页看得到, 而播放中想换源的人是从这里扫.
 *
 * 地址、IP 变化提示、手机是否已连上都读 [TvRemoteControl] 的状态, 与搜索页面板一致. 打开时重算一次地址:
 * 电视换过网络的话, 服务启动时算的那个已经不对了.
 */
@Composable
fun TvRemoteControlDialog(onDismissRequest: () -> Unit) {
    val url by TvRemoteControl.url.collectAsState()
    val hostChanged by TvRemoteControl.hostChanged.collectAsState()
    val knownHost by TvRemoteControl.knownHost.collectAsState()
    val phoneConnected by TvRemoteControl.phoneConnected.collectAsState()
    LaunchedEffect(Unit) { TvRemoteControl.refreshAddress() }
    // 扫完码在手机上搜索 / 点播: 电视那边已经换了页面, 这个弹窗别再挡着
    LaunchedEffect(Unit) { TvRemoteControl.remoteNavigations.collect { onDismissRequest() } }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Lang.tv_remote_control_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(Lang.tv_remote_control_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                val currentUrl = url
                if (currentUrl == null) {
                    Text(
                        stringResource(Lang.search_tv_remote_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    QrCodeImage(currentUrl, Modifier.size(QR_SIZE), quietZone = QR_QUIET_ZONE)
                    Text(
                        currentUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hostChanged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            hostChanged -> stringResource(Lang.search_tv_remote_host_changed, knownHost.orEmpty())
                            phoneConnected -> stringResource(Lang.search_tv_remote_connected)
                            else -> stringResource(Lang.search_tv_remote_waiting)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            hostChanged -> MaterialTheme.colorScheme.error
                            phoneConnected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onDismissRequest) { Text(stringResource(Lang.tv_remote_control_close)) }
        },
    )
}

/** 码本体边长; 地址 36 字符 = 29 模块, 200dp 下一模块约 6.9dp. */
private val QR_SIZE = 200.dp

/** 四周留白 ≥ 4 模块. */
private val QR_QUIET_ZONE = 28.dp
