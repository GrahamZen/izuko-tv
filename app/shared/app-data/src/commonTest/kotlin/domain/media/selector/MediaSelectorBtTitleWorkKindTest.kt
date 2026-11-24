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
import me.him188.ani.app.domain.media.selector.filter.BtTitleRules
import me.him188.ani.app.domain.media.selector.testFramework.MediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.SimpleMediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.runSimpleMediaSelectorTestSuite
import me.him188.ani.app.domain.mediasource.search.WorkKind
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.topic.EpisodeRange
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BT 发布标题写明是剧场版、外传、OVA 而条目不是这一类时不是条目正片的这一集 ([filter.BtTitleRules]);
 * 合集 (装着正片) 不算.
 */
@OptIn(UnsafeOriginalMediaAccess::class)
class MediaSelectorBtTitleWorkKindTest {
    // region 认类别

    @Test
    fun `movie and OVA releases name their kind`() {
        assertEquals(
            setOf(WorkKind.MOVIE),
            BtTitleRules.workKindsOf("[DBD-Raws][剧场版 约会大作战 万由里裁决/Gekijouban Date a Live Mayuri Judgement][正片+特典映像][1080P][BDRip]"),
        )
        // 字幕组名里的「&」不说明是合集
        assertEquals(setOf(WorkKind.MOVIE), BtTitleRules.workKindsOf("[澄空学园&华盟字幕社] 剧场版 Date A Live 约会大作战 万由里裁决 Mayuri Judgement BDRip 简繁外挂"))
        assertEquals(setOf(WorkKind.OVA), BtTitleRules.workKindsOf("【千夏字幕＆ANK-Raws】【約會大作戰_DATE_A_LIVE】[VOL.OVA][BDRIP_1080P][x264-Hi10P_FLAC][繁體外掛]"))
    }

    @Test
    fun `packs bundling other kinds with the series name none`() {
        assertEquals(emptySet(), BtTitleRules.workKindsOf("[FFF] DATE A LIVE [BD][1080p-FLAC] 约会大作战 第一季 含OVA [内封英文字幕]"))
        assertEquals(emptySet(), BtTitleRules.workKindsOf("【日漫】约会大作战 全5季+剧场版+外传 简日双语 爱恋字幕组 1080P"))
        assertEquals(emptySet(), BtTitleRules.workKindsOf("[字幕组] 约会大作战 TV+OVA [01-13][1080P]"))
        assertEquals(emptySet(), BtTitleRules.workKindsOf("[字幕组] 约会大作战 1-4季 剧场版 合集"))
        // 没写类别
        assertEquals(emptySet(), BtTitleRules.workKindsOf("[philosophy-raws][约会大作战][BDRIP][1080p 10bit][TV 01-13+Director's cut Edition+SP][日英双语]"))
    }

    // endregion

    // region 选择器

    private fun MediaSelectorTestSuite.initSubject(name: String, episodes: Int) {
        initSubject(name)
        val context = preferenceApi.mediaSelectorContext.value
        // 不按完结番算: 完结番默认藏起单集的 BT, 这里看的是类别
        preferenceApi.mediaSelectorContext.value = context.copy(
            subjectFinished = false,
            subjectInfo = context.subjectInfo?.copy(totalEpisodes = episodes),
        )
    }

    private suspend fun SimpleMediaSelectorTestSuite.describe(media: DefaultMedia): String =
        selector.filteredCandidates.first().single { it.original === media }.describe()

    private fun SimpleMediaSelectorTestSuite.bt(title: String, range: EpisodeRange = EpisodeRange.single(EpisodeSort(1))): DefaultMedia =
        mediaApi.addMedia(media(originalTitle = title, episodeRange = range))

    @Test
    fun `movie and OVA releases are not episodes of a TV series`() = runSimpleMediaSelectorTestSuite {
        initSubject("约会大作战", episodes = 12)
        val movie = bt("[DBD-Raws][剧场版 约会大作战 万由里裁决/Gekijouban Date a Live Mayuri Judgement][正片+特典映像][1080P][BDRip]")
        val movieByGroup = bt("[澄空学园&华盟字幕社] 剧场版 Date A Live 约会大作战 万由里裁决 Mayuri Judgement BDRip 简繁外挂")
        val ova = bt("【千夏字幕＆ANK-Raws】【約會大作戰_DATE_A_LIVE】[VOL.OVA][BDRIP_1080P][x264-Hi10P_FLAC][繁體外掛]")

        assertEquals("excluded SubjectNameMismatch", describe(movie))
        assertEquals("excluded SubjectNameMismatch", describe(movieByGroup))
        assertEquals("excluded SubjectNameMismatch", describe(ova))
    }

    @Test
    fun `packs and plain releases of a TV series stay`() = runSimpleMediaSelectorTestSuite {
        initSubject("约会大作战", episodes = 12)
        val withOva = bt("[FFF] DATE A LIVE [BD][1080p-FLAC] 约会大作战 第一季 含OVA [内封英文字幕]", EpisodeRange.range(EpisodeSort(1), EpisodeSort(12)))
        val all = bt("【日漫】约会大作战 全5季+剧场版+外传 简日双语 爱恋字幕组 1080P", EpisodeRange.range(EpisodeSort(1), EpisodeSort(12)))
        val plain = bt("[澄空学园&华盟字幕社] Date A Live 约会大作战 [01][BDRip][1080P]")

        assertEquals(true, describe(withOva).startsWith("included"), describe(withOva))
        assertEquals(true, describe(all).startsWith("included"), describe(all))
        assertEquals(true, describe(plain).startsWith("included"), describe(plain))
    }

    @Test
    fun `a subject of that kind keeps its releases`() = runSimpleMediaSelectorTestSuite {
        initSubject("约会大作战 OVA", episodes = 2)
        val ova = bt("[字幕组] 约会大作战 OVA [01][1080P]")

        assertEquals(true, describe(ova).startsWith("included"), describe(ova))
    }

    @Test
    fun `a one episode subject is not judged`() = runSimpleMediaSelectorTestSuite {
        initSubject("约会大作战 万由里裁决", episodes = 1)
        val movie = bt("[字幕组] 剧场版 约会大作战 万由里裁决 [1080P]")

        assertEquals(true, describe(movie).startsWith("included"), describe(movie))
    }

    // endregion

    private fun MaybeExcludedMedia.describe(): String = when (this) {
        is MaybeExcludedMedia.Included -> "included ${metadata.subjectMatchKind} ${metadata.episodeMatchKind}"
        is MaybeExcludedMedia.Excluded -> "excluded ${exclusionReason::class.simpleName}"
    }
}
