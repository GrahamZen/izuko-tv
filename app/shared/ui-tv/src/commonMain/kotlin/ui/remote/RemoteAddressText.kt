/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.launch
import me.him188.ani.app.ui.foundation.setClipEntryText
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_remote_address_copied
import org.jetbrains.compose.resources.stringResource

/**
 * Web 控制台地址那一行字 (码卡、启动弹窗、搜索页、引导……): 扫不了码时照着输入, 或核对手机书签是不是这个.
 * 平板模式 (触屏) 下画下划线, 点一下复制到剪贴板 (提示「已复制」), 长按能选中一段; 平板自己扫不了自己屏幕上的码, 复制了好发到别的设备上.
 * 遥控器上只是字.
 */
@Composable
internal fun RemoteAddressText(
    url: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    if (!LocalTvTouchInputEnabled.current) {
        Text(url, modifier, color = color, style = style, textAlign = textAlign)
        return
    }
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    val copied = stringResource(Lang.tv_remote_address_copied)
    val scope = rememberCoroutineScope()
    SelectionContainer(modifier) {
        Text(
            url,
            Modifier
                // 居中排的: 选区容器把字放在左上角, 字自己铺满才居中
                .then(if (textAlign != null) Modifier.fillMaxWidth() else Modifier)
                .testTag(REMOTE_ADDRESS_TEXT_TAG)
                .clickable {
                    scope.launch {
                        clipboard.setClipEntryText(url)
                        toaster.toast(copied)
                    }
                },
            color = color,
            style = style.copy(textDecoration = TextDecoration.Underline),
            textAlign = textAlign,
        )
    }
}

internal const val REMOTE_ADDRESS_TEXT_TAG = "remote-address-text"
