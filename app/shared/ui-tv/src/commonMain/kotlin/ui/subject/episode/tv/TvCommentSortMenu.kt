/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.him188.ani.app.data.models.preference.EpisodeCommentSort
import me.him188.ani.app.ui.comment.UIComment
import me.him188.ani.app.ui.foundation.FOCUS_REQ_DELAY_MILLIS
import me.him188.ani.app.ui.foundation.consumeHeldConfirmKey
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.widgets.AniDropdownMenu
import me.him188.ani.app.ui.foundation.widgets.AniDropdownMenuItem
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.video_player_tv_chrome_title
import me.him188.ani.app.ui.lang.video_player_tv_comment_sort_most_reactions
import me.him188.ani.app.ui.lang.video_player_tv_comment_sort_most_replies
import me.him188.ani.app.ui.lang.video_player_tv_comment_sort_time_ascending
import me.him188.ani.app.ui.lang.video_player_tv_comment_sort_time_descending
import org.jetbrains.compose.resources.stringResource

/**
 * 主楼按 [sort] 排好的先后, 楼中回复留在各自楼里不动. [threads] 是接口给的顺序 (楼层顺序);
 * 排序是稳定的, 计数相同的照楼层先后, 时间倒序时同一秒发的也倒过来.
 */
internal fun tvSortCommentThreads(threads: List<UIComment>, sort: EpisodeCommentSort): List<UIComment> = when (sort) {
    EpisodeCommentSort.TIME_ASCENDING -> threads.sortedBy { it.createdAt }
    EpisodeCommentSort.TIME_DESCENDING -> threads.asReversed().sortedByDescending { it.createdAt }
    EpisodeCommentSort.MOST_REACTIONS -> threads.sortedByDescending { thread -> thread.reactions.sumOf { it.count } }
    EpisodeCommentSort.MOST_REPLIES -> threads.sortedByDescending { it.replyCount }
}

/**
 * 评论胶囊长按弹出的菜单: 评论面板的四种排序 (当前那种打勾, 打开时焦点落在它上面), 末尾一项「自定义播放器按钮」——
 * 别的按钮长按直接去那一页, 这一颗的长按给了排序, 那一页的入口就放进菜单.
 *
 * 菜单只有长按一个入口, 恒吞掉那次长按残余的确认键 (见 [consumeHeldConfirmKey]); 开着时控制层不自动收起 (经 [onExpandedChanged] 上报).
 *
 * @param onEditChrome 去「自定义播放器按钮」; null = 没有那一页, 不给末项.
 */
@Composable
internal fun TvCommentSortMenu(
    expanded: Boolean,
    current: EpisodeCommentSort,
    onSelect: (EpisodeCommentSort) -> Unit,
    onEditChrome: (() -> Unit)?,
    onDismissRequest: () -> Unit,
    onExpandedChanged: (expanded: Boolean) -> Unit,
) {
    if (expanded) {
        DisposableEffect(Unit) {
            onExpandedChanged(true)
            onDispose { onExpandedChanged(false) }
        }
    }
    AniDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        // 菜单是独立窗口, 按键到不了播放页的根按键路由 —— 播放暂停键仍该管用
        modifier = Modifier.consumeHeldConfirmKey().tvOverlayWindowKeys(onDismissRequest),
    ) {
        // 打开时焦点直接落在当前排序上: 弹层一出来系统就把焦点给第一个能聚焦的项, 所以落定之前只有当前项能聚焦,
        // 不然会先亮第一项再跳过去. 落定后其余项放开; 万一初始焦点没给过来, 过一会儿自己请求一次, 同时放开
        val currentFocus = remember { FocusRequester() }
        var focusPlaced by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(FOCUS_REQ_DELAY_MILLIS)
            if (!focusPlaced) runCatching { currentFocus.requestFocus() }
            focusPlaced = true
        }
        for (sort in EpisodeCommentSort.entries) {
            val isCurrent = sort == current
            AniDropdownMenuItem(
                text = { Text(sort.label()) },
                onClick = {
                    onDismissRequest()
                    if (!isCurrent) onSelect(sort)
                },
                selected = isCurrent,
                modifier = if (isCurrent) {
                    Modifier.focusRequester(currentFocus).onFocusChanged { if (it.isFocused) focusPlaced = true }
                } else {
                    Modifier.focusProperties { canFocus = focusPlaced }
                },
            )
        }
        if (onEditChrome != null) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            AniDropdownMenuItem(
                text = { Text(stringResource(Lang.video_player_tv_chrome_title)) },
                onClick = {
                    onDismissRequest()
                    onEditChrome()
                },
                modifier = Modifier.focusProperties { canFocus = focusPlaced },
            )
        }
    }
}

@Composable
private fun EpisodeCommentSort.label(): String = stringResource(
    when (this) {
        EpisodeCommentSort.TIME_ASCENDING -> Lang.video_player_tv_comment_sort_time_ascending
        EpisodeCommentSort.TIME_DESCENDING -> Lang.video_player_tv_comment_sort_time_descending
        EpisodeCommentSort.MOST_REACTIONS -> Lang.video_player_tv_comment_sort_most_reactions
        EpisodeCommentSort.MOST_REPLIES -> Lang.video_player_tv_comment_sort_most_replies
    },
)
