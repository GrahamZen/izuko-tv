/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.network.RecommendationRepository
import me.him188.ani.app.data.network.SubjectService
import me.him188.ani.app.data.models.subject.preferredDisplayName as subjectPreferredDisplayName
import me.him188.ani.app.domain.usecase.UseCase
import me.him188.ani.utils.platform.Uuid


class SubjectRecommendation(
    val subjectId: Long?,
    val name: String,
    val nameCn: String?,
    val desc1: String,
    val desc2: String,
    val imageUrl: String,
    val uri: String?,
    /** 这一条从哪来的 (平板播放页的推荐行写成推荐理由). */
    val source: Source = Source.ALSO_WATCHED,
) {
    val uniqueId: String = Uuid.randomString()

    enum class Source {
        /** Bangumi 的「看过这部的人也看过」. */
        ALSO_WATCHED,

        /** 推荐系统里和这部相似的作品 ([RecommendationRepository.similarTo]). */
        SIMILAR,
    }
}

/**
 * 根据用户偏好选择的显示名称, 与 [subjectPreferredDisplayName] 同一约定.
 * @param useOriginalTitle 为 `true` 时优先显示原名 ([SubjectRecommendation.name]).
 */
fun SubjectRecommendation.preferredDisplayName(useOriginalTitle: Boolean): String =
    if (useOriginalTitle) {
        name.ifBlank { nameCn.orEmpty() }
    } else {
        nameCn.takeIf { !it.isNullOrBlank() } ?: name
    }

fun interface GetSubjectRecommendationUseCase : UseCase {
    suspend operator fun invoke(subject: SubjectInfo): List<SubjectRecommendation>
}

/**
 * Bangumi 的「看过这部的人也看过」在前; 不到 [RELATED_TARGET] 部时拿推荐系统里「和《X》相似的作品」补齐 ([RecommendationRepository.similarTo]).
 * Bangumi 这份数据停在 2023 年上半年, 之后开播的条目一部都没有, 老番去掉非动画后也常不到 10 部.
 */
class GetSubjectRecommendationUseCaseImpl(
    private val service: SubjectService,
    private val recommendationRepository: RecommendationRepository,
) : GetSubjectRecommendationUseCase {
    override suspend fun invoke(subject: SubjectInfo): List<SubjectRecommendation> {
        val related = service.getSubjectRecommendations(subject.subjectId, 15).map {
            SubjectRecommendation(
                subjectId = it.subjectId.toLong(),
                name = it.name,
                nameCn = it.nameCn,
                // Ani 那边这两行是服务端拼好的说明文字 (评分/播出年份之类), bangumi 的 recs 不给
                desc1 = "",
                desc2 = "",
                imageUrl = it.imageUrl,
                uri = null,
            )
        }
        if (related.size >= RELATED_TARGET) return related
        val similar = recommendationRepository.similarTo(
            subject.subjectId,
            subject.nameCn.ifEmpty { subject.name },
            subject.tags,
            exclude = related.mapNotNullTo(HashSet()) { it.subjectId?.toInt() },
            limit = RELATED_TARGET - related.size,
        )
        return related + similar.map {
            SubjectRecommendation(
                subjectId = it.bangumiId.toLong(),
                name = it.name,
                nameCn = it.nameCn,
                desc1 = "",
                desc2 = "",
                imageUrl = it.imageLarge,
                uri = null,
                source = SubjectRecommendation.Source.SIMILAR,
            )
        }
    }
}

/** 相关推荐凑到几部: Bangumi 的 `/recs` 一次最多给 10 部. */
private const val RELATED_TARGET = 10
