/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.api.topic.titles

import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.topic.EpisodeRange

/**
 * 「本季集号(系列内集号)」, 如续作的 `[02(50)]` (第三季第 2 集, 系列第 50 集), 解析为本季集号这一集.
 *
 * 只取本季集号: 选集按 sort 或 ep 任一对上就算, 本季集号总能对上 ep; 两个都放进去会变成多集, 被当成合集.
 * 括号里的数要比本季集号大才算系列内集号.
 */
internal fun parseAbsoluteNumberedEpisode(section: String): EpisodeRange? {
    val result = absoluteNumberedEpisode.matchEntire(section) ?: return null
    val episode = result.groupValues[1]
    val absolute = result.groupValues[2].toInt()
    if (absolute <= episode.toInt()) return null
    return EpisodeRange.single(EpisodeSort(episode))
}

private val absoluteNumberedEpisode = Regex("""(\d{1,4})[(（](\d{1,4})[)）]""")
