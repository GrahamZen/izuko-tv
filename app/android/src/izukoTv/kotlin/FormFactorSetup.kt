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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.him188.ani.android.tv.InstallTvPageVariants
import me.him188.ani.android.tv.TvHomeChannels
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.ui.foundation.AniUiBehavior
import me.him188.ani.app.ui.tv.TvAniUiBehavior
import org.koin.android.ext.android.getKoin

/*
 * 形态适配接缝 (tv 变体): 与 src/phone 下的同名文件一一对应, MainActivity 只调用它.
 * 遥控器形态的全部差异都收在这里 —— 界面行为开关 + 页面变体 + 主屏频道初始化.
 */

/** 遥控器设备的界面行为. */
internal val formFactorUiBehavior: AniUiBehavior get() = TvAniUiBehavior

/** 把遥控器形态的页面实现注入共享页面的变体插槽. [aniNavigator] 供「长按返回回主页」兜底用. */
@Composable
internal fun InstallFormFactorUi(aniNavigator: AniNavigator, content: @Composable () -> Unit) =
    InstallTvPageVariants(aniNavigator, content)

/**
 * 主屏预览频道 (热门动画 / 继续观看): 延迟到启动高峰之后开始, 之后"继续观看"行一直跟着收藏库变化重写
 * (只写一次的话, 用户把番标成"看过"后主屏还会挂着它). 随 activity 销毁一起结束.
 */
internal fun onFormFactorActivityCreated(activity: ComponentActivity) {
    activity.lifecycleScope.launch {
        delay(TV_HOME_CHANNELS_DELAY_MILLIS)
        // 需要 activity context 才能弹出添加频道的系统确认框
        TvHomeChannels.keepUpdated(activity, activity.getKoin())
    }
}

private const val TV_HOME_CHANNELS_DELAY_MILLIS = 10_000L
