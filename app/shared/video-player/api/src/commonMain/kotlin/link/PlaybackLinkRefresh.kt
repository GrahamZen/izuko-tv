/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.him188.ani.app.platform.PlaybackRequestHints
import kotlinx.coroutines.runBlocking
import me.him188.ani.utils.coroutines.RejectionRefresh
import me.him188.ani.utils.coroutines.RejectionRefresh.RefreshResult
import me.him188.ani.utils.coroutines.RejectionRefresh.Verdict
import me.him188.ani.utils.coroutines.update
import kotlin.concurrent.Volatile

/** 播放地址连同请求时要带的请求头 (含给播放器看的提示, 见 [PlaybackRequestHints]) */
data class PlaybackLink(
    val uri: String,
    val headers: Map<String, String>,
)

/**
 * 正在播的资源的地址. 带签名的地址几个小时就过期 (网盘直链约 6 小时), 暂停很久再接着播时播放器手上的地址已经被服务器拒绝:
 * 这时由 [refresher] 重新解析原资源换一个新地址, 播放器的请求改用新的接着读, 不用等播放器报错再整个重新加载
 * (缓冲里还有内容时画面不断). 什么时候换、什么时候不再换按各处共用的 [RejectionRefresh]; 换不成、换了也没用时
 * 被拒的错原样交给播放器, 同没有这一层时一样.
 *
 * 只在这个地址请求成过之后才换: 一上来就被拒的不是过期, 照旧报打开失败 (坏链接要尽快换下一个源).
 *
 * @param refresher 重新解析出一个新地址; 换不了时 `null`
 */
class RefreshablePlaybackLink(
    initial: PlaybackLink,
    nowMillis: (() -> Long)? = null,
    private val refresher: suspend () -> PlaybackLink?,
) {
    /** 某一刻的地址与它是第几代 (换一次加一) */
    class Lease(val generation: Int, val link: PlaybackLink)

    /** 现在的地址 */
    @Volatile
    var lease: Lease = Lease(0, initial)
        private set

    /** 请求成过的最新一代; 还没成过时为 -1 */
    @Volatile
    private var confirmedGeneration = -1

    private val policy = if (nowMillis == null) RejectionRefresh { refresh() } else RejectionRefresh(nowMillis) { refresh() }

    private suspend fun refresh(): RefreshResult {
        val current = lease
        val fresh = refresher() ?: return RefreshResult.Unavailable
        if (fresh == current.link) return RefreshResult.Unavailable
        lease = Lease(current.generation + 1, fresh)
        return RefreshResult.Refreshed()
    }

    /** 第 [generation] 代的地址请求成了 */
    suspend fun confirm(generation: Int) {
        if (confirmedGeneration >= generation) return
        policy.confirm(generation)
        if (confirmedGeneration < generation) confirmedGeneration = generation
    }

    /** 第 [generation] 代的地址被拒了 (状态码见 [me.him188.ani.utils.coroutines.REJECTED_LINK_STATUSES]): 该怎么办 */
    suspend fun rejected(generation: Int): Verdict {
        if (confirmedGeneration < 0 || mismatched) return Verdict.Backoff
        return policy.rejected(generation)
    }

    /**
     * 连接断了、从断处接着读也读不到数据时换一次地址 (阻塞调用线程, 给播放器的加载线程用): 什么时候换、什么时候不再换同被拒 ([rejected]).
     * 换上了返回 `true`, 请求方用新地址再读.
     */
    fun refreshAfterCutBlocking(): Boolean = runBlocking { rejected(lease.generation) } == Verdict.Retry

    private val unreachableState = MutableStateFlow(false)

    /**
     * 这条资源接不上了: 连接断了之后从断处接着读、换地址再读都没读到数据, 跟第一次播就打不开差不多.
     * 加载方看它趁缓冲还在播提前找别的资源; 之后又读到数据时撤销.
     */
    val unreachable: StateFlow<Boolean> get() = unreachableState

    fun markUnreachable() {
        unreachableState.value = true
    }

    private val rejectedState = MutableStateFlow(false)

    /**
     * 这个地址被服务器拒了: 连接断了之后重新请求时服务器说地址不对 (过期被拒、回的不是视频), 这里又换不了地址 (要开网页现抓的源).
     * 加载方看它趁缓冲还在播重新解析这条资源; 之后又读到数据时撤销.
     */
    val rejected: StateFlow<Boolean> get() = rejectedState

    fun markRejected() {
        rejectedState.value = true
    }

    /** 又读到数据了: 撤销「接不上」与「被拒」 */
    fun markReachable() {
        if (unreachableState.value) unreachableState.value = false
        if (rejectedState.value) rejectedState.value = false
    }

    /** 这个资源一共多长, 第一次从响应里知道时记下; 还不知道时为 -1 */
    @Volatile
    private var contentLength = -1L

    /**
     * 换来的地址指向的是另一个文件 (另一条线路或清晰度): 播放器接着按原来的位置读会读出对不上的数据.
     * 认出之后这条地址不再用, 请求方把请求按被拒报出去, 由播放器原来的出错处理整个重新加载.
     */
    @Volatile
    var mismatched = false
        private set

    /** 响应说这个资源一共 [total] 字节: 与先前知道的一样吗 (第一次知道时记下). 不一样时记成 [mismatched]. */
    fun sameContent(total: Long): Boolean {
        val known = contentLength
        if (known < 0) {
            contentLength = total
            return true
        }
        if (known != total) mismatched = true
        return known == total
    }

    /**
     * 用现在的地址做一次 [request] (阻塞调用线程, 给播放器的加载线程用): 被拒 ([rejectedStatus] 给出状态码) 时换地址马上再试,
     * 换不了或换了也没用时把这次的错原样抛出, 交给播放器原来的出错处理.
     */
    fun <R> requestBlocking(rejectedStatus: (Throwable) -> Int?, request: (Lease) -> R): R {
        var tries = 0
        while (true) {
            val current = lease
            val result = try {
                request(current)
            } catch (e: Throwable) {
                rejectedStatus(e) ?: throw e
                when (runBlocking { rejected(current.generation) }) {
                    Verdict.Retry -> if (++tries >= MAX_IMMEDIATE_RETRIES) throw e else continue
                    Verdict.Backoff, Verdict.Restart -> throw e
                }
            }
            if (confirmedGeneration < current.generation) runBlocking { confirm(current.generation) }
            return result
        }
    }

    private companion object {
        /** 一次请求里换地址后接着再试几次 */
        const val MAX_IMMEDIATE_RETRIES = 3
    }
}

/**
 * 正在播的资源的 [RefreshablePlaybackLink], 按播放器拿到的那个地址 (解析出来的第一个) 登记. 加载资源的一方登记、停止播放时注销;
 * 播放器的数据源、分享与同步播放按原地址来查现在该用哪个.
 */
object PlaybackLinks {
    private val links = MutableStateFlow(emptyMap<String, RefreshablePlaybackLink>())

    fun register(originalUri: String, link: RefreshablePlaybackLink) {
        links.update { this + (originalUri to link) }
    }

    /** 注销 [register] 登记的那一个 (已经换成别的就不动) */
    fun unregister(originalUri: String, link: RefreshablePlaybackLink) {
        links.update { if (this[originalUri] === link) this - originalUri else this }
    }

    fun of(originalUri: String): RefreshablePlaybackLink? = links.value[originalUri]

    /** [originalUri] 现在该用的地址: 换过就是新的, 没登记或没换过就是它自己 */
    fun currentUriOf(originalUri: String): String = links.value[originalUri]?.lease?.link?.uri ?: originalUri
}
