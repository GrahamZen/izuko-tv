/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * 按键差异更新的列表适配器 (leanback 竖列表里的行): 整表交来 (`submitList`), 差异在后台线程算, 项的键 ([keyOf]) 换成稳定的长整数 id.
 * leanback 的列表不回收持焦的那一项, 再加上稳定 id 与不做项动画, 内容变化时持焦的那一行留在原处、焦点不丢.
 * 记着挂在哪个列表上 ([recycler]): 行上的标记 (选中、正在挪) 变了时, 子类对此刻挂着的子视图逐个对一次.
 */
internal abstract class TvNativeKeyedListAdapter<T : Any, VH : RecyclerView.ViewHolder>(
    diff: DiffUtil.ItemCallback<T>,
) : ListAdapter<T, VH>(diff) {
    /** 此刻挂着的列表; 没挂为 null. */
    protected var recycler: RecyclerView? = null
        private set

    private val stableIds = HashMap<String, Long>()
    private var nextStableId = 1L

    init {
        setHasStableIds(true)
    }

    /** 一项的键: 同一项换了内容键不变, 键不同就是不同的项. */
    protected abstract fun keyOf(item: T): String

    final override fun getItemId(position: Int): Long = stableIds.getOrPut(keyOf(getItem(position))) { nextStableId++ }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recycler = null
    }
}

/** 按键比的差异: 键 ([keyOf]) 一样是同一项, [sameContent] 判内容没变 (没变的不重新绑定). */
internal class TvNativeKeyedDiff<T : Any>(
    private val keyOf: (T) -> String,
    private val sameContent: (T, T) -> Boolean,
) : DiffUtil.ItemCallback<T>() {
    override fun areItemsTheSame(oldItem: T, newItem: T): Boolean = keyOf(oldItem) == keyOf(newItem)

    override fun areContentsTheSame(oldItem: T, newItem: T): Boolean = sameContent(oldItem, newItem)
}
