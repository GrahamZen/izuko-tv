/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/*
 * TV 统一焦点管理框架 (使用侧 API 见 TvFocusModifiers.kt).
 *
 * 概念:
 * - [TvFocusKey]: 页面内一个具名焦点位置 (锚点). 页面用 enum 实现或 [TvFocusKey] 工厂创建.
 * - [TvFocusScope]: 页面级调度器. 所有"把焦点送到某处"的入口 (进页初始焦点 / 返回键分层 /
 *   弹层关闭归还 / 全局快捷键) 都走 [TvFocusScope.request], 由 [Resolver] 消化.
 *
 * **全事件驱动, 禁止轮询/延时** (§14.4-8): Compose 对未附着节点的 requestFocus 静默失败
 * 且返回值不可靠, 早期版本用"轮询 + 到位确认 + 延时"兜时序, 在慢设备上暴露出整族竞态
 * (烧满轮询抢用户焦点 / 时序窗口内按键误伤). 现在把缺失的"附着"事件补上:
 * 锚点 modifier 在节点附着/脱离时上报 ([onAnchorAttached]/[onAnchorDetached]),
 * [Resolver] 以快照流响应"pending 请求 && 目标已附着" —— 目标已附着则立即送焦,
 * 未附着 (Lazy 回收/转场中) 则请求悬挂, 目标一附着即送. 没有帧等待, 没有超时.
 *
 * 完成语义: 只认锚点真实上报的获焦事件, 不认 [FocusRequester.requestFocus] 的返回值 ——
 * 真机上导航退场层仍持焦时该方法会返回 true, 目标却没有收到焦点. 用户按下方向/确认键即取消
 * 在途请求 ([notifyUserNavigation]), 框架不与用户抢焦点.
 */

/** 页面内一个具名焦点位置. 页面私有 enum 直接实现本接口, 或用 [TvFocusKey] 工厂. */
interface TvFocusKey

/** 字符串命名的 [TvFocusKey] (不想定义 enum 的轻量场景). */
fun TvFocusKey(name: String): TvFocusKey = NamedFocusKey(name)

private data class NamedFocusKey(val name: String) : TvFocusKey {
    override fun toString(): String = name
}

/**
 * 页面级焦点调度器. 经 [rememberTvFocusScope] 创建; 页面根部须装 [Resolver] 消化请求.
 *
 * 线程模型: 全部在主线程 (组合/效应/按键分发) 使用.
 */
@Stable
class TvFocusScope {
    private val requesters = mutableMapOf<TvFocusKey, FocusRequester>()

    /** 当前待解析的请求 (锚点 + 序号; 序号使同锚点连续请求也能重新触发); null = 空闲. */
    internal var pending: Pair<TvFocusKey, Int>? by mutableStateOf(null)
        private set

    /**
     * 已附着锚点 -> 附着代数 (快照状态: [Resolver] 靠它感知"目标出现了").
     * 由锚点 modifier 的节点附着/脱离事件维护 (见 tvFocusAnchor).
     *
     * 值是**全局单调递增**的代数, 不是计数: 每次"从无到有"的附着都换一个新值, 让
     * [Resolver] 的快照流对"脱离又重附着"(Lazy 回收再滚回) 重新触发. 同 key 同时挂在
     * 多个节点上的引用计数另记在 [anchorRefCount] 里.
     */
    private val attachedAnchors = mutableStateMapOf<TvFocusKey, Int>()

    /**
     * 同一 key 当前挂着的锚点**节点个数** (非快照状态, 只用来记账).
     *
     * 为什么需要它: 网格的"待聚焦目标"锚点是**跟着目标卡走**的 (见 tvGridFocusItem) ——
     * 同一个 key 会在两张卡之间迁移. 目标下标变小且新旧两卡都已组合时, Compose 按下标
     * 升序重组, 于是顺序是「新卡 onAttach -> 旧卡 onDetach」; 若 [onAnchorDetached] 直接
     * `remove(key)`, 旧卡那一下会把新卡刚登记的附着**整个抹掉**, 此后 [attachedAnchors]
     * 一直谎报"未附着", 所有送焦请求悬挂到被按键取消 —— 而且不会自愈, 直到某次送焦的目标
     * 恰好在视口外、逼出一次真正的重新附着为止.
     *
     * 真机取证 (2026-08-22 app.log): 该 key 的 34 次"请求时未附着"里 13 次是这条
     * (2 次触发 + 11 次连带), 表现为"连按几下方向键没反应, 然后突然又好了".
     */
    private val anchorRefCount = mutableMapOf<TvFocusKey, Int>()

