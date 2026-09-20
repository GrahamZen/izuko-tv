/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import me.him188.ani.app.data.models.preference.TvPlayerChromeItem.CACHE
import me.him188.ani.app.data.models.preference.TvPlayerChromeItem.DANMAKU_SETTINGS
import me.him188.ani.app.data.models.preference.TvPlayerChromeItem.DANMAKU_TOGGLE
import me.him188.ani.app.data.models.preference.TvPlayerChromeItem.MEDIA_SOURCE
import me.him188.ani.app.data.models.preference.TvPlayerChromeItem.NEXT_EPISODE
import me.him188.ani.app.data.models.preference.TvPlayerChromeItem.PILL_COMMENTS
import me.him188.ani.app.data.models.preference.TvPlayerChromeItem.PILL_DANMAKU
import me.him188.ani.app.data.models.preference.TvPlayerChromeItem.PILL_RECOMMENDATIONS
import me.him188.ani.app.data.models.preference.TvPlayerChromeLayout.Companion.resolveOrder
import me.him188.ani.app.data.persistent.DataStoreJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvPlayerChromeLayoutTest {
    @Test
    fun `empty order falls back to default`() {
        assertEquals(
            TvPlayerChromeLayout.DEFAULT_ORDER.filter { it.row == TvPlayerChromeRow.BOTTOM },
            TvPlayerChromeLayout.Default.orderOf(TvPlayerChromeRow.BOTTOM),
        )
    }

    @Test
    fun `unknown items are dropped`() {
        // 用户排过之后某个条目被新版本删掉: 存下来的顺序里还有它
        val saved = listOf(MEDIA_SOURCE, DANMAKU_TOGGLE, CACHE)
        val defaults = listOf(MEDIA_SOURCE, DANMAKU_TOGGLE)

        assertEquals(listOf(MEDIA_SOURCE, DANMAKU_TOGGLE), resolveOrder(saved, defaults))
    }

    @Test
    fun `new item lands after its default predecessor`() {
        // 用户把「数据源」排到了最后; 新版本在「弹幕开关」后面加了「弹幕设置」
        val saved = listOf(DANMAKU_TOGGLE, CACHE, MEDIA_SOURCE)
        val defaults = listOf(MEDIA_SOURCE, DANMAKU_TOGGLE, DANMAKU_SETTINGS, CACHE)

        // 跟着它的前驱「弹幕开关」走, 而不是追到末尾
        assertEquals(listOf(DANMAKU_TOGGLE, DANMAKU_SETTINGS, CACHE, MEDIA_SOURCE), resolveOrder(saved, defaults))
    }

    @Test
    fun `new first item lands at the front`() {
        val saved = listOf(DANMAKU_TOGGLE, CACHE)
        val defaults = listOf(MEDIA_SOURCE, DANMAKU_TOGGLE, CACHE)

        assertEquals(listOf(MEDIA_SOURCE, DANMAKU_TOGGLE, CACHE), resolveOrder(saved, defaults))
    }

    @Test
    fun `moving stays within its own row`() {
        val moved = TvPlayerChromeLayout.Default.moved(PILL_DANMAKU, -1)

        assertEquals(
            listOf(PILL_RECOMMENDATIONS, TvPlayerChromeItem.PILL_STAFF, TvPlayerChromeItem.PILL_CHARACTERS, PILL_DANMAKU, PILL_COMMENTS),
            moved.orderOf(TvPlayerChromeRow.PILLS),
        )
        // 另一行一动不动
        assertEquals(
            TvPlayerChromeLayout.Default.orderOf(TvPlayerChromeRow.BOTTOM),
            moved.orderOf(TvPlayerChromeRow.BOTTOM),
        )
    }

    @Test
    fun `moving at the edge does nothing`() {
        val layout = TvPlayerChromeLayout.Default
        assertEquals(layout.orderOf(TvPlayerChromeRow.PILLS), layout.moved(PILL_RECOMMENDATIONS, -1).orderOf(TvPlayerChromeRow.PILLS))
    }

    @Test
    fun `hidden items are excluded from visible list`() {
        val layout = TvPlayerChromeLayout.Default.withHidden(PILL_COMMENTS, true)

        assertTrue(PILL_COMMENTS !in layout.visibleItemsOf(TvPlayerChromeRow.PILLS))
        assertTrue(PILL_COMMENTS in layout.orderOf(TvPlayerChromeRow.PILLS))
    }

    @Test
    fun `layout survives serialization`() {
        val layout = TvPlayerChromeLayout.Default
            .moved(MEDIA_SOURCE, -1)
            .withHidden(NEXT_EPISODE, true)
        val config = VideoScaffoldConfig.Default.copy(tvPlayerChrome = TvPlayerChromePresets(listOf(layout)))

        val decoded = DataStoreJson.decodeFromString(
            VideoScaffoldConfig.serializer(),
            DataStoreJson.encodeToString(VideoScaffoldConfig.serializer(), config),
        )

        assertEquals(layout.orderOf(TvPlayerChromeRow.BOTTOM), decoded.tvPlayerChrome.active.orderOf(TvPlayerChromeRow.BOTTOM))
        assertTrue(decoded.tvPlayerChrome.active.isHidden(NEXT_EPISODE))
    }

    @Test
    fun `moving skips over items that are not on screen`() {
        // 触屏专有的两颗在电视上不列出来: 从「跳过 OP」往右一格应该直接跨到分组竖线
        val layout = TvPlayerChromeLayout.Default
        val onScreen = layout.orderOf(TvPlayerChromeRow.BOTTOM).filterNot { it.isTouchOnly }
        val moved = layout.moved(TvPlayerChromeItem.SKIP_OP_ED, 1, onScreen)

        val after = moved.orderOf(TvPlayerChromeRow.BOTTOM).filterNot { it.isTouchOnly }
        assertEquals(
            listOf(TvPlayerChromeItem.RESTART, NEXT_EPISODE, TvPlayerChromeItem.DIVIDER_1, TvPlayerChromeItem.SKIP_OP_ED),
            after.take(4),
        )
    }

    @Test
    fun `presets default to a single default layout`() {
        val presets = TvPlayerChromePresets.Default

        assertEquals(1, presets.resolved.size)
        assertTrue(presets.active.isDefault)
    }

    @Test
    fun `duplicating copies the active layout and switches to it`() {
        val edited = TvPlayerChromeLayout.Default.withHidden(NEXT_EPISODE, true)
        val presets = TvPlayerChromePresets.Default.withActive(edited).duplicatedActive()

        assertEquals(2, presets.resolved.size)
        assertEquals(1, presets.activeIndexResolved)
        assertTrue(presets.active.isHidden(NEXT_EPISODE))
    }

    @Test
    fun `editing one preset leaves the others alone`() {
        val presets = TvPlayerChromePresets.Default
            .duplicatedActive()
            .withActive(TvPlayerChromeLayout.Default.withHidden(NEXT_EPISODE, true))

        assertTrue(presets.switchedTo(0).active.isDefault)
        assertTrue(presets.switchedTo(1).active.isHidden(NEXT_EPISODE))
    }

    @Test
    fun `the last preset cannot be removed`() {
        assertEquals(1, TvPlayerChromePresets.Default.removedActive().resolved.size)
        assertEquals(1, TvPlayerChromePresets.Default.duplicatedActive().removedActive().resolved.size)
    }

    @Test
    fun `out of range active index falls back to the first preset`() {
        val presets = TvPlayerChromePresets(listOf(TvPlayerChromeLayout.Default), activeIndex = 7)

        assertTrue(presets.active.isDefault)
        assertEquals(0, presets.activeIndexResolved)
    }

    @Test
    fun `old config without the field decodes to default layout`() {
        val config = DataStoreJson.decodeFromString(VideoScaffoldConfig.serializer(), "{}")

        assertTrue(config.tvPlayerChrome.active.isDefault)
    }
}
