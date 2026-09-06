/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.list

import me.him188.ani.app.data.models.preference.TvEpisodeSpecialsPlacement
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.utils.platform.annotations.TestOnly
import me.him188.ani.utils.serialization.BigNum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * @see arrangeSpecials
 * @see gridSpecialEpisodes
 */
@OptIn(TestOnly::class)
class EpisodeSpecialsPlacementTest {
    private var nextId = 1

    private fun main(sort: Int) = createTestEpisodeListItem(EpisodeSort(sort), episodeId = nextId++)
    private fun special(sort: String, type: EpisodeType = EpisodeType.SP) =
        createTestEpisodeListItem(EpisodeSort(BigNum(sort), type), episodeId = nextId++)

    private val ep1 = main(1)
    private val ep2 = main(2)
    private val ep3 = main(3)
    private val sp1 = special("1")
    private val sp2_5 = special("2.5")
    private val op1 = special("1", EpisodeType.OP)

    /** 数据库顺序: 正片在前, 特别篇按类型分组排在后面, 组内按序号. */
    private val dbOrder = listOf(ep1, ep2, ep3, sp1, sp2_5, op1)

    private fun List<EpisodeListItem>.sorts() = map { it.sort.toString() }

    @Test
    fun `after main keeps specials grouped after main episodes`() {
        assertEquals(dbOrder.sorts(), dbOrder.arrangeSpecials(TvEpisodeSpecialsPlacement.AfterMain).sorts())
    }

    @Test
    fun `after main moves interleaved specials after main episodes`() {
        val interleaved = listOf(ep1, sp1, ep2, sp2_5, ep3)
        assertEquals(
            listOf(ep1, ep2, ep3, sp1, sp2_5).sorts(),
            interleaved.arrangeSpecials(TvEpisodeSpecialsPlacement.AfterMain).sorts(),
        )
    }

    @Test
    fun `by number interleaves specials and puts main episode first on equal numbers`() {
        assertEquals(
            listOf(ep1, sp1, op1, ep2, sp2_5, ep3).sorts(),
            dbOrder.arrangeSpecials(TvEpisodeSpecialsPlacement.ByNumber).sorts(),
        )
    }

    @Test
    fun `by number puts specials without number last`() {
        val unknown = createTestEpisodeListItem(EpisodeSort("SP 特典"), episodeId = nextId++)
        assertEquals(
            listOf(ep1, sp1, ep2, ep3, unknown).sorts(),
            listOf(ep1, ep2, ep3, unknown, sp1).arrangeSpecials(TvEpisodeSpecialsPlacement.ByNumber).sorts(),
        )
    }

    @Test
    fun `hidden removes specials`() {
        assertEquals(
            listOf(ep1, ep2, ep3).sorts(),
            dbOrder.arrangeSpecials(TvEpisodeSpecialsPlacement.Hidden).sorts(),
        )
    }

    @Test
    fun `hidden keeps specials while a special is playing`() {
        assertEquals(
            dbOrder.sorts(),
            dbOrder.arrangeSpecials(TvEpisodeSpecialsPlacement.Hidden, playingEpisodeId = sp2_5.episodeId).sorts(),
        )
        assertEquals(
            listOf(ep1, ep2, ep3).sorts(),
            dbOrder.arrangeSpecials(TvEpisodeSpecialsPlacement.Hidden, playingEpisodeId = ep2.episodeId).sorts(),
        )
    }

    @Test
    fun `returns the same list when there are no specials or no main episodes`() {
        val mainOnly = listOf(ep1, ep2)
        val specialsOnly = listOf(sp1, op1)
        for (placement in TvEpisodeSpecialsPlacement.entries) {
            assertSame(mainOnly, mainOnly.arrangeSpecials(placement))
            assertSame(specialsOnly, specialsOnly.arrangeSpecials(placement))
        }
    }

    @Test
    fun `grid hides specials only when hidden and there are main episodes`() {
        val state = EpisodeListUiState(
            subjectTitle = "",
            mainEpisodes = listOf(ep1, ep2, ep3),
            otherEpisodes = listOf(sp1, sp2_5, op1),
            allEpisodes = dbOrder,
        )
        assertEquals(emptyList(), state.gridSpecialEpisodes(TvEpisodeSpecialsPlacement.Hidden))
        assertEquals(state.otherEpisodes, state.gridSpecialEpisodes(TvEpisodeSpecialsPlacement.AfterMain))
        assertEquals(state.otherEpisodes, state.gridSpecialEpisodes(TvEpisodeSpecialsPlacement.ByNumber))

        val specialsOnly = state.copy(mainEpisodes = emptyList(), allEpisodes = state.otherEpisodes)
        assertEquals(state.otherEpisodes, specialsOnly.gridSpecialEpisodes(TvEpisodeSpecialsPlacement.Hidden))
    }
}
