/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.download

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf

/**
 * 缓存列表的遥控器焦点原语 (fork).
 *
 * 上游 2026-09-19 把 `ui-cache` 整个重写成了 `ui-download`, 这几个符号原先在 `ui/cache/subject/`
 * 里, 随之被删. **生产方 (写接力棒的那些点击逻辑) 还没在新模块上重建**, 这里先把定义搬过来:
 * 消费方 (见 [DownloadRow]) 是 fork 自己的单列焦点适配, 它要先编得过。
 * 重做时见 [[project-tv-cache-page-focus]]。
 */

/**
 * 换行接力的补请求窗口 (渲染帧数). 只在换行那一刻起算, 不是轮询 —— 换行事件本身是触发条件.
 * 给到 ~20 帧是因为缓存建出来与行替换、以及"全部暂停"这类新控件出现并抢到默认焦点之间隔了几帧.
 */
internal const val REFOCUS_FRAMES = 20

/**
 * 本页此刻有没有"会抢走焦点的弹窗"在前台 (数据源选择 / 存储位置选择).
 *
 * 做成 CompositionLocal 而不是逐层传参: 剧集行分散在好几处, 挨个加形参要动一串签名,
 * 而这个值全页只有一个.
 */
internal val LocalCachePopupOpen = compositionLocalOf { false }

/**
 * 跨缓存按钮共享的"当前持有焦点的按钮 key". 用于区分"焦点被清空"(归属为 null, 需夺回)
 * 与"用户导航到了别的按钮"(归属为别的 key, 不可抢).
 * 仅在缓存列表处提供; 未提供时为 null (夺回逻辑退化为不跨按钮协调).
 */
internal val LocalCacheFocusOwner = compositionLocalOf<MutableState<Any?>?> { null }

/**
 * 换行接力棒的内容: 哪一集, 以及**该由哪一种行接住**.
 *
 * 方向是必须的: 交出接力棒的那一刻, 旧行还在、而且正持有焦点. 若只按 episodeId 认领, 旧行会
 * 在下一帧就把自己刚交出去的棒子接回来 (它 isFocused 当场成立), 等新行出现时棒子早没了 ——
 * 真机症状是"选完数据源关掉弹窗, 焦点跑到刚冒出来的『全部暂停』上" (2026-08-23).
 *
 * @param toCached `true` = 交给已缓存行 (按下载), `false` = 交给未缓存行 (删掉缓存).
 */
@Immutable
internal data class CacheRowRefocus(val episodeId: Int, val toCached: Boolean)

/**
 * 「这一集的行马上要被换掉, 新行请把焦点接住」—— `null` 表示没人在等.
 *
 * 一集有没有缓存决定它是哪一种行, 而且是列表里 key 都不同的两个 item. 按下载 / 删除会让这一集
 * 从一种行变成另一种, **原来持有焦点的节点连同它自己那套夺回逻辑一起被销毁**, 新行不接的话
 * 遥控器就此失灵. 所以接力棒要放在比行更长命的地方 —— 页面级的这个 state 上,
 * 按 episodeId 认领 (cacheId 会变, episodeId 不会).
 */
internal val LocalCacheRowRefocus = compositionLocalOf<MutableState<CacheRowRefocus?>?> { null }
