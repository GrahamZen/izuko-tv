/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.him188.ani.android.tv.InstallTvPageVariants
import me.him188.ani.android.tv.RemoteKeepAliveService
import me.him188.ani.android.tv.TvHomeChannels
import me.him188.ani.android.tv.TvOnboardingGate
import me.him188.ani.android.tv.TvStartupLogoPaletteMirror
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.ui.foundation.AniUiBehavior
import me.him188.ani.app.ui.foundation.tv.TvPageBackgroundTheme
import me.him188.ani.app.ui.main.TvStartupLogo
import me.him188.ani.app.ui.main.TvStartupLogoHost
import me.him188.ani.app.ui.main.tvStartupLogoColors
import me.him188.ani.app.ui.remote.TvRemoteControl
import me.him188.ani.app.ui.tv.TvAniUiBehavior
import org.koin.android.ext.android.getKoin

/*
 * 形态适配接缝 (tv 变体): 与 src/phone 下的同名文件一一对应, MainActivity 只调用它.
 * 遥控器形态的全部差异都收在这里 —— 界面行为开关 + 页面变体 + 主屏频道初始化.
 */

/** 遥控器设备的界面行为. */
internal val formFactorUiBehavior: AniUiBehavior get() = TvAniUiBehavior

/**
 * 把遥控器形态的页面实现注入共享页面的变体插槽, 并换上电视端的页面底色 (浅色下整屏同海报墙的浅灰, 见 [TvPageBackgroundTheme]).
 * [aniNavigator] 供「长按返回回主页」兜底用.
 */
@Composable
internal fun InstallFormFactorUi(aniNavigator: AniNavigator, content: @Composable () -> Unit) =
    TvPageBackgroundTheme {
        // 启动页的颜色抄一份, 下次冷启动主题读出来之前的占位用 (见 FormFactorStartupPlaceholder)
        val context = LocalContext.current
        val logoColors = tvStartupLogoColors()
        LaunchedEffect(logoColors) { TvStartupLogoPaletteMirror.write(context, logoColors) }
        InstallTvPageVariants(aniNavigator, content)
    }

/**
 * 应用状态 (主题等设置) 读出来之前画的东西: 冷启动一打开应用就先画上启动页 (颜色用上次记下的, 见 [TvStartupLogoPaletteMirror]),
 * 读出来之后根部 (InstallTvPageVariants) 接着盖同一份 (见 [TvStartupLogoHost]).
 */
@Composable
internal fun FormFactorStartupPlaceholder() {
    val context = LocalContext.current
    val logo = remember { TvStartupLogoHost.coldStart(onboarding = TvOnboardingGate.isPending(context)) } ?: return
    val colors = remember { TvStartupLogoPaletteMirror.read(context) }
    TvStartupLogo(logo, colors)
}

/**
 * 主屏预览频道 (热门动画 / 继续观看): 延迟到启动高峰之后开始, 之后"继续观看"行一直跟着收藏库变化重写
 * (只写一次的话, 用户把番标成"看过"后主屏还会挂着它). 随 activity 销毁一起结束.
 */
internal fun onFormFactorActivityCreated(activity: ComponentActivity) {
    // Web 控制台随 Activity 起, 不等界面组合 (息屏时被重启的进程要到亮屏才组合); 「退出 Ani 后保留」的常驻服务在本模块
    val app = activity.applicationContext
    TvRemoteControl.keepAliveService = { on -> RemoteKeepAliveService.set(app, on) }
    TvRemoteControl.onActivityCreated(activity)
    activity.lifecycleScope.launch {
        delay(TV_HOME_CHANNELS_DELAY_MILLIS)
        // 需要 activity context 才能弹出添加频道的系统确认框
        TvHomeChannels.keepUpdated(activity, activity.getKoin())
    }
}

private const val TV_HOME_CHANNELS_DELAY_MILLIS = 10_000L
