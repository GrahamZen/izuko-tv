/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.coroutines.CancellationException
import me.him188.ani.datasources.api.source.MediaFetchHostWork
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

private val logger = logger("HostWorks")

/**
 * 条目 [subjectId] 接在后面编号的本篇 ([MediaFetchHostWork]): Bangumi 上它的「主线故事」里, 正片集数刚好接到它正片第一集之前的那部.
 *
 * 只看系列关系图 [table] 覆盖到、不在前传续集链上 ([isolated])、正片第一集的 sort 大于 1 的条目 (TV 13 集之后的 OVA 记成第 14 集):
 * 这种条目在 Bangumi 上只用「主线故事」挂着本篇, 系列关系 (前传续集) 里没有它. 满足这些才取「主线故事」([mainStories], 一个请求).
 * 表里没有的条目 (比表新) 不取: 表才有正片第一集的 sort.
 *
 * 本篇的名字取表里的全部名字 (含别名: 站点给本篇起的名字常是别名, 如「XX3」); 表里没有本篇时用关系里的原名与中文名.
 * 取「主线故事」出错时当作没有本篇: 它只是锦上添花, 不能连累系列索引的其余部分.
 */
internal suspend fun findHostWork(
    subjectId: Int,
    isolated: Boolean,
    table: SeriesGraphTable?,
    mainStories: suspend () -> List<SeriesNode>,
): MediaFetchHostWork? {
    if (!isolated || table == null) return null
    val firstSort = table.nodeOf(subjectId)?.firstSort?.takeIf { it > 1 } ?: return null
    val stories = try {
        mainStories()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(e) { "Failed to get the main story of subject $subjectId, searching without it" }
        return null
    }
    return stories.firstNotNullOfOrNull { story ->
        val node = table.nodeOf(story.id) ?: story
        val episodes = node.episodes?.takeIf { it > 0 } ?: return@firstNotNullOfOrNull null
        val storyFirstSort = node.firstSort ?: 1
        if (storyFirstSort + episodes != firstSort) return@firstNotNullOfOrNull null
        val names = table.namesOf(story.id).ifEmpty { listOf(story.name, story.nameCn).filter { it.isNotBlank() }.distinct() }
        if (names.isEmpty()) return@firstNotNullOfOrNull null
        MediaFetchHostWork(names = names, firstSort = storyFirstSort, episodeCount = episodes)
    }
}
