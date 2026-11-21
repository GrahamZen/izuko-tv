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
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeComment
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCommentEdge
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCommentList
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCommentListController
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeEpisodeRow
import me.him188.ani.app.ui.remote.RegisterTvRemotePlayer
import me.him188.ani.app.ui.remote.RemoteCache
import me.him188.ani.app.ui.subject.details.layout.rememberTvPlayerCommentStyle
import me.him188.ani.app.ui.subject.details.sections.FocusEpisodeRowSpec
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.datasources.api.Media
import org.openani.mediamp.MediampPlayer

internal actual val tvCanCacheMedia: Boolean = true

internal actual suspend fun tvCacheMedia(subjectId: Int, episodeId: Int, media: Media): String? =
    RemoteCache.cacheMedia(subjectId, episodeId, media)["message"]?.jsonPrimitive?.content

@Composable
internal actual fun RegisterPlayerOnRemoteConsole(vm: EpisodeViewModel, page: EpisodePageState) {
    RegisterTvRemotePlayer(vm, page)
}

@Composable
internal actual fun RegisterPlayerScreenshotOnRemoteConsole(player: MediampPlayer) {
    RegisterTvPlayerScreenshot(player)
}

@Composable
internal actual fun TvPlayerEpisodeStripRow(spec: FocusEpisodeRowSpec) {
    TvNativeEpisodeRow(spec)
}

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
    TvNativeCommentList(
        items = items,
        style = rememberTvPlayerCommentStyle(),
        initialFocusIndex = 0,
        touchTaps = touchTaps,
        onClick = onClick,
        onBind = onBind,
        modifier = modifier,
        controller = controller,
        onEdge = onEdge,
        onFocusedPosition = onFocusedPosition,
    )
}
