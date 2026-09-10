/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SubjectSearchKeywordsTest {
    private val request = MediaFetchRequest(
        subjectId = "1",
        episodeId = "2",
        subjectNameCN = "关于我转生变成史莱姆这档事 第三季",
        subjectNames = listOf("关于我转生变成史莱姆这档事 第三季", "転生したらスライムだった件 第3期"),
        episodeSort = EpisodeSort(49),
        episodeName = "恶魔与阴谋",
        episodeEp = EpisodeSort(1),
    )

    @Test
    fun `只替换条目名, 分集字段不动`() {
        val applied = SubjectSearchKeywords(listOf("史莱姆 第三季", "転スラ")).applyTo(request)

        assertEquals("史莱姆 第三季", applied.subjectNameCN)
        assertEquals(listOf("史莱姆 第三季", "転スラ"), applied.subjectNames)
        assertEquals(request.episodeSort, applied.episodeSort)
        assertEquals(request.episodeEp, applied.episodeEp)
        assertEquals(request.episodeName, applied.episodeName)
        assertEquals(request.subjectId, applied.subjectId)
        assertEquals(request.episodeId, applied.episodeId)
    }

    @Test
    fun `空白名字被剔除`() {
        val applied = SubjectSearchKeywords(listOf("", "史莱姆", "  ")).applyTo(request)

        assertEquals(listOf("史莱姆"), applied.subjectNames)
        assertEquals("史莱姆", applied.subjectNameCN)
    }

    @Test
    fun `全是空白时原样返回`() {
        assertSame(request, SubjectSearchKeywords(listOf("", " ")).applyTo(request))
    }
}
