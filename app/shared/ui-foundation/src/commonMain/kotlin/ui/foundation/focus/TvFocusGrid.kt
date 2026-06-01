/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.focus

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.warn

/*
 * 网格"聚焦第 N 项 / 跨数据源对应位置"原语 —— 协议都在本文件内, 全事件驱动 (§14.4-8):
 *
 * 1. 页面 [rememberTvGridFocus] 创建状态 (挂在页面的 TvFocusScope 上); 创建处同时装有
 *    交互取消观察: pending 在途时用户再按键 (快照事件) 即取消. **另有 4 秒超时兜底**
 *    (fork 加的, 见 [TvGridFocusState.SendFocusEffect]): 本仓库有三处把"在途"当闸门用,
 *    没有超时的话闸门会永久锁死.
 * 2. 每张网格卡挂 [tvGridFocusItem]: 追踪当前聚焦下标 + "待聚焦目标"的动态锚点
 *    (目标下标钳到列表末项; 送达即清 pending —— 焦点事件).
 * 3. 网格组合内装 [TvGridFocusState.SendFocusEffect]: pending 出现后等数据就绪
 *    (快照事件) -> 目标滚进视口 -> [TvFocusScope.request] (锚点附着事件送达).
 * 4. 程序化聚焦第 N 项: [TvGridFocusState.focusItem]; 跨数据源 (相邻 tab / 相邻一天) 的
 *    "对应位置" 用 [TvGridFocusState.focusRowEdge] —— 目标网格的列数可能与源不同, 所以只给
 *    (行, 进入方向), 由 [TvGridFocusState.SendFocusEffect] 拿到目标布局后才解析成下标.
 *    **边缘按键本身由各页自己路由** (追番页跨 tab / 时间表跨天的边界语义各不相同, 时间表还要
 *    按"时间是一条线"接行首行末), 框架不再提供通用的边缘键 modifier.
 *
 * pending 的四个出口: 目标聚焦 (清) / 用户再交互 (取消) / 调用方确定目标不会出现
 * (如分页确定空列表, 调 [TvGridFocusState.cancel]) / 超时收摊 (fork 加的). 切换期间调用方应冻结"聚焦即选中"
 * 类副作用 (读 [TvGridFocusState.switching]): 旧网格聚焦卡销毁瞬间焦点会跌落到布局
 * 首个可聚焦节点, 其副作用会抢走刚选的目标 (TV 模拟器实测: 不冻结会连跳两个分类).
 */

/**
 * 网格焦点状态 (聚焦第 N 项 / 边缘切换). 经 [rememberTvGridFocus] 创建; 协议见文件头.
 */
@Stable
class TvGridFocusState internal constructor(internal val scope: TvFocusScope) {
    /** "待聚焦目标"的动态锚点 key (身份唯一, 不与页面 enum 冲突). */
    internal val entryKey: TvFocusKey = object : TvFocusKey {
        override fun toString(): String = "TvGridFocusEntry"
    }

    /** 当前聚焦卡下标 (由 [tvGridFocusItem] 上报; 仅按键判定读, 非 snapshot 状态). */
    internal var focusedIndex: Int = -1

    /**
     * 最后一个报告"我持着焦点"的卡节点 (见 [TvGridItemPresenceNode]).
     *
     * 补射的前提是**焦点真的悬空了**. 只比下标答不了这个问题 —— 两批卡片并存 (跨天淡出、
     * 分页换 generation) 时下标必然撞车, 而长按连发期间"用户接管"那道代数闸门又是瞎的
     * (`tvFocusNavSignal` 有 isAutoRepeat 守卫, 连发不推进代数), 于是补射把用户已经按到后面的
     * 焦点拽回来 (2026-09-06 用户报"长按右键焦点被上一张卡片拉回去, 偶尔反复拉").
     *
     * 节点身份能直接回答: 若它**仍在组合里** ([Modifier.Node.isAttached]), 焦点就没悬空 ——
     * 要么还在它身上, 要么用户把焦点移到了别处, 两种都轮不到补射.
     */
    internal var focusedNode: Modifier.Node? = null

