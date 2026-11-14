/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * 地址本身被服务器拒绝时的 HTTP 状态码 (签名过期、登录信息换过了、文件没了): 换一个新地址可能有用.
 * 服务器出错 (5xx) 与限流不算.
 */
val REJECTED_LINK_STATUSES: Set<Int> = setOf(401, 403, 404, 410, 412)

/**
 * 会过期的地址 (带签名的下载 / 播放地址连同请求头) 被服务器拒绝时换新的, 下载与播放共用的规矩.
 * 地址本身由使用方存着 (下载任务的记录、播放器手上的地址), 这里只管现在是第几代、该不该去换.
 *
 * 换地址只做加法: 被拒不一定是过期 (站点临时限流、风控, 过一会儿自己会好), 所以换不成、换了也没用时都只是不再换,
 * 让使用方照它原来的办法处理 (退避重试、报错), 不替它提前判失败.
 *
 * - 很多请求同时被拒只换一次: 换的时候持锁, 后到的看到已经换过 (手上的代数旧了) 就直接用新的再试.
 * - 去换过一次 (不管换没换成) 之后 [cooldownMillis] 内不再去换: 换地址要重新解析 (请求网盘接口、开网页),
 *   十几个请求一起被拒时不能每次重试都去.
 * - 连着 [maxUnconfirmed] 个新地址都一次没成过就又被拒, 说明换了也没用, 不再换; 之后哪个请求成了再重新算.
 * - 连着 [maxFailedAttempts] 次没换成 (解析不了、拿回来的还是原来那个), 不再去试.
 * - 为同一个请求 ([confirm] 与 [rejected] 的 `requester`) 换过几回它也照样被拒 (坏掉的那一个分片, 换哪个地址都一样),
 *   不再为它换; 别的请求被拒时照常换.
 *
 * @param nowMillis 单调的毫秒时间
 * @param refresh 去换一个新地址并存好. 抛出的异常 (取消除外) 当作 [RefreshResult.Unavailable]
 */
class RejectionRefresh(
    private val nowMillis: () -> Long = monotonicMillis(),
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    private val maxUnconfirmed: Int = DEFAULT_MAX_UNCONFIRMED,
    private val maxFailedAttempts: Int = DEFAULT_MAX_FAILED_ATTEMPTS,
    private val refresh: suspend () -> RefreshResult,
) {
    sealed interface RefreshResult {
        /**
         * 换上了.
         * @property restart 新地址的内容与原来的对不上 (分片数变了), 之前做的不能接着用, 使用方要从头来
         */
        data class Refreshed(val restart: Boolean = false) : RefreshResult

        /** 这次没换成 (不认得、重新解析失败、拿回来的还是原来那个、新地址与已经做了的对不上) */
        data object Unavailable : RefreshResult
    }

    sealed interface Verdict {
        /** 地址换过了, 马上用新的再试 */
        data object Retry : Verdict

        /** 没换, 照原来的办法处理 (退避重试、报错) */
        data object Backoff : Verdict

        /** 地址换过了, 但之前做的不能接着用: 从头来 (见 [RefreshResult.Refreshed.restart]) */
        data object Restart : Verdict
    }

    private val lock = Mutex()

    /** 换过几次地址 */
    private var generation = 0

    /** 连着几个换上的地址还没成过一次 */
    private var unconfirmed = 0

    /** 连着几次没换成 */
    private var failedAttempts = 0

    /** 上次去换的时刻 (不管换没换成) */
    private var lastRefreshAt: Long? = null

    /** 最近一次要求从头来的那一代 */
    private var restartGeneration = 0

    /** 各请求触发换成了几回地址而自己还没成过 */
    private val refreshedFor = HashMap<Any, Int>()

    /**
     * 读现在的地址: [block] 拿到现在的代数, 在里面读使用方存着的地址. 与换地址互斥, 读到的地址与代数对得上.
     */
    suspend fun <R> read(block: suspend (generation: Int) -> R): R = lock.withLock { block(generation) }

    /** 第 [generation] 代的地址请求成了 */
    suspend fun confirm(generation: Int, requester: Any? = null) {
        if (generation == 0 && requester == null) return
        lock.withLock {
            if (generation == this.generation) unconfirmed = 0
            if (requester != null) refreshedFor.remove(requester)
        }
    }

    /** 第 [generation] 代的地址被拒了: 该怎么办 */
    suspend fun rejected(generation: Int, requester: Any? = null): Verdict = lock.withLock {
        // 别人已经换过了
        if (generation != this.generation) {
            return@withLock if (generation < restartGeneration) Verdict.Restart else Verdict.Retry
        }
        if (unconfirmed >= maxUnconfirmed || failedAttempts >= maxFailedAttempts) return@withLock Verdict.Backoff
        // 为这个请求已经换过几回它还是被拒: 是它自己的问题
        if (requester != null && (refreshedFor[requester] ?: 0) >= MAX_REFRESHES_PER_REQUESTER) {
            return@withLock Verdict.Backoff
        }
        val now = nowMillis()
        lastRefreshAt?.let { if (now - it < cooldownMillis) return@withLock Verdict.Backoff }
        lastRefreshAt = now

        val result = try {
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            RefreshResult.Unavailable
        }
        when (result) {
            RefreshResult.Unavailable -> {
                failedAttempts++
                Verdict.Backoff
            }

            is RefreshResult.Refreshed -> {
                this.generation++
                unconfirmed++
                failedAttempts = 0
                if (requester != null) refreshedFor[requester] = (refreshedFor[requester] ?: 0) + 1
                if (result.restart) {
                    restartGeneration = this.generation
                    Verdict.Restart
                } else {
                    Verdict.Retry
                }
            }
        }
    }

    companion object {
        const val DEFAULT_COOLDOWN_MILLIS = 2 * 60_000L
        const val DEFAULT_MAX_UNCONFIRMED = 3
        const val DEFAULT_MAX_FAILED_ATTEMPTS = 3

        /** 为同一个请求换过这么多回它还没成过, 之后不再为它换 */
        private const val MAX_REFRESHES_PER_REQUESTER = 2

        private fun monotonicMillis(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }
    }
}