    /** [attachedAnchors] 的代数发号器 (单调递增, 见该属性文档). */
    private var attachEpoch = 0

    /**
     * **持焦的锚点节点被销毁**这个事件 (key + 递增序号; null = 还没发生过).
     *
     * Compose 里节点被移除时焦点凭空消失, **不会有失焦回调, 也不会自动改派** —— 这是本框架原本
     * 缺失的那个事件, 也是"焦点忽然跳到页面第一个可聚焦元素上"这一族 bug 的共同出口.
     *
     * **判据天然精确**: 焦点正常移走时先有 [onAnchorFocusChanged] 报 false、再脱离, 命不中这里;
     * 只有"带着焦点被销毁"才会.
     *
     * 消费者是 [TvGridFocusState] 的重新武装. 注意它只覆盖**请求在途期间**(锚点挂在目标卡上那
     * 一段) 的销毁; 请求到位后锚点就摘掉了, 之后的销毁由网格的逐卡存活追踪接手
     * (见 TvGridFocusState.onItemNodeDetached) —— 两条都要, 缺后者时真机复现如下:
     * 焦点落到首卡 -> 请求完成摘锚点 -> 400ms 后分页把那张卡的节点换掉 -> 无任何事件,
     * 焦点被页面兜底抢到第一个标签上.
     */
    internal var focusLostByDetach: Pair<TvFocusKey, Int>? by mutableStateOf(null)
        private set

    private var focusLostSeq = 0

    // 当前聚焦的锚点集合 (各锚点 onFocusChanged 得失双向上报, 见 tvFocusAnchor)
    private val focusedKeys = mutableSetOf<TvFocusKey>()

    /**
     * 已附着目标曾因所在页面仍在导航退场层之后而拒绝送焦时, 等页面自己的兜底焦点落稳再重送.
     *
     * 锚点附着只说明 modifier node 在树上, 不等于它此刻可接受焦点: NavDisplay 的 pop 动画会先
     * 组合返回页, 但退场页仍持焦期间, 返回页目标的 `requestFocus` 会返回 false. 目标节点随后
     * 不会重新附着, 只等附着事件就永远没有第二次机会. 页面把系统兜底落点 (首标签/搜索词/日期)
     * 的获焦事件报到 [notifyFocusFallbackSettled], 让 [Resolver] 对仍在途的请求再裁决一次.
     */
    private var focusFallbackEpoch by mutableIntStateOf(0)

    /**
     * 用户交互代数 (快照状态; [tvFocusNavSignal] 上报): 方向/确认键按下即自增并取消
     * 在途请求 —— 框架不与用户抢焦点. 需要交互取消语义的效应 (如网格切换冻结) 观察它.
     */
    var userNavGeneration: Int by mutableIntStateOf(0)
        private set

    /**
     * 按键代数, **连发也算** ([tvFocusNavSignal] 上报). 与 [userNavGeneration] 的分工:
     *
     * - [userNavGeneration] 是"用户介入了, 取消在途送焦" —— 它**必须**放过连发, 否则由长按驱动的
     *   整批切换 (换天/换分类) 会被自己的第二发取消掉 (见 [tvFocusNavSignal] 里那段).
     * - 本代数只回答一个更弱的问题: "自某个时刻起用户按过键吗". 用来分辨焦点是**用户按出来的**
     *   还是组合销毁时**跌落**过去的 —— 前者该让在途请求作废, 后者恰恰要靠在途请求救回来.
     *
     * 只有需要这个区分的地方读它 (见 [TvGridFocusState.onFocusTakenByOtherItem]).
     */
    var userInputGeneration: Int by mutableIntStateOf(0)
        private set