    /** 已解析的待聚焦目标下标 (锚点匹配用); null = 无已解析目标. */
    var pendingIndex: Int? by mutableStateOf(null)
        private set

    /** 待按目标网格列数解析的 (row, direction) 目标 (边缘切换: 同行近缘列). */
    private var pendingRowEdge: Pair<Int, Int>? by mutableStateOf(null)

    /** 请求代数 ([SendFocusEffect] 的重启键: 同目标连续请求也能重新触发). */
    private var requestGeneration by mutableStateOf(0)

    /** 发起请求时的用户交互代数: 之后的交互 (代数变化) = 用户接管, 取消请求. */
    internal var navGenerationAtRequest: Int = 0
        private set

    /** 发起请求时的按键代数 (含连发, 见 [TvFocusScope.userInputGeneration]). */
    private var inputGenerationAtRequest: Int = 0

    /** 是否有在途的送焦请求 (期间调用方应冻结"聚焦即选中"类副作用, 见文件头). */
    val switching: Boolean get() = pendingIndex != null || pendingRowEdge != null

    /**
     * 程序化聚焦第 [index] 项 (越界会钳到末项; 由 [SendFocusEffect] 消化).
     *
     * [reason] 只进日志: 排"焦点自己动了"这类问题时, 第一个要分清的就是"页面请求的"还是
     * "框架补射的" (见 [consumeRearmRequest]).
     */
    fun focusItem(index: Int, reason: String = "page") {
        // 只记**框架自己发起**的那几发: "焦点自己动了"的排查里, 第一件事就是分清是不是页面请求的.
        // 页面请求太密 (时间表长按右键每换一行一次), 记了只是噪音
        if (reason != "page") {
            logger.info { "tvfocus: focusItem($index) reason=$reason focusedIndex=$focusedIndex" }
        }
        scope.cancel(entryKey)
        navGenerationAtRequest = scope.userNavGeneration
        inputGenerationAtRequest = scope.userInputGeneration
        pendingRowEdge = null
        pendingIndex = index
        requestGeneration++
    }

    /**
     * 聚焦第 [row] 行的近缘列 (边缘切换的"对应位置"): [direction] > 0 = 从左进入落行首列,
     * < 0 = 从右进入落行尾列. 列数按**目标网格**的实际布局解析 (源网格列数可能不同).
     */
    fun focusRowEdge(row: Int, direction: Int) {
        scope.cancel(entryKey)
        navGenerationAtRequest = scope.userNavGeneration
        inputGenerationAtRequest = scope.userInputGeneration
        pendingIndex = null
        pendingRowEdge = row to direction
        requestGeneration++
    }

    /** 连续重新武装的下标与次数 (见 [onItemNodeDetached] 的上限闸门). */
    private var rearmIndex = -1
    private var rearmCount = 0

