/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import me.him188.ani.utils.platform.currentTimeMillis

/**
 * 发往 Bangumi 的请求在各条线路上的情况, 由 [BangumiRoutingFeatureHandler] 记:
 * - 正在发的请求 (在客户端里排队、还没轮到上网的也算): 按域名数个数、最久的等了多久 —— 同一个域名并发有上限, 前面挂住的请求会让后面的一直排着;
 * - 最近 [WINDOW_MILLIS] 内每条线路成功 / 失败多少次, 最近 [MAX_FAILURES] 次失败的原因;
 * - 一个请求所有线路都失败的时刻 ([allFailed]).
 *
 * 网络体检的报告读 [snapshot], 自动网络体检听 [allFailed]. 只在内存里, 进程重启就清空.
 */
class BangumiRouteStats(
    private val now: () -> Long = { currentTimeMillis() },
) {
    /** 正在发的一个请求. */
    private class InFlight(val host: String, val startedAt: Long)

    /** 一条线路一次尝试的结果. */
    private class Attempt(val at: Long, val route: String, val ok: Boolean)

    /** 一次连不上 (或回了 5xx / 网页) 的尝试. [path] 里的数字换成了 `{n}` (见 [pathPattern]). */
    class RouteFailure(val at: Long, val route: String, val host: String, val path: String, val elapsedMillis: Long, val cause: String)

    /** 一个请求所有线路都失败了. [cause] 是最后一条线路失败的原因. */
    class AllFailed(val at: Long, val host: String, val path: String, val tried: List<String>, val cause: String?)

    class HostInFlight(val host: String, val count: Int, val oldestMillis: Long)
    class RouteCounts(val route: String, val ok: Int, val failed: Int)

    class Snapshot(
        val inFlight: List<HostInFlight>,
        /** 最近 [WINDOW_MILLIS] 内各条线路的成功 / 失败次数. */
        val routes: List<RouteCounts>,
        /** 最近的失败, 新的在前. */
        val failures: List<RouteFailure>,
        /** 最近 [WINDOW_MILLIS] 内所有线路都失败的请求, 新的在前. */
        val allFailed: List<AllFailed>,
    )

    private val nextId = atomic(0L)
    private val inFlight = MutableStateFlow<Map<Long, InFlight>>(emptyMap())
    private val attempts = MutableStateFlow<List<Attempt>>(emptyList())
    private val failures = MutableStateFlow<List<RouteFailure>>(emptyList())
    private val allFailedRecent = MutableStateFlow<List<AllFailed>>(emptyList())

    private val _allFailed = MutableSharedFlow<AllFailed>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 所有线路都失败的请求, 每个一条. */
    val allFailed: SharedFlow<AllFailed> = _allFailed.asSharedFlow()

    /** 开始发一个请求, 返回的编号交给 [requestFinished]. */
    fun requestStarted(host: String): Long {
        val id = nextId.incrementAndGet()
        inFlight.update { it + (id to InFlight(host, now())) }
        return id
    }

    fun requestFinished(id: Long) {
        inFlight.update { it - id }
    }

    fun routeSucceeded(route: BangumiRoute) {
        record(Attempt(now(), route.label, ok = true))
    }

    fun routeFailed(route: BangumiRoute, host: String, path: String, elapsedMillis: Long, cause: String) {
        val at = now()
        record(Attempt(at, route.label, ok = false))
        failures.update { (listOf(RouteFailure(at, route.label, host, pathPattern(path), elapsedMillis, cause)) + it).take(MAX_FAILURES) }
    }

    fun allRoutesFailed(host: String, path: String, tried: List<BangumiRoute>, cause: String?) {
        val event = AllFailed(now(), host, pathPattern(path), tried.map { it.label }, cause)
        allFailedRecent.update { list -> (listOf(event) + list).filter { event.at - it.at <= WINDOW_MILLIS }.take(MAX_ALL_FAILED) }
        _allFailed.tryEmit(event)
    }

    private fun record(attempt: Attempt) {
        attempts.update { list ->
            (list + attempt).let { all -> all.drop((all.size - MAX_ATTEMPTS).coerceAtLeast(0)) }
                .filter { attempt.at - it.at <= WINDOW_MILLIS }
        }
    }

    fun snapshot(): Snapshot {
        val t = now()
        val hosts = inFlight.value.values.groupBy { it.host }.map { (host, list) ->
            HostInFlight(host, list.size, t - list.minOf { it.startedAt })
        }.sortedByDescending { it.count }
        val counts = attempts.value.filter { t - it.at <= WINDOW_MILLIS }.groupBy { it.route }.map { (route, list) ->
            RouteCounts(route, ok = list.count { it.ok }, failed = list.count { !it.ok })
        }
        return Snapshot(
            inFlight = hosts,
            routes = counts,
            failures = failures.value,
            allFailed = allFailedRecent.value.filter { t - it.at <= WINDOW_MILLIS },
        )
    }

    companion object {
        /** 成功 / 失败次数与「所有线路都失败」按最近这么久数. */
        const val WINDOW_MILLIS = 10 * 60_000L
        private const val MAX_FAILURES = 30
        private const val MAX_ALL_FAILED = 100
        private const val MAX_ATTEMPTS = 2_000
        private val NUMBER_SEGMENT = Regex("/\\d+(?=/|$)")

        /** 请求路径里的条目号、分集号换成 `{n}` (`/p1/subjects/9912/characters` → `/p1/subjects/{n}/characters`): 按接口看哪个在失败. */
        fun pathPattern(path: String): String = path.replace(NUMBER_SEGMENT, "/{n}")
    }
}
