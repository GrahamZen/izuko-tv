/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.ui

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 按真实尺寸模拟一段密集弹幕: 弹幕不断从右边进屏、从左边出屏, 每帧把在屏的都取一遍位置.
 * 在屏的图总面积远小于图集时, 每一帧都该放得下.
 */
class DanmakuAtlasStreamTest {
    private class Live(val key: Int, val w: Int, val h: Int, val leaveFrame: Int)

    /** 跑 [frames] 帧, 返回取不到位置的次数. 同时在屏的图总面积不超过 [maxLiveArea]. */
    private fun simulate(
        atlasWidth: Int, atlasHeight: Int,
        itemHeight: Int, widths: IntRange,
        newPerFrame: Double, framesOnScreen: (w: Int) -> Int,
        frames: Int, maxLiveArea: Long,
    ): Int {
        val random = Random(42)
        val atlas = DanmakuAtlasAllocator<Int>(atlasWidth, atlasHeight)
        val live = ArrayList<Live>()
        var nextKey = 0
        var failures = 0
        var debt = 0.0
        for (frame in 0 until frames) {
            atlas.beginFrame()
            live.removeAll { it.leaveFrame <= frame }
            debt += newPerFrame
            while (debt >= 1) {
                debt -= 1
                val w = widths.random(random)
                val area = live.sumOf { it.w.toLong() * it.h } + w.toLong() * itemHeight
                if (area > maxLiveArea) continue // 屏上满了, 这条进不来 (轨道占满)
                live += Live(nextKey++, w, itemHeight, frame + framesOnScreen(w))
            }
            for (item in live) {
                if (atlas.get(item.key) != null) continue
                if (atlas.allocate(item.key, item.w, item.h) == null) failures++
            }
        }
        return failures
    }

    @Test
    fun `a dense stream fits when the danmaku on screen take far less room than the atlas`() {
        // 1080p, 字号 2 倍: 弹幕图高 209, 4~14 个字宽 392~1184; 走过 1920 宽的屏幕约 10 秒 (600 帧), 长的多走一会儿.
        // 屏上同时最多约 6 MPx 的图 (满屏十几行), 图集 4096x4096 有 16.7 MPx
        val failures = simulate(
            atlasWidth = 4096, atlasHeight = 4096,
            itemHeight = 209, widths = 392..1184,
            newPerFrame = 0.25, framesOnScreen = { w -> (1920 + w) * 600 / 1920 },
            frames = 6000, maxLiveArea = 6_000_000,
        )
        assertEquals(0, failures, "取不到位置的次数")
    }
}
