/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.SubjectSeriesInfo
import me.him188.ani.app.data.repository.subject.SubjectRelationsRepository
import me.him188.ani.datasources.api.source.MediaFetchNameHints

/**
 * 请求里认站点条目用的名字 ([MediaFetchNameHints]): 条目服务上的全部名字加上系列关系推出的「系列名 第N季」([SubjectSeriesInfo.seasonAliases])、
 * 系列名 (同 [computeFallbackSearchKeywords] 推出的基础名)、同系列别的作品的名字. 港台译名另由 [withRegionalNames] 加上.
 */
internal fun fetchNameHintsOf(subject: SubjectInfo, seriesInfo: SubjectSeriesInfo?): MediaFetchNameHints {
    val names = subject.allNames.map { it.trim() }.filter { it.isNotEmpty() }
    val series = seriesInfo?.seriesSubjectNamesWithoutSelf.orEmpty()
    return MediaFetchNameHints(
        ownNames = (names + seriesInfo?.seasonAliases.orEmpty()).distinct(),
        seriesNames = computeFallbackSearchKeywords(names, series).filter { it !in names },
        otherWorkNames = series.toList(),
        splitSeason = seriesInfo?.splitSeason != null,
    )
}

/** 交给选择器的条目信息带上系列关系推出的「系列名 第N季」([SubjectSeriesInfo.seasonAliases]): 选择器与数据源按同一组名字认条目. */
fun SubjectInfo.withSeasonAliases(seriesInfo: SubjectSeriesInfo?): SubjectInfo {
    val aliases = seriesInfo?.seasonAliases.orEmpty().filter { alias -> allNames.none { it == alias } }
    return if (aliases.isEmpty()) this else copy(aliases = this.aliases + aliases)
}

/**
 * 给条目信息带 [withSeasonAliases] 用的系列信息: 先发 `null` (条目信息不等系列), 算出来再发一次; 算不出 (出错) 就一直是 `null`,
 * 条目信息照常, 只是少了推出的名字.
 */
fun SubjectRelationsRepository.seriesInfoOrNullFlow(subjectId: Int): Flow<SubjectSeriesInfo?> =
    subjectSeriesInfoFlow(subjectId)
        .map<SubjectSeriesInfo, SubjectSeriesInfo?> { it }
        .onStart { emit(null) }
        .catch { emit(null) }
