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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import me.him188.ani.app.ui.foundation.isAutoRepeat

/*
 * **横向索引栏**的共享焦点状态与方向键路由 —— "一行可聚焦项, 聚焦即选中" 这一族:
 * 追番页的分类标签行、时间表的日期胶囊行.
 *
 * 为什么值得抽出来 (不是为了少几行): 这两处**逐条对应**地重复着四件事 —— 焦点下标记账、
 * "聚焦即选中"的封印标志、左右键显式移动与边缘是否消费、用户导航上报的连发守卫. 2026-08-22
 * 那次给连发守卫打补丁时**同一个修法写了两遍**, 而且两处的"下标未知"分支当时是不一致的
 * (一处消费一处放行, 后者正是"末标签按右绕回第一个"那条真机 bug 的形状). 收到一处之后,
 * 这类修法只会有一个落点.
 *
 * **视觉不在本文件管辖内**: 标签画文字 + 滑动指示条, 日期画胶囊, 各页照旧自己画; 送焦方式
 * 也留给各页 (标签恒在屏可直接 requestFocus, 日期胶囊可能没组合出来要走 scope 请求 + 滚动).
 * 本文件只管"焦点在第几项、这次获焦算不算用户选的、左右键怎么走".
 *
 * 不收搜索页的已选筛选胶囊行: 它只有一个焦点下标, 既没有"聚焦即选中"也没有显式左右键 ——
 * 形似而已, 硬塞进来只会给本状态加一堆用不上的开关.
 */

/**
 * 横向索引栏的焦点状态. 经 [rememberTvFocusRail] 创建.
 *
 * 线程模型: 全部在主线程 (按键分发 / 焦点回调) 使用.
 */
@Stable
class TvFocusRailState internal constructor(
    internal val scope: TvFocusScope,
    internal val keyAt: (index: Int) -> TvFocusKey,
    private val move: (index: Int) -> Unit,
) {
    /**
     * 焦点所在项的下标; -1 = 还不知道是哪一项.
     *
     * **不是 snapshot 状态** (同 [TvGridFocusState.focusedIndex]): 只有按键判定读它, 做成
     * snapshot 只会让整行跟着每次移焦重组.
     *
     * -1 的含义不是"焦点不在本行" —— 方向键路由只在焦点位于本行子树内才会被调用. 它是
     * "requestFocus 已被接受、目标的 onFocusChanged 还没上报"那一小段窗口 (以及进页第一次获焦
     * 之前). 这个窗口里的按键必须消费掉, 见 [tvFocusRailKeys] 对右键的处理.
     */
    var focusedIndex: Int = -1
        private set

    /**
     * 已武装的"聚焦即选中"目标下标; -1 = 未武装.
     *
     * 为什么"武装"这件事必须**带上目标下标**, 而不是一个布尔 (原先两页各自都是布尔, 抽取时
     * 连这个缺陷一起搬过来了, 2026-08-22 二轮审计逮到): 送焦可能既不到位也不失败, 而是**悬在
     * 半路**. 时间表往一枚还没组合出来的日期胶囊移动时, 请求会挂在 [TvFocusScope] 上等锚点附着;
     * 若用户此刻按下键进网格, [TvFocusScope.notifyUserNavigation] 会取消那个请求, 而布尔武装
     * 留在原地 —— 之后任何一枚胶囊获焦 (系统兜底 / 用户按返回回到日期行) 都会被误判成"用户用
     * 左右键选的", 当场改掉选中的那天. 追番页在 requestFocus 被拒时同理.
     *
     * 而"聚焦即选中"这件事本身存在的理由就是要挡住这类误判: 页面切换 / 焦点悬空时焦点系统会把
     * 默认焦点塞给行内第一项, 那种情况**绝不能**改选中项 —— 追番页的症状是"从卡片进详情页再
     * 快速返回被拽到第一个 tab 的第一张卡", 时间表的症状是"被拽到 15 天前那天".
     */
    private var armedTarget: Int = -1

    /** 由本行左右键发起的移动: 武装"聚焦即选中", 送焦交给创建时给的 onMove. */
    fun moveTo(index: Int) {
        armedTarget = index
        move(index)
    }

    /**
     * 解除武装 —— 由 [tvFocusRailKeys] 在**每一次新的 (非连发) 按键**开始时调用, 左右键分支随后
     * 用 [moveTo] 重新武装, 所以实际只有"离开本行的那种按键"会真的把武装清掉.
     *
     * 必须有这一步: 送焦可能悬在半路 (目标项还没组合出来), 用户此刻按下键进网格,
     * [TvFocusScope.notifyUserNavigation] 只取消 scope 上的请求, **管不到这里的武装**.
     * 光靠目标下标比对挡不住 —— 见文件末尾那段.
     */
    fun cancelArmedMove() {
        armedTarget = -1
    }

    /**
     * 第 [index] 项获焦的上报 (由 [tvFocusRailItem] 自动挂接).
     *
     * @return 这次获焦是否应当触发"聚焦即选中" —— 仅当它正是本行武装的那个目标
     */
    internal fun onItemFocused(index: Int): Boolean {
        focusedIndex = index
        // 落到别的项上**不解除**武装: 送焦悬在半路时中途可能有别的项短暂获焦 (滚动引起的重组),
        // 真正的目标随后才到; 长按连续换天/换标签靠这条.
        if (armedTarget != index) return false
        armedTarget = -1
        return true
    }
}

