/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import me.him188.ani.app.ui.foundation.focus.TvGridFocusState
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageState

/*
 * 顶上一排胶囊 (聚焦即选中)、下面按选中项换内容的海报墙 —— 追番页的分类标签、新番时间表的日期 —— 共用的选中规则与进墙落点.
 * 胶囊本身 (外观、焦点接线、各自判断选没选中) 见 TvCollectionGlassRailTab, 左右键路由见 TvFocusRail.kt.
 *
 * 留在各页的是真不一样的部分: 胶囊行怎么排 (一条玻璃条里固定几个 / 横向滚、聚焦居中)、左边有没有出口、从海报墙边上跨到相邻一项的规则
 * (行对行 / 按时间线接着走)、换过去之后海报墙从哪开始 (各项记各自的位置 / 从第一行排起)、选中项存在哪 (ViewModel / 页面状态).
 */

/**
 * 胶囊行选中的那一项 ([selected]) 与海报墙显示的那一项 (页面给的 wall). 经 [rememberTvRailWallSelection] 创建.
 *
 * 两者分开走: 胶囊上左右移 ([moveTo]) 当场改 [selected], 胶囊的选中态跟着焦点走; 海报墙等方向键松开才跟上 —— 按住时一路只挪胶囊,
 * 松手换一次; 快速连点停下来换一次; 单按松手就换. 规则与 hero 背景图同一条: [TvNavigationSettle] 合并连发, [TvNavKeyTracker] 等抬起
 * (按住的第一格与单按分不出来, 只能等抬起, 见 TvNavKeyTracker).
 * 点按胶囊、从海报墙边上跨过去、页面自己定选中项用 [select], 当场一起换; 按下键进海报墙用 [enterWall], 胶囊上的选择先当场上墙.
 *
 * 线程模型: 全部在主线程 (按键分发 / 焦点回调 / 组合的协程).
 */
@Stable
class TvRailWallSelection<T> internal constructor(
    initial: T,
    private val wall: () -> T,
    private val showOnWall: (item: T, fromRail: Boolean) -> Unit,
) {
    /**
     * 胶囊行上选中的那一项. 胶囊各自读 (换一格只有选中态变了的两枚重组); 页面在按键回调与协程里读, 不在页面的组合里读 ——
     * 按住方向键时每一发都变, 读在页面上就是每一发整页重组.
     */
    var selected: T by mutableStateOf(initial)
        private set

    /** 胶囊行上左右移到了 [item] (聚焦即选中): 胶囊当场换, 海报墙等方向键松开. */
    fun moveTo(item: T) {
        selected = item
    }

    /**
     * 胶囊与海报墙当场一起换到 [item].
     *
     * @param fromRail 是胶囊行上的选择 (点按胶囊) 还是别处来的 (从海报墙边上跨过去、页面自己定的), 原样交给页面换海报墙
     */
    fun select(item: T, fromRail: Boolean) {
        selected = item
        if (wall() != item) showOnWall(item, fromRail)
    }

    /** 胶囊上还没上墙的选择当场上墙. 返回海报墙这一下换没换. */
    fun commit(): Boolean {
        val item = selected
        if (wall() == item) return false
        showOnWall(item, true)
        return true
    }
}

/**
 * 创建 [TvRailWallSelection], 并挂上"方向键松开海报墙才跟上"的协程.
 *
 * @param wall 海报墙此刻显示的那一项 (追番页是 ViewModel 里选中的分类, 时间表是页面状态); 胶囊的初始选中取它
 * @param showOnWall 把海报墙换成那一项. fromRail = 这次是胶囊行上的选择 (左右移之后上墙、点按胶囊、下键进墙之前), false = 从海报墙边上
 *   跨过去 / 页面自己定的
 */
@Composable
fun <T> rememberTvRailWallSelection(
    wall: () -> T,
    showOnWall: (item: T, fromRail: Boolean) -> Unit,
): TvRailWallSelection<T> {
    val currentWall by rememberUpdatedState(wall)
    val currentShowOnWall by rememberUpdatedState(showOnWall)
    val selection = remember {
        TvRailWallSelection(
            // 只读不订阅: 调用方的组合不因此多读一份状态
            initial = Snapshot.withoutReadObservation { wall() },
            wall = { currentWall() },
            showOnWall = { item, fromRail -> currentShowOnWall(item, fromRail) },
        )
    }
    val navKeys = LocalTvNavKeyTracker.current
    LaunchedEffect(selection, navKeys) {
        // 空闲后的第一下不等, 连着的 (按住的自动重复 / 快速连点) 等静默; 再等方向键抬起 —— 按住期间一次都不换
        val settle = TvNavigationSettle(TV_NAV_SETTLE_MILLIS)
        snapshotFlow { selection.selected }.collectLatest { item ->
            if (item == currentWall()) return@collectLatest
            settle.awaitTurn()
            if (navKeys != null) snapshotFlow { navKeys.held }.first { !it }
            selection.commit()
        }
    }
    return selection
}

/**
 * 胶囊行按下键进海报墙: 胶囊上还没上墙的选择先当场上墙 ([TvRailWallSelection.commit]). 墙上有卡时, 海报墙没换内容、焦点在这份墙上
 * 待过 ([visitedWall]) 就回到刚才看的那一行 (此刻屏上网格顶线以下那一行); 否则从第一行进, 送焦时网格一并滚回顶上 —— 各项的网格记着
 * 上次的位置, 换过去的那份可能停在中间. 没有卡交给 [onNoCards] (错误横幅). 送焦走统一落点解析 ([TvGridFocusState.focusRowEdge]: 等数据、
 * 到位确认).
 *
 * 这一下总是吃掉: 交给默认方向搜索的话, 换内容的滑动途中旧那份的卡还在屏上, 焦点会落到马上就不显示的卡上.
 *
 * @param hasCards 进去的那份有没有卡. switched = 这一下刚换了内容: 屏上还是换之前那份 (下一帧才换), 不能按屏上的网格判断
 */
fun <T> TvRailWallSelection<T>.enterWall(
    gridFocus: TvGridFocusState,
    wall: TvNativeGridPageState,
    visitedWall: () -> Boolean,
    hasCards: (switched: Boolean) -> Boolean,
    onNoCards: () -> Unit,
): Boolean {
    val switched = commit()
    if (!hasCards(switched)) {
        onNoCards()
        return true
    }
    val row = if (!switched && visitedWall()) {
        wall.view?.let { view ->
            view.firstIndexBelowTopLine()?.let { it / (view.grid?.metrics?.columns ?: 1).coerceAtLeast(1) }
        }
    } else {
        null
    }
    gridFocus.focusRowEdge(row ?: 0, direction = 1)
    return true
}