    /** 用户按下方向/确认键 (含系统连发) 的上报, 只推进 [userInputGeneration], 不取消任何请求. */
    fun notifyUserInput() {
        userInputGeneration++
    }

    /** [key] 的 FocusRequester (惰性创建). 框架内部互操作用; 页面侧一律走锚点 + [request]. */
    fun requesterOf(key: TvFocusKey): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    /**
     * 请求把焦点送到 [key] (fire-and-forget): 锚点已附着则 [Resolver] 立即送焦;
     * 未附着 (Lazy 回收/转场中) 则悬挂, 锚点一附着即送. 目标尚未持焦时, 同 key 连续请求
     * 也会重新触发; 目标已经真实持焦则保持空闲. 后发请求覆盖先发; 用户交互取消在途请求.
     */
    fun request(key: TvFocusKey) {
        if (key in focusedKeys) {
            // 目标已经真实持焦: 没有要送的, 顺手清掉可能还挂着的旧请求
            pending = null
            return
        }
        pending = key to ((pending?.second ?: 0) + 1)
    }

    /**
     * 页面里的系统兜底落点已经拿到焦点: 若此前有目标因导航转场拒绝送焦, 立即重试.
     *
     * 这不是用户交互, 不调用 [notifyUserNavigation]; 仅作为 [Resolver] 的事件源. 调用点应挂在
     * 页面按遍历顺序最先拿到焦点的稳定节点上 (如首标签、搜索词、选中日期胶囊).
     */
    fun notifyFocusFallbackSettled() {
        focusFallbackEpoch++
    }

    /** 取消指定锚点仍在途的请求; 其他锚点后来发出的请求不受影响. */
    internal fun cancel(key: TvFocusKey) {
        if (pending?.first != key) return
        pending = null
    }

    /** 用户按下方向/确认键的上报 (由 [tvFocusNavSignal] 自动挂接): 取消在途请求. */
    fun notifyUserNavigation() {
        userNavGeneration++
        pending = null
    }

    /** 锚点节点附着上报 (由 tvFocusAnchor 自动挂接). 同 key 多节点按引用计数记账. */
    fun onAnchorAttached(key: TvFocusKey) {
        val count = (anchorRefCount[key] ?: 0) + 1
        anchorRefCount[key] = count
        // **每次附着都先撤掉持焦标记**, 不只是 0->1 那次: 锚点在两个节点之间迁移时 (网格的目标
        // 锚点就是这样) 顺序可能是「新节点 onAttach -> 旧节点 onDetach」, 旧节点带着持焦标记走,
        // refcount 不为 0, [onAnchorDetached] 的收回逻辑就不生效 —— 那个标记指的是**旧节点**,
        // 对新目标毫无意义, 留着只会让 [request] 误判"已经在那儿了".
        //
        // 撤了不会误伤: 若新节点本来就挂在当前已聚焦的元素上, 紧随其后的 onFocusChanged 会立刻
        // 把它加回来 —— 锚点 modifier 链里附着节点排在 onFocusChanged 之前, 顺序有保证.
        focusedKeys.remove(key)
        // 每次附着都换一个新代数: 迁移 (计数 1->2->1) 也会让快照流重新触发
        attachedAnchors[key] = ++attachEpoch
    }

    /**
     * 锚点节点脱离上报 (由 tvFocusAnchor 自动挂接).
     *
     * **必须配对递减, 不能直接 remove** —— 理由见 [anchorRefCount].
     */
    fun onAnchorDetached(key: TvFocusKey) {
        val remaining = (anchorRefCount[key] ?: 1) - 1
        if (remaining <= 0) {
            anchorRefCount.remove(key)
            attachedAnchors.remove(key)
            // **节点脱离不会触发 onFocusChanged(false)**, 所以持焦标记必须在这里手动收回:
            // 一个 key 没有任何节点在树上, 它就不可能持焦.
            //
            // 漏了会让 [request] 的"已持焦即空闲"短路变成永久生效 —— 网格的锚点是跟着目标卡走的
            // (见 tvGridFocusItem), 送达即脱离, 于是第一次送焦成功后 entryKey 永远留在
            // [focusedKeys] 里, 之后每次 focusItem/focusRowEdge 都被当成"已经在那儿了"跳过.
            // 真机取证 (2026-08-22 17:09 app.log): 32 次请求里 27 次打的是"已真实持焦, 无须送焦",
            // 表现为追番页第一次下键能进网格、之后再也下不去; 换 tab 后内容变了焦点不动; 时间表
            // 行末右键到不了下一行. 而且 switching 闸门因为目标永不获焦要卡到 4 秒超时才回落,
            // 期间过渡锚点吞掉方向键、返回键也被闸门挡住.
            if (focusedKeys.remove(key)) {
                // 带着焦点被销毁: 此刻整棵树可能一个焦点都没有 (见 [focusLostByDetach])
                focusLostByDetach = key to ++focusLostSeq
            }
        } else {
            anchorRefCount[key] = remaining
        }
    }

