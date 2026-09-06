/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.download.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.foundation.tvLongPressKey
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_episode_pause_download
import me.him188.ani.app.ui.lang.cache_episode_resume_download
import me.him188.ani.app.ui.lang.cache_management_invalid_cache_info
import me.him188.ani.app.ui.lang.cache_management_more_actions
import me.him188.ani.app.ui.lang.cache_management_play
import me.him188.ani.app.ui.lang.cache_management_streaming_not_supported
import org.jetbrains.compose.resources.stringResource

/**
 * 电视上下载行行尾那颗按钮按确定做的事, `null` = 没有 (下载失败, 或暂停 / 继续还没落地), 按确定弹菜单.
 * 长按弹出的菜单里不再放这一项 (见 [me.him188.ani.app.ui.download.DownloadActionDropdown]).
 */
internal enum class TvDownloadPrimaryAction { Play, Resume, Pause }

internal val DownloadItem.tvPrimaryAction: TvDownloadPrimaryAction?
    get() = when {
        isFinished -> TvDownloadPrimaryAction.Play
        isFailed || isBusy -> null
        isPaused -> TvDownloadPrimaryAction.Resume
        else -> TvDownloadPrimaryAction.Pause
    }

/**
 * 电视上下载行行尾唯一的焦点落点 (一行只有一个落点, 见 [CacheEpisodeRowFocusNote]):
 * 按确定做这一行的主操作 —— 下完的播放, 下载中的暂停, 暂停的继续, 图标跟着主操作变; 按住确定弹出完整菜单 ([onShowMenu]).
 * 下载失败或正在处理 (暂停 / 继续还没落地) 时没有主操作, 显示「更多」, 按确定就弹菜单.
 *
 * 按钮一直可用: 它是这一行唯一能拿焦点的东西, 置灰就拿不到焦点, 整行会被遥控器跳过.
 */
@Composable
internal fun TvDownloadRowAction(
    episode: DownloadItem,
    onPlay: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onShowMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val toaster = LocalToaster.current
    val invalidCacheInfoText = stringResource(Lang.cache_management_invalid_cache_info)
    val streamingNotSupportedText = stringResource(Lang.cache_management_streaming_not_supported)
    val action = episode.tvPrimaryAction
    val primary: (() -> Unit)? = when (action) {
        TvDownloadPrimaryAction.Play -> {
            {
                when (episode.playability) {
                    DownloadItem.Playability.PLAYABLE -> onPlay()
                    DownloadItem.Playability.INVALID_SUBJECT_EPISODE_ID -> toaster.toast(invalidCacheInfoText)
                    DownloadItem.Playability.STREAMING_NOT_SUPPORTED -> toaster.toast(streamingNotSupportedText)
                }
            }
        }

        TvDownloadPrimaryAction.Resume -> onResume
        TvDownloadPrimaryAction.Pause -> onPause
        null -> null
    }
    val onClick = primary ?: onShowMenu
    IconButton(
        onClick = onClick,
        modifier = modifier.tvLongPressKey(onLongPress = onShowMenu, onShortPress = onClick),
        colors = if (primary != null) {
            IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        } else {
            IconButtonDefaults.iconButtonColors()
        },
    ) {
        when (action) {
            null -> Icon(Icons.Rounded.MoreVert, stringResource(Lang.cache_management_more_actions))
            TvDownloadPrimaryAction.Play -> Icon(Icons.Rounded.PlayArrow, stringResource(Lang.cache_management_play))
            TvDownloadPrimaryAction.Resume -> Icon(Icons.Rounded.PlayArrow, stringResource(Lang.cache_episode_resume_download))
            TvDownloadPrimaryAction.Pause -> Icon(Icons.Rounded.Pause, stringResource(Lang.cache_episode_pause_download))
        }
    }
}
