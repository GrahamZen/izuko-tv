/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.him188.ani.app.ui.foundation.lan.QrCodeImage
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.search_tv_remote_unavailable
import me.him188.ani.app.ui.lang.tv_remote_control_close
import me.him188.ani.app.ui.lang.tv_remote_control_dont_show_on_launch
import me.him188.ani.app.ui.lang.tv_remote_control_panel_hint
import me.him188.ani.app.ui.lang.tv_remote_control_title
import me.him188.ani.app.ui.lang.tv_remote_qr_connected
import me.him188.ani.app.ui.lang.tv_remote_qr_hint
import me.him188.ani.app.ui.lang.tv_remote_qr_ip_changed
import me.him188.ani.app.ui.lang.tv_remote_qr_ip_changed_hint
import me.him188.ani.app.ui.lang.tv_remote_qr_waiting
import me.him188.ani.app.ui.lang.tv_remote_qr_troubleshoot
import me.him188.ani.app.ui.lang.tv_remote_qr_troubleshoot_vpn
import org.jetbrains.compose.resources.stringResource

/** TV 根组合里调一次: 启动时 [TvRemoteControl.showDialogOnLaunch] 之后在这里画弹窗. */
@Composable
fun TvRemoteControlDialogHost() {
    val visible by TvRemoteControl.dialogVisible.collectAsState()
    if (visible) TvRemoteControlDialog(onDismissRequest = TvRemoteControl::dismissDialog)
}

/**
 * 动作面板开着时屏幕右上角那张「Web 控制台」码卡 (见 TvActionPanelDialog; 2026-09-12 用户要: 面板照旧, 码单独放右上角,
 * 长按播放键一开面板就能扫). 版式: 标题行 (「Web 控制台」+ 右边状态点与两三个字) / 码 / 一行说明 / 地址, 字尽量少.
 * 不吃焦点: 面板的焦点路径与标签行都不受影响.
 *
 * @param containerColor 卡片底色: 由面板传它自己的底色 (半透明玻璃), 两块透明度一致、看着是一套; 只有码自带不透明底
 *   (透过来的图案会干扰扫码). 圆角同面板 16dp, 面板没有投影, 卡片也不加
 */
@Composable
fun TvRemoteQrCard(containerColor: Color, modifier: Modifier = Modifier) {
    val url by TvRemoteControl.url.collectAsState()
    val hostChanged by TvRemoteControl.hostChanged.collectAsState()
    val phoneConnected by TvRemoteControl.phoneConnected.collectAsState()
    LaunchedEffect(Unit) { TvRemoteControl.refreshAddress() }

    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier.width(CARD_QR_SIZE + CARD_QR_QUIET_ZONE * 2 + CARD_PADDING * 2),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        // 半透明底查不到 "on" 色, 内容色显式给 (同动作面板)
        contentColor = scheme.onSurface,
    ) {
        Column(Modifier.padding(CARD_PADDING)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Lang.tv_remote_control_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                RemoteConnectionStatus(url, hostChanged, phoneConnected, MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(12.dp))
            RemoteQrCode(url, CARD_QR_SIZE, CARD_QR_QUIET_ZONE)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(if (hostChanged) Lang.tv_remote_qr_ip_changed_hint else Lang.tv_remote_qr_hint),
                style = MaterialTheme.typography.bodySmall,
                color = if (hostChanged) scheme.error else scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (url != null && !phoneConnected && !hostChanged) {
                Spacer(Modifier.height(4.dp))
                RemoteTroubleshootHint(MaterialTheme.typography.labelSmall, TextAlign.Center)
            }
            // 最底下一行地址: 扫不了码 (相机坏了 / 用电脑) 时照着输入, 或者核对手机书签是不是这个. 地址约 36 字,
            // 卡片这么窄会折成两行, 不省略 —— 省掉一截就输不对了
            url?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 启动时弹一次的「Web 控制台」弹窗 (设置里开着「启动时弹出」才有, 见 [TvRemoteControl.showDialogOnLaunch]).
 * 平时的入口是动作面板开着时右上角那张码卡, 这里只做「介绍一次」.
 *
 * 横排: 左 = 码 (同码卡的浅底深码), 右 = 标题 / 连接状态 / 一句能干什么 / 地址 / 以后去哪找 / 两颗按钮.
 * 原先是 AlertDialog 里码居中、字在下面, 左右两大块空白 (用户嫌空); 横排后码与说明各占一半, 高度也省下来 ——
 * 1080p 电视约 540dp 高, AlertDialog 的文字区不滚动, 竖排一长就截断.
 */
@Composable
fun TvRemoteControlDialog(onDismissRequest: () -> Unit) {
    val url by TvRemoteControl.url.collectAsState()
    val hostChanged by TvRemoteControl.hostChanged.collectAsState()
    val phoneConnected by TvRemoteControl.phoneConnected.collectAsState()
    LaunchedEffect(Unit) { TvRemoteControl.refreshAddress() }
    // 扫完码在手机上搜索 / 点播: 电视那边已经换了页面, 这个弹窗别再挡着
    LaunchedEffect(Unit) { TvRemoteControl.remoteNavigations.collect { onDismissRequest() } }
    // 焦点先落「关闭」: 旁边就是「启动时不再显示」, 打开后条件反射按确认键不该点到它
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }

    val scheme = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.width(LAUNCH_DIALOG_WIDTH),
            shape = RoundedCornerShape(28.dp),
            color = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
        ) {
            Row(Modifier.padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteQrCode(url, LAUNCH_QR_SIZE, LAUNCH_QR_QUIET_ZONE)
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Lang.tv_remote_control_title), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    RemoteConnectionStatus(url, hostChanged, phoneConnected, MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(if (hostChanged) Lang.tv_remote_qr_ip_changed_hint else Lang.tv_remote_qr_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hostChanged) scheme.error else scheme.onSurface,
                    )
                    url?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(Lang.tv_remote_control_panel_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    if (url != null && !phoneConnected && !hostChanged) {
                        Spacer(Modifier.height(8.dp))
                        RemoteTroubleshootHint(MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onDismissRequest, Modifier.focusRequester(closeFocus)) {
                            Text(stringResource(Lang.tv_remote_control_close))
                        }
                        TextButton(
                            onClick = {
                                // 先关弹窗再改设置 (写入在 TvRemoteControl 的作用域里, 不随弹窗离场取消)
                                onDismissRequest()
                                TvRemoteControl.setShowOnLaunch(false)
                            },
                        ) { Text(stringResource(Lang.tv_remote_control_dont_show_on_launch)) }
                    }
                }
            }
        }
    }
}

