/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

import me.him188.ani.datasources.api.EpisodeSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 测试 [findMatchingEpisodeOrNull] 在剧集列表中定位正在播放剧集的行为.
 * 搜索缓存以它判断缓存的条目页面是否包含当前请求的剧集.
 */
class FindMatchingEpisodeOrNullTest {
    private fun episode(channel: String?, sort: Int, name: String = "第0${sort}集") = WebSearchEpisodeInfo(
        channel = channel,
        name = name,
        episodeSortOrEp = EpisodeSort(sort),
        playUrl = "https://example.com/${channel ?: "nochannel"}/$sort",
    )

    @Test
    fun `findMatchingEpisodeOrNull prefers sort match`() {
        val episodes = listOf(episode("线路1", 1), episode("线路1", 2))
        assertEquals(episodes[1], episodes.findMatchingEpisodeOrNull(EpisodeSort(2), EpisodeSort(1), null))
    }

    @Test
    fun `findMatchingEpisodeOrNull falls back to ep match`() {
        // 第二季: 系列内 sort 为 14, 季度内 ep 为 2, 页面上解析到的是 2
        val episodes = listOf(episode("线路1", 1), episode("线路1", 2))
        assertEquals(episodes[1], episodes.findMatchingEpisodeOrNull(EpisodeSort(14), EpisodeSort(2), null))
    }

    @Test
    fun `findMatchingEpisodeOrNull matches special episode by name`() {
        val episodes = listOf(
            episode("线路1", 1),
            // 特殊剧集: 页面解析出的 sort (13) 与其系列内 sort ("OVA上") 不一致, 需要按名称匹配
            WebSearchEpisodeInfo("线路1", "OVA上", EpisodeSort(13), "https://example.com/ova"),
        )
        assertEquals(
            episodes[1],
            episodes.findMatchingEpisodeOrNull(EpisodeSort("OVA上"), null, "OVA上"),
        )
    }

    @Test
    fun `findMatchingEpisodeOrNull returns null when nothing matches`() {
        val episodes = listOf(episode("线路1", 1), episode("线路1", 2))
        assertNull(episodes.findMatchingEpisodeOrNull(EpisodeSort(99), null, null))
    }

    /**
     * 站点把集号写成剧集名: hanime1.me 的条目页并列了两部单集作品, 每条的"集号"就是作品名.
     * 见 https://github.com/open-ani/animeko/pull/3345
     */
    @Test
    fun `findMatchingEpisodeOrNull matches by name when sort is the episode title`() {
        val episodes = listOf(titleAsSort(TONARI), titleAsSort(YOGORETA))
        // 两部作品在 Bangumi 侧都是单集, sort 都是 01, 只有剧集名不同
        assertEquals(episodes[1], episodes.findMatchingEpisodeOrNull(EpisodeSort(1), EpisodeSort(1), YOGORETA))
        assertEquals(episodes[0], episodes.findMatchingEpisodeOrNull(EpisodeSort(1), EpisodeSort(1), TONARI))
    }

    @Test
    fun `findMatchingEpisodeOrNull does not match another work on the same page`() {
        val episodes = listOf(titleAsSort(TONARI), titleAsSort(YOGORETA))
        assertNull(episodes.findMatchingEpisodeOrNull(EpisodeSort(1), EpisodeSort(1), "毫不相干的作品"))
    }

    @Test
    fun `findMatchingEpisodeOrNull keeps a parsed sort even if the name matches`() {
        // 站点给了集号就以它为准: 页面第 3 集不能因为名字里有剧集名就当成第 1 集
        val episodes = listOf(WebSearchEpisodeInfo("线路1", "第03集 $TONARI", EpisodeSort(3), MOVIE_URL))
        assertNull(episodes.findMatchingEpisodeOrNull(EpisodeSort(1), EpisodeSort(1), TONARI))
        assertEquals(episodes[0], episodes.findMatchingEpisodeOrNull(EpisodeSort(3), EpisodeSort(3), TONARI))
    }

    @Test
    fun `findMatchingEpisodeOrNull without episode name falls back to sorts only`() {
        val episodes = listOf(titleAsSort(TONARI), episode("线路1", 1))
        assertEquals(episodes[1], episodes.findMatchingEpisodeOrNull(EpisodeSort(1), EpisodeSort(1), null))
    }

    @Test
    fun `findMatchingEpisodeOrNull matches a whole work label as first episode`() {
        val episodes = listOf(
            WebSearchEpisodeInfo("线路1", "HD高清国语版", EpisodeSort("HD高清国语版"), MOVIE_URL),
            WebSearchEpisodeInfo("线路1", "HD高清原声版", EpisodeSort("HD高清原声版"), MOVIE_URL),
        )
        assertEquals(episodes[0], episodes.findMatchingEpisodeOrNull(EpisodeSort(1), EpisodeSort(1), null))
        assertNull(episodes.findMatchingEpisodeOrNull(EpisodeSort(5), EpisodeSort(5), null))
    }

    @Test
    fun `findMatchingEpisodeOrNull matches the only episode on the page`() {
        // xfdm 的剧场版条目页只有一条「剧场版」
        val episodes = listOf(WebSearchEpisodeInfo("旧番主线①", "剧场版", EpisodeSort("剧场版"), MOVIE_URL))
        assertEquals(episodes[0], episodes.findMatchingEpisodeOrNull(EpisodeSort(1), EpisodeSort(1), null))
        assertNull(episodes.findMatchingEpisodeOrNull(EpisodeSort(5), EpisodeSort(5), null))
    }

    @Test
    fun `findMatchingEpisodeOrNull keeps a match that already worked`() {
        // 站点把集号写成了与请求完全相同的怪字符串: 本来就能按相等匹配上, 替换判据不能把它弄丢.
        // (整页只有一条, 否则会走"只有一条"那条判据)
        val weird = EpisodeSort("剧场版")
        val episodes = listOf(WebSearchEpisodeInfo("线路1", "剧场版", weird, MOVIE_URL))
        assertEquals(episodes[0], episodes.findMatchingEpisodeOrNull(weird, null, null))
    }

    private companion object {
        private const val TONARI = "住在隔壁的她"
        private const val YOGORETA = "被玷污的她"
        private const val MOVIE_URL = "https://example.com/movie"

        /** 站点把作品名写在集号位置: `epName` 与 `sort` 都是作品名 */
        private fun titleAsSort(title: String) = WebSearchEpisodeInfo(
            channel = "あんてきぬすっ",
            name = title,
            episodeSortOrEp = EpisodeSort(title),
            playUrl = "https://example.com/$title",
        )
    }
}
