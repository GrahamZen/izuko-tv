/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.profile

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.cancellation.CancellationException

/**
 * 登录着的 Bangumi 用户改成本地用户、选了留下记录时, 先把账号里的收藏整个取下来写进当前用户的库 (见 [LocalProfileConversion]):
 * 库里平时只缓存用到过的那些 (收藏页翻到的那几类与那几页、进过的条目), 直接改就只留下这些, 账号里其余的收藏没了.
 *
 * 1. 收藏列表全部类型翻到底写进库 (同收藏页的刷新), 条目的收藏类型、评分、短评、标签、私密都在里面; 没翻完就抛出.
 * 2. 想看以外的每一部再取一次分集 (带自己的观看状态) 写进库: 收藏列表只说看到第几集, 不说是哪几集. 一部取不到重试一次,
 *    还不行就跳过 (那一部只少了看过的集, 收藏状态与评分已经在库里), 不为一部拦下整个改动.
 */
class AccountCollectionsDownload(
    /** 把账号的收藏列表 (全部类型) 翻到底写进库; 返回是否翻完了. */
    private val refreshCollectionList: suspend () -> Boolean,
    /** 库里收藏成这几类的条目. */
    private val collectedSubjectIds: suspend (types: List<UnifiedCollectionType>) -> List<Int>,
    /** 取这个条目的分集, 带自己的观看状态. */
    private val fetchEpisodes: suspend (subjectId: Int) -> List<EpisodeCollectionEntity>,
    private val saveEpisodes: suspend (List<EpisodeCollectionEntity>) -> Unit,
    /** 同时取几部的分集. */
    private val parallelism: Int = 4,
) {
    /**
     * @property total 要取分集的条数; 0 = 还在取收藏列表
     */
    data class Progress(val done: Int, val total: Int)

    /**
     * 取下来写进库. 收藏列表没取全时抛出 (调用方不改用户类型).
     * @return 取不到分集、跳过了的条数
     */
    @OptIn(ExperimentalAtomicApi::class)
    suspend fun download(onProgress: (Progress) -> Unit = {}): Int {
        onProgress(Progress(0, 0))
        check(refreshCollectionList()) { "The collection list of the Bangumi account was not fully fetched" }
        val subjectIds = collectedSubjectIds(EPISODE_TYPES)
        onProgress(Progress(0, subjectIds.size))
        val done = AtomicInt(0)
        val permits = Semaphore(parallelism)
        val skipped = coroutineScope {
            subjectIds.map { subjectId ->
                async {
                    permits.withPermit {
                        val ok = fetchAndSave(subjectId)
                        onProgress(Progress(done.incrementAndFetch(), subjectIds.size))
                        ok
                    }
                }
            }.awaitAll().count { !it }
        }
        logger.info { "Downloaded ${subjectIds.size} collections with episodes from Bangumi, $skipped without episodes" }
        return skipped
    }

    private suspend fun fetchAndSave(subjectId: Int): Boolean {
        repeat(ATTEMPTS) { attempt ->
            try {
                saveEpisodes(fetchEpisodes(subjectId))
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to fetch episodes of subject $subjectId (attempt ${attempt + 1})" }
            }
        }
        return false
    }

    private companion object {
        private val logger = logger<AccountCollectionsDownload>()
        private const val ATTEMPTS = 2

        /** 想看的不该有看过的集, 不取. */
        private val EPISODE_TYPES = listOf(
            UnifiedCollectionType.DOING,
            UnifiedCollectionType.DONE,
            UnifiedCollectionType.ON_HOLD,
            UnifiedCollectionType.DROPPED,
        )
    }
}
