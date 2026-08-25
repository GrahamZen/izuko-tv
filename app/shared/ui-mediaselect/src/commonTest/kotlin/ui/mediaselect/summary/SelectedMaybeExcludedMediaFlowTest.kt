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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.SubjectSeriesInfo
import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.app.domain.media.selector.DefaultMediaSelector
import me.him188.ani.app.domain.media.selector.MediaExclusionReason
import me.him188.ani.app.domain.media.selector.MediaSelectorContext
import me.him188.ani.app.domain.media.selector.MediaSelectorSourceTiers
import me.him188.ani.app.domain.media.selector.MediaSelectorSubtitlePreferences
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 播放器上那句 "数据源 · 资源名 [条目名不匹配]" 里的排除原因, 必须在**排除规则真的跑起来的那一刻**
 * 跟着出现, 而不是等 `selected` 再变一次.
 *
 * 2026-08-25 真机: 标签要么等一会才出现, 要么得打开数据源菜单再返回才看得到 —— 因为
 * [selectedMaybeExcludedMediaFlow] 当时只在 `selected` 变化时对 `filteredCandidates` 取一次快照,
 * 而那一份快照是在条目信息还没到位时算的 (那时 `MediaListFilterContext` 压根没构造, 所有资源都是
 * `Included`, `exclusionReason` 全为 null).
 */
class SelectedMaybeExcludedMediaFlowTest {
    private val notReadyContext = MediaSelectorContext.Initial.copy(
        subjectFinished = false,
        mediaSourcePrecedence = emptyList(),
        subtitlePreferences = MediaSelectorSubtitlePreferences.AllNormal,
        subjectSeriesInfo = SubjectSeriesInfo.Fallback,
        mediaSourceTiers = MediaSelectorSourceTiers.Empty,
    )

    private val readyContext = notReadyContext.copy(
        subjectInfo = SubjectInfo.Empty.copy(
            subjectId = 583729,
            name = "BanG Dream! ゆめ∞みた",
            nameCn = "BanG Dream! YUME∞MITA",
        ),
        episodeInfo = EpisodeInfo.Empty.copy(
            episodeId = 1687829,
            name = "MewType",
            sort = EpisodeSort(1),
            ep = EpisodeSort(1),
        ),
    )

    /**
     * 与本条目无关的 web 资源. 规则跑得到时会被 [MediaExclusionReason.SubjectNameMismatch] 排除.
     */
    private val mismatched = createTestDefaultMedia(
        mediaId = "web.mismatched",
        mediaSourceId = "web",
        originalUrl = "https://example.com/1",
        download = ResourceLocation.WebVideo("https://example.com/1"),
        originalTitle = "能帮我弄干净吗？ 第01集",
        publishedTime = 0,
        properties = createTestMediaProperties(subjectName = "能帮我弄干净吗？", alliance = "简中"),
        episodeRange = EpisodeRange.single(EpisodeSort(1)),
        location = MediaSourceLocation.Online,
        kind = MediaSourceKind.WEB,
    )

    private val mediaSelectorContext = MutableStateFlow(notReadyContext)

    private val selector = DefaultMediaSelector(
        mediaSelectorContextNotCached = mediaSelectorContext,
        mediaListNotCached = MutableStateFlow<List<Media>>(listOf(mismatched)),
        savedUserPreference = MutableStateFlow(MediaPreference.Empty),
        savedDefaultPreference = MutableStateFlow(MediaPreference.Empty),
        mediaSelectorSettings = MutableStateFlow(MediaSelectorSettings.Default),
        enableCaching = false,
    )

    @Test
    fun `条目信息晚到时排除原因仍会补上`() = runTest {
        selector.select(mismatched)

        // 必须是**一次**持续收集: UI 就是这么用的.
        // 若改成两次 .first(), 第二次会重新取一份新快照, 等于模拟了"打开数据源菜单再返回",
        // 那样连出 bug 的旧写法也能通过, 这个 test 就什么都没钉住.
        val seen = mutableListOf<MediaExclusionReason?>()
        val job = launch {
            selector.selectedMaybeExcludedMediaFlow.collect { seen += it?.exclusionReason }
        }
        runCurrent()

        // 规则还没上岗, 这条没被判过
        assertNull(seen.last())

        // 条目信息到位 —— selected 没有再变过, 排除原因必须自己跟上
        mediaSelectorContext.value = readyContext
        runCurrent()
        assertEquals(MediaExclusionReason.SubjectNameMismatch, seen.last())

        job.cancel()
    }
}