    /** [key] 的锚点当前是否附着 (目标存在性判断). */
    fun isAnchorAttached(key: TvFocusKey): Boolean = attachedAnchors.containsKey(key)

    /**
     * 锚点焦点得失上报 (由 tvFocusAnchor 自动挂接, 手写节点亦可直接调用).
     *
     * 送焦请求只在这里观察到目标**真实获焦**时完成. `requestFocus(Enter)` 返回 true 只表示
     * 焦点事务被接受, NavDisplay pop 退场期间仍可能没有任何目标节点获焦 (真机已复现).
     */
    fun onAnchorFocusChanged(key: TvFocusKey, focused: Boolean) {
        if (focused) {
            focusedKeys.add(key)
            // 送焦请求只由这里的真实获焦事件完成 (requestFocus 的返回值不可信, 见本函数文档)
            if (pending?.first == key) pending = null
        } else {
            focusedKeys.remove(key)
        }
    }

    /** [key] (或其子树) 当前是否持有焦点. */
    fun isFocused(key: TvFocusKey): Boolean = key in focusedKeys

    /**
     * 解析安装点. 快照流响应"有 pending 且目标锚点已附着" -> 立即尝试送焦. 无论返回 true/false
     * 都保持 pending, 只由目标锚点的真实获焦事件完成请求; 页面兜底焦点落稳后由
     * [notifyFocusFallbackSettled] 触发再次尝试. 单实例消化所有 [request], 避免多处请求打架.
     *
     * **由 [rememberTvFocusScope] 自动安装, 不对页面开放** —— 从前它要页面自己在根部组合一次,
     * 而"创建了 scope 却忘了装消费者"从外面完全看不出来: 请求照常登记, 锚点照常附着上报, 只是
     * 永远没人消化. 真机取证 (2026-08-22 app.log): 追番页迁移时漏了这一句, 34 次送焦里 21 次
     * 就死在这, 日志上是请求与附着齐全而一条裁决都没有. 装进工厂就不可能再漏.
     */
    @Composable
    internal fun Resolver() {
        // **窗口获焦也是一个重试事件** (fork 加的). 锚点已附着但焦点事务被拒时请求会一直悬挂,
        // 而"锚点不会再附着一次"—— 只等附着事件就永远没有第二次机会. 页面级调用点用
        // [notifyFocusFallbackSettled] 补了这个事件, 但**独立窗口 (Dialog/Popup) 没有兜底落点可挂**:
        // tvWindowInitialFocus 与 restoreFocusAfter 建的是私有 scope, 一旦首发被拒就没人再推一把,
        // 症状是"弹窗打开没有初始焦点"或"关掉弹窗焦点没还回来". 而弹窗窗口拿到焦点这件事本身
        // 天然晚于它的首次组合 (本仓库已在 TvPlayerPanels 的面板落点上踩到过 isWindowFocused
        // 这条时序), 正好是那个缺失的第二事件.
        //
        // 装在 Resolver 里而不是补到那两个 helper 上: 这样每一个 scope 都自动有, 不用再指望
        // 调用点记得挂 —— 同 Resolver 本身收进 rememberTvFocusScope 的理由.
        val windowInfo = LocalWindowInfo.current
        LaunchedEffect(this, windowInfo) {
            // 已在"窗口有焦点"下试过的那一发 (窗口焦点位归一后比对, 见下面的 collect)
            var lastFocusedAttempt: FocusDelivery? = null
            snapshotFlow {
                val p = pending
                if (p == null) {
                    return@snapshotFlow null
                }
                val gen = attachedAnchors[p.first]
                val fallbackGen = focusFallbackEpoch
                val windowFocused = windowInfo.isWindowFocused
                // 附着代数入元组: 目标脱离又重附着 (Lazy 回收再滚回) 也会重新触发
                gen?.let { generation -> FocusDelivery(p.first, p.second, generation, fallbackGen, windowFocused) }
            }
                .filterNotNull()
                .collect { delivery ->
                    // **窗口焦点翻转只补一次机会, 不反复复活旧请求.**
                    //
                    // 上面那条"窗口获焦也是重试事件"补的是"当时窗口还没焦点"这一种拒焦. 若某一发
                    // 已经在"窗口有焦点"的状态下试过还是没落地, 那就不是窗口的问题, 后来的窗口切换
                    // 不该把它再送一遍 —— 私有 scope 的 pending 取消不掉 (它们的 navSignal 只覆盖
                    // 自己子树, 见 restoreFocusAfter), 反复重试等于给"迟到抢焦点"开了新入口.
                    // 锚点重附着 / 兜底落焦 / 新请求都会换掉元组里别的字段, 照常放行.
                    val attempt = delivery.copy(windowFocused = true)
                    if (delivery.windowFocused && lastFocusedAttempt == attempt) {
                        return@collect
                    }
                    if (delivery.windowFocused) lastFocusedAttempt = attempt
                    // 仍用带方向的布尔重载记录焦点事务是否被接受, 但**不能据此完成请求**:
                    // 真机上 pop 退场层仍持焦时会返回 true, 目标的 onFocusChanged 却没有发生.
                    // pending 只由 onAnchorFocusChanged 的真实获焦事件清掉.
                    runCatching { requesterOf(delivery.key).requestFocus(FocusDirection.Enter) }
                }
        }
        // **fork 追加的按帧重试**: 上游假设"锚点附着事件足够触发送焦", 但真机上目标已附着、窗口
        // 也有焦点时焦点事务照样会被拒 (2026-08-25: key=PROGRESS 目标已附着=true 窗口有焦点=true
        // 当前持焦=ROOT, 请求悬挂到用户再按一次为止 —— 现象是"返回键第一次没反应, 第二次才生效",
        // 加载转圈那一下持焦节点被移除则表现为"焦点整个丢失". 实测**全部在第 1 帧就被接受**,
        // 进度条上方那排按钮同样中招). 而 Resolver 的触发元组 (附着代数/兜底代数/窗口焦点) 此后
        // 都不会再变, 于是没有任何人重试.
        //
        // 只在"目标已附着 + 窗口有焦点"时重试, 按帧而不是按时间 (等的是布局/焦点树落定, 不是时长),
        // 有限次后放弃交给上面那条软超时告警. 不与用户抢: 用户按方向键会 notifyUserNavigation
        // 清掉 pending, collectLatest 当场结束本轮.
        LaunchedEffect(this, windowInfo) {
            snapshotFlow { pending }.collectLatest { p ->
                if (p == null) return@collectLatest
                repeat(PENDING_RETRY_FRAMES) {
                    withFrameNanos { }
                    val current = pending
                    if (current != p) return@collectLatest // 已完成或被换掉
                    if (p.first !in attachedAnchors || !windowInfo.isWindowFocused) return@repeat
                    runCatching { requesterOf(p.first).requestFocus(FocusDirection.Enter) }
                }
            }
        }
        // fork 追加的软超时: **只打日志, 不重试也不清 pending** —— 语义与上游完全一致.
        //
        // 上游那套的失败模式是"目标锚点永不附着 -> 请求无声悬挂到用户按键为止", 界面上与
        // "送焦成功了但落点不对" 分不出来, 也没有任何一条线索说"我在等". 旧的轮询解析器至少
        // 有个终点 (试满 40 次), 这里把那条线索补回来.
        //
        // 注意: 这段 delay 只是诊断计时, 不参与控制流 —— 上游的 Konsist 守护禁止 focus/ 目录
        // 出现 delay(, 若这套代码日后要并回上游, 这一段要单独摘掉或挪走.
        LaunchedEffect(this, windowInfo) {
            snapshotFlow { pending }.collectLatest { p ->
                if (p == null) return@collectLatest
                delay(PENDING_STALL_WARN_MILLIS)
                // 这三项分开报: "锚点没附着"与"附着了但焦点事务被拒"是两种完全不同的病, 而
                // 光打一个集合是分不出来的 (而且 SnapshotStateMap.keys 的 toString 只有对象地址,
                // 打出来是 SnapshotMapKeySet@644293f —— 2026-08-25 就因此白拿了一份日志)
                logger.warn {
                    "TvFocusScope: 送焦请求悬挂超过 ${PENDING_STALL_WARN_MILLIS}ms. " +
                            "key=${p.first}, seq=${p.second}, " +
                            "目标已附着=${p.first in attachedAnchors}, " +
                            "窗口有焦点=${windowInfo.isWindowFocused}, " +
                            "当前持焦=${focusedKeys.joinToString().ifEmpty { "(无)" }}, " +
                            "已附着锚点=${attachedAnchors.keys.joinToString()}"
                }
            }
        }
    }