/*
 * 武装的作废靠 [TvFocusRailState.cancelArmedMove] (每次新按键先清、左右键随后重新武装),
 * **不是**比对 [TvFocusScope.userNavGeneration]. 两者效果一样, 前者少一个状态, 也不依赖
 * "onUserNavigation 必须先于 moveTo 跑"这个次序约定.
 *
 * 中途一度只靠"目标下标比对"而不解除武装, 理由是"残留窄缝极窄: 选中项从没移到过那一项,
 * 页面兜底落点送的是**选中**那一项". **那个论证有个洞, 已作废**: 送焦请求被取消了, 但
 * `onMove` 里那句 `scrollToItem` 是另起协程跑的, **不受取消影响, 照样把行滚过去了** ——
 * 于是组合窗口移到了包含旧目标的位置, 之后任何一次按遍历顺序分配的兜底焦点 (全局兜底循环 /
 * 焦点悬空后的重分配) 都可能正好落在它上面, 当场改掉选中的那天. 这正是"聚焦即选中"要挡的
 * 那类误判本身.
 *
 * 代价是已知的, 接受: "按右键、紧接着按下键进网格"这种快速连招里, 第一发的选中会被第二发
 * 清掉 —— 焦点圈移过去了而内容没换, 用户得再按一次右键. 但要撞上它, 第二次按键必须早于第一发
 * 的焦点事件派发 (下一帧, ~16ms), 对追番页 (标签恒在屏) 基本不可能; 只有时间表往未组合的胶囊
 * 移动时才有那么一段窗口. 拿它换掉"用户什么都没做、选中项自己变了"是划算的.
 */

/**
 * 创建横向索引栏的焦点状态.
 *
 * @param scope 本行所在页面的焦点调度器 (项的锚点挂在它上面)
 * @param keyAt 第 n 项的锚点 key (页面私有的 data class, 如 `CollectionTabFocusKey(index)`)
 * @param onMove 把焦点送到第 n 项 —— **各页方式不同, 所以留给调用方**: 恒在屏的项直接
 *   `requesterOf(keyAt(n)).requestFocus()`; 可能没组合出来的项走 `scope.request(keyAt(n))`
 *   再滚动 (未组合目标的 requestFocus 是静默 no-op, 而 scope 请求会悬挂到锚点真正附着)
 */
@Composable
fun rememberTvFocusRail(
    scope: TvFocusScope,
    keyAt: (index: Int) -> TvFocusKey,
    onMove: (index: Int) -> Unit,
): TvFocusRailState {
    val currentKeyAt by rememberUpdatedState(keyAt)
    val currentMove by rememberUpdatedState(onMove)
    return remember(scope) {
        TvFocusRailState(scope, { currentKeyAt(it) }, { currentMove(it) })
    }
}

/**
 * 横向索引栏的方向键路由 (挂在行容器上).
 *
 * 左右键在行内显式移动, 不交给默认方向搜索 —— 行右侧通常没有可聚焦目标, 空间搜索会退化成
 * "按遍历顺序取首个", 表现为按住右键在行内无限循环 (追番页实测).
 *
 * 上下键的语义各页差别太大 (回内容区 / 进网格 / 已是最上层就消费掉不动), 原样交回
 * [onNavigateUp] / [onNavigateDown], 返回值即是否消费.
 *
 * @param itemCount 项数 (读 snapshot 状态: 时间表的天数会变)
 * @param onUserNavigation 本行自己要在"新的一次独立按键"上做的事 (如追番页解除标签落点门控).
 *   **不要在这里调 `focus.notifyUserNavigation()`** —— 页面根的 [tvFocusNavSignal] 是外层
 *   onPreviewKeyEvent, 每次按键必先于本行跑过一遍; 再报一次会让一次按键推进两次代数, 网格送焦
 *   的取消判据就得靠"重取基线"去躲那个中间态, 而重取会吞掉真正的用户取消
 *   (见 [TvGridFocusState.SendFocusEffect] 里那段). 本 modifier 已带 [isAutoRepeat] 守卫,
 *   连发不会调到这里
 * @param onNavigateUp 上键; 返回是否消费. 默认不消费 (交回焦点系统, 如追番页标签行上面就是页顶)
 * @param onNavigateDown 下键; 返回是否消费
 * @param consumeLeftEdge 焦点在首项 (或下标未知) 时按左是否消费: true = 消费掉不动
 *   (本行不做左出口, 交回空间搜索的落点不可预测); false = 放行, 由焦点系统接手
 *   (追番页靠它从第一个标签按左进侧边栏)
 * @param preSwallow 页面自己的额外闸门, 返回 true 即当场消费且不再做任何上报/移动
 *   (追番页用它在"从空网格回落标签"的窗口里只吞长按残余连发)
 */
