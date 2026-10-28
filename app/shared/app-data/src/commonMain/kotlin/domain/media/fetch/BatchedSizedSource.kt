/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.datasources.api.paging.map
import me.him188.ani.datasources.api.source.MediaMatch

/**
 * 按批交出结果的 [SizedSource]: 一批是数据源一次拿到的一组 (网页类数据源开完的一个条目页、一个词剩下的结果).
 * [MediaSourceMediaFetcher] 按批累计、发布 (见 [mediaBatches]): 选源器拿到的总是整页, 不会在同一页上更好的线路
 * 还没进来时先选了排在前面的那条; 累计的列表也是一批复制一次, 不是来一条复制一次.
 * [results] 仍逐条, 给只认 [SizedSource] 的调用方.
 */
interface BatchedSizedSource<out T> : SizedSource<T> {
    val batches: Flow<List<T>>
}

/** 由 [batches] 拼出的 [BatchedSizedSource], [results] 逐条展开它. */
fun <T> batchedSizedSource(batches: Flow<List<T>>, finished: Flow<Boolean>, totalSize: Flow<Int?>): BatchedSizedSource<T> =
    object : BatchedSizedSource<T> {
        override val batches = batches
        override val results: Flow<T> = batches.transform { batch -> batch.forEach { emit(it) } }
        override val finished = finished
        override val totalSize = totalSize
    }

/** 同 [SizedSource.map], 分批的仍分批. */
inline fun <T, R> SizedSource<T>.mapKeepingBatches(crossinline transform: suspend (T) -> R): SizedSource<R> {
    if (this !is BatchedSizedSource<T>) return map(transform)
    val self = this
    return batchedSizedSource(
        self.batches.map { batch -> batch.map { transform(it) } },
        self.finished,
        self.totalSize,
    )
}

/** 一次查询交出的资源, 按批 ([BatchedSizedSource]); 不分批的一条一批. */
internal fun SizedSource<MediaMatch>.mediaBatches(): Flow<List<Media>> =
    if (this is BatchedSizedSource<MediaMatch>) batches.map { batch -> batch.map { it.media } }
    else results.map { listOf(it.media) }
