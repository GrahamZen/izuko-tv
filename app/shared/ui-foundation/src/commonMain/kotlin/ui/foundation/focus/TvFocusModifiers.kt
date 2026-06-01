/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.focus

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import me.him188.ani.app.ui.foundation.isAutoRepeat

/*
 * TV 焦点框架的使用侧 API: 页面用这些 Modifier 扩展声明焦点层级与操作方式,
 * 调度逻辑集中在 [TvFocusScope]. 典型页面:
 *
 * ```
 * private enum class HomeFocus : TvFocusKey { Play, Cards, Rail }
 *
 * val focus = rememberTvFocusScope()    // 解析器由工厂装好
 * focus.InitialFocus(HomeFocus.Play)    // 进页初始焦点
 *
 * Modifier.tvFocusNavSignal(focus)                        // 页面根: 用户按键即让路 (必装)
 * Modifier.tvFocusAnchor(focus, HomeFocus.Play)           // 标注锚点
 * Modifier.tvFocusLink(focus, down = HomeFocus.Cards)     // 显式方向链接
 * focus.request(HomeFocus.Play)                           // 程序化聚焦 (返回分层等)
 * ```
 */

/**
 * 标注本节点为 [key] 锚点: 挂 FocusRequester + **节点附着/脱离上报** (事件驱动送焦的
 * 核心: 悬挂中的 request 在锚点附着瞬间送达, 见 [TvFocusScope]) + 焦点得失上报.
 *
 * 焦点上报默认用 hasFocus (含子树): 锚点可以是容器 (如轮播行), 也可以是叶子按钮.
 * 当可聚焦节点同时还是其它焦点节点的祖先、而 key 只表示节点自身时, 传
 * [includeDescendants] = false (如播放器纯视频根节点).
 */
fun Modifier.tvFocusAnchor(
    scope: TvFocusScope,
    key: TvFocusKey,
    includeDescendants: Boolean = true,
): Modifier = this
    .focusRequester(scope.requesterOf(key))
    .then(TvFocusAnchorAttachElement(scope, key))
    .onFocusChanged {
        scope.onAnchorFocusChanged(key, if (includeDescendants) it.hasFocus else it.isFocused)
    }

/** 锚点附着追踪节点: 把 Compose 缺失的"节点已附着"事件上报给 [TvFocusScope]. */
private data class TvFocusAnchorAttachElement(
    val scope: TvFocusScope,
    val key: TvFocusKey,
) : ModifierNodeElement<TvFocusAnchorAttachNode>() {
    override fun create() = TvFocusAnchorAttachNode(scope, key)

    override fun update(node: TvFocusAnchorAttachNode) = node.update(scope, key)

    override fun InspectorInfo.inspectableProperties() {
        name = "tvFocusAnchorAttach"
        properties["key"] = key
    }
}

private class TvFocusAnchorAttachNode(
    private var scope: TvFocusScope,
    private var key: TvFocusKey,
) : Modifier.Node() {
    override fun onAttach() = scope.onAnchorAttached(key)

    override fun onDetach() = scope.onAnchorDetached(key)

    fun update(newScope: TvFocusScope, newKey: TvFocusKey) {
        if (newScope === scope && newKey == key) return
        if (isAttached) scope.onAnchorDetached(key)
        scope = newScope
        key = newKey
        if (isAttached) scope.onAnchorAttached(key)
    }
}

/**
 * 显式方向链接: 声明方向的焦点搜索直达目标锚点, 不走空间搜索.
 *
 * TV 上跨大段不可聚焦内容 (标题/指示器/渐变区) 的空间焦点搜索不可靠 (落错或落空),
 * 边缘元素应显式声明去向 —— 这是上游 PR 与本项目实测一致的结论.
 */
fun Modifier.tvFocusLink(
    scope: TvFocusScope,
    up: TvFocusKey? = null,
    down: TvFocusKey? = null,
    left: TvFocusKey? = null,
    right: TvFocusKey? = null,
): Modifier = focusProperties {
    up?.let { this.up = scope.requesterOf(it) }
    down?.let { this.down = scope.requesterOf(it) }
    left?.let { this.left = scope.requesterOf(it) }
    right?.let { this.right = scope.requesterOf(it) }
}

/**
 * 用户交互信号 (挂页面根节点): 方向键或确认键按下时上报 [TvFocusScope.notifyUserNavigation],
 * 放弃在途的焦点解析 —— 否则迟到的附着/转场事件会把用户刚移走的焦点抢回目标锚点. 不消费事件.
 * 确认键也算: 点击 (如侧边栏条目把焦点送回内容区) 引发的焦点变化同样不该被在途请求抢回.
 *
 * 每个持有 [TvFocusScope] 的页面都应在根上挂本 modifier.
 */
fun Modifier.tvFocusNavSignal(scope: TvFocusScope): Modifier = onPreviewKeyEvent { event ->
    // **系统按住连发不算新的用户介入** (fork 加的守卫; 上游只在它的快捷键 modifier 里有, 这里漏了).
    //
    // 漏了会让"长按方向键驱动的整批切换"自己把自己取消: 换天/换分类是由按键触发的 —— 第一发
    // 按下发出送焦请求, 50ms 后的第二发连发在这里递增代数, 网格的观察者看到"代数变了"就
    // cancel(), 而此刻焦点正钉在过渡用的隐形锚点上, 锚点随即不可聚焦 -> onStranded -> 焦点被
    // 送回标签/日期行, 后续连发就变成在标签行里横向导航. 真机实测: 时间表长按右键换天时焦点
    // 跳到日期行并开始左右切日期.
    if (event.type == KeyEventType.KeyDown && event.key in TV_USER_INTERACTION_KEYS) {
        // 诊断: 连发守卫是否真的生效 —— isAutoRepeat 是 expect/actual, Android 读
        // nativeKeyEvent.repeatCount; 电视遥控器长按到底会不会带 repeatCount>0 未经实测
        if (event.isAutoRepeat != true) scope.notifyUserNavigation()
        // 连发也要记一笔 (只推进代数, 不取消请求): 用来分辨"焦点是用户按过去的"还是
        // "组合销毁时跌落过去的", 见 [TvFocusScope.userInputGeneration]
        scope.notifyUserInput()
    }
    false // 只旁听, 不消费
}

private val TV_USER_INTERACTION_KEYS = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
)

