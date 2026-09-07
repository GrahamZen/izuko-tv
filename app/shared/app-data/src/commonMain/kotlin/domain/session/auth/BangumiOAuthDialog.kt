/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.him188.ani.app.domain.mediasource.web.captcha.TvWebInputMode

/**
 * bangumi 授权用的全屏浏览器. 挂在 app 根部 (与验证码那个对话框同一处), 消费
 * [BangumiOAuthManager.state].
 *
 * 为什么是**根部的叠层**而不是授权页里的一块: 授权中用户可能返回、可能被别的页面盖住,
 * 而这个浏览器一旦销毁, 授权就只能从头再来 —— 电视上尤其伤 (遥控器打一遍账号密码).
 */
@Composable
fun BangumiOAuthDialogHost(manager: BangumiOAuthManager) {
    val state by manager.state.collectAsState()
    val authorizing = state as? BangumiOAuthManager.State.Authorizing ?: return
    val browser = authorizing.browser ?: return

    // 电视上默认虚拟光标: 焦点遍历**给不出"不漏按钮"的保证** (只到得了标准可聚焦元素,
    // `<div onclick>` 永远碰不到), 而光标是注入触摸事件, 页面上什么都点得到, 走到边缘还带着
    // 页面滚. 顶栏那个钮切到焦点遍历, 输入账号密码时用它更顺手 (焦点进输入框会自动弹输入法).
    var tvInputMode by remember { mutableStateOf(TvWebInputMode.Cursor) }

    Dialog(
        onDismissRequest = { manager.cancel() },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                Box(Modifier.fillMaxWidth().statusBarsPadding()) {
                    IconButton(
                        onClick = { manager.cancel() },
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                        )
                    }
                    Text(
                        "登录 Bangumi",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 104.dp),
                    )
                    IconButton(
                        onClick = {
                            tvInputMode = if (tvInputMode == TvWebInputMode.NativeFocus) {
                                TvWebInputMode.Cursor
                            } else {
                                TvWebInputMode.NativeFocus
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Icon(
                            if (tvInputMode == TvWebInputMode.NativeFocus) {
                                Icons.Rounded.Adjust // 切到光标
                            } else {
                                Icons.Rounded.Keyboard // 切回方向键遍历
                            },
                            contentDescription = "切换遥控器操作方式",
                            tint = Color.White,
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    val isLoading by browser.isLoading.collectAsState()
                    if (isLoading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                // 电视上的操作方式见 TvWebInputMode: 默认虚拟光标 (随便移动 + 边缘带着页面滚 +
                // 确认键注入触摸点击), 顶栏那个钮可以切成网页自己的焦点遍历
                browser.View(
                    Modifier.fillMaxSize(),
                    onExitRequest = { manager.cancel() },
                    tvInputMode = tvInputMode,
                )
            }
        }
    }
}
