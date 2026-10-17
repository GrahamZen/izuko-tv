/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.sections

import androidx.compose.runtime.Composable
import me.him188.ani.app.ui.foundation.tv.tvPrefersTmdbText
import me.him188.ani.app.ui.foundation.tv.tvUseOriginalTitle
import me.him188.ani.app.ui.subject.episode.list.EpisodeListItem

/**
 * 电视上这一集显示的集名: 非中文界面先用 TMDB 的 ([tmdbNames]: episodeId -> 集名, 只有非中文界面有, 见 TmdbEpisodeMedia.name),
 * 没有时与中文界面一样按「显示原名」取 Bangumi 的.
 */
@Composable
fun EpisodeListItem.tvEpisodeName(tmdbNames: Map<Int, String>): String =
    tvEpisodeName(tmdbNames, tvPrefersTmdbText(), tvUseOriginalTitle())

/** 同上, 给在组合之外 (如 remember 里建卡片) 用: [prefersTmdb] 与 [useOriginalTitle] 先在组合里取好. */
fun EpisodeListItem.tvEpisodeName(tmdbNames: Map<Int, String>, prefersTmdb: Boolean, useOriginalTitle: Boolean): String =
    tmdbNames[episodeId]?.takeIf { prefersTmdb } ?: preferredDisplayName(useOriginalTitle)
