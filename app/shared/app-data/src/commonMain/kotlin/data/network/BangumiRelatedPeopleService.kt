/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.him188.ani.app.data.models.subject.RelatedSubjectInfo
import me.him188.ani.app.data.models.subject.SubjectRelation
import me.him188.ani.app.data.network.mapper.orBangumiPlaceholder
import me.him188.ani.datasources.bangumi.next.apis.SubjectBangumiNextApi
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubjectRelation
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubjectType
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

class BangumiRelatedPeopleService(
    private val subjectApi: ApiInvoker<SubjectBangumiNextApi>,
) {
    private val logger = logger<BangumiRelatedPeopleService>()

    fun relatedSubjectsFlow(subjectId: Int): Flow<List<RelatedSubjectInfo>> = flow {
        val list = subjectApi { fetchAllAnimeRelations(subjectId) }
        logger.info { "bgm-direct: relatedSubjects subject=$subjectId -> ${list.size}" }
        emit(
            list.map { relation ->
                val subject = relation.subject
                RelatedSubjectInfo(
                    subjectId = subject.id,
                    relation = when (relation.relation.id) {
                        2 -> SubjectRelation.PREQUEL
                        3 -> SubjectRelation.SEQUEL
                        6 -> SubjectRelation.SPECIAL
                        11 -> SubjectRelation.DERIVED
                        else -> null
                    },
                    name = subject.name,
                    nameCn = subject.nameCN,
                    image = subject.images?.large.orBangumiPlaceholder(),
                )
            }.let(RelatedSubjectInfo::sortList),
        )
    }

    /**
     * `type` 必须传: 不传的话除了动画还会带回漫画/游戏/音乐 (OST、OP/ED 单曲) 等等,
     * 而详情页的「关联条目」只展示动画. Ani 那个端点是在服务端做的这个过滤.
     */
    private suspend fun SubjectBangumiNextApi.fetchAllAnimeRelations(
        subjectId: Int,
    ): List<BangumiNextSubjectRelation> {
        val result = mutableListOf<BangumiNextSubjectRelation>()
        var offset = 0
        while (true) {
            val page = getSubjectRelations(
                subjectID = subjectId,
                type = BangumiNextSubjectType.Anime,
                limit = PAGE_SIZE,
                offset = offset,
            ).body()
            result.addAll(page.data)
            offset += page.data.size
            if (page.data.isEmpty() || result.size >= page.total || offset >= MAX_ITEMS) break
        }
        return result
    }

    private companion object {
        const val PAGE_SIZE = 50

        /**
         * 长寿系列 (高达之类) 的关联动画可以上百条, 但详情页那一排展示不了那么多, 取个上限免得翻页翻不完.
         */
        const val MAX_ITEMS = 200
    }
}
