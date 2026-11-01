/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.episode

import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.data.models.comment.CommentVoteValue
import me.him188.ani.app.data.models.episode.EpisodeComment
import me.him188.ani.app.data.network.AniEpisodeCommentService
import me.him188.ani.app.data.network.toEpisodeComment
import me.him188.ani.app.data.repository.Repository
import me.him188.ani.app.data.repository.runWrappingExceptionAsLoadResult

class EpisodeCommentRepository(
    private val aniCommentService: AniEpisodeCommentService,
) : Repository() {
    fun subjectEpisodeCommentsPager(
        episodeId: Long,
    ): Flow<PagingData<EpisodeComment>> {
        return Pager(defaultPagingConfig) {
            EpisodeCommentPagingSource(
                episodeId = episodeId,
                aniCommentService = aniCommentService,
            )
        }.flow
    }

    suspend fun submitReaction(
        episodeId: Long,
        commentId: String,
        value: String,
        selected: Boolean,
    ) {
        if (selected) {
            aniCommentService.addEpisodeCommentReaction(episodeId, commentId, value)
        } else {
            aniCommentService.removeEpisodeCommentReaction(episodeId, commentId, value)
        }
    }

}

/**
 * 剧集评论翻页. bangumi 的吐槽箱一次给全部, 所以只有首屏一页.
 */
internal class EpisodeCommentPagingSource(
    private val episodeId: Long,
    private val aniCommentService: AniEpisodeCommentService,
) : PagingSource<String, EpisodeComment>() {
    override fun getRefreshKey(state: PagingState<String, EpisodeComment>): String? = null

    override suspend fun load(params: LoadParams<String>): LoadResult<String, EpisodeComment> {
        return runWrappingExceptionAsLoadResult {
            val response = aniCommentService.listEpisodeComments(
                episodeId = episodeId,
                after = params.key,
                limit = params.loadSize.coerceAtMost(MAX_LIMIT),
            )
            LoadResult.Page(
                // 直连之后每条回复自带 relatedID, 不用再补回复关系
                data = response.items,
                prevKey = null,
                nextKey = response.nextCursor,
            )
        }
    }

    private companion object {
        /** 服务端 `limit` 的上限, 超过会 400 */
        const val MAX_LIMIT = 100
    }
}
