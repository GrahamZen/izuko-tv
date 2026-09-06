/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.person

import androidx.compose.runtime.Immutable
import me.him188.ani.app.data.models.UserInfo
import me.him188.ani.app.data.models.comment.CommentVoteValue

/**
 * 评论挂在人物 (声优/制作人员) 还是角色上. 两者在 Bangumi 上是独立的 id 空间, 服务端接口也分开 (`/persons`, `/characters`).
 */
@Immutable
sealed class PersonCommentTarget {
    data class Person(val personId: Int) : PersonCommentTarget()
    data class Character(val characterId: Int) : PersonCommentTarget()
}

enum class PersonCommentSource {
    ANI,
    BANGUMI,
}

/**
 * 人物/角色的一条评论 (吐槽箱, 无评分). Ani 与 Bangumi 的评论已由服务端合并, 结构与 [me.him188.ani.app.data.models.episode.EpisodeComment] 一致.
 */
@Immutable
data class PersonComment(
    /** 服务端下发的稳定 id (`ani:<uuid>` / `bangumi:<id>`), 用作列表 key. */
    val stableId: String,
    val source: PersonCommentSource,
    /** 来源侧的评论 id (Ani 评论为裸 UUID), 用于回复/回应/投票/举报. */
    val sourceCommentId: String,
    /** Timestamp, millis */
    val createdAt: Long,
    val content: String,
    val author: UserInfo?,
    val reactions: List<PersonCommentReaction> = emptyList(),
    /** 简要回复, 可能少于 [replyCount]. */
    val replies: List<PersonComment> = emptyList(),
    val canReply: Boolean = false,
    val replyCount: Int = replies.size,
    /** 点赞总数. [PersonCommentSource.BANGUMI] 来源的评论恒为 `0`. */
    val likeCount: Int = 0,
    /** 当前登录用户对这条评论的投票, 未投票或未登录时为 `null`. */
    val selfVote: CommentVoteValue? = null,
    /** 楼中回复针对的同楼另一条回复; 直接回复主楼时为 `null`. 同 [me.him188.ani.app.data.models.episode.EpisodeComment.replyToCommentId]. */
    val replyToCommentId: String? = null,
    /** 楼层号, 同 bangumi 网页: 主楼 `"3"`, 楼中回复 `"3-2"`. */
    val floor: String? = null,
    /** 已被删除 (仍占着楼层, 没有正文). */
    val deleted: Boolean = false,
)

@Immutable
data class PersonCommentReaction(
    val value: String,
    val count: Int,
    val selected: Boolean,
)
