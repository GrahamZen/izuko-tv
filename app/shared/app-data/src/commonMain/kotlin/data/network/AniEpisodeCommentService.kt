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
import me.him188.ani.app.data.models.episode.EpisodeComment
import me.him188.ani.app.data.models.episode.EpisodeCommentReaction
import me.him188.ani.app.data.models.episode.EpisodeCommentSource
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.datasources.bangumi.next.apis.EpisodeBangumiNextApi
import me.him188.ani.datasources.bangumi.next.apis.MiscBangumiNextApi
import me.him188.ani.datasources.bangumi.next.models.BangumiNextComment
import me.him188.ani.datasources.bangumi.next.models.BangumiNextCommentBase
import me.him188.ani.datasources.bangumi.next.models.BangumiNextLikeEpisodeCommentRequest
import me.him188.ani.datasources.bangumi.next.models.BangumiNextReaction
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.ktor.ApiInvoker
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * 剧集评论 (bangumi 的"章节吐槽箱"), 直连 `next.bgm.tv/p1`.
 *
 * 与经 Ani 服务端合并的那版相比:
 * - **回复关系是真的**. p1 的每条回复自带 `relatedID` 指出它在回复谁, 不再需要从正文开头那条
 *   `[quote][b]昵称[/b] 说:` 反推 (原来那套认不出被删掉引用的情况, 也认不出同名的人).
 * - Animeko 自己那部分评论没有了, 只剩 bangumi 的.
 * - 点赞/点踩 (`vote`) 这个概念 bangumi 没有, 它只有表情回应.
 */
open class AniEpisodeCommentService(
    private val episodeApi: ApiInvoker<EpisodeBangumiNextApi>,
    private val meApi: ApiInvoker<MiscBangumiNextApi>,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
) {
    /**
     * 自己的 bangumi 用户 id, 用来判断某个表情回应里有没有自己. 未登录时为 `null`.
     *
     * 只取一次: 同一个进程里不会变 (换账号会重建 Koin 图).
     */
    private var selfUserId: Int? = null
    private var selfUserIdLoaded = false

    /**
     * 取剧集评论. bangumi 的吐槽箱**不分页**, 一次给全部, 所以 [after] 只用于判断是不是首屏.
     */
    open suspend fun listEpisodeComments(
        episodeId: Long,
        after: String? = null,
        limit: Int = 30,
    ): EpisodeCommentPage = withContext(ioDispatcher) {
        if (after != null) return@withContext EpisodeCommentPage(emptyList())
        try {
            val comments = episodeApi.invoke { getEpisodeComments(episodeId.toInt()).body() }
            EpisodeCommentPage(comments.map { it.toEpisodeComment(episodeId, currentUserIdOrNull()) })
        } catch (e: Exception) {
            throw RepositoryException.wrapOrThrowCancellation(e)
        }
    }

    open suspend fun addEpisodeCommentReaction(
        episodeId: Long,
        commentId: String,
        value: String,
    ) = withContext(ioDispatcher) {
        val reactionValue = value.toReactionValueOrNull() ?: return@withContext
        try {
            episodeApi.invoke {
                likeEpisodeComment(
                    commentID = commentId.toInt(),
                    bangumiNextLikeEpisodeCommentRequest = BangumiNextLikeEpisodeCommentRequest(reactionValue),
                ).body()
            }
        } catch (e: Exception) {
            throw RepositoryException.wrapOrThrowCancellation(e)
        }
    }

    open suspend fun removeEpisodeCommentReaction(
        episodeId: Long,
        commentId: String,
        value: String,
    ) = withContext(ioDispatcher) {
        try {
            episodeApi.invoke { unlikeEpisodeComment(commentID = commentId.toInt()).body() }
        } catch (e: Exception) {
            throw RepositoryException.wrapOrThrowCancellation(e)
        }
    }

    private suspend fun currentUserIdOrNull(): Int? {
        if (selfUserIdLoaded) return selfUserId
        selfUserId = try {
            meApi.invoke { getCurrentUser().body().id }
        } catch (e: Exception) {
            null // 未登录, 或者取不到: 表情回应就都显示成"没选中"
        }
        selfUserIdLoaded = true
        return selfUserId
    }
}

/** bangumi 的吐槽箱一次给全部, 保留这个包装只是为了调用方仍能表达"还有没有下一页". */
class EpisodeCommentPage(
    val items: List<EpisodeComment>,
    val nextCursor: String? = null,
)

internal fun BangumiNextComment.toEpisodeComment(episodeId: Long, selfUserId: Int?): EpisodeComment {
    return EpisodeComment(
        stableId = id.toString(),
        source = EpisodeCommentSource.BANGUMI,
        sourceCommentId = id.toString(),
        commentId = id.toString(),
        episodeId = episodeId,
        createdAt = createdAt.toLong().seconds.inWholeMilliseconds,
        content = content,
        author = user?.toUserInfo(),
        reactions = reactions?.map { it.toEpisodeCommentReaction(selfUserId) }.orEmpty(),
        replies = replies.map { it.toEpisodeComment(episodeId, mainId = id, selfUserId = selfUserId) },
        // 发表评论要过 Cloudflare Turnstile 验证码, 电视上没法做, 见 PostCommentUseCase
        canReply = false,
        replyCount = replies.size,
        likeCount = 0,
        selfVote = null,
    )
}

private fun BangumiNextCommentBase.toEpisodeComment(
    episodeId: Long,
    mainId: Int,
    selfUserId: Int?,
): EpisodeComment {
    return EpisodeComment(
        stableId = id.toString(),
        source = EpisodeCommentSource.BANGUMI,
        sourceCommentId = id.toString(),
        commentId = id.toString(),
        episodeId = episodeId,
        createdAt = createdAt.toLong().seconds.inWholeMilliseconds,
        content = content,
        author = user?.toUserInfo(),
        reactions = reactions?.map { it.toEpisodeCommentReaction(selfUserId) }.orEmpty(),
        canReply = false,
        // relatedID 指向主楼 (或自身/缺失) 时只是普通的楼内回复, 不算指向某条回复
        replyToCommentId = relatedID
            .takeIf { it != 0 && it != mainID && it != mainId && it != id }
            ?.toString(),
    )
}

private fun me.him188.ani.datasources.bangumi.next.models.BangumiNextSlimUser.toUserInfo(): UserInfo = UserInfo(
    id = id.toString(),
    username = username,
    nickname = nickname,
    avatarUrl = avatar.large,
)

private fun BangumiNextReaction.toEpisodeCommentReaction(selfUserId: Int?): EpisodeCommentReaction =
    EpisodeCommentReaction(
        // 传输用的值是 "bgm" + bangumi 的回应编号 (不是表情代码, 两者差 16), 见 BangumiStickers
        value = "bgm$value",
        count = users.size,
        selected = selfUserId != null && users.any { it.id == selfUserId },
    )

private fun String.toReactionValueOrNull(): Int? = removePrefix("bgm").toIntOrNull()
