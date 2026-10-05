/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
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
import me.him188.ani.app.data.models.preference.SubjectTrackChoice
import me.him188.ani.app.domain.player.tracks.SubjectTrackChooser
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.features.audioTracks
import org.openani.mediamp.features.subtitleTracks
import org.openani.mediamp.source.SystemFileMediaData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * 按语言选字幕与音轨 ([PreferredTrackSelector]) 在真的 ExoPlayer 上 (见 [TrackLanguageTestMedia]): 打开时按界面语言选,
 * 手动换了记下来、真的交给 ExoPlayer (mediamp 自己不下发音轨), 再打开沿用.
 */
class PreferredTrackSelectorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.cacheDir, "track-language-test.mkv").apply { writeBytes(TrackLanguageTestMedia.bytes) }

    @Test
    fun `picks by language and applies and remembers manual switches`() = runBlocking(Dispatchers.Main) {
        val job = SupervisorJob()
        val player = LibassExoPlayerMediampPlayer(context, Dispatchers.Main + job)
        val saved = mutableListOf<SubjectTrackChoice>()
        val chooser = SubjectTrackChooser("zh-CN") { saved += it }
        try {
            player.trackChooser = chooser
            open(player)
            // 字幕 English 标了默认、排第一, 简体界面选简体; 音轨国语标了默认、排第一, 选日语原声
            awaitSelected(player, "简体中文", "ja")

            val subtitle = player.subtitleTracks!!.candidates.first { it.isNotEmpty() }
                .first { track -> track.labels.any { it.value == "繁體中文" } }
            player.subtitleTracks!!.select(subtitle)
            awaitSelected(player, "繁體中文", "ja")

            val audio = player.audioTracks!!.candidates.first { it.isNotEmpty() }
                .first { track -> track.labels.any { it.value == "国语" } }
            player.audioTracks!!.select(audio)
            // 音轨真的换了 (ExoPlayer 实际选中的), 不只是界面上的状态
            awaitSelected(player, "繁體中文", "zh")
            assertEquals(SubjectTrackChoice(subtitle = "zh-Hant", audio = "zh"), chooser.choice)
            assertEquals(chooser.choice, saved.last())

            // 再打开 (下一集): 按记下的语言选, 不是按序号
            open(player)
            awaitSelected(player, "繁體中文", "zh")

            // 关掉字幕也记下来
            player.subtitleTracks!!.select(null)
            awaitSelected(player, null, "zh")
            open(player)
            awaitSelected(player, null, "zh")
            assertEquals(SubjectTrackChoice(subtitle = "off", audio = "zh"), chooser.choice)
        } finally {
            player.close()
            job.cancel()
            file.delete()
        }
    }

    @OptIn(ExperimentalMediampApi::class)
    private suspend fun open(player: LibassExoPlayerMediampPlayer) {
        // 同本地缓存的播放 (SystemFileMediaDataProvider); file:// 的 UriMediaData 会被当成网络地址读
        player.setMediaData(SystemFileMediaData(Path(file.absolutePath)), playWhenReady = false, startPositionMillis = 0)
    }

    /** 等到 ExoPlayer 实际选中的字幕名为 [subtitleLabel] (null = 没选字幕)、音轨语言为 [audioLanguage]. */
    private suspend fun awaitSelected(player: LibassExoPlayerMediampPlayer, subtitleLabel: String?, audioLanguage: String) {
        val exo = player.exoMediampPlayer.impl
        try {
            withTimeout(10_000) {
                while (exo.selected(C.TRACK_TYPE_TEXT)?.label != subtitleLabel || exo.selected(C.TRACK_TYPE_AUDIO)?.language != audioLanguage) {
                    delay(50)
                }
            }
        } catch (e: TimeoutCancellationException) {
            fail(
                "expected subtitle=$subtitleLabel audio=$audioLanguage, " +
                    "but subtitle=${exo.selected(C.TRACK_TYPE_TEXT)?.label} audio=${exo.selected(C.TRACK_TYPE_AUDIO)?.language}",
            )
        }
    }

    private fun ExoPlayer.selected(type: Int): Format? =
        currentTracks.groups.firstOrNull { it.type == type && it.isSelected }?.let { group ->
            group.getTrackFormat((0 until group.length).first { group.isTrackSelected(it) })
        }
}