fun Modifier.tvFocusRailKeys(
    state: TvFocusRailState,
    itemCount: () -> Int,
    onNavigateDown: () -> Boolean,
    onUserNavigation: () -> Unit = {},
    onNavigateUp: () -> Boolean = { false },
    consumeLeftEdge: Boolean = false,
    preSwallow: (KeyEvent) -> Boolean = { false },
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    if (preSwallow(event)) return@onPreviewKeyEvent true
    // **系统连发不算新的一次用户介入** —— 与 [tvFocusNavSignal] 保持一致. 页面根的 navSignal 是
    // 外层 onPreviewKeyEvent, 独立按键早已在那里取消过在途送焦, 所以这一句实际只多做"连发也
    // 取消"这一件, 而它是有害的 (理由见 onUserNavigation 的文档).
    if (event.isAutoRepeat != true) {
        onUserNavigation()
        // 新的一次独立按键: 先解除上一次移动的武装 (下面左右键分支会立刻重新武装).
        // 连发不清 —— 长按连续换天/换标签靠每一发的 moveTo 顺延武装.
        state.cancelArmedMove()
    }
    val index = state.focusedIndex
    when (event.key) {
        Key.DirectionUp -> onNavigateUp()

        Key.DirectionDown -> onNavigateDown()

        Key.DirectionLeft ->
            if (index > 0) {
                state.moveTo(index - 1)
                true
            } else if (event.isAutoRepeat == true || index < 0) {
                // 长按滑到首项后的**残余连发**, 以及下标未知的窗口: 一律就地消费.
                // [consumeLeftEdge] = false 的放行只属于"全新按下"那一次 (首标签按左进侧边栏是
                // 一个刻意手势) —— 连发也放行的话会交给空间焦点搜索, 而此刻网格正因"聚焦即选中"
                // 换内容, 搜索会捞到一张即将销毁的卡片: 焦点落上去随分页替换销毁而死, 再被全局
                // 兜底捞回标签 (2026-08-29 真机日志: 长按左到首标签, 焦点闪跳到卡片又弹回,
                // 观感是"不知什么时候跳到卡片上"). 与右键"末项一律消费"对称.
                true
            } else {
                consumeLeftEdge
            }

        // 行内还有下一项就移动过去; 末项与**下标未知**的窗口一律消费掉 —— 不消费就落到默认
        // 方向搜索, 而行右侧没有可聚焦目标, 搜索退化成按遍历顺序取首个, 于是绕回第一项
        // (追番页真机症状: 按住右键在标签间无限循环)
        Key.DirectionRight ->
            if (index in 0..<itemCount() - 1) {
                state.moveTo(index + 1)
                true
            } else {
                true
            }

        else -> false
    }
}

/**
 * 横向索引栏内一项 (每项都挂): 挂锚点 + 上报获焦, 并在该算"用户选的"时调 [onSelectByFocus].
 *
 * @param onFocusChanged 焦点得失的原样透传 (页面另有用途时才传, 如追番页要靠它判断"选中的标签
 *   到位了没"、时间表要靠它记"焦点进过本页")
 * @param onSelectByFocus 本次获焦应当触发"聚焦即选中"时调用 (即这次移焦是本行左右键引发的)
 */
fun Modifier.tvFocusRailItem(
    state: TvFocusRailState,
    index: Int,
    onFocusChanged: (focused: Boolean) -> Unit = {},
    onSelectByFocus: () -> Unit,
): Modifier = this
    .tvFocusAnchor(state.scope, state.keyAt(index))
    .onFocusChanged {
        val selectByFocus = it.isFocused && state.onItemFocused(index)
        onFocusChanged(it.isFocused)
        if (selectByFocus) onSelectByFocus()
    }
