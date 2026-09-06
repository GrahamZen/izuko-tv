/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.UserInfo
import me.him188.ani.app.data.models.subject.SubjectReview
import me.him188.ani.app.data.models.subject.SubjectReviewSource
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.datasources.api.paging.Paged
import me.him188.ani.datasources.bangumi.next.apis.SubjectBangumiNextApi
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubjectInterestComment
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * 条目吐槽箱 (详情页的"评价"), 直连 `next.bgm.tv/p1`.
 *
 * 投票 (点赞/点踩) 那个方法没了: 那是 Ani 自己的概念, bangumi 的条目吐槽只有表情回应.
 */
interface BangumiCommentService {
    /**
     * @return `null` if [subjectId] is invalid
     */
    suspend fun getSubjectComments(subjectId: Int, offset: Int, limit: Int): Paged<SubjectReview>?
}

class BangumiBangumiCommentServiceImpl(
    private val subjectsApi: ApiInvoker<SubjectBangumiNextApi>,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
) : BangumiCommentService {
    private val logger = logger<BangumiBangumiCommentServiceImpl>()

    override suspend fun getSubjectComments(subjectId: Int, offset: Int, limit: Int): Paged<SubjectReview>? {
        return withContext(ioDispatcher) {
            try {
                val response = subjectsApi {
                    getSubjectComments(subjectId, limit = limit, offset = offset).body()
                }
                val list = response.data.map { it.toSubjectReview() }
                logger.info { "bgm-direct: subjectComments subject=$subjectId offset=$offset -> ${list.size}/${response.total}" }
                Paged(
                    total = response.total,
                    hasMore = offset + list.size < response.total,
                    page = list,
                )
            } catch (e: Exception) {
                throw RepositoryException.wrapOrThrowCancellation(e)
            }
        }
    }
}

private fun BangumiNextSubjectInterestComment.toSubjectReview() = SubjectReview(
    id = id.toLong(),
    reviewId = id.toString(),
    source = SubjectReviewSource.BANGUMI,
    content = comment,
    updatedAt = updatedAt.toLong().seconds.inWholeMilliseconds,
    rating = rate,
    creator = UserInfo(
        id = user.id.toString(),
        nickname = user.nickname,
        username = user.username,
        avatarUrl = user.avatar.large,
    ),
    likeCount = reactions.orEmpty().sumOf { it.users.size },
    selfVote = null,
)
