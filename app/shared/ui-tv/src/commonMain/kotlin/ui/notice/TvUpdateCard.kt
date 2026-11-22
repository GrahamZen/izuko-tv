/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.him188.ani.app.domain.foundation.VersionExpiryService
import me.him188.ani.app.ui.foundation.animation.ProvideAniMotionCompositionLocals
import me.him188.ani.app.ui.update.AppUpdateViewModel
import me.him188.ani.app.ui.update.LocalUpdateNotifierAtRoot
import me.him188.ani.app.ui.update.UpdateNotifier
import org.koin.mp.KoinPlatform

/**
 * 电视上右下角那张卡 (新版本的下载卡与通知气泡, 见 [UpdateNotifier]) 挂在界面最外层, 哪一页都在: 在设置里打开的通知中心按「自动更新」
 * 当场开始下载, 走到哪一页都看得到进度. 外面提供 [LocalUpdateNotifierAtRoot], 首页就不再挂一份.
 * [inPlayer]: 播放时不画、不自动安装 (下载照常), 退出播放再出来、再装. 版本过期时强制查一次 (同首页那一处, 关了自动检查也查).
 * 这一层在页面框架 (AniAppContent) 外面, 动画参数要自己提供 ([ProvideAniMotionCompositionLocals]), 不然卡一组合就抛异常.
 */
@Composable
fun BoxScope.TvUpdateCard(inPlayer: Boolean) {
    val viewModel = viewModel { AppUpdateViewModel() }
    val expiry = remember { KoinPlatform.getKoin().get<VersionExpiryService>() }
    val expired by expiry.state.collectAsStateWithLifecycle(null)
    LaunchedEffect(expired) {
        if (expired != null) viewModel.startCheckLatestVersion(null)
    }
    ProvideAniMotionCompositionLocals {
        UpdateNotifier(viewModel, suspended = inPlayer)
    }
}
