/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.focus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/*
 * 把旧网格焦点协调器时代那两件**与送焦机制无关**的东西接到 [TvGridFocusState] 上.
 *
 * 迁移到事件驱动焦点之后, 网格的"送焦"部分整体由 [TvGridFocusState] 接管 (锚点跟着目标卡走,
 * 滚动一次 + 附着事件送达, 不再轮询). 旧实现还捎带了两件独立的能力, 上游 PR #3271
 * 没有等价物, 而它们解决的是真问题, 因此在这里保留事件驱动版本.
 */

/**
 * 竖版海报网格的方向键路由 —— [Modifier.gridKeyNavigation] 的 [TvGridFocusState] 版本,
 * 按键语义**逐字照搬**, 只把落点请求的出口换成 [TvGridFocusState.focusItem].
 *
 * 为什么这条路由不能省 (原文照录): 上/下键显式同列导航, 不交给默认方向搜索 —— 吸顶后上一行在
 * 视口外未组合, 越界组合只补出前一个 item (上一行最后一张), 焦点必然斜跳; 吸顶滚动进行中方向
 * 搜索又按瞬时几何位置挑候选, 偶尔斜跳到别的列.
 *
 * 与旧版的唯一差别: **不再自己上报"用户在导航"**. 事件驱动那套的上报口是挂在
 * 页面根上的 [Modifier.tvFocusNavSignal] (它同时清掉 scope 的在途请求), 这里再报一次只是重复计数.
 * 因此**页面根必须装 [Modifier.tvFocusNavSignal]**, 否则在途落点不会因用户按键而让路.
 *
 * 次序上也因此是安全的: 根上的 navSignal 作为外层 onPreviewKeyEvent 先收到并递增代数, 之后
 * [TvGridFocusState.focusItem] 才把自己的基线设成新代数 —— 不会被自己这一次按键判成"用户接管".
 *
 * **刻意不处理播放键** (理由同 [Modifier.gridKeyNavigation]): 本路由只看 KeyDown, 而播放键按下
 * 那一刻还不知道是短按还是长按; 要"聚焦卡直达播放"在页面根挂 `tvPlayKeyShortPress`.
 */
fun Modifier.tvGridKeyNavigation(
    state: TvGridFocusState,
    focusedIndex: () -> Int,
    itemCount: () -> Int,
    columns: () -> Int,
    onTopRowUp: () -> Boolean,
    enabled: () -> Boolean = { true },
    extraKeys: ((event: KeyEvent, focusedIndex: Int, columns: Int, itemCount: Int) -> Boolean)? = null,
): Modifier = onPreviewKeyEvent { event ->
    if (!enabled()) return@onPreviewKeyEvent false
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val focused = focusedIndex()
    val count = itemCount()
    if (focused < 0 || count == 0) return@onPreviewKeyEvent false
    val cols = columns().coerceAtLeast(1)
    when (event.key) {
        Key.DirectionUp ->
            if (focused < cols) {
                onTopRowUp()
            } else {
                state.focusItem(focused - cols)
                true
            }

        Key.DirectionDown -> {
            val next = focused + cols
            when {
                next < count -> {
                    state.focusItem(next)
                    true
                }

                // 末行不满时同列下方没有卡: 落到最后一张
                focused / cols < (count - 1) / cols -> {
                    state.focusItem(count - 1)
                    true
                }

                // 已是最后一行: 消费掉, 防止焦点斜跳
                else -> true
            }
        }

        else -> extraKeys?.invoke(event, focused, cols, count) ?: false
    }
}

/**
 * 过渡期的隐形焦点驻留点 (1dp, 不可见, 无聚焦样式), 服务于 [TvGridFocusState].
 *
 * **上游 PR #3271 没有这个东西, 但它解决的问题真实存在, 所以保留.** 上游 TvFocusGrid 的文件头
 * 承认了同一个现象 ——「旧网格聚焦卡销毁瞬间焦点会跌落到布局首个可聚焦节点」—— 但只处理它的
 * *副作用* (要求调用方读 [TvGridFocusState.switching] 冻结"聚焦即选中"), 没有处理那一下**可见的
 * 焦点闪跳**. 而事件驱动并不能消掉这个空档: 换天/换 tab 时旧卡先销毁, 新卡要等数据组合出来,
 * 中间焦点无处可去, 于是被焦点系统重分配 (见 FocusTargetNode.onReset/onDetach —— 源码明确**不**
 * 把焦点交给焦点祖先), 实测会闪到页面顶部的标签行上. 先把焦点钉在本节点上就没有这个空档,
 * 目标卡到位后焦点自然离开.
 *
 * 用法: 换数据**之前**先 [TvGridFocusState.focusItem] / [TvGridFocusState.focusRowEdge]
 * (让 [TvGridFocusState.switching] 成立, 锚点才可聚焦), 再 `requester.requestFocus()`, 然后切数据.
 *
 * **只在过渡期间可聚焦**, 平时对方向搜索完全不可见 —— 否则一个不可见节点会成为方向键的落点
 * 候选, 表现为"焦点圈不见了但还能走".
 *
 * 摆放位置: 放在**网格之外** (如网格上方), 不要放进网格内部 —— 行内左右键是交回默认方向搜索的,
 * 压在首卡位置上的锚点会成为候选.
 *
 * @param requester 调用方持有的请求器 (用它把焦点钉过来)
 * @param switching 过渡是否进行中; 传 [TvGridFocusState.switching]
 * @param extraCanFocus 除过渡以外额外允许聚焦的条件 (如等条目离开本 tab 期间)
 * @param onStranded 焦点仍在锚点上但锚点已不再允许聚焦时调用 (在途请求被取消/放弃):
 *   焦点即将被系统收走, 调用方补一个自己的落点 (如聚焦选中的标签/日期胶囊)
 */
