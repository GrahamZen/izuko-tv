/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.lan.QrCodeImage
import me.him188.ani.app.ui.foundation.widgets.aniDialogContainerColor
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.foundation_browser_open_failed_close
import me.him188.ani.app.ui.lang.foundation_browser_open_failed_scan_hint
import me.him188.ani.app.ui.lang.foundation_browser_open_failed_title
import org.jetbrains.compose.resources.stringResource

/**
 * 「无法打开链接」兜底弹窗: 本机拉不起浏览器 (电视上很常见: 系统根本没装, 或者装了个只认自己
 * 主页的壳) 时, 把链接画成二维码让用户用手机扫, 同时印出链接文字给手敲.
 *
 * 由 [me.him188.ani.app.platform.navigation.rememberAsyncBrowserNavigator] 在打开失败时弹出,
 * 各调用点不用自己处理.
 */
@Composable
fun OpenLinkFallbackDialog(
    url: String,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Lang.foundation_browser_open_failed_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 该平台编不出码时 (iOS) 这一行什么都不画, 只剩下面的文字
                QrCodeImage(url, Modifier.size(OPEN_LINK_QR_SIZE))
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(Lang.foundation_browser_open_failed_scan_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(onDismissRequest) { Text(stringResource(Lang.foundation_browser_open_failed_close)) }
        },
        containerColor = aniDialogContainerColor(),
    )
}

/**
 * 码本体边长. 链接可能很长 (QQ 群邀请链接 200 字符 = 版本 10+, 57 模块), 240dp 下一模块 4dp,
 * 沙发距离要凑近一点扫; 再大弹窗就装不下了.
 */
private val OPEN_LINK_QR_SIZE = 240.dp
