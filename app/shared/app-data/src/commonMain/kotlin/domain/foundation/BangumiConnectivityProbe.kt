/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.data.models.preference.BangumiEndpointMode
import me.him188.ani.app.data.models.preference.BangumiMirrorHosts
import me.him188.ani.utils.ktor.ScopedHttpClient

/**
 * 「这台设备连不连得上 Bangumi」的一次检测 (首次启动引导用): 官方与镜像清单里的每个镜像**直接**各连一次, 并行.
 * 怎么连、怎么判见 [ReachabilityProbe]; [client] 同样不能带 [BangumiMirrorFeature].
 */
class BangumiConnectivityProbe(
    client: () -> ScopedHttpClient,
    private val mirrors: Flow<List<String>>,
    timeoutMillis: Long = 8_000L,
) {
    private val probe = ReachabilityProbe(client, timeoutMillis)

    data class Result(
        val origin: Reachability = Reachability.Checking,
        /** 展示用的镜像: 连得上的第一个, 都连不上时是清单第一个; 清单为空时为 `null`. */
        val mirror: String? = null,
        val mirrorReachability: Reachability = Reachability.Checking,
    ) {
        val completed: Boolean
            get() = origin != Reachability.Checking && mirrorReachability != Reachability.Checking

        /** 两边至少有一边连得上: 本机是联网的. */
        val online: Boolean
            get() = origin is Reachability.Reachable || mirrorReachability is Reachability.Reachable

        /** 建议的连接方式; 还没测完, 或两边都连不上 (多半是本机没网) 时为 `null`. */
        val recommendedMode: BangumiEndpointMode?
            get() = when {
                !completed -> null
                origin is Reachability.Reachable -> BangumiEndpointMode.AUTO
                mirrorReachability is Reachability.Reachable -> BangumiEndpointMode.MIRROR
                else -> null
            }
    }

    /** 测一轮: 先发一次「检测中」, 之后每出一个结论发一次; 最后一次的 [Result.completed] 为 true. */
    fun run(): Flow<Result> = channelFlow {
        val mirrorList = mirrors.first()
        var result = Result(
            mirror = mirrorList.firstOrNull(),
            mirrorReachability = if (mirrorList.isEmpty()) Reachability.Unreachable else Reachability.Checking,
        )
        val lock = Mutex()
        suspend fun update(transform: (Result) -> Result) = lock.withLock {
            result = transform(result)
            send(result)
        }
        send(result)
        launch {
            val origin = probe.probe("https://$ORIGIN_PROBE_HOST$PROBE_PATH")
            update { it.copy(origin = origin) }
        }
        if (mirrorList.isNotEmpty()) launch {
            val targets = mirrorList.mapNotNull { mirror ->
                BangumiMirrorHosts.mirrorHostOf(ORIGIN_PROBE_HOST, mirror)?.let { mirror to "https://$it$PROBE_PATH" }
            }
            val hit = probe.firstReachable(targets.map { it.second })
            update {
                it.copy(
                    mirror = hit?.let { hit -> targets[hit.index].first } ?: mirrorList.first(),
                    mirrorReachability = hit?.value ?: Reachability.Unreachable,
                )
            }
        }
    }

    private companion object {
        /** 官方那一路测的域名. */
        const val ORIGIN_PROBE_HOST = "api.bgm.tv"

        /**
         * 测的路径: 不带令牌取「我」, 立刻回 401 (对方在回答, 算连得上), 不查数据 (官方约 0.2 秒, 镜像约 0.6 秒).
         * 不用根路径: 根路径走「找不到」那条处理, 2026-09-25 实测 3~8 秒才回 (正常接口 0.1~0.2 秒), 5 秒的读超时下
         * 官方和镜像 (镜像只是转发) 会一起被判成连不上.
         */
        const val PROBE_PATH = "/v0/me"
    }
}
