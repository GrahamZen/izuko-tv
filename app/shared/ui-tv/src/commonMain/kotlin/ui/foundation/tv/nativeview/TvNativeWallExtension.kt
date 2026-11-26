/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.view.View
import android.view.ViewGroup

/**
 * 海报墙的扩展: 页面往墙上放自己的一块原生视图 (如新番时间表顶上的日期行), 不用给墙写子类. 网格页 ([TvNativeGridPageView.addExtension])
 * 与行式海报墙 ([TvNativeRowWallView.addExtension]) 都能挂, 墙对扩展做的事两边一样:
 * - [views] 加在卡片上面; 墙量 / 排自己的时候调 [onMeasure] / [onLayout], 扩展自己量自己排 (坐标同墙: 页面坐标, 横向含侧边栏底下那一截);
 * - hero 时间线的每一帧、每次排版之后调 [onFrame]; 切进 / 切出 hero 态时调 [onHeroActiveChanged];
 * - 焦点在扩展的视图里时方向键先给 [onKey]; hero 态里聚焦卡上的上下键先问 [onHeroVerticalKey], 确认键先问 [onHeroConfirm], 接了 (返回 true)
 *   墙就不再处理 (不换行 / 不进详情页);
 * - 焦点进出扩展的视图时调 [onFocusInside].
 * 扩展经 [TvNativeWallHost] 读墙此刻的样子、把焦点送回卡上. 一个扩展同时只挂在一面墙上: 墙重建时挂到新那面, 视图跟着搬过去.
 * 经网格页宿主 ([TvNativeGridPageHost] 的 extensions) 挂时按公共类型 [TvGridPageExtension] 传.
 */
interface TvNativeWallExtension : TvGridPageExtension {
    val views: List<View>

    /** 挂上 [wall] (页面要在回调以外驱动墙时记下它). */
    fun onAttach(wall: TvNativeWallHost) {}

    fun onMeasure(wall: TvNativeWallHost) {}

    fun onLayout(wall: TvNativeWallHost) {}

    fun onFrame(wall: TvNativeWallHost) {}

    /** 切进 / 切出 hero 态 (墙的状态已经换好, 页面的回调也报过了). */
    fun onHeroActiveChanged(wall: TvNativeWallHost, active: Boolean) {}

    /** 焦点 ([focused]) 在扩展的视图里时的方向键 ([keyCode]); 接了返回 true. */
    fun onKey(wall: TvNativeWallHost, focused: View, keyCode: Int): Boolean = false

    /** hero 态里聚焦卡上的上下键 ([down] = 下); 接了返回 true. 按住的连发也问这里. */
    fun onHeroVerticalKey(wall: TvNativeWallHost, down: Boolean): Boolean = false

    /** hero 态里聚焦卡上的确认键; 接了返回 true (不进详情页). */
    fun onHeroConfirm(wall: TvNativeWallHost): Boolean = false

    fun onFocusInside(wall: TvNativeWallHost, inside: Boolean) {}
}

/** 墙给扩展的那一面: 几何 (px, 墙的坐标)、hero 态、焦点. */
interface TvNativeWallHost {
    /** 页面左缘 (侧边栏底下那一截的宽). */
    val pageLeftPx: Int
    val pageWidthPx: Int
    val pageHeightPx: Int

    /** 卡片区的左缘 / 右缘: 第一列卡片的左边、最后一列卡片的右边. */
    val cardsStartPx: Int
    val cardsEndPx: Int

    /** 在 hero 态 (切进去当场为 true, 不等淡入). */
    val heroActive: Boolean

    /** 聚焦卡的 hero 内容此刻显示到什么程度 (0..1, 同 hero 文字那一层; 显示的是别的内容时为 0, 如探索页的轮播). */
    val heroShown: Float

    /** 焦点送回聚焦的那张卡 (hero 态里就是 hero 那张); 没有卡时 false. */
    fun focusCurrentCard(): Boolean

    /** 退出 hero 态 (同页面按返回). */
    fun exitHero()

    /** 焦点不在卡上时聚焦的那张卡仍画成聚焦态 ([hold] = false 放开; 卡重新拿到焦点时自然接上). */
    fun holdCardLook(hold: Boolean)
}

/** 一面墙挂着的扩展: 两种墙共用的簿记 (加视图、转发各回调、记焦点在哪个扩展里). */
internal class TvNativeWallExtensions(private val wall: ViewGroup, private val host: TvNativeWallHost) {
    private val items = ArrayList<TvNativeWallExtension>(1)
    private var focused: TvNativeWallExtension? = null

    fun add(extension: TvNativeWallExtension) {
        if (extension in items) return
        items += extension
        for (view in extension.views) {
            // 墙重建: 从旧的那面搬过来
            (view.parent as? ViewGroup)?.removeView(view)
            wall.addView(view)
        }
        extension.onAttach(host)
        wall.requestLayout()
    }

    fun remove(extension: TvNativeWallExtension) {
        if (!items.remove(extension)) return
        if (focused === extension) {
            focused = null
            extension.onFocusInside(host, false)
        }
        for (view in extension.views) if (view.parent === wall) wall.removeView(view)
    }

    fun measure() = items.forEach { it.onMeasure(host) }

    fun layout() = items.forEach { it.onLayout(host) }

    fun frame() = items.forEach { it.onFrame(host) }

    fun heroActiveChanged(active: Boolean) = items.forEach { it.onHeroActiveChanged(host, active) }

    /** 焦点 ([focused]) 在哪个扩展的视图里就把方向键 [keyCode] 给它. */
    fun key(focused: View?, keyCode: Int): Boolean {
        val view = focused ?: return false
        return ownerOf(view)?.onKey(host, view, keyCode) == true
    }

    fun heroVerticalKey(down: Boolean): Boolean = items.any { it.onHeroVerticalKey(host, down) }

    fun heroConfirm(): Boolean = items.any { it.onHeroConfirm(host) }

    /** 焦点换到了 [newFocus] (null = 离开了墙): 进出扩展的视图时告诉它. */
    fun focusChanged(newFocus: View?) {
        val owner = newFocus?.let { ownerOf(it) }
        if (owner === focused) return
        val old = focused
        focused = owner
        old?.onFocusInside(host, false)
        owner?.onFocusInside(host, true)
    }

    private fun ownerOf(view: View): TvNativeWallExtension? =
        items.firstOrNull { e -> e.views.any { tvNativeIsInside(view, it) } }
}
