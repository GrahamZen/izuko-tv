/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItemsWithLifecycle
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.tools.LocalTimeFormatter
import me.him188.ani.app.ui.comment.CommentColumn
import me.him188.ani.app.ui.comment.CommentItem
import me.him188.ani.app.ui.comment.CommentMenuHandlers
import me.him188.ani.app.ui.comment.CommentOverlayCleanupEffect
import me.him188.ani.app.ui.comment.UIComment
import me.him188.ani.app.ui.comment.UICommentSource
import me.him188.ani.app.ui.foundation.LocalImageViewerHandler
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.episode_comments
import me.him188.ani.app.ui.lang.tablet_player_replies
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.ui.subject.episode.tv.TvCommentThreadContent
import me.him188.ani.app.ui.subject.episode.tv.toTvQuotedComment
import me.him188.ani.app.ui.subject.episode.tv.toTvThreadReplies
import me.him188.ani.app.ui.subject.episode.tv.tvThemeCommentThreadColors
import org.jetbrains.compose.resources.stringResource

/**
 * 本集评论 (同 YouTube 的评论面板): 栏顶「评论 N」+ ✕, 下面是上游的评论列表 (同手机端, 赞 / 表情回应 / 举报照旧).
 * 点「N 条回复」往里推一层看一整楼 ([onOpenThread]). 不放「写评论」: 直连 Bangumi 时发不出去.
 */
@Composable
internal fun TabletCommentsPanel(
    vm: EpisodeViewModel,
    page: EpisodePageState,
    onClose: () -> Unit,
    onOpenThread: (UIComment) -> Unit,
    /** 列表滚到哪: 由外层记住, 从一整楼退回来时还在原处. */
    gridState: LazyGridState,
) {
    val state = vm.episodeCommentState
    val reportState = vm.commentReportState
    val imageViewer = LocalImageViewerHandler.current
    val uriHandler = LocalUriHandler.current
    val toaster = LocalToaster.current
    LaunchedEffect(state) {
        state.actionSubmitFailures.collect { toaster.showLoadError(LoadError.fromException(it)) }
    }
    LaunchedEffect(state) {
        state.commentLoadFailures.collect { toaster.showLoadError(LoadError.fromException(it)) }
    }
    val episodeId = page.episodePresentation.episodeId
    Column(Modifier.fillMaxSize()) {
        TabletPanelHeader(stringResource(Lang.episode_comments), onClose, note = state.count?.toString())
        val items = state.list.collectAsLazyPagingItemsWithLifecycle()
        CommentOverlayCleanupEffect(state, items)
        CommentColumn(
            items,
            state = gridState,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { _, comment ->
            val withOverlay = state.withOverlay(comment)
            CommentItem(
                comment = withOverlay,
                onClickUrl = { uriHandler.openUri(it) },
                onClickImage = { imageViewer.viewImage(it) },
                onExpandReplies = onOpenThread,
                onToggleVote = { c, vote -> state.toggleVote(c, vote) },
                onToggleReaction = if (withOverlay.source == UICommentSource.ANI) {
                    { c, value -> state.submitReaction(c, value) }
                } else {
                    null
                },
                menu = CommentMenuHandlers(
                    onOpenOriginal = if (withOverlay.source == UICommentSource.BANGUMI) {
                        { uriHandler.openUri("https://bgm.tv/ep/$episodeId") }
                    } else {
                        null
                    },
                    onReport = if (withOverlay.source == UICommentSource.ANI && reportState != null) {
                        { reportState.show(it) }
                    } else {
                        null
                    },
                ),
            )
        }
    }
}

/**
 * 一整楼 (同 YouTube 的回复页): 栏顶 ← (回到评论列表)、「回复」、✕ (收起整个评论面板); 内容同电视版播放器里那一整楼 (主楼 + 各条回复), 配色跟主题.
 */
@Composable
internal fun TabletThreadPanel(
    comment: UIComment,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val timeFormatter = LocalTimeFormatter.current
    val root = remember(comment, timeFormatter) { comment.toTvQuotedComment(timeFormatter) }
    val replies = remember(comment, timeFormatter) { comment.toTvThreadReplies(timeFormatter) }
    Column(Modifier.fillMaxSize()) {
        TabletPanelHeader(stringResource(Lang.tablet_player_replies), onClose, onBack = onBack)
        TvCommentThreadContent(
            root = root,
            replies = replies,
            focusReply = -1,
            openKey = comment,
            imageZoom = null,
            colors = tvThemeCommentThreadColors(),
            onNavigate = null,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
            topPadding = 12.dp,
            requestFocusOnOpen = false,
        )
    }
}
