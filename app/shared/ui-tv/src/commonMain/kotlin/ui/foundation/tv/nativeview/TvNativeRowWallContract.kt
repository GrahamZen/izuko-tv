/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.ui.geometry.Rect

/** 行式海报墙的事件 (页面实现). 行键就是页面焦点簿记用的那套. */
interface TvNativeRowWallListener {
    /** 卡片拿到焦点: [column] = 行按需挪之后它落在屏上第几列. */
    fun onCardFocused(rowKey: String, index: Int, column: Int)

    /**
     * 卡片上的确定键: hero 态里 = 进详情页; 卡片墙上按确定先进 hero 态, 不回调. 没有 hero 态 ([TvNativeRowWallView.heroEnabled] = false) 时
     * = 卡片墙上的确定 (页面按自己的规则播放或进详情页).
     */
    fun onCardClick(rowKey: String, index: Int)

    fun onCardLongPress(rowKey: String, index: Int, anchor: Rect)

    /** 行尾「更多」卡 ([TvNativeCard.more]) 上按确定: 卡片墙上与 hero 态里都直接回调, 不进 hero 态、不进详情页. */
    fun onMoreClick(rowKey: String) {}

    /** 卡片被绑定 (分页的访问提示). */
    fun onBindCard(rowKey: String, index: Int)

    /** 按左出页面 (进侧边栏). */
    fun onExitLeft()

    /** 行尾按右 (这一下不在途中): 页面要换到右边那一页时接; 默认吞掉. */
    fun onExitRight() {}

    /** 首行按上、或卡片行全没了: 交给页面 (回标签行等); 没有顶部区域时用. 返回 false = 吞掉. */
    fun onTopRowUp(): Boolean = false

    /** 进 / 出 hero 态 (页面存起来, 返回本页时恢复). */
    fun onHeroActiveChanged(active: Boolean)

    /** 焦点进出卡片区. */
    fun onCardAreaFocusChanged(hasFocus: Boolean)

    /** 整屏黑度 (hero 态时间线的 tone, 见 TvNativeHeroTimeline) 与顶部区域的分界线 (px, 从页面顶算; 探索页的轮播) 变了: 交给主壳画整屏底色. */
    fun onToneChanged(tone: Float, splitY: Float)

    /** 滚动容器在不在挪 (hero 文字滚动期间藏起来, 见 TvScrollActivity). */
    fun onScrollingChanged(scrolling: Boolean)

    /** hero 态模糊背景点开途中 (对焦还没到位、还没进详情页, 见 [TvNativeRowWallView.heroBlur]): 这期间返回键取消点开. */
    fun onWallOpeningChanged(opening: Boolean) {}

    /** 点开对焦到位, 这就进详情页 (紧接着是 [onCardClick]): 回到本页时页面调 [TvNativeRowWallView.endWallOpen] 倒放. */
    fun onWallOpened() {}
}

/**
 * 行式海报墙在页面状态那一侧看到的样子 (安卓上是原生的 TvNativeRowWallView): 追番页「追更」那份一组一行的内容 (见 TvNativeGridPageState.rows)
 * 由页面接数据与事件、记停位、跨标签送焦.
 */
interface TvRowWallSurface {
    var rowListener: TvNativeRowWallListener?

    /** 有没有 hero 态 (卡片墙上按确定先进 hero 态). */
    var heroEnabled: Boolean

    /** hero 态里组标题也淡没. */
    var hideHeadersInHero: Boolean

    /** 组标题没聚焦时的不透明度. */
    var headerIdleAlpha: Float

    /** 触屏设备 (平板模式). */
    var touchInput: Boolean

    val heroActive: Boolean
    val scrolledPx: Int
    val rowLeftIndex: Map<String, Int>
    val rowFocusedIndexMap: Map<String, Int>

    fun setItems(items: List<TvNativeExploreItem>)

    /** 进页恢复: 列表停位、各行行首与上次停的那张、焦点簿记 (直接到位, 不走过渡). */
    fun restore(
        scrollPx: Int,
        rowLeftIndex: Map<String, Int>,
        rowFocusedIndex: Map<String, Int>,
        focusedRowKey: String?,
        focusedCardIndex: Int,
        heroActive: Boolean = false,
        holdLook: Boolean = true,
    )

    /** 从旁边的网格进来 (卡片墙): 落在纵向离 [windowY] (窗口坐标) 最近的那一行的行缘那张. */
    fun enterNearRow(windowY: Int, fromLeft: Boolean = false)

    /** 从旁边的网格进来 (hero 态): 落在第 [number] 行的行缘那张. */
    fun enterRow(number: Int, fromLeft: Boolean = false)

    /** 焦点所在的是第几行; 焦点不在卡上时 null. */
    fun focusedRowNumber(): Int?

    /** 聚焦那张卡的纵向中心 (窗口坐标); 没有时 null. */
    fun focusedCardCenterYInWindow(): Int?

    /** 送焦到 [rowKey] 行的第 [index] 张 (-1 = 这一行上次停的那张); [far] = 远跳. */
    fun focusCard(rowKey: String, index: Int, far: Boolean = false): Boolean

    /** 远跳还在滚时又按了返回: 当场落到目标 (返回 true). */
    fun landFarJump(): Boolean

    /** 第一行的行键; 没有行时 null. */
    fun firstRowKey(): String?

    /** 把焦点送进卡片区 (上次那张). */
    fun requestCardsFocus(): Boolean
}
