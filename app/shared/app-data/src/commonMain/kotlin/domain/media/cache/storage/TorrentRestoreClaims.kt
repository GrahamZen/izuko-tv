/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.storage

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheKey
import me.him188.ani.utils.coroutines.update
import kotlin.concurrent.Volatile

/**
 * [TorrentMediaCacheStorage] 不持存储锁的启动恢复与同一集的新建、删除怎么错开.
 *
 * 启动恢复要等种子认完文件 (没有新的续传数据时整个校验盘上的数据), 不持锁等它, 期间别的集照常新建与删除. 同一集要错开:
 * - [restoring]: 这次启动恢复要恢复的集, 开始时一次记下 (同时只恢复几条, 排着的也算), 每集恢复完、列出来之后去掉.
 *   同一集的新建与删除等它, 否则会给同一集再开一份下载、多一条记录, 或删掉的是即将被恢复出来的那份换下的旧对象;
 * - [creating]: 正在新建的集. 恢复到它时不再恢复一份, 用新建的那份.
 *
 * 恢复出来的要等接着下载 (或保持暂停) 与统计订阅都做完才列出来: 列出来以后同一集的新建直接复用它.
 */
internal class TorrentRestoreClaims {
    private val restoring = MutableStateFlow(persistentSetOf<MediaCacheKey>())
    private val creating = MutableStateFlow(persistentSetOf<MediaCacheKey>())

    /** 不持锁的启动恢复开始时的列表; 其余时候为 null. 恢复期间新建好的不在里面 */
    @Volatile
    var unlockedRefreshBase: List<MediaCache>? = null

    fun beginRestore(keys: Collection<MediaCacheKey>) = restoring.update { addAll(keys) }

    fun restored(key: MediaCacheKey) = restoring.update { remove(key) }

    fun endRestore() {
        restoring.value = persistentSetOf()
    }

    fun isRestoring(key: MediaCacheKey) = key in restoring.value

    /** 记下 [key] 正在新建; 已经有别人在新建时返回 false */
    fun tryCreate(key: MediaCacheKey): Boolean {
        var claimed = false
        creating.update {
            claimed = key !in this
            if (claimed) add(key) else this
        }
        return claimed
    }

    fun created(key: MediaCacheKey) = creating.update { remove(key) }

    fun isCreating(key: MediaCacheKey) = key in creating.value

    fun isBusy(key: MediaCacheKey) = isRestoring(key) || isCreating(key)

    /** 等 [key] 既不在恢复也不在新建 */
    suspend fun awaitFree(key: MediaCacheKey) {
        combine(restoring, creating) { r, c -> key !in r && key !in c }.first { it }
    }
}
