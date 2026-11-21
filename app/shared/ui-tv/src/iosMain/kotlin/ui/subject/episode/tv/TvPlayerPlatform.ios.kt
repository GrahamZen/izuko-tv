/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeComment
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCommentEdge
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCommentListController
import me.him188.ani.app.ui.subject.details.sections.FocusEpisodeRowSpec
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.danmaku.ui.DanmakuLayerSnapshots
import me.him188.ani.datasources.api.Media
import org.openani.mediamp.MediampPlayer

// iOS 没有 Web 控制台, 也不从选源里加下载
internal actual val tvCanCacheMedia: Boolean = false

internal actual suspend fun tvCacheMedia(subjectId: Int, episodeId: Int, media: Media): String? = null

@Composable
internal actual fun RegisterPlayerOnRemoteConsole(vm: EpisodeViewModel, page: EpisodePageState) {
}

@Composable
internal actual fun RegisterPlayerScreenshotOnRemoteConsole(player: MediampPlayer) {
}

// 这两样提示只针对安卓的 ExoPlayer
@Composable
internal actual fun TvThrottledBufferHint(player: MediampPlayer, buffering: Boolean, modifier: Modifier) {
}

@Composable
internal actual fun TvDiskCacheHint(player: MediampPlayer) {
}

// 遥控器选集条 iPad 上不出现
@Composable
internal actual fun TvPlayerEpisodeStripRow(spec: FocusEpisodeRowSpec) {
}

@Composable
internal actual fun rememberTvScreenshotDanmakuLayer(snapshots: DanmakuLayerSnapshots): Modifier = Modifier

// 遥控器评论面板 iPad 上不出现
@Composable
internal actual fun TvPlayerCommentsList(
    items: List<TvNativeComment?>,
    touchTaps: Boolean,
    onClick: (index: Int) -> Unit,
    onBind: (index: Int) -> Unit,
    modifier: Modifier,
    controller: TvNativeCommentListController,
    onEdge: (edge: TvNativeCommentEdge, newPress: Boolean) -> Boolean,
    onFocusedPosition: (Int) -> Unit,
) {
}
