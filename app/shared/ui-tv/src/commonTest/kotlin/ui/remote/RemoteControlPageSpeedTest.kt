/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import me.him188.ani.app.data.models.preference.VideoScaffoldConfig
import me.him188.ani.app.ui.foundation.SLIDER_VALUE_STEP
import me.him188.ani.app.ui.foundation.quantizeSliderValue
import me.him188.ani.app.ui.subject.episode.tv.TV_PLAYBACK_SPEED_RANGE
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 手机控制台的倍速条必须与电视上那条**同范围同档位**.
 *
 * 出过一次: 网页读 `vm.playbackSpeedRange` (配置里的 min/max), 而「倍速范围」这条设置在遥控器形态下
 * 整条被隐藏, 于是永远是出厂的 0.5x–2.5x; 电视自己的倍速条却用 [TV_PLAYBACK_SPEED_RANGE] (0.25x–4x).
 * 表现为手机上调不到电视能调的档位, 而且发过去还会被 coerceIn 夹回 2.5x.
 * 步进也各写各的 (电视 0.25, 网页 0.05), 手机能调出电视产生不了的 1.15x.
 */
class RemoteControlPageSpeedTest {
    private val page = renderRemoteControlPage(
        initialTab = "player",
        searchFormHtml = "",
        requestSectionHtml = "",
    )

    @Test
    fun `电视侧范围就是播放器支持的全范围`() {
        assertEquals(VideoScaffoldConfig.MIN_SUPPORTED_PLAYBACK_SPEED, TV_PLAYBACK_SPEED_RANGE.start)
        assertEquals(VideoScaffoldConfig.MAX_SUPPORTED_PLAYBACK_SPEED, TV_PLAYBACK_SPEED_RANGE.endInclusive)
        assertEquals(0.25f, TV_PLAYBACK_SPEED_RANGE.start)
        assertEquals(4f, TV_PLAYBACK_SPEED_RANGE.endInclusive)
    }

    @Test
    fun `档位落在 0_25 的整数倍上，端点都可达`() {
        val reachable = generateSequence(TV_PLAYBACK_SPEED_RANGE.start) { it + SLIDER_VALUE_STEP }
            .takeWhile { it <= TV_PLAYBACK_SPEED_RANGE.endInclusive + 1e-4f }
            .toList()
        assertEquals(16, reachable.size, "0.25..4 步进 0.25 应该是 16 档")
        assertEquals(0.25f, reachable.first())
        assertEquals(4f, reachable.last())
        // 每一档量化之后都是它自己 (不会被吸到别处)
        for (v in reachable) {
            assertEquals(v, quantizeSliderValue(v, TV_PLAYBACK_SPEED_RANGE), 1e-4f)
        }
    }

    @Test
    fun `档位之间的值被量化到最近一档`() {
        assertEquals(1.25f, quantizeSliderValue(1.15f, TV_PLAYBACK_SPEED_RANGE), 1e-4f)
        assertEquals(1f, quantizeSliderValue(1.1f, TV_PLAYBACK_SPEED_RANGE), 1e-4f)
        // 超出范围的夹回端点, 而不是绕回去
        assertEquals(4f, quantizeSliderValue(9f, TV_PLAYBACK_SPEED_RANGE), 1e-4f)
        assertEquals(0.25f, quantizeSliderValue(0.01f, TV_PLAYBACK_SPEED_RANGE), 1e-4f)
    }

    @Test
    fun `网页的滑条按电视送来的 speedStep 画档位`() {
        assertContains(page, "p.speedStep")
        assertContains(page, "sl.step = String(st)")
    }

    @Test
    fun `网页初始属性就在 0_25 的网格上，不是旧的 0_5 到 2_5 步进 0_05`() {
        assertContains(page, """id="pb-speed" min="25" max="400" step="25"""")
        assertFalse(
            page.contains("""id="pb-speed" min="50" max="250" step="5""""),
            "初始属性还是旧的窄范围 —— 首帧会画错, 轮询回来才纠正",
        )
    }

    @Test
    fun `取不到电视范围时的兜底也在网格上`() {
        // 兜底值必须是 0.25 的整数倍, 否则整条网格错位
        for (fallback in listOf(0.25f, 4f, SLIDER_VALUE_STEP)) {
            assertTrue(
                (fallback / SLIDER_VALUE_STEP).let { it - it.toInt() } < 1e-4f,
                "$fallback 不在 0.25 的网格上",
            )
        }
        assertContains(page, "p.speedStep != null ? p.speedStep : 0.25")
        assertContains(page, "p.speedMin != null ? p.speedMin : 0.25")
        assertContains(page, "p.speedMax != null ? p.speedMax : 4")
    }
}
