/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector.filter

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import me.him188.ani.app.data.models.subject.SplitSeason
import me.him188.ani.datasources.api.EpisodeSort

/**
 * 留着上一次建的 [SplitSeasonPageMatcher], 参数相同就复用: 每批搜索结果到达都要按它给几百个页名分类,
 * 分类 ([SplitSeasonPageMatcher.classify]) 记在匹配器里, 每次新建就得全部重算 (搜一次要好几秒 CPU).
 * 匹配器的分类表加了锁, 可以在几条候选流之间共用.
 */
internal object SplitSeasonPageMatcherCache {
    private data class Key(val season: SplitSeason, val ownNames: List<String>, val sort: EpisodeSort, val ep: EpisodeSort?)

    private val lock = SynchronizedObject()
    private var key: Key? = null
    private var matcher: SplitSeasonPageMatcher? = null

    fun get(season: SplitSeason, ownNames: List<String>, sort: EpisodeSort, ep: EpisodeSort?): SplitSeasonPageMatcher? {
        val newKey = Key(season, ownNames, sort, ep)
        synchronized(lock) {
            if (key == newKey) return matcher
        }
        val created = SplitSeasonPageMatcher.create(season, ownNames, sort, ep)
        synchronized(lock) {
            key = newKey
            matcher = created
        }
        return created
    }
}
