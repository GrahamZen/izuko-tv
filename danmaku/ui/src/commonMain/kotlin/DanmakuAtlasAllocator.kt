/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.ui

/**
 * 弹幕图集里的位置分配 (见 [DanmakuLayer]): 按行放, 同一行放高度相近的图, 行内从左往右排.
 * 放不下时腾出最久没画过的一行 (弹幕从右往左滚过屏幕, 早放进去的那几行先整行用不上);
 * 每一行都还在用时才整张清空. 只管位置, 上传由调用方做. 不是线程安全的.
 *
 * 每一帧先调 [beginFrame], 再对要画的每条弹幕调 [get], 没有的调 [allocate].
 */
internal class DanmakuAtlasAllocator<K : Any>(
    val width: Int,
    val height: Int,
    private val gap: Int = 2,
) {
    class Slot(val x: Int, val y: Int, val width: Int, val height: Int)

    /** [cleared] 为 true 时整张图集清空过: 这一帧在这之前拿到的位置都已作废, 要重新取. */
    class Allocation(val slot: Slot, val cleared: Boolean)

    private class Shelf(val y: Int, val height: Int) {
        var cursorX = 0
        var lastUsedFrame = 0L
        val keys = ArrayList<Any>()
    }

    private val shelves = ArrayList<Shelf>()
    private val slots = HashMap<K, Pair<Shelf, Slot>>()
    private var frame = 0L

    /** 整张清空过几次 (看图集是否太小). */
    var clears = 0
        private set

    fun beginFrame() {
        frame++
    }

    /** 已经在图集里的为它的位置, 并记这一行在本帧用过; 不在为 null. */
    fun get(key: K): Slot? {
        val (shelf, slot) = slots[key] ?: return null
        shelf.lastUsedFrame = frame
        return slot
    }

    /** 给 [key] 分配一块 [w]×[h]. 比整张图集还大为 null. */
    fun allocate(key: K, w: Int, h: Int): Allocation? {
        if (w > width || h > height) return null
        val shelf = shelves.firstOrNull { it.fits(w, h) }
            ?: newShelfOrNull(h)
            ?: evictShelfOrNull(w, h)
        if (shelf != null) return Allocation(place(shelf, key, w, h), cleared = false)

        // 每一行这一帧都在用: 整张清空, 本帧之前分配的作废
        shelves.clear()
        slots.clear()
        clears++
        val first = newShelfOrNull(h) ?: return null
        return Allocation(place(first, key, w, h), cleared = true)
    }

    /** 行高与 [h] 相近 (高出不多) 且这一行还放得下. */
    private fun Shelf.fits(w: Int, h: Int): Boolean =
        h <= height && height <= h + h / 4 + gap && cursorX + w <= width

    private fun newShelfOrNull(h: Int): Shelf? {
        val y = shelves.lastOrNull()?.let { it.y + it.height + gap } ?: 0
        if (y + h > height) return null
        return Shelf(y, h).also { shelves += it }
    }

    /** 腾出最久没画过的一行: 上一帧也没用过的优先 (那一行的弹幕多半已经滚出去了). */
    private fun evictShelfOrNull(w: Int, h: Int): Shelf? {
        if (w > width) return null
        val candidates = shelves.filter { it.height >= h && it.lastUsedFrame < frame }
        val shelf = candidates.filter { it.lastUsedFrame < frame - 1 }.minByOrNull { it.lastUsedFrame }
            ?: candidates.minByOrNull { it.lastUsedFrame }
            ?: return null
        for (key in shelf.keys) slots.remove(key)
        shelf.keys.clear()
        shelf.cursorX = 0
        return shelf
    }

    private fun place(shelf: Shelf, key: K, w: Int, h: Int): Slot {
        val slot = Slot(shelf.cursorX, shelf.y, w, h)
        shelf.cursorX += w + gap
        shelf.lastUsedFrame = frame
        shelf.keys += key
        slots[key] = shelf to slot
        return slot
    }
}

/**
 * 弹幕层宽 [layerWidth] 时图集的宽: 不小于层宽的 2 的幂 (至少 1024, 不超过 [maxTextureSize]); 高取宽的一半.
 * 与屏幕同比例 (4K 界面 4096×2048, 1080p 2048×1024), 放得下屏上的全部弹幕和刚滚出去的几行.
 */
internal fun danmakuAtlasWidth(layerWidth: Int, maxTextureSize: Int): Int =
    minOf(maxTextureSize, ((layerWidth - 1).coerceAtLeast(1023).takeHighestOneBit() shl 1))