    /**
     * **聚焦卡的节点被销毁了** (由每张卡上的存活追踪节点上报): 对同一下标补一次送焦.
     *
     * 为什么必须逐卡上报, 不能只靠锚点: 锚点 ([entryKey]) 只在请求在途期间挂在目标卡上, 请求
     * 一到位就摘掉 —— 而分页把卡片节点换掉往往发生在**那之后**几百毫秒 (Room + RemoteMediator
     * 每次写库都换 generation, 窗口外的条目退回 placeholder、回填时又换回真数据, 两次都是整个
     * 节点替换). 那时锚点已不在, 框架收不到任何事件: 焦点凭空消失、Compose 不改派, 页面的系统
     * 兜底把它抢到第一个可聚焦元素 (标签行) 上.
     *
     * 真机取证 (2026-08-23, 追番页翻到第 60+ 行按返回回首卡, logcat):
     * `[grid.item] index=0 拿到焦点` -> 请求完成摘锚点 -> 400ms 后 `[fallback]` 已经在第一个
     * 标签上, 中间**一条事件都没有**. 加上本条之后同一操作补射两次 (placeholder 一次、
     * generation 替换一次), 焦点稳定停在首卡.
     *
     * **判据**: 焦点还记在这张卡上 (`focusedIndex == index`) 而它的节点没了. 正常的滚动回收
     * 命不中 —— 电视上滚动一定由焦点移动驱动, 焦点先落到新卡、[focusedIndex] 随之更新, 旧卡
     * 才被回收. 在途请求期间不管 (那条路有自己的到位/超时逻辑, 且锚点会随新节点重新附着).
     *
     * **光凭下标不够, 上报端还必须确认"销毁的正是持焦的那个节点"** (见
     * [TvGridItemPresenceNode]): 两批卡片并存时下标会撞车 —— 时间表长按右键跨天, 新一天的
     * 第 0 张刚拿到焦点, 旧一天的第 0 张随淡出销毁, 下标同为 0、`focusedIndex` 同为 0,
     * 于是补射把用户已经按到第 1、2 张的焦点拽回第 0 张, 长按期间还会连着拽几次
     * (2026-09-06 用户报"长按右键焦点被上一张卡片拉回去, 偶尔反复拉"; 真机日志里就是
     * `聚焦卡 0 的节点被销毁, 补一次送焦`). 长按连发不推进 [TvFocusScope.userNavGeneration]
     * (`tvFocusNavSignal` 有 isAutoRepeat 守卫), 所以下面那道"用户接管"闸门此时是瞎的.
     */
    internal fun onItemNodeDetached(index: Int) {
        if (focusedIndex != index || switching) return
        // 用户期间按过方向/确认键: 焦点归他, 不抢
        if (scope.userNavGeneration != navGenerationAtRequest) return
        // 上限闸门: 同一下标反复被销毁时别无限补射 (真机没见过, 纯保险)
        if (rearmIndex == index && rearmCount >= REARM_MAX_CONSECUTIVE) return
        // **只登记, 不当场发** —— 见 [rearmRequest]
        rearmRequest = index to ++rearmSeq
    }

    /**
     * 待裁决的补射请求 (下标 + 递增序号), 由 [rememberTvGridFocus] 的效应消化.
     *
     * **为什么不能在节点脱离那一刻就补**: "这张卡被换掉了"与"整个网格离开组合了"在那一刻
     * 完全同形 —— 焦点都还记在那张卡上, 它的节点都没了. 分辨它必须等这一轮组合/销毁走完
     * (网格自己的 [SendFocusEffect] 是否还装着), 所以改成登记 + 下一轮裁决.
     *
     * 真机取证 (2026-08-23): 搜索页深滚一路返回, 回到搜索框之后 300ms 又补了一发, 把焦点从
     * 搜索框拽回正在淡出的网格, 网格随即销毁、焦点二次悬空, 系统重分配落到候选列表第一项
     * (症状: "先聚焦第一个候选、IME 闪一下, 再按一次返回才回搜索框").
     */
    internal var rearmRequest: Pair<Int, Int>? by mutableStateOf(null)
        private set

    private var rearmSeq = 0

    /** 装着 [SendFocusEffect] 的网格实例数 (换 tab 的淡入淡出期间可能两份并存). */
    private var installedGrids by mutableStateOf(0)

    /** 裁决一次补射请求 (在销毁那一轮之后跑, 见 [rearmRequest]). */
    internal fun consumeRearmRequest(index: Int) {
        rearmRequest = null
        // 网格已经不在组合里 = 面板/页面走了, 不是"卡被换掉": 丢弃
        if (installedGrids <= 0) return
        if (switching) return
        // **焦点没悬空就不补**: 见 [focusedNode]. 这条比下标判据靠得住 —— 下标会撞车,
        // 而"还有活着的卡持着焦点"是直接的否定证据
        val holder = focusedNode
        if (holder != null && holder.isAttached) return
        if (focusedIndex != index) return
        if (scope.userNavGeneration != navGenerationAtRequest) return
        if (rearmIndex == index && rearmCount >= REARM_MAX_CONSECUTIVE) return
        rearmCount = if (rearmIndex == index) rearmCount + 1 else 1
        rearmIndex = index
        logger.info { "tvfocus: 聚焦卡 $index 的节点被销毁, 补一次送焦 (第 $rearmCount 次)" }
        focusItem(index, reason = "rearm")
    }

