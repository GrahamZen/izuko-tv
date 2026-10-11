/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.datasources.api.Media
import org.openani.mediamp.errorOrNull

/**
 * 选源器选中的 [media] 就是播放器里正常播着的那个: 选源会话重建 (重新搜索、条目信息刷新) 后新的选源器又选中了它,
 * 这时接着播, 不停下来重新解析、重新缓冲 (原来会在画面上顿两三秒).
 *
 * 切集时播放器先停 ([PlayerSession.stopPlayback]), 装着的总是本集的资源. 还在装、装失败或播放出错时照常重新装.
 */
internal fun PlayerSession.isPlayingFine(media: Media): Boolean =
    loadedMedia.value == media &&
            videoLoadingState.value is VideoLoadingState.Succeed &&
            player.state.value.errorOrNull == null
