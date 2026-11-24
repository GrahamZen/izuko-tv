/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import kotlinx.coroutines.flow.first
import me.him188.ani.app.domain.media.selector.filter.BtTitleSeason
import me.him188.ani.app.domain.media.selector.testFramework.MediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.SimpleMediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.runSimpleMediaSelectorTestSuite
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.topic.EpisodeRange
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BT 发布标题写明的季号与当前条目冲突时按其他季排除 ([filter.BtTitleSeason]): 第一季的名字是各季名字的前缀,
 * 「[约会大作战][Date A Live V][01]」原本被当成第一季的第 01 集.
 */
@OptIn(UnsafeOriginalMediaAccess::class)
class MediaSelectorBtTitleSeasonTest {
    // region 认季号

    private val dateALive = listOf("约会大作战", "デート・ア・ライブ", "DATE A LIVE")

    @Test
    fun `roman numerals and glued digits right after a name are seasons`() {
        assertEquals(setOf(5), BtTitleSeason.seasonNumbersOf("[爱恋&漫猫字幕组][4月新番][约会大作战][Date A Live V][01][1080p][MP4][繁中]", dateALive))
        assertEquals(
            setOf(5),
            BtTitleSeason.seasonNumbersOf("[7³ACG] 约会大作战5/ デート・ア・ライブ Ⅴ/Date A Live S05 | 01-12 [简繁字幕] BDrip 1080p x265 FLAC 2.0", dateALive),
        )
        assertEquals(setOf(4), BtTitleSeason.seasonNumbersOf("[字幕组] デート・ア・ライブⅣ - 03 [1080p]", dateALive))
        assertEquals(setOf(3), BtTitleSeason.seasonNumbersOf("[字幕组] 约会大作战3 [01][1080P]", dateALive))
    }

    @Test
    fun `written season labels count anywhere in the title`() {
        assertEquals(setOf(2), BtTitleSeason.seasonNumbersOf("[字幕组] 某番 第二季 [01]", dateALive))
        assertEquals(setOf(2), BtTitleSeason.seasonNumbersOf("[Sub] Something 2nd Season - 01 [1080p]", dateALive))
        assertEquals(setOf(3), BtTitleSeason.seasonNumbersOf("[Sub] Something S03E01 [1080p]", dateALive))
        assertEquals(setOf(1, 2), BtTitleSeason.seasonNumbersOf("[字幕组] 约会大作战 第一季+第二季 [01-24]", dateALive))
    }

    @Test
    fun `numbers that are not season labels are ignored`() {
        // 集号、分辨率、编码、版本号
        assertEquals(emptySet(), BtTitleSeason.seasonNumbersOf("[字幕组][约会大作战][01][1080P][X265 V2]", dateALive))
        assertEquals(emptySet(), BtTitleSeason.seasonNumbersOf("[字幕组] 约会大作战 05 [1080P]", dateALive))
        assertEquals(emptySet(), BtTitleSeason.seasonNumbersOf("[字幕组] 约会大作战05 [1080P]", dateALive))
        assertEquals(emptySet(), BtTitleSeason.seasonNumbersOf("[字幕组] Date A Live Vol.4 [BDRip]", dateALive))
        // 跨季合集「I-V」不是哪一季
        assertEquals(emptySet(), BtTitleSeason.seasonNumbersOf("[VCB-Studio] Date A Live I-V [Ma10p_1080p]", dateALive))
        // 别处的大写 X / V 不是罗马数字季号
        assertEquals(emptySet(), BtTitleSeason.seasonNumbersOf("[Sub] HUNTER X HUNTER - 01 [1080p]", listOf("全职猎人", "HUNTER×HUNTER")))
        // 前面连着字母的不是名字: 「OLIVE」里的 LIVE 不算
        assertEquals(emptySet(), BtTitleSeason.seasonNumbersOf("[Sub] OLIVE V [01]", listOf("LIVE")))
    }

    @Test
    fun `a longer name at the same place wins`() {
        // 条目名本身带数字: 「女神异闻录5」不是「女神异闻录」的第 5 季
        assertEquals(emptySet(), BtTitleSeason.seasonNumbersOf("[字幕组] 女神异闻录5 [01][1080P]", listOf("女神异闻录5", "女神异闻录")))
    }

    // endregion

    // region 选择器

