/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 当前页面的「强制刷新」动作注册口, 给长按返回的快捷菜单里那颗「刷新本页」用.
 *
 * 强刷原本挂在播放键长按上 (tvPlayKeyForceRefresh); 播放键长按改成开动作面板后,
 * 刷新挪进快捷菜单 —— 菜单是全局的 (组合在应用根部), 页面的刷新动作只能自下而上注册进来
 * (CompositionLocal 只能往下流). 模式与 [TvKeyLongPressHost] 的注册栈一致: 页面在场时注册,
 * 离开组合自动注销, 菜单读栈顶; 没人注册时菜单不显示该项.
 *
 * 只在"刷新有意义"的页面上注册 (追番 / 新番时间表 / 探索页): 这些页面的数据是一小时一刷的
 * 定时拉取, 用户想立刻看到更新时没有别的入口.
 */
@Stable
class TvPageRefreshHost {
    // mutableStateListOf: 菜单在组合里读 current 决定「刷新本页」显不显示, 得可观察
    private val entries = mutableStateListOf<() -> Unit>()

    /** 当前页面的刷新动作; null = 没有页面注册 (菜单不显示「刷新本页」). */
    val current: (() -> Unit)? get() = entries.lastOrNull()

    fun register(action: () -> Unit): () -> Unit {
        entries.add(action)
        return { entries.remove(action) }
    }
}

/** 由应用根部 (TV 形态装配处) 提供; 其余形态为 null, [TvPageRefreshHandler] 退化为空操作. */
val LocalTvPageRefreshHost = staticCompositionLocalOf<TvPageRefreshHost?> { null }

/** 注册本页的强制刷新动作, 生命周期跟随组合. */
@Composable
fun TvPageRefreshHandler(onRefresh: () -> Unit) {
    val host = LocalTvPageRefreshHost.current ?: return
    val currentAction by rememberUpdatedState(onRefresh)
    DisposableEffect(host) {
        val unregister = host.register { currentAction() }
        onDispose { unregister() }
    }
}
