/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import me.him188.ani.app.videoplayer.media.LibassExoPlayerMediampPlayer
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.features.subtitleTracks
import org.openani.mediamp.source.SystemFileMediaData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.fail

/**
 * 画 ASS 的那层 ([AssSubtitleLayer]) 在真的播放器上 (见 [AssSrtTestMedia]): 选中 ASS 字幕轨时挂上, 换成 SRT、关掉字幕时拿下,
 * 再换回 ASS 时挂上新的一层并沿用挪动距离.
 *
 * 测试里没有窗口: 直接告诉它字幕视图挂上 / 离开了窗口, 那层只进出视图树, 不会真的建出图形缓冲.
 */
class AssSubtitleLayerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.cacheDir, "ass-srt-test.mkv").apply { writeBytes(AssSrtTestMedia.bytes) }

    @Test
    fun `the layer is in the view tree only while an ASS track is selected`() = runBlocking(Dispatchers.Main) {
        val job = SupervisorJob()
        val player = LibassExoPlayerMediampPlayer(context, Dispatchers.Main + job)
        val container = FrameLayout(context)
        val layer = AssSubtitleLayer(container, player.exoMediampPlayer.impl, player.assHandler)
        try {
            layer.onViewAttachedToWindow(container)
            assertNull(layer.view, "no media yet")
            open(player)
            // ASS 标了默认
            awaitSubtitle(player, "ASS")
            awaitLayer(layer, container, present = true)
            val first = layer.view

            select(player, null)
            awaitSubtitle(player, null)
            awaitLayer(layer, container, present = false)

            layer.bottomLift = 42f
            select(player, "ASS")
            awaitSubtitle(player, "ASS")
            awaitLayer(layer, container, present = true)
            assertNotSame(first, layer.view, "a fresh layer each time")
            assertEquals(42f, layer.view!!.bottomLift)

            select(player, "SRT")
            awaitSubtitle(player, "SRT")
            awaitLayer(layer, container, present = false)

            // 字幕视图离开窗口期间不跟着轨道变, 回到窗口时照当时的轨道对一遍
            select(player, "ASS")
            awaitSubtitle(player, "ASS")
            awaitLayer(layer, container, present = true)
            layer.onViewDetachedFromWindow(container)
            select(player, "SRT")
            awaitSubtitle(player, "SRT")
            assertNotNull(layer.view, "left alone while detached")
            layer.onViewAttachedToWindow(container)
            awaitLayer(layer, container, present = false)
        } finally {
            player.close()
            job.cancel()
            file.delete()
        }
    }

    @OptIn(ExperimentalMediampApi::class)
    private suspend fun open(player: LibassExoPlayerMediampPlayer) {
        player.setMediaData(SystemFileMediaData(Path(file.absolutePath)), playWhenReady = false, startPositionMillis = 0)
    }

    /** 选名为 [label] 的字幕轨, null = 关掉字幕. */
    private suspend fun select(player: LibassExoPlayerMediampPlayer, label: String?) {
        val tracks = player.subtitleTracks!!
        val track = label?.let { name ->
            tracks.candidates.first { it.isNotEmpty() }.first { track -> track.labels.any { it.value == name } }
        }
        tracks.select(track)
    }

    /** 等到 ExoPlayer 实际选中的字幕名为 [label] (null = 没选字幕). */
    private suspend fun awaitSubtitle(player: LibassExoPlayerMediampPlayer, label: String?) {
        val exo = player.exoMediampPlayer.impl
        fun selected(): String? = exo.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.let { group ->
            group.getTrackFormat((0 until group.length).first { group.isTrackSelected(it) }).label
        }
        try {
            withTimeout(10_000) { while (selected() != label) delay(50) }
        } catch (e: TimeoutCancellationException) {
            fail("expected subtitle $label, but ${selected()}")
        }
    }

    private suspend fun awaitLayer(layer: AssSubtitleLayer, container: FrameLayout, present: Boolean) {
        try {
            withTimeout(10_000) { while ((layer.view != null) != present) delay(50) }
        } catch (e: TimeoutCancellationException) {
            fail("expected the layer ${if (present) "present" else "gone"}")
        }
        if (present) {
            assertEquals(1, container.childCount)
            assertSame(layer.view, container.getChildAt(0))
        } else {
            assertEquals(0, container.childCount)
        }
    }
}
