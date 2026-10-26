/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.app.domain.mediasource.search.KeywordStyle
import me.him188.ani.app.domain.mediasource.search.SubjectSearchPlan
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import java.io.File
import kotlin.test.Test

/**
 * 网盘按名字搜的关键词 (搜索计划的主关键词, 见 [KeywordStyle.literal]) 与副标题 ([DriveSeriesParts.keywords]) 批量算出来, 给改关键词前后对照用.
 * 设 `DRIVE_KEYWORD_CASES` (输入) 与 `DRIVE_KEYWORD_OUT` (输出) 才跑; 再设 `DRIVE_SERIES_GRAPH` (系列关系图 bgm-series-graph.tsv) 时也算副标题.
 * 输入每行 `条目 id<TAB>名字<TAB>名字…` (名字按请求里的顺序: 中文名、原名、别名),
 * 输出每行 `条目 id<TAB>关键词<TAB>关键词…`, 有副标题时后面接 `<TAB>##<TAB>副标题<TAB>副标题…`.
 */
class DriveKeywordsDumpTest {
    @Test
    fun dump() {
        val input = System.getenv("DRIVE_KEYWORD_CASES") ?: return println("跳过: 设 DRIVE_KEYWORD_CASES 与 DRIVE_KEYWORD_OUT 才跑")
        val output = System.getenv("DRIVE_KEYWORD_OUT") ?: return println("跳过: 没设 DRIVE_KEYWORD_OUT")
        val table = System.getenv("DRIVE_SERIES_GRAPH")?.let { SeriesGraphTable.parse(File(it).readBytes()) }
        val lines = File(input).readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { line ->
            val fields = line.split('\t')
            val parts = table?.let { DriveSeriesParts.of(fields.first().toIntOrNull(), it) }
            val request = MediaFetchRequest(
                subjectId = fields.first(), episodeId = "", subjectNames = fields.drop(1), episodeSort = EpisodeSort(1), episodeName = "",
            )
            val keywords = SubjectSearchPlan.of(request).keywords(KeywordStyle.literal(DriveSubjectMatcher.NAME_KEYWORDS)).primary
            val columns = listOf(fields.first()) + keywords.map { it.keyword } + parts?.let { listOf("##") + it.keywords }.orEmpty()
            columns.joinToString("\t")
        }
        File(output).writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }
}
