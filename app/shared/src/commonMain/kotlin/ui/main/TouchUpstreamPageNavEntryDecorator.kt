/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import me.him188.ani.app.navigation.NavRoutes
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.subject.episode.forTouchUpstreamUi

/**
 * 触屏设备上的电视界面 (平板装电视包、iPad, 见 [LocalTvTouchInputEnabled]) 里没有电视版的页面 ([isTouchUpstreamPage]: 缓存、人物、
 * 观看记录、数据源编辑等) 照上游的指针设备写法: 页面子树换回指针设备的界面行为 ([forTouchUpstreamUi]), 不画电视焦点框、不抢初始焦点.
 * 有电视版的页面与遥控器形态原样不动; 播放页自己换 (见 ProvideTouchPlayerUiBehavior).
 *
 * 条目只带 contentKey, 路由对象从 [backStack] 里按同一个 contentKey 找回来 (同 [rememberPageForegroundNavEntryDecorator]),
 * 条目第一次组合时定下, 之后出栈途中 (返回栈里已经没有它) 也不变.
 */
@Composable
fun rememberTouchUpstreamPageNavEntryDecorator(backStack: List<NavRoutes>): NavEntryDecorator<NavRoutes> {
    val currentBackStack = rememberUpdatedState(backStack)
    return remember {
        NavEntryDecorator { entry ->
            val upstream = remember(entry.contentKey) {
                currentBackStack.value.firstOrNull { NavEntry(it) {}.contentKey == entry.contentKey }?.isTouchUpstreamPage() == true
            }
            if (upstream) ProvideTouchUpstreamUi { entry.Content() } else entry.Content()
        }
    }
}

/**
 * 触屏设备上的电视界面里 (见 [LocalTvTouchInputEnabled]), [content] 换回上游的指针设备界面行为 ([forTouchUpstreamUi]); 别处原样.
 * 给没有电视版的上游界面用: 这些单独的页面 (见 [rememberTouchUpstreamPageNavEntryDecorator]), 以及主壳里的缓存管理页.
 * 外壳带来的样式 (两栏的详情侧铺在页面底上、顶栏不放头像) 按 immersiveShell 走, 换了也不变.
 */
@Composable
fun ProvideTouchUpstreamUi(content: @Composable () -> Unit) {
    if (!LocalTvTouchInputEnabled.current) {
        content()
        return
    }
    CompositionLocalProvider(LocalAniUiBehavior provides LocalAniUiBehavior.current.forTouchUpstreamUi(), content = content)
}

/** 没有电视版、在触屏上照上游写法的页面. */
internal fun NavRoutes.isTouchUpstreamPage(): Boolean = when (this) {
    is NavRoutes.PlaybackHistory,
    is NavRoutes.Caches,
    is NavRoutes.CacheDetail,
    is NavRoutes.PersonDetail,
    is NavRoutes.CharacterDetail,
    is NavRoutes.SubjectCaches,
    is NavRoutes.EditMediaSource,
    is NavRoutes.TorrentPeerSettings -> true

    else -> false
}
