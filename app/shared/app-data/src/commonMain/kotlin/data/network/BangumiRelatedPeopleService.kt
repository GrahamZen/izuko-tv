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
                    // relation id 表是 2026-09-06 采样 28 个条目的 p1 relations 收全的:
                    // 1 改编 / 2 前传 / 3 续集 / 4 总集篇 / 5 全集 / 6 番外篇 / 7 角色出演 /
                    // 8 相同世界观 / 9 不同世界观 / 10 不同演绎 / 11 衍生 / 12 主线故事 /
                    // 14 联动 / 99 其他. (1004+ / 3001+ / 4007+ 那几组属于书籍/音乐/游戏,
                    // 上面按 type=2 过滤过, 到不了这里.)
                    // 原先只认 2/3/6/11, 其余全落进 null —— 详情页「关联条目」卡片下方一片空白,
                    // 而衍生条目最常见的那条边恰好是 12 主线故事.
                    relation = when (relation.relation.id) {
                        1 -> SubjectRelation.ADAPTATION
                        2 -> SubjectRelation.PREQUEL
                        3 -> SubjectRelation.SEQUEL
                        4 -> SubjectRelation.SUMMARY
                        5 -> SubjectRelation.FULL_STORY
                        6 -> SubjectRelation.SPECIAL
                        7 -> SubjectRelation.CHARACTER_APPEARANCE
                        8 -> SubjectRelation.SAME_SETTING
                        9 -> SubjectRelation.DIFFERENT_SETTING
                        10 -> SubjectRelation.ALTERNATIVE_VERSION
                        11 -> SubjectRelation.DERIVED
                        12 -> SubjectRelation.MAIN_STORY
                        14 -> SubjectRelation.COLLABORATION
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
