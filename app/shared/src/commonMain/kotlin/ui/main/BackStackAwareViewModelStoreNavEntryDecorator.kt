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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator

/**
 * 给每个导航条目一份自己的 [ViewModelStore], 并且**只在该条目真正离开返回栈时**销毁它.
 *
 * ## 为什么不用库里的 `rememberViewModelStoreNavEntryDecorator`
 *
 * 它在**条目内容离开组合**时就销毁 store, 而 Nav3 里"被别的页面盖住"同样会离开组合 (只有栈顶条目
 * 在组合里). 于是从详情页返回时, 下层页面拿到的是全新的 store → 全新的 ViewModel:
 *
 * - 搜索页: 用户提交的搜索词只活在 VM 里 (没写回路由), 于是顶部搜索词变空、pager 用空词查 →
 *   "没有找到相关条目", 看起来像结果丢了;
 * - 追番页: 选中的 tab 回到默认;
 * - 各页焦点落点与页面状态对不上;
 * - 主屏: 启动/更新检查的 effect 每次返回都重跑 (同一进程 80 秒跑了 5 次) → 更新气泡反复弹.
 *
 * 它的第二个重载收一个 `removeViewModelStoreOnPop: () -> Boolean`, 但那条路走不通:
 * `ViewModelStoreNavEntryDecoratorDefaults.removeViewModelStoreOnPop()` 反编译出来就是
 * `remember { { true } }` (恒真), 而这个签名拿不到"正在销毁的是哪个条目", 写不出按条目判定的逻辑.
 *
 * ## 判据
 *
 * 与 [rememberPageForegroundNavEntryDecorator] 同一套: 条目手上只有 [NavEntry.contentKey], 拿不到
 * 路由对象, 所以用栈里的路由现造一个空 [NavEntry] 问它的 contentKey (让库自己算, 不照抄它 internal
 * 的默认规则). 内容销毁那一刻返回栈已经更新完毕, 所以"key 还在栈里"= 只是被盖住, store 必须留着;
 * "key 不在栈里"= 真的出栈了, 这时才 clear.
 *
 * 整个 decorator 离开组合 (NavHost 销毁) 时清掉所有 store, 否则那些 VM 会一直挂着.
 *
 * ## 取证 (2026-08-23, 别再从头查一遍)
 *
 * 埋点定层的结论: 根组合没被重建 (`AniAppContent` 全程只 created 一次), 而 per-entry store 在返回
 * 那一刻从 `314586a` 换成 `467b9a5`, `SearchViewModel` 实例随之从 `b781df8` 变成 `558817a`;
 * 同页的 `rememberSaveable` 却保住了 —— 正是这个"saveable 活着、VM 死了"的不对称暴露了病因.
 * 上游 Nav3 迁移 (`e5e8d4ec6`, 2026-08-19) 起就是这么装配的; 基线包 (`30450e8f9`) 一样复现,
 * 与 fork 今天的改动无关.
 */
@Composable
fun <T : Any> rememberBackStackAwareViewModelStoreNavEntryDecorator(
    backStack: List<T>,
): NavEntryDecorator<T> {
    val currentBackStack = rememberUpdatedState(backStack)
    val stores = remember { mutableMapOf<Any, ViewModelStore>() }
    DisposableEffect(Unit) {
        onDispose {
            stores.values.forEach { it.clear() }
            stores.clear()
        }
    }
    return remember {
        NavEntryDecorator { entry ->
            val key = entry.contentKey
            val owner = remember(key) {
                object : ViewModelStoreOwner {
                    override val viewModelStore: ViewModelStore = stores.getOrPut(key) { ViewModelStore() }
                }
            }
            DisposableEffect(key) {
                onDispose {
                    val stillInBackStack = currentBackStack.value.any { route ->
                        NavEntry(route) {}.contentKey == key
                    }
                    if (!stillInBackStack) {
                        stores.remove(key)?.clear()
                    }
                }
            }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                entry.Content()
            }
        }
    }
}