/**
 * 码本体 (或没地址时同样大小的占位). **浅底深码** (常规极性, 老扫码器也认): 深色主题的 primary 本身是浅色 (tone 80),
 * 直接当底、onPrimary 画码; 浅色主题的 primary 是深色, 改用 primaryContainer / onPrimaryContainer. 按主题深浅判,
 * 不按 primary 自己的亮度判 —— tone 80 的亮度正好卡在 0.5 上下, 按它判会时对时错. 码自带不透明底:
 * 动作面板那边背后是半透明玻璃, 透过来的图案会干扰扫码.
 */
@Composable
private fun RemoteQrCode(url: String?, size: Dp, quietZone: Dp) {
    val scheme = MaterialTheme.colorScheme
    val darkTheme = scheme.surface.luminance() < 0.5f
    if (url != null) {
        QrCodeImage(
            url,
            Modifier.size(size),
            quietZone = quietZone,
            foreground = if (darkTheme) scheme.onPrimary else scheme.onPrimaryContainer,
            background = if (darkTheme) scheme.primary else scheme.primaryContainer,
        )
    } else {
        Box(
            Modifier
                .size(size + quietZone * 2)
                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(Lang.search_tv_remote_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 还没有手机连上时码下面的排障一句: 扫了码打不开, 最常见是手机没连同一个 Wi-Fi, 或手机开着 VPN 没「绕过局域网」
 * (请求被收进隧道, 到不了电视). 电视自己开着 VPN (用户多半手机也开着) 时换成更具体、更醒目的一句. 几秒查一次, VPN 开关随时跟上.
 */
@Composable
private fun RemoteTroubleshootHint(style: TextStyle, textAlign: TextAlign? = null) {
    val vpn by produceState(false) {
        while (true) {
            value = withContext(Dispatchers.IO) { TvRemoteControl.tvVpnActive() }
            delay(3.seconds)
        }
    }
    val scheme = MaterialTheme.colorScheme
    Text(
        stringResource(if (vpn) Lang.tv_remote_qr_troubleshoot_vpn else Lang.tv_remote_qr_troubleshoot),
        style = style,
        color = if (vpn) scheme.tertiary else scheme.onSurfaceVariant,
        textAlign = textAlign,
        modifier = if (textAlign != null) Modifier.fillMaxWidth() else Modifier,
    )
}

/** 状态点 + 两三个字 (已连接 绿 / 等待连接 灰 / IP 已变 红); 没有地址时不画 (码的位置已经写着「未连接到局域网」). */
@Composable
private fun RemoteConnectionStatus(url: String?, hostChanged: Boolean, phoneConnected: Boolean, style: TextStyle) {
    val scheme = MaterialTheme.colorScheme
    val connectedColor = if (scheme.surface.luminance() < 0.5f) CONNECTED_GREEN_DARK else CONNECTED_GREEN_LIGHT
    val (dot, label) = when {
        url == null -> return
        hostChanged -> scheme.error to stringResource(Lang.tv_remote_qr_ip_changed)
        phoneConnected -> connectedColor to stringResource(Lang.tv_remote_qr_connected)
        else -> scheme.outline to stringResource(Lang.tv_remote_qr_waiting)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = style, color = if (dot == scheme.outline) scheme.onSurfaceVariant else dot)
    }
}

/** 右上角码卡: 码本体 128dp, 留白 18dp (≥ 4 模块: 29 模块时一模块 ≈ 4.4dp); 整张卡约 196dp 宽, 与居中的面板不重叠. */
private val CARD_QR_SIZE = 128.dp
private val CARD_QR_QUIET_ZONE = 18.dp
private val CARD_PADDING = 16.dp

/**
 * 启动弹窗: 码 210dp + 留白 24dp (≥ 4 模块); 弹窗 700dp 宽, 右栏约 360dp.
 *
 * 右栏太窄会把说明折成五六段, 那一列比码高出一大截 —— 码在 Row 里垂直居中, 于是上下空隙明显大过左右的
 * 28dp padding, 看着不齐. 所以宽度按"右栏够放下地址和说明"定, 不是按码的大小定; 但也不能一味加宽,
 * 弹窗本身在电视上会显得空 (720dp 试过, 太宽).
 */
private val LAUNCH_QR_SIZE = 210.dp
private val LAUNCH_QR_QUIET_ZONE = 24.dp
private val LAUNCH_DIALOG_WIDTH = 650.dp

/** 「已连接」的绿: 深色主题上用亮一些的, 浅色主题上用深一些的, 两边与卡片底色都有足够对比. */
private val CONNECTED_GREEN_DARK = Color(0xFF6DD58C)
private val CONNECTED_GREEN_LIGHT = Color(0xFF1E8E3E)
