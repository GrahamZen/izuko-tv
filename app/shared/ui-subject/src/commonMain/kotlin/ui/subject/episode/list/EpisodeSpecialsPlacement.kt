/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.list

import me.him188.ani.app.data.models.preference.TvEpisodeSpecialsPlacement
import me.him188.ani.datasources.api.EpisodeSort

/**
 * 按 [placement] 摆放特别篇 (非 [EpisodeSort.Normal] 的剧集) 后的选集列表. 详情页选集轮播、播放器选集条与剧照预取都用它,
 * 三处必须是同一份列表: 选集条按下标取相邻的卡, 详情页与播放器里同一个条目的卡片也要逐项一致.
 *
 * 输入保持数据库顺序 ([EpisodeListUiState.allEpisodes]): 正片在前, 特别篇按类型分组排在后面.
 * 没有特别篇或没有正片时原样返回 (同一个实例).
 *
 * @param playingEpisodeId 正在播的集. 它是特别篇时 [TvEpisodeSpecialsPlacement.Hidden] 照常列出特别篇:
 * 当前集与 (同类型的) 下一集都得在选集条上.
 */
fun List<EpisodeListItem>.arrangeSpecials(
    placement: TvEpisodeSpecialsPlacement,
    playingEpisodeId: Int? = null,
): List<EpisodeListItem> {
    val (main, specials) = partition { it.sort is EpisodeSort.Normal }
    if (main.isEmpty() || specials.isEmpty()) return this
    return when (placement) {
        TvEpisodeSpecialsPlacement.Hidden ->
            if (specials.any { it.episodeId == playingEpisodeId }) main + specials else main

        TvEpisodeSpecialsPlacement.AfterMain -> main + specials
        // 稳定排序: 序号相同的正片在前, 特别篇之间保持按类型分组的先后; 没有序号的排最后
        TvEpisodeSpecialsPlacement.ByNumber -> (main + specials).sortedBy { it.sort.number ?: Float.POSITIVE_INFINITY }
    }
}

/**
 * 选集网格里正片之后那一组特别篇: [TvEpisodeSpecialsPlacement.Hidden] 时为空 (条目只有特别篇时照常给, 否则网格里什么都没有).
 * 网格是数字方块快速跳转, 另外两档都把特别篇分组放在正片后面.
 */
fun EpisodeListUiState.gridSpecialEpisodes(placement: TvEpisodeSpecialsPlacement): List<EpisodeListItem> =
    if (placement == TvEpisodeSpecialsPlacement.Hidden && mainEpisodes.isNotEmpty()) emptyList() else otherEpisodes
