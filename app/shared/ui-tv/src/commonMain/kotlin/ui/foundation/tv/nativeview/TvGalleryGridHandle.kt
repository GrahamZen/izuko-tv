/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.runtime.staticCompositionLocalOf

/** 画廊网格在 [TvGalleryGridHandle] 眼里的样子 (安卓的原生网格 TvNativeGalleryGridView 实现). */
interface TvGalleryGridViewHandle {
    /** 选中的那格; -1 = 没有. */
    fun currentSelection(): Int

    /** 此刻排出来了几格 (0 = 还没排, 或没有内容). */
    fun laidOutCount(): Int

    /** 选中第一格 (网格滚回顶上). */
    fun selectFirst()

    /** 选中看得见的最下一整行里横向离 [windowX] (窗口坐标) 最近的那格; 一格都还没排出来时不动, 返回 false. */
    fun selectNearestBottom(windowX: Float): Boolean

    /** 选中第一屏里最右列 ([rightColumn]) / 最左列、纵向离 [windowY] (窗口坐标) 最近的那一行的那格; 一格都还没排出来时不动, 返回 false. */
    fun selectEdgeNearest(rightColumn: Boolean, windowY: Float): Boolean
}

/**
 * 播放器胶囊聚焦时就显示的画廊 (相关推荐 / 角色 / 制作人员, 见 ui-tv 的 TvPlayerDetailLayers) 拿来摆布网格的把手, 经 [LocalTvGalleryGridHandle]
 * 交给网格: 焦点进来过之前网格只排看得见的格 (安卓原生网格的 lazyUntilFocused); 网格重建 (换胶囊再回来、样式变了) 时落回原来选中的那格.
 */
class TvGalleryGridHandle {
    internal var view: TvGalleryGridViewHandle? = null

    /** 网格拆掉时选中的那一格: 重建时落回去 (换胶囊再回来停在原处); -1 = 没有. */
    internal var savedSelection: Int = -1

    /** 焦点进来过 (之后网格自己记着选中那格, 送焦点进来就落在它上面). */
    var entered: Boolean = false
        internal set

    /** 选中看得见的最下一整行里横向离 [windowX] 最近的那格 (见 [TvGalleryGridViewHandle.selectNearestBottom]). */
    fun selectNearestBottom(windowX: Float): Boolean = view?.selectNearestBottom(windowX) ?: false

    /** 最左列按左 / 最右列按右时调 (参数: 往右走、按键时选中格在窗口里的纵坐标); 由播放器的画廊那一层设. */
    var onExitSide: ((toRight: Boolean, windowY: Float) -> Unit)? = null

    /** 见 [TvGalleryGridViewHandle.selectEdgeNearest]. */
    fun selectEdgeNearest(rightColumn: Boolean, windowY: Float): Boolean = view?.selectEdgeNearest(rightColumn, windowY) ?: false

    /** 选中第一格 (网格滚回顶上). */
    fun selectFirst() {
        view?.selectFirst()
    }
}

/** 见 [TvGalleryGridHandle]; null = 平常的画廊 (打开就进焦点, 照常多排). */
val LocalTvGalleryGridHandle = staticCompositionLocalOf<TvGalleryGridHandle?> { null }

/**
 * 没有 [TvGalleryGridHandle] 的画廊 (播放器里人物页叠出来的「查看全部」) 最左列按左 / 最右边按右时调, 参数同 [TvGalleryGridHandle.onExitSide];
 * null = 到边停住. 有把手的画廊用把手上的那个.
 */
val LocalTvGalleryExitSide = staticCompositionLocalOf<((toRight: Boolean, windowY: Float) -> Unit)?> { null }
