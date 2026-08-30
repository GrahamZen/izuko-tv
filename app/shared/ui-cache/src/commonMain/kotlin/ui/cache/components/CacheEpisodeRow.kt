/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.cache.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.him188.ani.app.tools.getOrZero
import me.him188.ani.app.ui.cache.CacheActionDropdown
import me.him188.ani.app.ui.cache.DeleteActionDialog
import me.him188.ani.app.ui.cache.rememberPlayingCacheWarning
import me.him188.ani.app.ui.foundation.animation.AniAnimatedVisibility
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_episode_download_failed
import me.him188.ani.app.ui.lang.cache_episode_pause_download
import me.him188.ani.app.ui.lang.cache_episode_resume_download
import me.him188.ani.app.ui.lang.cache_episode_status_paused
import me.him188.ani.app.ui.lang.cache_episode_watched_progress
import me.him188.ani.app.ui.lang.cache_filter_status_finished
import me.him188.ani.app.ui.lang.cache_management_episode_label
import me.him188.ani.app.ui.lang.cache_management_invalid_cache_info
import me.him188.ani.app.ui.lang.cache_management_more_actions
import me.him188.ani.app.ui.lang.cache_management_play
import me.him188.ani.app.ui.lang.cache_management_streaming_not_supported
import me.him188.ani.app.ui.cache.subject.CacheRowRefocus
import me.him188.ani.app.ui.cache.subject.LocalCachePopupOpen
import me.him188.ani.app.ui.cache.subject.REFOCUS_FRAMES
import me.him188.ani.app.ui.cache.subject.LocalCacheRowRefocus
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.mediafetch.MediaSourceInfoProvider
import org.jetbrains.compose.resources.stringResource

/*
 * [CacheEpisodeRowFocusNote]
 *
 * **本行整行绝不能同时既是焦点目标、又装着可聚焦的子节点** —— 遥控器会把整行连人带按钮一起跳过.
 *
 * Compose 的二维焦点搜索 (TwoDimensionalFocusSearch.searchChildren) 只把"最近的一层 FocusTarget"
 * 收进候选集: 整行的 clickable Surface 一旦成为 FocusTarget, 里面的播放/更多两颗 IconButton 就不再
 * 是候选. 而候选排序 isBetterCandidate 的权重是 `13 × 主轴距离² + 次轴距离²`, 次轴取矩形中心之差 ——
 * 整行横跨全屏, 中心在屏幕正中, 对着行尾那一列右对齐的按钮算出来的次轴距离极大:
 *
 *   Shield 1920 宽, 从上一行的下载按钮 (x 1792..1888, 中心 1840) 按下键:
 *     已缓存行整行 (x 0..1920, 中心 960):  13×16²  + 880² = 777,728
 *     再下一集的下载按钮 (中心 1840):      13×176² + 0²   = 402,688   ← 赢
 *
 *   于是焦点从上一行直接跳到下一行, 中间这一整行永远拿不到焦点 (2026-08-23 真机实测, 上下皆然).
 *   症状: "按下载之后焦点就没了", "下载中的条目聚焦不了".
 *
 * 注意宽度会改变结论: 桌面测试窗口只有 500dp 时次轴惩罚不够大, 整行反而会赢下比较、拿到焦点 ——
 * 同一个 bug 在窄屏上表现成"焦点落在整行", 在电视上表现成"整行被跳过". 复现必须按真机宽高比来,
 * 见 SubjectCachePageTest 里那条焦点用例.
 */

/**
 * 新设计的剧集缓存行, 用于条目缓存页与全局缓存管理页的详情栏.
 *
 * - 已完成: 标题 + "1.2 GB · AnimeGarden · 已完成 · 已观看 50.0%" (有观看进度时) + 播放/更多按钮
 * - 下载中: 标题 + "890 MB / 1.3 GB · AnimeGarden" + 暂停/更多按钮 + 进度条 + 速度/百分比
 * - 已暂停: 同上, 主操作为继续, 进度条右侧显示 "已暂停"
 * - 多选模式: 行首复选框, 行尾操作隐藏
 *
 * 设计稿: [Figma](https://www.figma.com/design/LET1n9mmDa6npDTIlUuJjU/Animeko?node-id=1657-414)
 *
 * @param showSubjectTitle 是否将条目名作为标题展示 (用于跨条目的场景). 否则展示 "第x话 · 名称".
 */