    /**
     * **焦点落到了别的卡上, 在途送焦作废**.
     *
     * "用户接管就取消"原先只从按键侧观察 (`userNavGeneration`), 而**长按连发不推进代数**
     * (`tvFocusNavSignal` 的 isAutoRepeat 守卫, 见文件头三条不变量), 于是长按期间的在途请求
     * 谁也取消不掉: 时间表长按右键每换一行都会请求一次 `focusItem(focused + 1)` (行末接下一行
     * 行首), 而送焦要等滚动 + 锚点附着才落地 —— 这期间默认空间搜索已经把焦点又往右挪了几张,
     * 迟到的那一发就把焦点拽回去 (2026-09-06 用户报"长按右键焦点被上一张卡片拉回去, 偶尔反复拉").
     *
     * 从**焦点侧**观察就没有这个盲区: 焦点自己落到了非目标的卡上, 就说明有别人在驱动移动,
     * 这一发已经过期. 与"目标到位即清"是同一个出口, 只是理由相反.
     */
    internal fun onFocusTakenByOtherItem(index: Int) {
        // **必须是用户按出来的那种落点**. 组合销毁时焦点会跌落到布局里第一个可聚焦节点 ——
        // 那正是在途请求要救的场面 (见文件头), 作废掉它焦点就永远停在跌落处.
        // 判据: 请求发出之后用户按过键 (含连发, 所以长按也认得出来).
        if (scope.userInputGeneration == inputGenerationAtRequest) return
        logger.info { "tvfocus: 焦点已落到 $index (在途目标 $pendingIndex), 作废这一发" }
        cancel()
    }

    /** 取消在途请求 (调用方确定目标不会出现, 如分页确定空列表). */
    fun cancel() {
        pendingIndex = null
        pendingRowEdge = null
        scope.cancel(entryKey)
    }

