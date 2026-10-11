/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.dandanplay

import me.him188.ani.danmaku.api.provider.DanmakuEpisodeWithSubject
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.datasources.api.EpisodeSort

/**
 * 在 [DanmakuFetchRequest.anidbId] 指的那部作品 (弹弹 play 的作品编号就是 AniDB 条目编号) 的分集 [episodes] 里找当前这一集.
 * 找不到返回 `null`, 调用方照旧按 Bangumi 映射与名字搜.
 *
 * 那部作品可能比 Bangumi 条目大: AniDB 常把 Bangumi 拆成几段的一季合成一部、接着编号 (Re：从零开始的异世界生活 第四季的
 * 丧失篇与夺还篇是 AniDB 19242 的第 1~11 与 12~19 集), 所以不能直接拿条目内序号对:
 * 1. 标题精确匹配 (同 [normalizeEpisodeTitle] 的口径);
 * 2. 有 [DanmakuFetchRequest.seasonNumbering] (拆分季) 时, 那部作品只收这一段 (集号都不超过这一段的集数) 就按条目内序号, 否则按整季序号;
 * 3. 不是拆分季时先按系列内序号 (sort), 再按条目内序号 (ep).
 * 正片的集号不在那部作品里 (还没上传、或者其实不是这部) 时不猜.
 */
internal fun pickAnidbEpisode(
    request: DanmakuFetchRequest,
    episodes: List<DanmakuEpisodeWithSubject>,
): DanmakuEpisodeWithSubject? {
    val expectedTitles = (request.episodeNames + request.episodeName)
        .map { normalizeEpisodeTitle(it) }
        .filterTo(HashSet()) { it.isNotEmpty() }
    val byTitle = episodes.filter { normalizeEpisodeTitle(it.episodeName) in expectedTitles }.distinctBy { it.id }
    byTitle.singleOrNull()?.let { return it }

    val numbers = episodes.mapNotNullTo(HashSet()) { it.epOrSort?.integerOrNull() }
    if (numbers.isEmpty()) return null
    val wanted = wantedNumber(request, numbers) ?: return null
    val candidates = byTitle.ifEmpty { episodes }
    return candidates.firstOrNull { it.epOrSort?.integerOrNull() == wanted }
}

private fun wantedNumber(request: DanmakuFetchRequest, numbers: Set<Int>): Int? {
    val ep = request.episodeEp?.integerOrNull()
    val numbering = request.seasonNumbering
    if (numbering != null) {
        val perPart = numbers.max() <= numbering.partEpisodeCount + 2
        return if (perPart) ep?.takeIf { it in numbers } else numbering.seasonEpisode.takeIf { it in numbers }
    }
    request.episodeSort.integerOrNull()?.takeIf { it in numbers }?.let { return it }
    return ep?.takeIf { it in numbers }
}

private fun EpisodeSort.integerOrNull(): Int? {
    val number = (this as? EpisodeSort.Normal)?.number ?: return null
    if (number % 1f != 0f) return null
    return number.toInt()
}
