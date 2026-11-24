/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.resolver

import me.him188.ani.datasources.api.EpisodeSort
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BD 合集里的菜单、预告、特典名字里也带着集号, 挑一集的文件时不能选成它们 ([TorrentVideoOrder]).
 * 文件表照「[DBD-Raws] 约会大作战 第一季 导演剪辑版 01-12TV全集+OAD」种子里的先后 (菜单排在正片前面), 大小照块数.
 */
class SelectVideoFileBdExtrasTest {
    private class Entry(val name: String, val size: Long)

    private fun dbd(tag: String) = "[DBD-Raws][Date a Live][Director's Cut][$tag][1080P][BDRip][HEVC-10bit][FLAC]"

    private val piece = 2L * 1024 * 1024

    private val pack: List<Entry> = buildList {
        add(Entry("Fonts.zip", 29 * piece))
        for (i in 1..10) add(Entry(dbd("NCED$i") + ".mkv", 20 * piece))
        add(Entry(dbd("NCOP") + ".mkv", 36 * piece))
        for (i in 1..12) add(Entry(dbd("PV][" + i.toString().padStart(2, '0')) + ".mkv", 10 * piece))
        for (disc in 1..3) for (i in 1..2) add(Entry(dbd("menu][D$disc][0$i") + ".mkv", 5 * piece))
        for (i in 1..12) {
            val ep = i.toString().padStart(2, '0')
            add(Entry(dbd(ep) + ".mkv", 400 * piece))
            add(Entry(dbd(ep) + ".sc.ass", piece))
            add(Entry(dbd(ep) + ".tc.ass", piece))
        }
        add(Entry(dbd("OAD") + ".mkv", 296 * piece))
    }

    private fun select(entries: List<Entry>, episode: Int, withSize: Boolean = true): String? =
        TorrentMediaResolver.selectVideoFileEntry(
            entries,
            { name },
            episodeTitles = listOf("四月十日"),
            episodeSort = EpisodeSort(episode),
            episodeEp = EpisodeSort(episode),
            getSize = if (withSize) ({ size }) else null,
        )?.name

    @Test
    fun `BD menus with episode numbers lose to the main episode`() {
        for (episode in 1..12) {
            assertEquals(dbd(episode.toString().padStart(2, '0')) + ".mkv", select(pack, episode), "episode $episode")
        }
    }

    @Test
    fun `menus lose even without file sizes`() {
        assertEquals(dbd("01") + ".mkv", select(pack, 1, withSize = false))
        assertEquals(dbd("02") + ".mkv", select(pack, 2, withSize = false))
    }

    @Test
    fun `previews and extras named with an episode lose to the main episode`() {
        val entries = listOf(
            Entry("[Sub] Show [01][Web Preview][1080P].mkv", 30 * piece),
            Entry("[Sub] Show [01][Trailer][1080P].mkv", 20 * piece),
            Entry("特典/[Sub] Show [01][映像特典][1080P].mkv", 50 * piece),
            Entry("[Sub] Show [01][1080P].mkv", 400 * piece),
        )
        assertEquals("[Sub] Show [01][1080P].mkv", select(entries, 1))
        assertEquals("[Sub] Show [01][1080P].mkv", select(entries, 1, withSize = false))
    }

    @Test
    fun `the larger of two files naming the same episode wins`() {
        // 名单里没有的附带视频: 靠大小
        val entries = listOf(
            Entry("[Sub] Show [01][Creditless][1080P].mkv", 30 * piece),
            Entry("[Sub] Show [01][1080P].mkv", 400 * piece),
        )
        assertEquals("[Sub] Show [01][1080P].mkv", select(entries, 1))
    }

    @Test
    fun `an extra is still picked when nothing else names the episode`() {
        val entries = listOf(Entry(dbd("menu][D1][01") + ".mkv", 5 * piece), Entry(dbd("02") + ".mkv", 400 * piece))
        assertEquals(dbd("menu][D1][01") + ".mkv", select(entries, 1))
    }
}
