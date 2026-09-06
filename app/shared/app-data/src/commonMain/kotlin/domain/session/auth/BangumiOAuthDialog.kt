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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

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
                }
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    val isLoading by browser.isLoading.collectAsState()
                    if (isLoading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                // 电视上这个视图自带遥控器虚拟光标 (见 CaptchaBrowser.View 的说明): 方向键移动、
                // 确认键点击、返回键退出 —— 授权页要填账号密码, 没有光标根本点不到输入框
                browser.View(
                    Modifier.fillMaxSize(),
                    onExitRequest = { manager.cancel() },
                )
            }
        }
    }
}
