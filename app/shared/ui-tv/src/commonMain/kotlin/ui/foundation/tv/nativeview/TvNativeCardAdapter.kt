/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.panpf.sketch.Sketch

/**
 * 原生海报墙卡片的事件. [index] 是卡在这一组 (一行 / 一个网格) 里的下标.
 *
 * - [onFocused]: 卡拿到焦点 (页面据此记焦点、喂 hero 媒体流水线);
 * - [onClick]: 确定键短按 (抬起时);
 * - [onLongPress]: 确定键按住到阈值 (弹收藏菜单, 同 Compose 版 tvLongPressKey: 至少两次按下且按住 [TV_NATIVE_LONG_PRESS_MILLIS]),
 *   [anchor] 是卡片封面在窗口里的框 (菜单锚点).
 */
interface TvNativeCardListener {
    fun onFocused(index: Int)
    fun onClick(index: Int)
    fun onLongPress(index: Int, anchor: Rect) {}
}

/**
 * 一组海报卡的适配器 (横滑行与网格共用). [cards] 里的 null 是分页还没到的占位 (照样可聚焦, 画占位底色). 稳定 id 由 [keyOf] 给
 * (分页里同一部可能重复出现, 搜索页的键带下标, 见调用方).
 */
@SuppressLint("NotifyDataSetChanged")
class TvNativeCardAdapter(
    private val style: TvNativeWallStyle,
    private val sketch: Sketch,
) : RecyclerView.Adapter<TvNativeCardAdapter.Holder>() {
    var listener: TvNativeCardListener? = null
    private var cards: List<TvNativeCard?> = emptyList()
    private var keyOf: (Int) -> Long = { it.toLong() }

    /** 卡片整体的压暗 / 淡化 (0..1, 见 [TvNativeCardView.dim]), 按卡的下标给; 刷新请调 [refreshDim]. */
    var dimOf: ((index: Int, view: TvNativeCardView) -> Float)? = null

    /** 番名显隐 (hero 态淡出), 对这一组所有卡都一样. */
    var titleVisibility: Float = 1f
        private set

    /** 远跳途中压住聚焦效果 (见 [TvNativeCardView.focusEffectSuppressed]); 改用 [setFocusEffectSuppressed]. */
    var focusEffectSuppressed: Boolean = false
        private set

    /** 第几张卡被绑定 (分页的访问提示: 页面据此让分页往后取). */
    var onBind: ((index: Int) -> Unit)? = null

    init {
        setHasStableIds(true)
    }

    val items: List<TvNativeCard?> get() = cards

    /** 换数据. 内容没变就不动 (稳定 id 让持焦的卡原样留着). */
    fun submit(cards: List<TvNativeCard?>, keyOf: (Int) -> Long) {
        if (cards == this.cards) return
        this.cards = cards
        this.keyOf = keyOf
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = cards.size

    override fun getItemId(position: Int): Long = keyOf(position)

    override fun getItemViewType(position: Int): Int = TV_NATIVE_CARD_VIEW_TYPE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val holder = Holder(TvNativeCardView(parent.context, style))
        // 事件按此刻绑定这张卡的适配器派发, 不按建它的这个: 探索页各行共用回收池, 卡会换到别的行 (别的适配器) 里去
        holder.card.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            val index = holder.bindingAdapterPosition
            if (hasFocus && index != RecyclerView.NO_POSITION) holder.owner?.listener?.onFocused(index)
        }
        holder.card.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) holder.owner?.listener?.onClick(index)
        }
        holder.card.longPressHandler = { anchor ->
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) holder.owner?.listener?.onLongPress(index, anchor)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = cards[position]
        if (card != null) {
            holder.card.bind(card, sketch)
        } else {
            holder.card.bind(TV_NATIVE_PLACEHOLDER_CARD, sketch)
        }
        holder.card.titleVisibility = titleVisibility
        holder.card.focusEffectSuppressed = focusEffectSuppressed
        holder.card.dim = dimOf?.invoke(position, holder.card) ?: 1f
        onBind?.invoke(position)
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        // 回收缓存里原样拿回同一位置的卡不重绑: 按组统一的状态在重新上屏时补上 (离屏期间改的只落到了当时屏上那些卡)
        holder.card.titleVisibility = titleVisibility
        holder.card.focusEffectSuppressed = focusEffectSuppressed
    }

    fun setTitleVisibility(recycler: RecyclerView, value: Float) {
        if (titleVisibility == value) return
        titleVisibility = value
        forEachCard(recycler) { _, card -> card.titleVisibility = value }
    }

    /** 压住 / 放开这一组所有卡的聚焦效果 (屏上的当场改, 离屏的重新上屏时补, 见 [onViewAttachedToWindow]). */
    fun setFocusEffectSuppressed(recycler: RecyclerView, value: Boolean) {
        focusEffectSuppressed = value
        for (i in 0 until recycler.childCount) (recycler.getChildAt(i) as? TvNativeCardView)?.focusEffectSuppressed = value
    }

    /** 按 [dimOf] 重算屏上每张卡的压暗 (滚动中 / 淡化动画每帧调). */
    fun refreshDim(recycler: RecyclerView) {
        val dim = dimOf ?: return
        forEachCard(recycler) { index, card -> card.dim = dim(index, card) }
    }

    private inline fun forEachCard(recycler: RecyclerView, action: (Int, TvNativeCardView) -> Unit) {
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i) as? TvNativeCardView ?: continue
            val index = recycler.getChildAdapterPosition(child)
            if (index != RecyclerView.NO_POSITION) action(index, child)
        }
    }

    class Holder(val card: TvNativeCardView) : RecyclerView.ViewHolder(card) {
        /** 此刻绑定这张卡的适配器 (共用回收池时不一定是建它的那个). */
        val owner: TvNativeCardAdapter? get() = bindingAdapter as? TvNativeCardAdapter
    }
}

/** 确定键按住多久算长按 (同 Compose 版 tvLongPressKey 的阈值). */
internal const val TV_NATIVE_LONG_PRESS_MILLIS = 350L

internal const val TV_NATIVE_CARD_VIEW_TYPE = 0x7a11

private val TV_NATIVE_PLACEHOLDER_CARD = TvNativeCard(imageUrl = null, title = "")

/**
 * 确定键的长按判定 (同 Compose 版 tvLongPressKey): 按下后至少再来一次按下 (自动重复) 且按住满 [TV_NATIVE_LONG_PRESS_MILLIS] 就当场触发长按,
 * 之后到抬起的确定键全部吞掉 (不再算点击); 没到阈值就抬起 = 点击. 返回 true = 这次按键已处理.
 */
internal class TvNativeConfirmKey {
    private var downAt = -1L
    private var downCount = 0
    private var fired = false

    fun onKey(view: View, event: KeyEvent, onLongPress: (() -> Unit)?): Boolean {
        if (!isConfirm(event.keyCode)) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    downAt = SystemClock.uptimeMillis()
                    downCount = 1
                    fired = false
                    view.isPressed = true
                } else {
                    downCount++
                }
                if (!fired && onLongPress != null && downCount >= 2 &&
                    SystemClock.uptimeMillis() - downAt >= TV_NATIVE_LONG_PRESS_MILLIS
                ) {
                    fired = true
                    view.isPressed = false
                    onLongPress()
                }
                return true
            }

            KeyEvent.ACTION_UP -> {
                val wasDown = downAt >= 0
                downAt = -1
                view.isPressed = false
                if (wasDown && !fired) view.performClick()
                fired = false
                return true
            }
        }
        return false
    }

    private fun isConfirm(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
}