    /**
     * 进页初始焦点: 组合完成后请求 [key] (锚点未附着则悬挂到附着, 无延时).
     *
     * 只做"登记一个请求", 不含任何转场等待: 请求由 [Resolver] 在目标锚点附着那一刻消化, 目标若
     * 还在转场退场层之后就悬挂着, 等页面兜底落点上报 [notifyFocusFallbackSettled] 再裁决一次.
     *
     * **上游在这里等 `Lifecycle RESUMED`** (它把这个当"转场完成"的信号), 本仓库不需要:
     * 迁到 Navigation 3 之后那个信号不再翻转 (入口的 LifecycleOwner 由
     * `BackStackAwareLifecycleNavEntryDecorator` 在入口内容的组合里造, maxState 只看"还在返回栈里
     * 没有", 前进导航时被盖住的条目一直是 RESUMED), 照搬只会得到一句立即返回的空等待. 上游那句
     * 等待服务的是它的焦点记忆 (TvFocusMemory), 而本仓库的返回恢复由各页自己的
     * `OnReturnToForeground` 承担 (真机日志已验证能恢复到原卡), 记忆那一套连同这句等待一起删了.
     */
    @Composable
    fun InitialFocus(key: TvFocusKey) {
        LaunchedEffect(this) {
            // LaunchedEffect 在组合应用后执行, 此刻锚点多半已附着; 未附着则请求悬挂
            request(key)
        }
    }
}

