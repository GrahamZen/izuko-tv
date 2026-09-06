/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import me.him188.ani.app.data.network.SubjectService
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
) {
    val uniqueId: String = Uuid.randomString()
}

fun interface GetSubjectRecommendationUseCase : UseCase {
    suspend operator fun invoke(subjectId: Int): List<SubjectRecommendation>
}

class GetSubjectRecommendationUseCaseImpl(private val service: SubjectService) : GetSubjectRecommendationUseCase {
    override suspend fun invoke(subjectId: Int): List<SubjectRecommendation> {
        return service.getSubjectRecommendations(subjectId, 15).map {
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
    }
}