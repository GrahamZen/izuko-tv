/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector.legacy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.selector.DefaultMediaSelector
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaExclusionReason
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.topic.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「缓存还没下完」这一档排除 ([MediaExclusionReason.CacheNotReady]) 的行为.
 *
 * 它与其他排除原因不同: 其余排除只是"不合偏好", 用户手动点了还是能播; 这一档是**硬性不可用**
 * —— 最终文件还不存在, 选了必定加载失败. 所以除了界面禁用之外, 自动选择的每条路径都得挡住.
 */
class DefaultMediaSelectorCacheNotReadyTest : AbstractDefaultMediaSelectorTest() {
    private val cacheSourceId = "local-file-system"

    // 基类的 mediaList 限定为 DefaultMedia, 装不下 CachedMedia, 所以本测试自建一条输入流与 selector
    private val cachedList = MutableStateFlow<List<Media>>(emptyList())
    private val cacheSelector = DefaultMediaSelector(
        mediaSelectorContextNotCached = mediaSelectorContext,
        mediaListNotCached = cachedList,
        savedUserPreference = savedUserPreference,
        savedDefaultPreference = savedDefaultPreference,
        enableCaching = false,
        mediaSelectorSettings = mediaSelectorSettings,
    )

    /** 造一条"本地缓存"资源, 其 mediaId 与线上源的关系同 [CachedMedia] 的约定 */
    private fun cachedMediaOf(origin: me.him188.ani.datasources.api.DefaultMedia) = CachedMedia(
        origin = origin,
        cacheMediaSourceId = cacheSourceId,
        download = ResourceLocation.LocalFile("/tmp/not-yet-there.mp4"),
    )

    @Test
    fun `没下完的缓存会被排除`() = runTest {
        val origin = media(alliance = "字幕组")
        val cached = cachedMediaOf(origin)
        cachedList.value = listOf(cached)
        mediaSelectorContext.value = mediaSelectorContext.value.copy(
            unplayableCacheMediaIds = setOf(cached.mediaId),
        )

        val result = cacheSelector.filteredCandidates.first().single()
        assertIs<MaybeExcludedMedia.Excluded>(result)
        assertEquals(MediaExclusionReason.CacheNotReady, result.exclusionReason)
    }

    /**
     * 自动选择缓存 ([DefaultMediaSelector.trySelectCached]) 也必须挡住 —— 它原本刻意无视一切排除
     * 原因 ("只要缓存了就行"), 于是进入只有未完成缓存的剧集时会去播一个还不存在的文件.
     */
    @Test
    fun `没下完的缓存不会被自动选中`() = runTest {
        val origin = media(alliance = "字幕组")
        val cached = cachedMediaOf(origin)
        cachedList.value = listOf(cached)
        mediaSelectorContext.value = mediaSelectorContext.value.copy(
            unplayableCacheMediaIds = setOf(cached.mediaId),
        )

        assertNull(cacheSelector.trySelectCached())
        assertNull(cacheSelector.selected.value)
    }

    /** 下完之后判据翻转, 同一条立刻可选 —— 选源列表是一次性快照, 不会换对象, 只有排除状态在变 */
    @Test
    fun `下完之后可以被自动选中`() = runTest {
        val origin = media(alliance = "字幕组")
        val cached = cachedMediaOf(origin)
        cachedList.value = listOf(cached)
        mediaSelectorContext.value = mediaSelectorContext.value.copy(
            unplayableCacheMediaIds = setOf(cached.mediaId),
        )
        assertNull(cacheSelector.trySelectCached())

        // 下完了: 这条从"不可播"集合里消失
        mediaSelectorContext.value = mediaSelectorContext.value.copy(unplayableCacheMediaIds = emptySet())

        assertEquals(cached, cacheSelector.trySelectCached())
    }

    /**
     * 记忆表的定点失效: 生产者按设计**先发一个 null**, 那一轮缓存会被算成可选并记进表.
     * 真集合到达时若不剔除 (曾经的 bug: `old != null` 判据把首次变化整个跳过), 没下完的缓存
     * 就会一直可选下去.
     */
    @Test
    fun `首次拿到不可播集合时记忆表会失效`() = runTest {
        val origin = media(alliance = "字幕组")
        val cached = cachedMediaOf(origin)
        cachedList.value = listOf(cached)
        // 第一轮: 还不知道可播性 (null), 这条被算成可选并记进记忆表
        mediaSelectorContext.value = mediaSelectorContext.value.copy(unplayableCacheMediaIds = null)
        assertIs<MaybeExcludedMedia.Included>(cacheSelector.filteredCandidates.first().single())

        // 第二轮: 真集合到了, 记忆表必须被剔除这一条, 否则它会一直保持可选
        mediaSelectorContext.value = mediaSelectorContext.value.copy(
            unplayableCacheMediaIds = setOf(cached.mediaId),
        )
        val result = cacheSelector.filteredCandidates.first().single()
        assertIs<MaybeExcludedMedia.Excluded>(result)
        assertEquals(MediaExclusionReason.CacheNotReady, result.exclusionReason)
        assertNull(cacheSelector.trySelectCached())
    }

    /** 其余排除原因不是硬性不可用, 自动选缓存照旧要能选中 (别把这条路一起挡死了) */
    @Test
    fun `其他原因被排除的缓存仍可自动选中`() = runTest {
        val origin = media(alliance = "字幕组")
        val cached = cachedMediaOf(origin)
        cachedList.value = listOf(cached)
        mediaSelectorContext.value = mediaSelectorContext.value.copy(unplayableCacheMediaIds = emptySet())

        val result = cacheSelector.filteredCandidates.first().single()
        assertTrue(result.exclusionReason?.let { it != MediaExclusionReason.CacheNotReady } ?: true)
        assertEquals(cached, cacheSelector.trySelectCached())
    }
}