/**
 * 创建页面级焦点调度器, **并就地安装 [TvFocusScope.Resolver]** (为什么由工厂代劳见该函数文档).
 *
 * 页面侧仍须在根节点挂 [tvFocusNavSignal] —— 那一句要占 modifier 位置, 没法在这里代劳.
 */
@Composable
fun rememberTvFocusScope(): TvFocusScope {
    val scope = remember { TvFocusScope() }
    scope.Resolver()
    return scope
}

/**
 * 送焦请求悬挂多久算异常 (只影响日志, 见 [TvFocusScope.Resolver]).
 *
 * 取 2 秒: 比最慢的正常情形 (Lazy 首屏组合 + 转场 + 分页首帧) 宽一截, 免得正常路径刷日志;
 * 又短到用户还记得自己刚做了什么, 对得上现场。
 */
private const val PENDING_STALL_WARN_MILLIS = 2000L

/**
 * 送焦被拒后按帧重试的次数. 8 帧 ≈ 130ms (60Hz), 够覆盖"弹窗卸载 / 面板收起 / 列表重组"这类
 * 一两帧内落定的时序, 又不至于在真的没救时一直撞.
 */
private const val PENDING_RETRY_FRAMES = 8

private data class FocusDelivery(
    val key: TvFocusKey,
    val seq: Int,
    val anchorEpoch: Int,
    val fallbackEpoch: Int,
    val windowFocused: Boolean,
)

private val logger = logger("TvFocusScope")