@Composable
fun TvFocusTransitAnchor(
    requester: FocusRequester,
    switching: () -> Boolean,
    modifier: Modifier = Modifier,
    extraCanFocus: () -> Boolean = { false },
    onStranded: () -> Unit = {},
) {
    var hasFocus by remember { mutableStateOf(false) }
    // canFocus 变 false 会让 Compose 在同一轮焦点失效处理中先移走焦点. 原实现只观察
    // (hasFocus, canFocus), 协程常常直接从 (true,true) 跳到 (false,false), 永远看不到
    // (true,false), 因而漏掉 onStranded. 在失焦回调里把这条因果单独记成事件代数.
    var strandedEpoch by remember { mutableIntStateOf(0) }
    val canFocus = { switching() || extraCanFocus() }
    val onStrandedState by rememberUpdatedState(onStranded)
    // snapshotFlow 既保留焦点/可聚焦状态日志, 又可靠消费"因取消而失焦"事件. 事件代数避免
    // 首次收集晚于失焦时丢事件, 也避免一次失焦重复补落点.
    LaunchedEffect(Unit) {
        var handledStrandedEpoch = 0
        snapshotFlow { Triple(hasFocus, canFocus(), strandedEpoch) }.collect { (focused, allowed, epoch) ->
            if (epoch > handledStrandedEpoch) {
                handledStrandedEpoch = epoch
                onStrandedState()
            }
        }
    }
    Box(
        modifier
            .size(TV_TRANSIT_ANCHOR_SIZE)
            .focusRequester(requester)
            .focusProperties { this.canFocus = canFocus() }
            .onFocusChanged {
                val lostWhileDisallowed = hasFocus && !it.isFocused && !canFocus()
                hasFocus = it.isFocused
                if (lostWhileDisallowed) strandedEpoch++
            }
            // 焦点驻留期间吞掉方向键与确认键: 锚点不是真正的落点, 交回默认方向搜索的落点不可
            // 预测 (长按方向键跨 tab/跨天时连发尤其明显, 实测会闪到标签行), 确认键则会误触.
            // 过渡只有一两百毫秒, 期间丢掉的连发按键正好让"一次按住走一格"更可控
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown && event.key in TV_TRANSIT_ANCHOR_SWALLOWED_KEYS
            }
            .focusable(),
    )
}

/** 隐形锚点的尺寸: 不能为 0 —— 零尺寸节点在部分版本上会被焦点系统跳过. */
private val TV_TRANSIT_ANCHOR_SIZE = 1.dp

/** 焦点驻留在隐形锚点期间要吞掉的按键 (返回键不在内: 它走返回分发器, 由页面的分层规则处理). */
private val TV_TRANSIT_ANCHOR_SWALLOWED_KEYS = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
)

/**
 * 独立窗口 (Dialog / Popup) 打开时的初始落点: 挂在**该拿到焦点的那个节点**上, 一行搞定.
 *
 * 弹窗自身不分配焦点, 焦点驱动的界面必须显式请求; 而弹窗组合刚建立那一刻 requestFocus 会被
 * 焦点系统**静默拒绝** (节点还没附着), 旧写法因此需要逐帧重发请求.
 *
 * 事件驱动之下不需要重试: 本 modifier 把节点登记成锚点, 请求悬挂到**附着事件**再送, 一次到位.
 * 实测动作面板 (TvMainScreenLayout 的长按返回面板) 用同一机制在真机上没有问题, 说明对 Dialog
 * 这一族够用 —— 那一族原先重试的理由都是同一个"组合与焦点分配有时序竞争".
 *
 * 不装 [Modifier.tvFocusNavSignal]: 请求在锚点附着那一刻就消化掉了 (与本次组合同一帧),
 * 中间没有留给用户按键的窗口, 也就没有抢焦点的可能.
 *
 * @param enabled false 时什么都不做 (如非焦点驱动的平台, 或本弹窗此刻不该抢焦点)
 */
@Composable
fun Modifier.tvWindowInitialFocus(enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val scope = rememberTvFocusScope()
    LaunchedEffect(scope) { scope.request(TvWindowInitialFocusKey) }
    return this.tvFocusAnchor(scope, TvWindowInitialFocusKey)
}

/** [Modifier.tvWindowInitialFocus] 的锚点 key: 每个弹窗一个私有 scope, 所以同一个 key 不会撞. */
private val TvWindowInitialFocusKey = TvFocusKey("tvWindowInitialFocus")
