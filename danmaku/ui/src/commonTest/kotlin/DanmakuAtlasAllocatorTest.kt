/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DanmakuAtlasAllocatorTest {
    @Test
    fun `fills a row left to right then opens the next row`() {
        val atlas = DanmakuAtlasAllocator<String>(width = 100, height = 100, gap = 2)
        atlas.beginFrame()
        val a = atlas.allocate("a", 40, 20)!!
        val b = atlas.allocate("b", 40, 20)!!
        val c = atlas.allocate("c", 40, 20)!!
        assertEquals(0 to 0, a.x to a.y)
        assertEquals(42 to 0, b.x to b.y)
        assertEquals(0 to 22, c.x to c.y, "第三张这一行放不下, 换下一行")
        assertEquals(b.x, atlas.get("b")!!.x, "放过的直接取")
    }

    @Test
    fun `images of very different heights go to separate rows`() {
        val atlas = DanmakuAtlasAllocator<String>(width = 100, height = 100, gap = 2)
        atlas.beginFrame()
        atlas.allocate("small", 20, 20)
        val tall = atlas.allocate("tall", 20, 40)!!
        assertEquals(22, tall.y, "高出很多的不挤进矮的那一行")
    }

    @Test
    fun `when full the row unused for longest is reused instead of clearing everything`() {
        val atlas = DanmakuAtlasAllocator<String>(width = 50, height = 46, gap = 2)
        atlas.beginFrame()
        atlas.allocate("old", 50, 20) // 第一行, 之后不再画
        atlas.allocate("visible", 50, 20) // 第二行
        atlas.beginFrame()
        atlas.beginFrame()
        assertNotNull(atlas.get("visible"))
        val slot = atlas.allocate("new", 50, 20)!!
        assertEquals(0, slot.y, "腾出没再画过的第一行")
        assertNull(atlas.get("old"), "被腾掉的行里的图要重新传")
        assertNotNull(atlas.get("visible"), "还在画的不动")
    }

    @Test
    fun `nothing is placed when every row is in use this frame`() {
        val atlas = DanmakuAtlasAllocator<String>(width = 50, height = 46, gap = 2)
        atlas.beginFrame()
        atlas.allocate("a", 50, 20)
        atlas.allocate("b", 50, 20)
        assertNull(atlas.allocate("c", 50, 20), "这一帧在画的不能被挤掉")
        assertNotNull(atlas.get("a"))
        assertNotNull(atlas.get("b"))
        assertEquals(2, atlas.size)

        atlas.clear()
        assertEquals(0, atlas.size)
        assertNull(atlas.get("a"))
        assertEquals(0, atlas.allocate("c", 50, 20)!!.y, "清空后从头放")
    }

    @Test
    fun `images larger than the atlas are not placed`() {
        val atlas = DanmakuAtlasAllocator<String>(width = 50, height = 50)
        atlas.beginFrame()
        assertNull(atlas.allocate("wide", 60, 10))
    }

    @Test
    fun `atlas width is the power of two covering the layer width`() {
        assertEquals(4096, danmakuAtlasWidth(3840, maxTextureSize = 16384))
        assertEquals(2048, danmakuAtlasWidth(1920, maxTextureSize = 16384))
        assertEquals(2048, danmakuAtlasWidth(2048, maxTextureSize = 16384))
        assertEquals(1024, danmakuAtlasWidth(400, maxTextureSize = 16384), "至少 1024")
        assertEquals(2048, danmakuAtlasWidth(3840, maxTextureSize = 2048), "不超过显卡上限")
    }

    @Test
    fun `a full atlas grows by doubling its area`() {
        assertEquals(2048 to 2048, grownDanmakuAtlasSize(2048, 1024, minWidth = 340, minHeight = 104, limit = 4096))
        assertEquals(4096 to 2048, grownDanmakuAtlasSize(2048, 2048, minWidth = 340, minHeight = 104, limit = 4096))
        assertEquals(4096 to 4096, grownDanmakuAtlasSize(4096, 2048, minWidth = 340, minHeight = 104, limit = 4096))
        assertNull(grownDanmakuAtlasSize(4096, 4096, minWidth = 340, minHeight = 104, limit = 4096), "到顶了")
        assertEquals(2048 to 2048, grownDanmakuAtlasSize(2048, 1024, minWidth = 340, minHeight = 104, limit = 2048), "显卡上限 2048")
    }

    @Test
    fun `an atlas narrower than one image grows to fit it`() {
        assertEquals(4096 to 1024, grownDanmakuAtlasSize(2048, 1024, minWidth = 3000, minHeight = 104, limit = 4096), "很长的弹幕先加宽")
        assertNull(grownDanmakuAtlasSize(2048, 1024, minWidth = 5000, minHeight = 104, limit = 4096), "到顶也装不下")
    }

    @Test
    fun `floating danmaku starts at the right edge and moves left at its speed`() {
        assertEquals(1000f, floatingDanmakuX(hostWidth = 1000, elapsedNanos = 5_000_000_000, placeNanos = 5_000_000_000, speedPxPerSecond = 200f))
        assertEquals(700f, floatingDanmakuX(hostWidth = 1000, elapsedNanos = 6_500_000_000, placeNanos = 5_000_000_000, speedPxPerSecond = 200f))
    }
}
