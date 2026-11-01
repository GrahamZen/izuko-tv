/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeEpisodeCard
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeEpisodeGrid
import me.him188.ani.app.ui.foundation.tv.tvPrefersTmdbText
import me.him188.ani.app.ui.foundation.tv.tvUseOriginalTitle
import me.him188.ani.app.ui.subject.details.sections.episodeStillImageUrl
import me.him188.ani.app.ui.subject.details.sections.tvEpisodeName
import me.him188.ani.app.ui.subject.episode.list.EpisodeListItem

/** 安卓上是原生网格 (TvNativeEpisodeGrid): 剧照卡同详情页的选集行, 集号格自己画. */
@Composable
internal actual fun TvAllEpisodesGrid(
    episodes: List<EpisodeListItem>,
    numbers: Boolean,
    selectedIndex: Int,
    currentEpisodeId: Int?,
    stills: Map<Int, String>,
    names: Map<Int, String>,
    playProgress: Map<Int, Float>,
    touch: Boolean,
    onFocused: (index: Int) -> Unit,
    onClick: (index: Int) -> Unit,
    onLongPress: (index: Int) -> Unit,
    modifier: Modifier,
) {
    val useOriginalTitle = tvUseOriginalTitle()
    val prefersTmdb = tvPrefersTmdbText()
    val cards = remember(episodes, stills, names, playProgress, currentEpisodeId, useOriginalTitle, prefersTmdb) {
        episodes.map { it.toNativeCard(stills, names, playProgress, currentEpisodeId, prefersTmdb, useOriginalTitle) }
    }
    TvNativeEpisodeGrid(
        cards = cards,
        numbers = numbers,
        selectedIndex = selectedIndex,
        touchTaps = touch,
        onFocused = onFocused,
        onClick = onClick,
        onLongPress = onLongPress,
        modifier = modifier,
    )
}

/** 一集换成原生网格的一格 (同详情页选集行的那张卡: 剧照用卡片档的 URL, 看过的满条). */
private fun EpisodeListItem.toNativeCard(
    stills: Map<Int, String>,
    names: Map<Int, String>,
    playProgress: Map<Int, Float>,
    currentEpisodeId: Int?,
    prefersTmdb: Boolean,
    useOriginalTitle: Boolean,
): TvNativeEpisodeCard {
    val watched = isDoneOrDropped
    return TvNativeEpisodeCard(
        id = episodeId,
        sort = sort.toString(),
        name = tvEpisodeName(names, prefersTmdb, useOriginalTitle),
        stillUrl = stills[episodeId]?.let(::episodeStillImageUrl),
        playing = episodeId == currentEpisodeId,
        watched = watched,
        progress = if (watched) 1f else playProgress[episodeId],
    )
}