    /**
     * 送焦效应: 网格组合内装一次. 等数据就绪 (快照事件) -> 行缘目标按目标网格布局列数
     * 解析为下标 (布局事件) -> 目标滚进视口 -> [TvFocusScope.request] (锚点附着事件送达)
     * -> 等到位. 超时 (4 秒) 即 [cancel]; pending 出口见文件头. [itemCount] 须读 snapshot 状态.
     */
    @Composable
    fun SendFocusEffect(gridState: LazyGridState, itemCount: () -> Int) {
        // 网格在场记账: 补射请求的裁决靠它区分"卡被换掉"与"整个网格走了" (见 [rearmRequest])
        DisposableEffect(this) {
            installedGrids++
            onDispose { installedGrids-- }
        }
        LaunchedEffect(requestGeneration, gridState) {
            if (pendingIndex == null && pendingRowEdge == null) {
                return@LaunchedEffect
            }
            // **基线就用 focusItem/focusRowEdge 请求时记的那个, 不在这里重取.**
            //
            // 一度改成在这里重取, 是因为同一次按键分发里"用户在导航"会被上报**两次** (页面根的
            // tvFocusNavSignal 一次, 各页标签行/日期行自己再一次), 观察者读到中间态就会把请求
            // 判成"用户接管"而当场取消, 表现为按键像被吞了. 但重取会**吞掉真正的用户取消**:
            // 新一次方向键若落在"请求登记"与"本协程启动"之间, 它推进的代数会被当成基线, 旧送焦
            // 照常执行, 把用户刚移走的焦点抢回来 —— 正是这套框架要根除的那类问题.
            //
            // 正解是在**上报端消重**: 各页的标签行/日期行不再自己调 notifyUserNavigation
            // (页面根的 navSignal 是外层 onPreviewKeyEvent, 每次按键必先于它们跑过一遍),
            // 于是一次按键只推进一次代数, 中间态不存在, 请求时的基线就是可信的.

            // **必须有超时, 而且要真的 cancel** (fork 改的; 上游这里是无限等).
            //
            // 上游那样做在它自己的页面上没问题, 但本仓库有三处把"在途"当闸门用:
            // 搜索页的返回键归属 (backToFirstCard)、追番页的"聚焦即选中"抑制 (onSelect)、
            // 以及过渡锚点的可聚焦条件 (TvFocusTransitAnchor 聚焦期间还吞方向键). 闸门一旦
            // 卡在"在途"就永久锁死 —— 真机实测: 搜索页进卡片再返回后跳到搜索框, 且要连按几下
            // 方向键 (每下都递增代数, 直到观察者把它取消) 才恢复响应.
            // 旧实现靠"试满 N 次就放弃"保证闸门总会回落; 这里用超时补回同一条不变量.
            val satisfied = withTimeoutOrNull(SEND_FOCUS_TIMEOUT_MILLIS) {
                snapshotFlow { itemCount() }.first { it > 0 }
                pendingRowEdge?.let { (row, direction) ->
                    // 等目标网格首帧布局 (快照事件) 拿实际列数
                    val columns = snapshotFlow {
                        gridState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column }
                    }.first { it != null }!! + 1
                    pendingRowEdge = null
                    pendingIndex = (row * columns + if (direction > 0) 0 else columns - 1)
                        .coerceAtMost(itemCount() - 1)
                        .coerceAtLeast(0)
                }
                // **双向钳制 + 重新确认非空** (fork 加的; 上面 rowEdge 那条路本来就 coerceAtLeast(0),
                // 这条路原先只钳上界, 双标就摆在同一个函数里).
                //
                // 负下标是真能走到的, 两条路: ① 上面等到过 itemCount > 0, 但分页 refresh/invalidate
                // 会让它缩回 0, 此时 `itemCount() - 1` 就是 -1; ② 调用方直接传了负数 ——
                // TvCollectionPage 的"焦点卡离开本 tab"效应里那两处 focusItem(lastFocusedCard) 不钳制,
                // 而第二处还在一个 8 秒等待之后重读, 中途完全可能已经变回 -1.
                //
                // 后果比"pending 留死"重: scrollToItem(-1) 抛的是 IllegalArgumentException, **不是**
                // CancellationException, 下面那个 catch 接不住 —— 异常冒出 LaunchedEffect 会直接崩.
                //
                // **空判必须在钳制之前**: `coerceIn(0, -1)` 自己就抛 IllegalArgumentException
                // ("Cannot coerce value to an empty range"), 那样这个守卫会先于它要守的东西崩掉.
                val count = itemCount()
                if (count <= 0 || pendingIndex == null) {
                    // 目标已不存在 (等到过非空又缩回空): 闸门必须回落, 否则那三处读 switching 的
                    // 地方全锁死 —— 这条路不走超时, 所以得在这里自己收摊
                    this@TvGridFocusState.cancel()
                    return@withTimeoutOrNull Unit
                }
                // **钳制结果必须写回**: 锚点匹配 (tvGridFocusItem) 读的是 pendingIndex, 不是这里的
                // 局部变量. 不写回的话 focusItem(-1) 会滚到第 0 项, 却没有任何卡挂 entryKey ——
                // 送焦无人接, switching 一路卡到 4 秒超时. 上面 rowEdge 那条路本来就是写回的.
                val target = pendingIndex!!.coerceIn(0, count - 1)
                pendingIndex = target
                if (target !in gridState.layoutInfo.visibleItemsInfo.map { it.index }) {
                    // **别人的取消要吞掉, 自己的要照抛** (fork 加的).
                    //
                    // 竞争滚动 (用户手动滚 / 焦点 bringIntoView / 分页插入) 打断 scrollToItem 时抛的
                    // MutationInterruptedException 是 CancellationException 的子类: 让它冒出去只会
                    // 静默结束本效应 —— 连同下面那句超时兜底一起 —— 而 pendingIndex 还在, switching
                    // 于是永久为真, 上面注释里那三处闸门全部锁死, 4 秒超时也救不回来 (它自己也死了).
                    // 滚动没走完不影响送焦: 目标卡一附着锚点, 悬挂的请求就送达.
                    //
                    // ensureActive() 负责区分: 本效应自己被取消 (requestGeneration 变了 / 组合退场 /
                    // withTimeoutOrNull 超时) 时它重新抛出, 语义不变.
                    try {
                        gridState.scrollToItem(target)
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                    }
                }
                if (scope.isFocused(entryKey) && focusedIndex == target) {
                    // **目标已经真实持焦: 当场判到位, 别去等一个不会来的获焦事件.**
                    //
                    // [TvFocusScope.request] 对"已持焦"是短路返回 (什么都不武装), 而本效应的到位
                    // 判据是"目标卡报上来一次**新的**获焦" —— 那一下永远不会发生, 于是 pendingIndex
                    // 留在原地, switching 空卡到 4 秒超时, 期间三处闸门 (标签聚焦即选中、返回键
                    // 归属、过渡锚点吞方向键) 全被锁着.
                    // 真机取证 (2026-08-23 追番页深滚按返回回首卡): 页面的塌缩抢救对同一下标补发
                    // 一次, 恰好命中这条, 日志是"已真实持焦"紧跟 4 秒后的"送焦 4000ms 未到位".
                    this@TvGridFocusState.cancel()
                    return@withTimeoutOrNull Unit
                }
                scope.request(entryKey)
                // 等这次送焦真的到位: pending 由目标卡的 onFocusChanged 清掉 (见 tvGridFocusItem).
                // 等到了才算成功 —— 只发不等的话超时判据就成了摆设
                snapshotFlow { switching }.first { !it }
            }
            if (satisfied == null) {
                logger.warn {
                    "TvGridFocusState: 送焦 ${SEND_FOCUS_TIMEOUT_MILLIS}ms 未到位, 主动收摊. " +
                            "pendingIndex=$pendingIndex, pendingRowEdge=$pendingRowEdge, " +
                            "itemCount=${itemCount()}, visible=${gridState.layoutInfo.visibleItemsInfo.size}"
                }
                // 闸门回落交给调用方的 onStranded / 空态兜底接手
                this@TvGridFocusState.cancel()
            }
        }
    }
}

