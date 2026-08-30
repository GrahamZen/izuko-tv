/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.subject.SubjectSeriesInfo
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [MediaSelectorContextFlowProducer] 首个 emit 的语义.
 *
 * 存在的理由: [MediaSelectorContext.unplayableCacheMediaIds] 的构造默认值是 `emptySet()`
 * ("已确认没有不可播的缓存", 给直接构造 context 的测试与预览用), 而
 * [MediaSelectorContext.Initial] 是按位置参数构造的 —— 少写一个参数, 它就会带着"已确认为空"
 * 出门, 于是 [MediaSelector.trySelectCached] 的等待在第一帧当场结束, 没下完的缓存照样被自动
 * 选中. 手工构造 context 的测试完全覆盖不到这条路径 (它们本来就自己写 null), 只有走 producer
 * 才看得见.
 */
class MediaSelectorContextProducerTest {
    private fun createProducer(
        unplayableCacheMediaIds: kotlinx.coroutines.flow.Flow<Set<String>>,
    ) = MediaSelectorContextFlowProducer(
        subjectCompleted = emptyFlow(),
        mediaSourcePrecedence = emptyFlow(),
        subjectSeriesInfo = flowOf(SubjectSeriesInfo.Fallback),
        subjectInfoFlow = flowOf(SubjectInfo.Empty),
        episodeInfoFlow = flowOf(EpisodeInfo.Empty),
        mediaSourceTiersFlow = flowOf(MediaSelectorSourceTiers.Empty),
        unplayableCacheMediaIds = unplayableCacheMediaIds,
    )

    @Test
    fun `first emission must report cache playability as unknown`() = runTest {
        // 缓存那条流一直不发 —— 模拟"还没查到"
        val context = createProducer(MutableSharedFlow()).flow.first()
        assertNull(
            context.unplayableCacheMediaIds,
            "首个 emit 必须是 null (还没查到); 给成 emptySet() 会让 trySelectCached 的等待失效",
        )
    }

    @Test
    fun `Initial itself reports cache playability as unknown`() {
        assertNull(MediaSelectorContext.Initial.unplayableCacheMediaIds)
    }

    @Test
    fun `cache playability arrives after it is known`() = runTest {
        val ids = setOf("local-file-system:foo")
        val emissions = createProducer(flowOf(ids)).flow
            .take(2)
            .toList()
        assertNull(emissions.first().unplayableCacheMediaIds)
        assertEquals(ids, emissions.last { it.unplayableCacheMediaIds != null }.unplayableCacheMediaIds)
    }

    @Test
    fun `directly constructed context defaults to known-empty`() {
        // 与上面相反的那一半: 测试与预览直接 new 出来的 context 不该停在"未知"
        assertEquals(
            emptySet(),
            MediaSelectorContext(
                subjectFinished = null,
                mediaSourcePrecedence = null,
                subtitlePreferences = null,
                subjectSeriesInfo = null,
                subjectInfo = null,
                episodeInfo = null,
                mediaSourceTiers = null,
            ).unplayableCacheMediaIds,
        )
    }
}
