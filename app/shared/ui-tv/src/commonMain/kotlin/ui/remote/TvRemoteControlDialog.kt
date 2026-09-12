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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.data.models.preference.TvRemoteEntryPlacement
import me.him188.ani.app.ui.foundation.lan.QrCodeImage
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.search_tv_remote_connected
import me.him188.ani.app.ui.lang.search_tv_remote_host_changed
import me.him188.ani.app.ui.lang.search_tv_remote_unavailable
import me.him188.ani.app.ui.lang.search_tv_remote_waiting
import me.him188.ani.app.ui.lang.tv_remote_control_close
import me.him188.ani.app.ui.lang.tv_remote_control_desc
import me.him188.ani.app.ui.lang.tv_remote_control_entry_hint_avatar
import me.him188.ani.app.ui.lang.tv_remote_control_move_to_avatar
import me.him188.ani.app.ui.lang.tv_remote_control_title
import org.jetbrains.compose.resources.stringResource

/** TV 根组合里调一次: [TvRemoteControl.showDialog] 之后在这里画弹窗, 哪一页的侧边栏都能开. */
@Composable
fun TvRemoteControlDialogHost() {
    val visible by TvRemoteControl.dialogVisible.collectAsState()
    if (visible) TvRemoteControlDialog(onDismissRequest = TvRemoteControl::dismissDialog)
}

/**
 * 「手机遥控」二维码弹窗 (侧边栏那一项或头像菜单弹出, 见 [ThemeSettings.tvRemoteEntryPlacement]): 与搜索页右侧面板是
 * **同一个地址**, 只是入口不同 —— 搜索页面板只在搜索页看得到, 而播放中想换源的人是从这里扫.
 *
 * 地址、IP 变化提示、手机是否已连上都读 [TvRemoteControl] 的状态, 与搜索页面板一致. 打开时重算一次地址:
 * 电视换过网络的话, 服务启动时算的那个已经不对了.
 *
 * 入口在侧边栏时多一颗「收进头像菜单」(嫌多余的一键收起, 不用去设置里找); 已在头像菜单时改成一行字说怎么放回去.
 */
@Composable
fun TvRemoteControlDialog(onDismissRequest: () -> Unit) {
    val url by TvRemoteControl.url.collectAsState()
    val hostChanged by TvRemoteControl.hostChanged.collectAsState()
    val knownHost by TvRemoteControl.knownHost.collectAsState()
    val phoneConnected by TvRemoteControl.phoneConnected.collectAsState()
    val inRail = LocalThemeSettings.current.tvRemoteEntryPlacement == TvRemoteEntryPlacement.Rail
    LaunchedEffect(Unit) { TvRemoteControl.refreshAddress() }
    // 扫完码在手机上搜索 / 点播: 电视那边已经换了页面, 这个弹窗别再挡着
    LaunchedEffect(Unit) { TvRemoteControl.remoteNavigations.collect { onDismissRequest() } }
    // 焦点先落「关闭」: 旁边就是「收进头像菜单」, 不定落点的话打开后按确认键可能点到它
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Lang.tv_remote_control_title)) },
        text = {
            val currentUrl = url
            // 横排两栏: 左 = 码 + 码下的地址与连接状态 (都是「这个码」的属性), 右 = 说明 + 入口提示.
            // 全部竖排在 1080p 电视上超出 AlertDialog 文字区 (不滚动) 的高度, 最后一行被截掉一半
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (currentUrl != null) {
                    // 码下方到地址那段空白是码自己的留白 (扫码器定位要用), 不能再压; 码与右栏的间距同理, 不另加
                    Column(
                        Modifier.width(QR_SIZE + QR_QUIET_ZONE * 2),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
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
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Lang.tv_remote_control_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (currentUrl == null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(Lang.search_tv_remote_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (!inRail) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(Lang.tv_remote_control_entry_hint_avatar),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onDismissRequest, Modifier.focusRequester(closeFocus)) {
                Text(stringResource(Lang.tv_remote_control_close))
            }
        },
        dismissButton = if (inRail) {
            {
                TextButton(
                    onClick = {
                        // 先关弹窗再改设置 (写入在 TvRemoteControl 的作用域里, 不随弹窗离场取消)
                        onDismissRequest()
                        TvRemoteControl.setEntryPlacement(TvRemoteEntryPlacement.Avatar)
                    },
                ) { Text(stringResource(Lang.tv_remote_control_move_to_avatar)) }
            }
        } else {
            null
        },
    )
}

/** 码本体边长; 地址 36 字符 = 29 模块, 200dp 下一模块约 6.9dp. */
private val QR_SIZE = 200.dp

/** 四周留白 ≥ 4 模块. */
private val QR_QUIET_ZONE = 28.dp
