/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect

/**
 * 探索页原生海报墙的几何 (px). 原生根视图从屏幕左缘铺起 (页面与主壳都铺满, 侧边栏盖在上面), [bleedLeftPx] 是侧边栏底下那一截:
 * [pageWidthPx] 与横坐标都按让开它之后的内容区算, 横滑行从这一截里滑过、出屏; 纵坐标 = 页面坐标.
 * 由页面 (TvExplorationPage) 按窗口尺寸与各 TV_EXPLORATION_* 常量算好.
 */
@Immutable
data class TvNativeExploreMetrics(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val bleedLeftPx: Int,
    val columns: Int,
    /** 卡片区顶线 (TV_EXPLORATION_WALL_TOP). */
    val listTopPx: Int,
    val listTopBleedPx: Int,
    val listBottomBleedPx: Int,
    val spacerPx: Int,
    val headerPx: Int,
    val rowPx: Int,
    val rowGapPx: Int,
    /** 顶线到屏幕底边. */
    val viewportPx: Int,
    val endMarginPx: Int,
    /** hero 态聚焦行组标题停的那条线 (列表内容坐标). */
    val heroHeaderTopPx: Int,
    /** 行首停靠线离屏幕左缘 (TV_EXPLORATION_ROW_START_BLEED). */
    val rowStartPx: Int,
    val endPadPx: Int,
    val fadeDistancePx: Float,
    /** 轮播背景图 (未缩放) 的下缘离页面顶. */
    val carouselBottomPx: Float,
    /** 背景图比 hero 占位长出去的那一截 (见 tvNativeCarouselShift). */
    val overhangPx: Int,
    val backdropWidthPx: Int,
    val backdropHeightPx: Int,
    /** hero 态 (聚焦卡) 背景图相对轮播的缩放 (TV_CARD_HERO_TUNING / TV_WALL_BACKDROP_HEIGHT). */
    val cardBackdropScale: Float,
    val heroStartPx: Int,
    val heroTopPx: Int,
    val heroEndPadPx: Int,
    val heroBlockPx: Int,
    val heroBlockExpandedPx: Int,
    val titleWidthPx: Int,
    val carouselSummaryWidthPx: Int,
    val cardSummaryWidthPx: Int,
    val buttonsTopGapPx: Int,
    val buttonGapPx: Int,
    /** 轮播指示器中心离页面顶 (分界线下方 TV_CAROUSEL_INDICATOR_OFFSET). */
    val dotsCenterYPx: Int,
    val dotPx: Float,
    val dotSelectedWidthPx: Float,
    val dotGapPx: Float,
    /** 非当前项的不透明度 (乘在当前项的颜色上). */
    val dotInactiveAlpha: Float,
)

/** 探索页原生海报墙的事件 (页面实现): 行式海报墙的那些 + 顶部热门轮播的按钮与翻页. 行键就是页面焦点簿记用的那套 (TV_FOLLOWED_ROW_KEY / tvRecRowKey). */
interface TvNativeExploreListener : TvNativeRowWallListener {
    /** hero 按钮拿到焦点 ([button] 0 = 立即观看, 1 = 新番时间表). */
    fun onHeroButtonFocused(button: Int)

    fun onHeroButtonClick(button: Int)

    /**
     * 触屏上程序送来的焦点 (落点请求、焦点兜底) 停在了画面的入口节点上, 没送到卡 / 按钮 (平板上海报墙不按焦点滚动, 见 TvExploreComposeSurface.parkFocus):
     * 挂着的落点请求作废.
     */
    fun onFocusParked() {}

    /** hero 按钮上按左 / 右翻轮播; 返回 false = 没翻 (第一项按左: 交给侧边栏). */
    fun onSwitchCarousel(delta: Int): Boolean
}

/**
 * 探索页海报墙纵向列表的一项: 轮播下面垫的 hero 占位、组标题、一条横滑行. 各项定高 (见 [TvNativeExploreMetrics]), 停位直接按高度算.
 */
@Immutable
sealed interface TvNativeExploreItem {
    val key: String

    @Immutable
    data class Spacer(override val key: String) : TvNativeExploreItem

    @Immutable
    data class Header(override val key: String, val title: String) : TvNativeExploreItem

    /**
     * 一条横滑行. [cards] 里的 null 是分页还没到的占位. 卡的稳定 id 按下标: 推荐重算落库时持焦的卡原地换内容,
     * 焦点不被带走 (按条目 id 的话持焦的那张被换掉, 焦点跟着丢).
     */
    @Immutable
    data class Row(override val key: String, val cards: List<TvNativeCard?>) : TvNativeExploreItem
}

/** [items] 里 [rowKey] 那一行的卡; 没有这一行时 null. */
internal fun tvNativeRowCards(items: List<TvNativeExploreItem>, rowKey: String): List<TvNativeCard?>? =
    (items.firstOrNull { it.key == rowKey } as? TvNativeExploreItem.Row)?.cards

/**
 * 探索页卡片墙的画面 (安卓是原生 View [TvNativeExploreView], 别的端是 Compose 版) 给页面与 [TvNativeExploreListener] 那一侧的状态用的操作.
 */
interface TvExploreSurface {
    /** hero 态 (以画面为准). */
    val heroActive: Boolean

    fun setHeroActive(active: Boolean)

    /** 列表停位 (px) 与各行行首 / 上次停的那张: 页面重建时恢复. */
    val scrolledPx: Int
    val rowLeftIndex: Map<String, Int>
    val rowFocusedIndexMap: Map<String, Int>

    /** 两路 hero 内容 (热门轮播 / 聚焦卡). */
    fun setSources(carousel: TvNativeHeroSource, card: TvNativeHeroSource)

    /** 远跳途中当场落地 (返回键分层): 没在远跳返回 false. */
    fun settleFarJump(): Boolean

    /** 焦点在不在卡片墙里. */
    fun hasFocus(): Boolean
}
