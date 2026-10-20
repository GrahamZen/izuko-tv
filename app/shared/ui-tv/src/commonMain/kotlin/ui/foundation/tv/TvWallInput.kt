/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import kotlin.math.abs

/*
 * 海报墙里「按了什么 → 该做什么」的规则 (方向键、确定键、手指横滑、按住连发), 只做判定, 不碰画面与滚动.
 * 海报墙的各个画法 (原生视图、Compose 版) 都按这里判, 两边行为一致; 判完怎么挪焦点、怎么滚由各自去做.
 */

/** 海报墙里的方向键. */
enum class TvWallDirection { Up, Down, Left, Right }

/** 网格海报墙 (追番 / 搜索 / 新番时间表) 上按方向键的结果, 见 [tvWallGridKey]. */
sealed interface TvWallGridKey {
    /** 焦点挪到第 [index] 张. */
    data class MoveTo(val index: Int) : TvWallGridKey

    /** 首行按上: 交给页面 (标签行、搜索框等); 页面不接就放出去. */
    data object TopRowUp : TvWallGridKey

    /**
     * 行首按左 ([direction] = -1) / 行尾或最后一张按右 (+1), 是第 [row] 行: 交给页面 (换标签、跨天、进侧边栏等);
     * 页面不接时按右吞掉、按左放出去 ([swallowWhenUnhandled]).
     */
    data class RowEdge(val direction: Int, val row: Int) : TvWallGridKey {
        val swallowWhenUnhandled: Boolean get() = direction > 0
    }

    /** 吞掉 (末行按下). */
    data object Swallow : TvWallGridKey
}

/**
 * 网格海报墙上焦点在第 [index] 张 (共 [count] 张、每行 [columns] 张) 时按 [direction]: 上下同一列, 左右同一行;
 * 下面没有同列的卡、又不在末行时落到最后一张; 末行按下吞掉; 首行按上与左右行缘交给页面.
 */
fun tvWallGridKey(direction: TvWallDirection, index: Int, count: Int, columns: Int): TvWallGridKey {
    val cols = columns.coerceAtLeast(1)
    return when (direction) {
        TvWallDirection.Up -> if (index < cols) TvWallGridKey.TopRowUp else TvWallGridKey.MoveTo(index - cols)
        TvWallDirection.Down -> when {
            index + cols < count -> TvWallGridKey.MoveTo(index + cols)
            index / cols < (count - 1) / cols -> TvWallGridKey.MoveTo(count - 1)
            else -> TvWallGridKey.Swallow
        }

        TvWallDirection.Left ->
            if (index % cols == 0) TvWallGridKey.RowEdge(-1, index / cols) else TvWallGridKey.MoveTo(index - 1)

        TvWallDirection.Right ->
            if (index % cols == cols - 1 || index == count - 1) TvWallGridKey.RowEdge(1, index / cols) else TvWallGridKey.MoveTo(index + 1)
    }
}

/** 确认海报墙上的一张卡 (按确定, 或点已经聚焦的那张) 的结果, 见 [tvWallConfirm]. */
enum class TvWallConfirm {
    /** 「更多」卡: 打开那一类的完整列表. */
    More,

    /** 卡片墙上先进 hero 态 (海报墙确定键两步的第一步). */
    EnterHero,

    /** 打开这一部 (进详情页). */
    Open,
}

/**
 * 确认一张卡: 「更多」卡打开完整列表; 这一页有 hero 态 ([heroEnabled]) 而此刻在卡片墙上 (不在 hero 态) 时先进 hero 态,
 * hero 态里 (或这一页没有 hero 态) 才打开.
 */
fun tvWallConfirm(isMore: Boolean, heroEnabled: Boolean, heroActive: Boolean): TvWallConfirm = when {
    isMore -> TvWallConfirm.More
    heroEnabled && !heroActive -> TvWallConfirm.EnterHero
    else -> TvWallConfirm.Open
}

/**
 * 触屏上点一张还没聚焦的卡时, 同一下是否也确认: 卡片墙上点一下 = 聚焦并进 hero 态;
 * hero 态里点另一张只换焦点 (再点才打开), 「更多」卡也只聚焦.
 */
fun tvWallTapConfirmsWhileFocusing(isMore: Boolean, heroEnabled: Boolean, heroActive: Boolean): Boolean =
    tvWallConfirm(isMore, heroEnabled, heroActive) == TvWallConfirm.EnterHero

