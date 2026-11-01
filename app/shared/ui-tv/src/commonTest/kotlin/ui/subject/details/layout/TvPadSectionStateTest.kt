/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.paging.LoadState
import me.him188.ani.app.data.repository.subject.EpisodesFetchState
import kotlin.test.Test
import kotlin.test.assertEquals

class TvPadSectionStateTest {
    @Test
    fun `episodes on screen are content whatever the fetch says`() {
        assertEquals(TvPadSectionState.Content, tvPadEpisodesState(true, false, EpisodesFetchState.Pending))
        assertEquals(TvPadSectionState.Content, tvPadEpisodesState(true, false, EpisodesFetchState.Failed))
    }

    @Test
    fun `episodes wait while the subject is a placeholder or still fetching`() {
        assertEquals(TvPadSectionState.Loading, tvPadEpisodesState(false, true, EpisodesFetchState.Fetched(0)))
        assertEquals(TvPadSectionState.Loading, tvPadEpisodesState(false, false, EpisodesFetchState.Pending))
        // 取到了已落库, 界面还没收到
        assertEquals(TvPadSectionState.Loading, tvPadEpisodesState(false, false, EpisodesFetchState.Fetched(12)))
    }

    @Test
    fun `episodes are empty when fetched none or failed`() {
        assertEquals(TvPadSectionState.Empty, tvPadEpisodesState(false, false, EpisodesFetchState.Fetched(0)))
        assertEquals(TvPadSectionState.Empty, tvPadEpisodesState(false, false, EpisodesFetchState.Failed))
    }

    @Test
    fun `people keep the placeholder until the row has items`() {
        assertEquals(TvPadSectionState.Loading, tvPadPeopleState(0, null))
        // 总数先到, 列表还没刷出来
        assertEquals(TvPadSectionState.Loading, tvPadPeopleState(0, 34))
        assertEquals(TvPadSectionState.Content, tvPadPeopleState(6, 34))
        assertEquals(TvPadSectionState.Empty, tvPadPeopleState(0, 0))
    }

    @Test
    fun `paged list waits for the first load`() {
        assertEquals(TvPadSectionState.Loading, tvPadPagedState(0, LoadState.Loading))
        assertEquals(TvPadSectionState.Loading, tvPadPagedState(0, LoadState.Loading, totalCount = 5))
    }

    @Test
    fun `paged list with items is content`() {
        assertEquals(TvPadSectionState.Content, tvPadPagedState(3, LoadState.Loading))
        assertEquals(TvPadSectionState.Content, tvPadPagedState(3, LoadState.NotLoading(true)))
    }

    @Test
    fun `paged list is empty when loaded none failed or total is zero`() {
        assertEquals(TvPadSectionState.Empty, tvPadPagedState(0, LoadState.NotLoading(true)))
        assertEquals(TvPadSectionState.Empty, tvPadPagedState(0, LoadState.NotLoading(false)))
        assertEquals(TvPadSectionState.Empty, tvPadPagedState(0, LoadState.Error(RuntimeException("x"))))
        assertEquals(TvPadSectionState.Empty, tvPadPagedState(0, LoadState.Loading, totalCount = 0))
    }
}