/**
 * 一次网格送焦最多等多久, 超时即 [TvGridFocusState.cancel] (见 [TvGridFocusState.SendFocusEffect]).
 *
 * 4 秒: 比旧实现最长的那条等待还宽一点 (进页恢复是 attempts=120 × (一帧 + 30ms) ≈ 5.5s, 但那
 * 里面大头是无谓的空转; 真正要等的是分页首屏, 实测 0.3~1.9s), 又短到闸门锁死时用户不会以为
 * 界面坏了.
 */
private const val SEND_FOCUS_TIMEOUT_MILLIS = 4000L

/**
 * 同一下标连续补射的上限, 见 [TvGridFocusState.onItemNodeDetached].
 *
 * 实测一次返回最多补两次 (placeholder -> 真数据, 以及紧随的一次 generation 替换), 留些余量;
 * 只为拦住"反复销毁"这种病态循环, 正常路径碰不到.
 */
private const val REARM_MAX_CONSECUTIVE = 5

/** 卡片节点存活追踪: 节点脱离即上报, 让 [TvGridFocusState] 判"聚焦卡带着焦点被销毁". */
private data class TvGridItemPresenceElement(
    val state: TvGridFocusState,
    val index: Int,
) : ModifierNodeElement<TvGridItemPresenceNode>() {
    override fun create() = TvGridItemPresenceNode(state, index)

    override fun update(node: TvGridItemPresenceNode) = node.update(state, index)

    override fun InspectorInfo.inspectableProperties() {
        name = "tvGridItemPresence"
        properties["index"] = index
    }
}

private class TvGridItemPresenceNode(
    private var state: TvGridFocusState,
    private var index: Int,
) : Modifier.Node(), FocusEventModifierNode {
    /**
     * 本节点 (这张卡) 此刻是不是持着焦点.
     *
     * 存在这里而不是只看 [TvGridFocusState.focusedIndex]: 两批卡片并存时下标会撞车,
     * 只比下标会把"另一批里同号的那张被销毁"误判成"持焦卡被销毁" —— 见
     * [TvGridFocusState.onItemNodeDetached] 的判据一段.
     */
    private var focused = false

    /**
     * `isFocused` 是本节点自己持焦, `hasFocus` 是焦点在它子树里 (卡片的可聚焦体通常在更里层).
     * 两者取或: 这里只关心"焦点是不是在这张卡上".
     */
    override fun onFocusEvent(focusState: FocusState) {
        focused = focusState.isFocused || focusState.hasFocus
        // 谁最后持的焦点要记身份, 不能只记下标 (见 [TvGridFocusState.focusedNode])
        if (focused) state.focusedNode = this
    }

    override fun onDetach() {
        if (focused) state.onItemNodeDetached(index)
    }

    fun update(newState: TvGridFocusState, newIndex: Int) {
        // 下标变了 = 同一个节点被 Lazy 复用到了另一张卡, 旧下标那张卡并没有"被销毁", 只更新记账
        state = newState
        index = newIndex
    }
}

