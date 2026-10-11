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
 * 放不下时腾出最久没画过的一行 (弹幕从右往左滚过屏幕, 早放进去的那几行先整行用不上). 每一行这一帧都在画、腾不出地方时
 * 不分配, 由调用方决定整理 ([clear] 后整帧重传) 还是换一张更大的图集 ([grownDanmakuAtlasSize]). 只管位置, 上传由调用方做.
 * 不是线程安全的.
 *
 * 每一帧先调 [beginFrame], 再对要画的每条弹幕调 [get], 没有的调 [allocate].
 */
internal class DanmakuAtlasAllocator<K : Any>(
    val width: Int,
    val height: Int,
    private val gap: Int = 2,
) {
    class Slot(val x: Int, val y: Int, val width: Int, val height: Int)

    private class Shelf(val y: Int, val height: Int) {
        var cursorX = 0
        var lastUsedFrame = 0L
        val keys = ArrayList<Any>()
    }

    private val shelves = ArrayList<Shelf>()
    private val slots = HashMap<K, Pair<Shelf, Slot>>()
    private var frame = 0L

    /** 图集里有几张图 (含已经不画了、还没腾掉的). */
    val size: Int get() = slots.size

    fun beginFrame() {
        frame++
    }

    /** 已经在图集里的为它的位置, 并记这一行在本帧用过; 不在为 null. */
    fun get(key: K): Slot? {
        val (shelf, slot) = slots[key] ?: return null
        shelf.lastUsedFrame = frame
        return slot
    }

    /** 给 [key] 分配一块 [w]×[h]. 比整张图集还大, 或每一行这一帧都在画、腾不出地方时为 null. */
    fun allocate(key: K, w: Int, h: Int): Slot? {
        if (w > width || h > height) return null
        val shelf = shelves.firstOrNull { it.fits(w, h) }
            ?: newShelfOrNull(h)
            ?: evictShelfOrNull(w, h)
            ?: return null
        return place(shelf, key, w, h)
    }

    /** 全部清空, 之前分配的位置都作废. */
    fun clear() {
        shelves.clear()
        slots.clear()
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
 * 弹幕层宽 [layerWidth] 时图集起始的宽: 不小于层宽的 2 的幂 (至少 1024, 不超过 [maxTextureSize]); 高取宽的一半.
 * 与屏幕同比例 (4K 界面 4096×2048, 1080p 2048×1024), 默认字号与显示区域下放得下屏上的全部弹幕和刚滚出去的几行;
 * 字号、显示区域调大后不够时换大的 ([grownDanmakuAtlasSize]).
 */
internal fun danmakuAtlasWidth(layerWidth: Int, maxTextureSize: Int): Int =
    minOf(maxTextureSize, ((layerWidth - 1).coerceAtLeast(1023).takeHighestOneBit() shl 1))

/**
 * [width]×[height] 的图集放不下一帧要画的弹幕时换的尺寸, 两边都不超过 [limit]; 到顶了为 null.
 * 先翻到装得下放不下的那张 ([minWidth]×[minHeight], 如很长的弹幕); 本来就装得下 (是张数太多) 时面积翻一倍:
 * 高不到宽时高翻倍, 否则宽翻倍. 1080p 界面依次 2048×1024、2048×2048、4096×2048、4096×4096.
 */
internal fun grownDanmakuAtlasSize(width: Int, height: Int, minWidth: Int, minHeight: Int, limit: Int): Pair<Int, Int>? {
    var w = width
    var h = height
    while (w < minWidth && w * 2 <= limit) w *= 2
    while (h < minHeight && h * 2 <= limit) h *= 2
    if (w < minWidth || h < minHeight) return null
    if (w == width && h == height) {
        when {
            h < w && h * 2 <= limit -> h *= 2
            w * 2 <= limit -> w *= 2
            h * 2 <= limit -> h *= 2
            else -> return null
        }
    }
    return w to h
}
