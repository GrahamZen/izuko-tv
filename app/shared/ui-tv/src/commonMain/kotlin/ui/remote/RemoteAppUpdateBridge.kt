/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.him188.ani.app.ui.foundation.widgets.Toaster

/**
 * 下载并安装新版本、测试包 (安卓上是手机控制台「应用更新」那一套, 见 androidMain 的 RemoteAppUpdate). 公共代码只用这几样:
 * 通知中心「我的反馈」的「安装测试包」与取消、动作面板顶上下载安装那一格、播放器报在不在播放.
 * iOS 上装不了安装包: 状态一直是空闲, 别的什么都不做.
 */
internal expect object RemoteAppUpdate {
    /** 这一次下载并安装走到哪了. */
    val status: StateFlow<AppInstallStatus>

    /** 正在播放 (TvAppUpdateHost 报): 电视上发起的安装下好了等退出播放再弹安装确认. */
    val playerShowing: MutableStateFlow<Boolean>

    /** 通知中心「我的反馈」里按「安装测试包」. 提示经 [toaster]. */
    suspend fun installTestPackage(url: String, sha256: String?, issue: Int, toaster: Toaster)

    /** 停下正在下载、核对、等着装的那一次. */
    fun cancel()

    /** 没装上、安装包还在时不重新下载再装一次; 装不了时返回 false. */
    fun retryInstall(): Boolean
}