@Composable
fun CacheEpisodeRow(
    episode: CacheEpisodeState,
    mediaSourceInfoProvider: MediaSourceInfoProvider?,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onEnterSelection: () -> Unit,
    onPlay: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
    onViewDetail: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showSubjectTitle: Boolean = false,
    // 设计稿: 手机上行通栏无圆角 (选中高亮铺满全宽), 宽屏详情栏内为圆角.
    shape: Shape = MaterialTheme.shapes.medium,
) {
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var showConfirmDelete by rememberSaveable { mutableStateOf(false) }

    // TV (fork): 整行不能是焦点目标, 见 [CacheEpisodeRowFocusNote]. 遥控器靠行尾那颗"更多"
    // IconButton 上下走; 多选模式下靠行首的 Checkbox.
    val focusDriven = LocalAniUiBehavior.current.focusDrivenNavigation

    // TV (fork): 上一集刚按下下载, 缓存建出来之后那一行 (EpisodeNotCachedRow) 被整个换成本行,
    // 原来持有焦点的下载按钮连同它自己的夺回逻辑一起没了 —— 由本行接住. 见 [LocalCacheRowRefocus].
    val rowRefocus = LocalCacheRowRefocus.current
    val popupOpen = LocalCachePopupOpen.current
    val moreFocusRequester = remember { FocusRequester() }
    var moreFocused by remember { mutableStateOf(false) }
    // 只接"按下载"那个方向的棒子; 本行自己删除时交出去的那根 (toCached=false) 是给未缓存行的,
    // 不能自己接回来 —— 交棒那一刻本行还在且正持有焦点, 接回来等于棒子当场作废.
    val myBaton = CacheRowRefocus(episode.episodeId, toCached = true)
    if (focusDriven && rowRefocus != null) {
        LaunchedEffect(rowRefocus.value == myBaton, popupOpen, selectionMode) {
            if (rowRefocus.value != myBaton || popupOpen || selectionMode) return@LaunchedEffect
            // 整个窗口内只要没拿到就一直补 —— 新行出现前后, 刚冒出来的"全部暂停"之类会被默认
            // 焦点分配抢走, 只试一帧会输给它.
            repeat(REFOCUS_FRAMES) {
                withFrameNanos { } // 等新行真的布局出来, 不靠计时
                if (!moreFocused) runCatching { moreFocusRequester.requestFocus() }
            }
            if (rowRefocus.value == myBaton) rowRefocus.value = null
        }
    }

    if (showConfirmDelete) {
        DeleteActionDialog(
            onDismiss = { showConfirmDelete = false },
            // 正在播的就是这一条时多提示一句 (fork; 见 rememberPlayingCacheWarning)
            warning = rememberPlayingCacheWarning(listOf(episode)),
            onConfirm = {
                // 删完本行会变回"未缓存"行, 把焦点交接过去 (方向与按下载时相反的同一件事)
                if (focusDriven) {
                    rowRefocus?.value = CacheRowRefocus(episode.episodeId, toCached = false)
                }
                onDelete()
                showConfirmDelete = false
            },
        )
    }

    val containerColor by animateColorAsState(
        if (selectionMode && selected) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
    )
    Surface(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (focusDriven) {
                    Modifier
                } else {
                    Modifier.combinedClickable(
                        onClick = {
                            if (selectionMode) {
                                onToggleSelected()
                            } else {
                                showMenu = true
                            }
                        },
                        onLongClick = onEnterSelection,
                    )
                },
            ),
        shape = shape,
        color = containerColor,
    ) {
        Column(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 设计稿: 多选模式下复选框在行首, 行尾单项操作隐藏.
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelected() },
                    )
                }

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val title = if (showSubjectTitle) {
                        episode.subjectName
                    } else {
                        stringResource(Lang.cache_management_episode_label, episode.sort, episode.displayName)
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        cacheEpisodeMetaText(episode, mediaSourceInfoProvider),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (!selectionMode) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // TV (fork): 行尾只留"更多"一颗, 让每一行**只有一个焦点落点**, 且它跟上下
                        // 各行下载按钮在同一列上 (中心 x 差 8px), 遥控器上下就是一条干净的单列.
                        // 播放/暂停并没有丢, 都在下面那个菜单里 —— 多按一次确认键换来的是能按得到.
                        if (!focusDriven) {
                            CacheEpisodePrimaryAction(episode, onPlay = onPlay, onResume = onResume, onPause = onPause)
                        }

                        Box {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier
                                    .focusRequester(moreFocusRequester)
                                    .onFocusChanged { moreFocused = it.isFocused },
                            ) {
                                Icon(Icons.Rounded.MoreVert, stringResource(Lang.cache_management_more_actions))
                            }
                            CacheActionDropdown(
                                show = showMenu,
                                onDismiss = { showMenu = false },
                                episode = episode,
                                onPlay = {
                                    onPlay()
                                    showMenu = false
                                },
                                onResume = {
                                    onResume()
                                    showMenu = false
                                },
                                onPause = {
                                    onPause()
                                    showMenu = false
                                },
                                onViewDetail = onViewDetail?.let {
                                    {
                                        it()
                                        showMenu = false
                                    }
                                },
                                onDelete = { showConfirmDelete = true },
                            )
                        }
                    }
                }
            }

            AniAnimatedVisibility(!episode.isFinished) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val progress by animateFloatAsState(episode.progress.getOrZero())
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.weight(1f),
                        strokeCap = StrokeCap.Round,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val statusText = when {
                            episode.isFailed -> stringResource(Lang.cache_episode_download_failed)
                            episode.isPaused -> stringResource(Lang.cache_episode_status_paused)
                            else -> episode.speedText
                        }
                        statusText?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (episode.isFailed) MaterialTheme.colorScheme.error else Color.Unspecified,
                            )
                        }
                        episode.progressText?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        }
    }
}

