/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.persistent.JsonFileCache
import me.him188.ani.app.data.recommendation.RecommendationCatalog
import me.him188.ani.app.data.repository.subject.SubjectNsfw
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import kotlin.coroutines.CoroutineContext

/**
 * 播放器 / 详情页「相关推荐」(bangumi 的 `/p1/subjects/{id}/recs`, 看过这部的人也看过) 的本地来源.
 *
 * bangumi 这份数据停在 2023 年上半年开播的作品, 之后的作品一律返回空, 已有的也不再变 ⇒ 问过一次就一直有效:
 * 1. 推荐目录 ([RecommendationCatalog]) 里存着这一部、推荐到的每一部也都在目录里: 整份从目录取, 不问 bangumi;
 * 2. 问过的存在 [file] 里 (空的也存: 那就是「没有」), 下次直接用;
 * 3. 都没有才问一次.
 *
 * 不缓存的话, 每打开一次播放器或详情页就问一遍 (2026-10-17 一台电视四天里同一部问了 40 多次, 每次都是空).
 */
class SubjectRecommendationsCache(
    private val catalog: suspend () -> RecommendationCatalog?,
    file: SystemPath?,
    ioDispatcher: CoroutineContext = Dispatchers.IO_,
) {
    private val disk = file?.let { JsonFileCache(it, Saved.serializer(), ioDispatcher) }
    private val lock = Mutex()
    private var loaded: LinkedHashMap<Int, List<Item>>? = null

    /** [subjectId] 的「相关推荐」; 本地都没有时用 [fetch] 问一次并存下. [fetch] 抛的异常照样抛出去 (不存). */
    suspend fun get(subjectId: Int, fetch: suspend () -> List<SubjectRecommendationItem>): List<SubjectRecommendationItem> {
        catalog()?.recommendationItemsOf(subjectId)?.let { return it }
        lock.withLock { entries()[subjectId] }?.let { items ->
            return items.map { it.toItem() }
        }
        val fetched = fetch()
        lock.withLock {
            val all = entries()
            all.remove(subjectId)
            all[subjectId] = fetched.map { Item(it.subjectId, it.name, it.nameCn, it.imageUrl, it.subjectId in SubjectNsfw.ids.value) }
            while (all.size > MAX_SUBJECTS) all.remove(all.keys.first())
            disk?.write(Saved(all))
        }
        return fetched
    }

    private suspend fun entries(): LinkedHashMap<Int, List<Item>> =
        loaded ?: LinkedHashMap(disk?.read()?.subjects.orEmpty()).also { loaded = it }

    @Serializable
    private class Saved(val subjects: Map<Int, List<Item>>)

    @Serializable
    private class Item(val id: Int, val name: String, val nameCn: String?, val image: String, val nsfw: Boolean = false) {
        fun toItem(): SubjectRecommendationItem {
            SubjectNsfw.record(id, nsfw)
            return SubjectRecommendationItem(subjectId = id, name = name, nameCn = nameCn, imageUrl = image)
        }
    }

    private companion object {
        /** 最多存多少部 (按最近问的留). 一部十来条, 一千部约一两百 KB. */
        const val MAX_SUBJECTS = 1000
    }
}