private val logger = logger("TvGridFocusState")

/**
 * 创建 [TvGridFocusState] (挂在页面的 [scope] 上), 并装交互取消观察:
 * 在途请求期间用户再按方向/确认键即取消 (代数对比排除发起切换的那次按键).
 */
@Composable
fun rememberTvGridFocus(scope: TvFocusScope): TvGridFocusState {
    val state = remember(scope) { TvGridFocusState(scope) }
    // 目标卡在**请求在途期间**带着焦点被销毁 -> 补一次送焦 (见 [TvFocusScope.focusLostByDetach]).
    // 请求到位之后的销毁由逐卡存活追踪接手 (见 [TvGridFocusState.onItemNodeDetached]).
    // 逐卡存活追踪登记的补射请求: 在销毁那一轮之后裁决 (见 [TvGridFocusState.rearmRequest])
    LaunchedEffect(state) {
        snapshotFlow { state.rearmRequest }
            .filterNotNull()
            .collect { (index, _) -> state.consumeRearmRequest(index) }
    }
    LaunchedEffect(state) {
        snapshotFlow { scope.focusLostByDetach }
            .filterNotNull()
            .collect { (key, _) ->
                if (key !== state.entryKey) return@collect
                val index = state.focusedIndex
                if (index < 0 || state.switching) return@collect
                // 与 consumeRearmRequest 同一道判据: 还有活着的卡持着焦点就不是"焦点悬空"
                val holder = state.focusedNode
                if (holder != null && holder.isAttached) return@collect
                if (scope.userNavGeneration != state.navGenerationAtRequest) return@collect
                state.focusItem(index, reason = "anchor-detach")
            }
    }
    LaunchedEffect(state) {
        snapshotFlow { scope.userNavGeneration }
            .drop(1) // 首值为当前代数, 非事件
            .collect { generation ->
                if (state.switching && generation != state.navGenerationAtRequest) {
                    state.cancel()
                }
            }
    }
    return state
}

/**
 * 网格卡接线 (每张卡都挂): 聚焦时上报下标; 本卡是"待聚焦目标" (钳到 [itemCount] 末项)
 * 时挂动态锚点, 送达即清 pending (焦点事件出口).
 */
fun Modifier.tvGridFocusItem(
    state: TvGridFocusState,
    index: Int,
    itemCount: Int,
): Modifier {
    // **与 SendFocusEffect 用同一份规范化** (那边是 coerceIn(0, count-1) 并写回). 只钳上界的话,
    // pendingIndex 为负时这里算出 -1、没有任何卡匹配, 而效应那边已经滚到第 0 项 —— 送焦无人接.
    // 不用 coerceIn(0, itemCount-1): itemCount 是调用方传的, 为 0 时 coerceIn 自己会抛.
    val targetIndex = state.pendingIndex?.coerceAtMost(itemCount - 1)?.coerceAtLeast(0)
    return this
        .then(
            if (index == targetIndex) {
                Modifier.tvFocusAnchor(state.scope, state.entryKey)
            } else Modifier,
        )
        // 每张卡都挂: 聚焦卡的节点被销毁时补一次送焦, 见 [TvGridFocusState.onItemNodeDetached]
        .then(TvGridItemPresenceElement(state, index))
        .onFocusChanged {
            if (it.isFocused) {
                state.focusedIndex = index
                when {
                    index == targetIndex -> state.cancel() // 到位
                    // 落到了别的卡上 = 有别人在驱动焦点, 在途那一发已过期 (长按连发的盲区)
                    state.pendingIndex != null -> state.onFocusTakenByOtherItem(index)
                }
            }
        }
}

