/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import me.him188.ani.datasources.api.EpisodeSort
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 播放器页「添加夸克分享链接…」: 网页与服务端的文案都在译文表里, 认出的集号写成一行.
 */
class RemoteQuarkSharesTest {
    private val page = renderRemoteControlPage(
        initialTab = "player",
        searchFormHtml = "",
        requestSectionHtml = "",
    )

    private val table = REMOTE_I18N_TABLE.associateBy { it.zh }

    private val webKeys = listOf(
        "添加夸克分享链接…",
        "夸克分享链接",
        "粘贴分享链接，可以连「提取码：xxxx」一起",
        "正在打开分享…",
        "还没登录夸克",
        "播放这一集",
        "当作这一集播放",
        "还有 {0} 个视频没列出",
        "这部番已添加的分享",
        "删除这个分享",
        "删除这个分享？这部番的选源列表里就不再有它",
    )

    private val serverKeys = listOf(
        "只支持夸克网盘的分享链接",
        "没有找到夸克分享链接",
        "这个分享要提取码，请把「提取码：xxxx」一起粘贴进来",
        "分享打不开：{0}",
        "认出第 {0} 集",
        "认出第 {0} 集，没有第 {1} 集",
        "。正在重新搜索一次，让「我添加的分享」加入，电视上的视频会重新加载",
        "正在电视上播放",
        "已记下这个文件是第 {0} 集，正在电视上播放",
        "分享里没有视频文件，可能被分享者删除或被夸克屏蔽了",
        "打不开这个分享（{0}）",
        "分享里已经没有文件了，可能被分享者删除或被夸克屏蔽",
        "。已经转存到你网盘的 {0} 集照常能播",
        "。可以删掉它，换一个分享链接",
        "没认出这部番的剧集（文件名认不出集号，或者季对不上）。在下面点一个文件当作这一集播放，会记下这个分享和这一集",
    )

    @Test
    fun `web keys are used by the page and translated`() {
        for (key in webKeys) {
            assertContains(page, "T('$key'")
            assertTrue(key in table, "译文表里没有「$key」")
        }
        // 面板标题是静态 HTML, 由 translateStatic 整段替换
        assertContains(page, ">添加夸克分享<")
        assertTrue("添加夸克分享" in table)
    }

    @Test
    fun `server keys are translated`() {
        for (key in serverKeys) assertTrue(key in table, "译文表里没有「$key」")
    }

    @Test
    fun `consecutive episodes are folded into ranges`() {
        val episodes = listOf(1, 2, 3, 5, 7, 8).map { EpisodeSort(it) }
        assertEquals("1~3、5、7~8", RemoteQuarkShares.formatEpisodes(episodes))
        assertEquals("12", RemoteQuarkShares.formatEpisodes(listOf(EpisodeSort(12))))
        assertEquals("1~2、SP", RemoteQuarkShares.formatEpisodes(listOf(EpisodeSort(1), EpisodeSort(2), EpisodeSort("SP"))))
    }
}
