/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import me.him188.ani.app.data.models.preference.VideoScaffoldConfig
import me.him188.ani.app.ui.foundation.AniUiBehavior
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled

/**
 * 触屏设备上的电视界面 (平板装电视包、iPad, 见 [LocalTvTouchInputEnabled]) 播放页用共享的那一版 (没装 [EpisodeScreenVariant]),
 * 它是照指针设备写的: 播放页子树里换回指针设备的界面行为 ([forTouchUpstreamUi]). 别的上游页面同理, 见 TouchUpstreamPageNavEntryDecorator.
 * 遥控器形态, 或触屏上装了播放页变体 (调试对照电视播放器) 时原样不动.
 */
@Composable
fun ProvideTouchPlayerUiBehavior(content: @Composable () -> Unit) {
    if (!LocalTvTouchInputEnabled.current || LocalEpisodeScreenVariant.current != null) {
        content()
        return
    }
    CompositionLocalProvider(LocalAniUiBehavior provides LocalAniUiBehavior.current.forTouchUpstreamUi(), content = content)
}

/**
 * 上游页面 (共享版播放页、没有电视版的页面) 在触屏上的界面行为: 不按焦点驱动导航 (不画电视焦点框、不抢初始焦点,
 * 选完源关不关侧边栏照触屏那一份设置, 见 [hideSelectorOnSelectForCurrentUi])、侧边栏与弹窗带关闭按钮、侧边栏不改成居中弹窗、
 * 选中态与底部弹窗宽度用默认的.
 * 外壳那几项 (播放页强制深色、沉浸式系统栏、退出后保留播放会话) 照电视界面的.
 */
fun AniUiBehavior.forTouchUpstreamUi(): AniUiBehavior = copy(
    focusDrivenNavigation = AniUiBehavior.Default.focusDrivenNavigation,
    showDismissButtons = AniUiBehavior.Default.showDismissButtons,
    panelsAsCenteredDialogs = AniUiBehavior.Default.panelsAsCenteredDialogs,
    strongSelectionColors = AniUiBehavior.Default.strongSelectionColors,
    sheetMaxWidthFraction = AniUiBehavior.Default.sheetMaxWidthFraction,
)

/**
 * 选中一个数据源后要不要顺手关掉选源面板: 遥控器形态读 [VideoScaffoldConfig.hideSelectorOnSelect],
 * 指针形态 (触屏上的共享版播放器) 读 [VideoScaffoldConfig.touchHideSelectorOnSelect].
 */
@Composable
fun VideoScaffoldConfig.hideSelectorOnSelectForCurrentUi(): Boolean =
    if (LocalAniUiBehavior.current.focusDrivenNavigation) hideSelectorOnSelect else touchHideSelectorOnSelect