    private fun MediaSelectorTestSuite.initDateALive() {
        initSubject("约会大作战") {
            aliases("デート・ア・ライブ", "DATE A LIVE")
            seriesInfo(seasonSort = 1) {
                sequel("约会大作战 第二季", "约会大作战 第三季", "约会大作战 第四季", "约会大作战 第五季", "デート・ア・ライブV")
            }
        }
        unfinished()
    }

    /** 不按完结番算: 完结番默认藏起单集的 BT, 这里看的是季号 */
    private fun MediaSelectorTestSuite.unfinished() {
        preferenceApi.mediaSelectorContext.value = preferenceApi.mediaSelectorContext.value.copy(subjectFinished = false)
    }

    private suspend fun SimpleMediaSelectorTestSuite.describe(media: DefaultMedia): String =
        selector.filteredCandidates.first().single { it.original === media }.describe()

    private fun SimpleMediaSelectorTestSuite.bt(title: String, range: EpisodeRange = EpisodeRange.single(EpisodeSort(1))): DefaultMedia =
        mediaApi.addMedia(media(originalTitle = title, episodeRange = range))

    @Test
    fun `first work excludes BT releases of later seasons`() = runSimpleMediaSelectorTestSuite {
        initDateALive()
        val single = bt("[爱恋&漫猫字幕组][4月新番][约会大作战][Date A Live V][01][1080p][MP4][繁中]")
        val packed = bt(
            "[7³ACG] 约会大作战5/ デート・ア・ライブ Ⅴ/Date A Live S05 | 01-12 [简繁字幕] BDrip 1080p x265 FLAC 2.0",
            EpisodeRange.range(EpisodeSort(1), EpisodeSort(12)),
        )
        val fin = bt(
            "[爱恋&漫猫字幕组] 约会大作战/Date A Live V (01-12Fin WEBRIP 1080p AVC AAC MP4 2024年4月 繁中)",
            EpisodeRange.range(EpisodeSort(1), EpisodeSort(12)),
        )

        assertEquals("excluded FromSeriesSeason", describe(single))
        assertEquals("excluded FromSeriesSeason", describe(packed))
        assertEquals("excluded FromSeriesSeason", describe(fin))
    }

    @Test
    fun `first work keeps its own releases and cross season packs`() = runSimpleMediaSelectorTestSuite {
        initDateALive()
        val own = bt("[澄空学园&华盟字幕社] Date A Live 约会大作战 [01][BDRip][1080P]")
        val labelled = bt("[字幕组] 约会大作战 第一季 [01][1080P]")
        val merged = bt("[字幕组] 约会大作战 第一季+第二季 [01-24]", EpisodeRange.range(EpisodeSort(1), EpisodeSort(24)))
        val all = bt("[VCB-Studio] Date A Live I-V [Ma10p_1080p]", EpisodeRange.range(EpisodeSort(1), EpisodeSort(12)))

        assertEquals(true, describe(own).startsWith("included"), describe(own))
        assertEquals(true, describe(labelled).startsWith("included"), describe(labelled))
        assertEquals(true, describe(merged).startsWith("included"), describe(merged))
        assertEquals(true, describe(all).startsWith("included"), describe(all))
    }

    @Test
    fun `a later season excludes releases labelled with an earlier season`() = runSimpleMediaSelectorTestSuite {
        initSubject("约会大作战 第五季") {
            aliases("デート・ア・ライブV", "DATE A LIVE V")
            seriesInfo(seasonSort = 5) { series("约会大作战", "约会大作战 第二季", "约会大作战 第三季", "约会大作战 第四季") }
        }
        unfinished()
        val fourth = bt("[字幕组] 约会大作战 第四季 [01][1080P]")
        val own = bt("[爱恋&漫猫字幕组][4月新番][约会大作战][Date A Live V][01][1080p][MP4][繁中]")

        assertEquals("excluded FromSeriesSeason", describe(fourth))
        assertEquals(true, describe(own).startsWith("included"), describe(own))
    }

    @Test
    fun `a work without sequels is not judged`() = runSimpleMediaSelectorTestSuite {
        initSubject("约会大作战") { aliases("DATE A LIVE") }
        unfinished()
        val release = bt("[字幕组] Date A Live V [01][1080P]")

        assertEquals(true, describe(release).startsWith("included"), describe(release))
    }

    // endregion

    private fun MaybeExcludedMedia.describe(): String = when (this) {
        is MaybeExcludedMedia.Included -> "included ${metadata.subjectMatchKind} ${metadata.episodeMatchKind}"
        is MaybeExcludedMedia.Excluded -> "excluded ${exclusionReason::class.simpleName}"
    }
}