/**
 * 行尾的主操作按钮: 已完成 → 播放, 下载中 → 暂停, 已暂停 → 继续. 失败时不显示.
 */
@Composable
private fun CacheEpisodePrimaryAction(
    episode: CacheEpisodeState,
    onPlay: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
) {
    val toaster = LocalToaster.current
    val invalidCacheInfoText = stringResource(Lang.cache_management_invalid_cache_info)
    val streamingNotSupportedText = stringResource(Lang.cache_management_streaming_not_supported)
    val primaryIconColors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
    when {
        episode.isFinished -> {
            IconButton(
                onClick = {
                    when (episode.playability) {
                        CacheEpisodeState.Playability.PLAYABLE -> onPlay()
                        CacheEpisodeState.Playability.INVALID_SUBJECT_EPISODE_ID ->
                            toaster.toast(invalidCacheInfoText)

                        CacheEpisodeState.Playability.STREAMING_NOT_SUPPORTED ->
                            toaster.toast(streamingNotSupportedText)
                    }
                },
                colors = primaryIconColors,
            ) {
                Icon(Icons.Rounded.PlayArrow, stringResource(Lang.cache_management_play))
            }
        }

        episode.isPaused -> {
            IconButton(onClick = onResume, colors = primaryIconColors) {
                Icon(Icons.Rounded.PlayArrow, stringResource(Lang.cache_episode_resume_download))
            }
        }

        !episode.isFailed -> {
            IconButton(onClick = onPause, colors = primaryIconColors) {
                Icon(Icons.Rounded.Pause, stringResource(Lang.cache_episode_pause_download))
            }
        }
    }
}

/**
 * "1.2 GB · AnimeGarden · 已完成 · 已观看 50.0%" 形式的行副标题, 观看进度仅在已完成且有播放历史时展示.
 */
@Composable
private fun cacheEpisodeMetaText(
    episode: CacheEpisodeState,
    mediaSourceInfoProvider: MediaSourceInfoProvider?,
): String {
    val sourceName = episode.mediaSourceId?.let { id ->
        mediaSourceInfoProvider?.rememberMediaSourceInfo(id)?.value?.displayName
    }
    val statusText = when {
        episode.isFinished -> stringResource(Lang.cache_filter_status_finished)
        else -> null
    }
    val watchedText = if (episode.isFinished) {
        episode.playbackProgressText?.let { stringResource(Lang.cache_episode_watched_progress, it) }
    } else {
        null
    }
    return listOfNotNull(episode.detailedSizeText, sourceName, statusText, watchedText)
        .joinToString(" · ")
}
