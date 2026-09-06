/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediaselect.summary

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.app.domain.media.selector.MatchMetadata
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.datasources.api.Media
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 播放器顶部的数据源: 播放中改了搜索名, 选源会话重建、新的选源器暂时没有选中项, 仍要显示播放器正在播的那个.
 *
 * 像界面那样持续收集同一条流, 看最新一次的结果 (两次 `.first()` 等于重新收集, 测不出沿用).
 */
class OrStillPlayingTest {
    private val playing = TestMediaList[0]
    private val other = TestMediaList[1]
    private val playingSelected = MaybeExcludedMedia.Included(
        playing,
        MatchMetadata(MatchMetadata.SubjectMatchKind.EXACT, MatchMetadata.EpisodeMatchKind.EP, 100),
    )

    private val selected = MutableStateFlow<MaybeExcludedMedia?>(null)
    private val loaded = MutableStateFlow<Media?>(null)

    private fun TestScope.collectShown(): List<MaybeExcludedMedia?> {
        val seen = mutableListOf<MaybeExcludedMedia?>()
        backgroundScope.launch { selected.orStillPlaying(loaded).collect { seen += it } }
        runCurrent()
        return seen
    }

    @Test
    fun `选源会话重建后播放器还在播原来那个，照旧显示它`() = runTest {
        val seen = collectShown()
        selected.value = playingSelected
        loaded.value = playing
        runCurrent()
        selected.value = null // 改了搜索名, 新的选源器还没选出东西
        runCurrent()
        assertEquals(playingSelected, seen.last())
    }

    @Test
    fun `播放器停了就不再显示`() = runTest {
        val seen = collectShown()
        selected.value = playingSelected
        loaded.value = playing
        runCurrent()
        selected.value = null
        loaded.value = null
        runCurrent()
        assertNull(seen.last())
    }

    @Test
    fun `播放器换成了别的资源，不沿用上一次`() = runTest {
        val seen = collectShown()
        selected.value = playingSelected
        loaded.value = playing
        runCurrent()
        selected.value = null
        loaded.value = other
        runCurrent()
        assertNull(seen.last())
    }

    @Test
    fun `新的选源器选中了别的，显示新的`() = runTest {
        val seen = collectShown()
        selected.value = playingSelected
        loaded.value = playing
        runCurrent()
        val otherSelected = MaybeExcludedMedia.Included(
            other,
            MatchMetadata(MatchMetadata.SubjectMatchKind.FUZZY, MatchMetadata.EpisodeMatchKind.NONE, 50),
        )
        selected.value = otherSelected
        runCurrent()
        assertEquals(otherSelected, seen.last())
    }
}
