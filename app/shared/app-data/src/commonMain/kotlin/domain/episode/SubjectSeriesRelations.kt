/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.subject.SplitSeason
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.SubjectSeriesInfo
import me.him188.ani.app.data.network.SubjectRelationIndex
import me.him188.ani.app.data.network.splitSeasonOf
import me.him188.ani.app.data.network.toSubjectRelations
import me.him188.ani.app.data.repository.subject.SubjectRelationsRepository
import me.him188.ani.app.domain.media.fetch.create
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** 比系列关系图新的条目要现场走系列, 开播最多等这么久, 等不到就先不带系列信息. */
internal val SERIES_INDEX_WAIT: Duration = 1500.milliseconds

private val logger = logger<SubjectSeriesInfo>()

/**
 * 给条目填上系列关系 ([SubjectCollectionInfo.relations]), 由它算出的 [SubjectSeriesInfo] 供开播用: 数据源搜索的回退关键词
 * (系列基础名)、选源时排除别季的资源, 以及拆分季的后半按季内序号对集 ([SplitSeason]).
 * 条目库里的 relations 原本由 Ani 服务端随条目下发, 直连后没人填, 这里用 [index] (系列索引, 表覆盖到的条目查系列关系图当场就有) 补上;
 * 拆分季要用条目自己的名字与分集, 每次发出时随条目算.
 *
 * 先等系列索引再放条目出去, 而不是先放再补: 补一次会让下游重建搜索会话、整个重搜. 最多等 [wait].
 */
internal fun Flow<SubjectCollectionInfo>.withSeriesRelations(
    subjectId: Int,
    index: suspend (Int) -> SubjectRelationIndex,
    wait: Duration = SERIES_INDEX_WAIT,
): Flow<SubjectCollectionInfo> = flow {
    val relationIndex = try {
        withTimeoutOrNull(wait) { index(subjectId) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(e) { "Failed to get series index of subject $subjectId, playing without series info" }
        null
    }
    if (relationIndex == null) {
        emitAll(this@withSeriesRelations)
    } else {
        val relations = relationIndex.toSubjectRelations()
        var lastLogged: SplitSeason? = null
        emitAll(
            map { subject ->
                val splitSeason = relationIndex.splitSeasonOf(subject)
                if (splitSeason != null && splitSeason != lastLogged) {
                    lastLogged = splitSeason
                    logger.info { "Split season of subject $subjectId: ${splitSeason.describe()}" }
                }
                subject.copy(relations = relations.copy(splitSeason = splitSeason))
            },
        )
    }
}

/**
 * 播放页以外建数据源查询 (缓存页、Web 控制台) 时用的系列信息, 交给 [MediaFetchRequest.Companion.create]: 回退搜索关键词
 * (系列基础名) 与拆分季的整季序号, 与开播时一致. 同开播最多等 [wait] (表覆盖到的条目当场就有), 等不到或出错就不带.
 */
suspend fun SubjectRelationsRepository.seriesInfoForFetchOrNull(
    subjectId: Int,
    wait: Duration = SERIES_INDEX_WAIT,
): SubjectSeriesInfo? = try {
    withTimeoutOrNull(wait) { subjectSeriesInfoFlow(subjectId).first() }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    logger.warn(e) { "Failed to get series info of subject $subjectId, fetching without it" }
    null
}

private fun SplitSeason.describe(): String =
    parts.joinToString(prefix = "parts [", postfix = "]") { "${it.subjectId} sort ${it.firstSort}+${it.episodeCount}" } +
            ", self #$selfIndex, base names $baseNames, other seasons $otherSeasonNumbers"