/** 按触屏的规矩走时 (平板: 按下不动焦点, 轻点抬起才算) 手指点一张卡的结果, 见 [tvWallTouchTap]. */
enum class TvWallTouchTap {
    /** 当成点 hero 区: 打开聚焦的那张. */
    OpenFocused,

    /** 只把焦点换到这张 (hero 态里换 hero 内容). */
    FocusOnly,

    /** 聚焦这张并确认 (卡片墙上一下进 hero 态). */
    FocusAndConfirm,

    /** 确认 (同按确定, 见 [tvWallConfirm]). */
    Confirm,
}

/**
 * 按触屏的规矩走时手指点一张卡: hero 态里点到聚焦行上面那几行 ([aboveFocusedRow], 它们淡没在 hero 区里, 看不见) 算点 hero 区,
 * 点没聚焦的那张 ([isFocused] 为 false) 只换焦点、再点才打开; 卡片墙上点一下就聚焦并进 hero 态 (见 [tvWallTapConfirmsWhileFocusing]);
 * 「更多」卡与没有 hero 态的页面上点一下就确认.
 */
fun tvWallTouchTap(
    isMore: Boolean,
    heroEnabled: Boolean,
    heroActive: Boolean,
    isFocused: Boolean,
    aboveFocusedRow: Boolean,
): TvWallTouchTap = when {
    heroActive && aboveFocusedRow -> TvWallTouchTap.OpenFocused
    heroActive && !isFocused -> TvWallTouchTap.FocusOnly
    tvWallTapConfirmsWhileFocusing(isMore, heroEnabled, heroActive) -> TvWallTouchTap.FocusAndConfirm
    else -> TvWallTouchTap.Confirm
}

/** 手指横滑的接管判定, 见 [tvWallSwipeClaim]. */
enum class TvWallSwipeClaim {
    /** 还没越过 slop, 接着看. */
    Undecided,

    /** 横着先越过且横向占优: 接管这个手势 (子视图收到取消). */
    Claim,

    /** 竖着先越过: 整个手势放给列表. */
    Release,
}

/** 手指从按下点挪了 ([dx], [dy]) 时横滑接不接管: 横着越过 [slop] 且横向占优才接管, 竖着先越过就放手. */
fun tvWallSwipeClaim(dx: Float, dy: Float, slop: Float): TvWallSwipeClaim {
    val ax = abs(dx)
    val ay = abs(dy)
    return when {
        ax > slop && ax > ay -> TvWallSwipeClaim.Claim
        ay > slop -> TvWallSwipeClaim.Release
        else -> TvWallSwipeClaim.Undecided
    }
}

/**
 * 接管了的横滑松手时翻几项: 横向位移 [dx] 过 [TV_WALL_SWIPE_DISTANCE_DP], 或横向速度 [velocityX] (每秒) 过 [TV_WALL_SWIPE_VELOCITY_DP]
 * 且方向与位移一致, 就翻一项 —— 左滑 +1 (下一项), 右滑 -1 (上一项); 否则 0. [density] 是 dp 换像素的倍数.
 */
fun tvWallSwipeStep(dx: Float, velocityX: Float, density: Float): Int {
    val farEnough = abs(dx) >= TV_WALL_SWIPE_DISTANCE_DP * density
    val fastEnough = abs(velocityX) >= TV_WALL_SWIPE_VELOCITY_DP * density && velocityX * dx > 0f
    return if (farEnough || fastEnough) (if (dx < 0f) 1 else -1) else 0
}

/** 横滑翻一项要的横向位移. */
const val TV_WALL_SWIPE_DISTANCE_DP = 64f

/** 位移不够时, 横向速度过这么多也翻 (同 Compose 分页器的翻页速度门槛). */
const val TV_WALL_SWIPE_VELOCITY_DP = 400f

/**
 * 按住方向键连发的限速: 连发 (repeatCount > 0) 里离上一发不到 [intervalMillis] 的吞掉; 新按下 (repeatCount = 0) 不限, 也不记.
 * 横、纵各用一个.
 */
class TvKeyRepeatGate(var intervalMillis: Long) {
    private var lastPassedMillis = 0L

    /** 这一发放不放行. [nowMillis] 用单调时钟. */
    fun pass(repeatCount: Int, nowMillis: Long): Boolean {
        if (repeatCount == 0) return true
        if (nowMillis - lastPassedMillis < intervalMillis) return false
        lastPassedMillis = nowMillis
        return true
    }
}
