/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.resolver

import me.him188.ani.datasources.api.EpisodeSort

/** 文件名里的 SxxEyy (如 S02E08). */
private val SEASON_EPISODE = Regex("""(?<![A-Za-z0-9])S(\d{1,2})E(\d{1,4})(?!\d)""", RegexOption.IGNORE_CASE)

/**
 * 按文件名里的 SxxEyy 选这一集: 先拿季内集数 [episodeEp] 对 Eyy, 再拿系列集数 [episodeSort].
 * 标题解析器认不出「Show.S02E08.Title.1080p」这种点分隔的命名, 整季包里的文件都解析不出集数.
 * [videos] 已按黑名单排过序 (S00 特典在后), 同号时取前面的. 没有文件写着 SxxEyy、或都对不上时返回 null.
 */
internal fun <T> selectBySeasonEpisode(
    videos: List<T>,
    getPath: T.() -> String,
    episodeSort: EpisodeSort,
    episodeEp: EpisodeSort?,
): T? {
    val numbered = videos.mapNotNull { video ->
        val name = video.getPath().substringAfterLast('/').substringAfterLast('\\')
        SEASON_EPISODE.find(name)?.groupValues?.get(2)?.toIntOrNull()?.let { video to it }
    }
    if (numbered.isEmpty()) return null
    for (target in listOfNotNull(episodeEp, episodeSort)) {
        val number = target.number ?: continue
        numbered.firstOrNull { it.second.toFloat() == number }?.let { return it.first }
    }
    return null
}

/**
 * [path] 里有这一集的 [number]. 纯数字时前后都不能挨着数字: 「1080p」里的 08、10 不算第 8 集、第 10 集;
 * 前面多补的零不算挨着 (「- 001」也是 01, 「mp4」不是 04). 「OVA」这类非数字的照旧只看包含 (「OVA12」算).
 */
internal fun containsEpisodeNumber(path: String, number: String): Boolean {
    if (number.isEmpty() || !number.all { it.isDigit() }) return path.contains(number, ignoreCase = true)
    return Regex("""(?<!\d)0*$number(?!\d)""").containsMatchIn(path)
}
