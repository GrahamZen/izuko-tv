/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.data.models.preference.RepoHostedListCache
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.foundation.RepoHostedList
import me.him188.ani.app.domain.foundation.ScopedHttpClientFeatureHandler
import me.him188.ani.app.domain.foundation.ScopedHttpClientFeatureKey
import me.him188.ani.utils.ktor.ScopedHttpClient

/**
 * 一起看连哪些公共 MQTT 代理 (MQTT over WebSocket): 仓库根目录的 `together-brokers.json` (见 [RepoHostedList])。
 * 几家互不相干, 都不用注册; 一起看同时连着清单里的每一家 (见 [MqttTogetherChannel])。
 * 包里不带任何代理地址: 代理被封、下线, 或要补大陆连得上的代理时改清单, 不用发版。
 *
 * 拉到过的清单存在本机, 之后拉不到就用存下的; 一次都没拉到过 (刚装好、应用启动时那次没拉到) 时,
 * 开房间或加入前当场再拉一次 ([brokersToConnect]), 还是没有就报 [TogetherNoBrokersException]。
 *
 * 同一个房间的人要连着至少一家相同的代理才收得到彼此, 而 1.0.9-alpha04 及以前的包只认包里带的
 * broker.emqx.io / broker.hivemq.com / test.mosquitto.org 三家、不拉清单, 所以清单里一直留着其中至少一家。
 * 连不上的代理不碍事: 几家同时连, 连不上的那家只是不起作用。
 */
class TogetherBrokerList(
    cache: Settings<RepoHostedListCache>,
    client: () -> ScopedHttpClient,
    scope: CoroutineScope,
) : RepoHostedList(SPEC, cache, client, scope) {
    private val fetchLock = Mutex()

    /** 开通道时要连的代理; 手里还没有清单时当场拉一次 (同一时刻只拉一次), 拉不到返回空 */
    suspend fun brokersToConnect(): List<String> {
        entries.first().takeIf { it.isNotEmpty() }?.let { return it }
        fetchLock.withLock {
            if (entries.first().isEmpty()) refreshIfStale()
        }
        return entries.first()
    }

    companion object {
        private val WSS_URL = Regex("""wss://[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+(:\d{1,5})?(/\S*)?""")

        /** 只认带域名的 `wss://` 地址 (清单是远程内容, 写进去的东西不由我们决定); 认不出返回 `null` */
        fun normalizeBroker(input: String): String? = input.trim().takeIf { WSS_URL.matches(it) }

        private val SPEC = Spec(
            fileName = "together-brokers.json",
            field = "brokers",
            bundled = emptyList(),
            normalize = ::normalizeBroker,
        )
    }
}

/** 一起看的代理清单一次都没拉到过, 这次也拉不到: 没有能连的代理 (见 [TogetherBrokerList]) */
class TogetherNoBrokersException : Exception("No broker list for watching together")

/** 给 HTTP 客户端装上 WebSocket 插件; 一起看连代理用 */
val TogetherWebSocketsFeature = ScopedHttpClientFeatureKey<Boolean>("TogetherWebSockets")

data object TogetherWebSocketsFeatureHandler : ScopedHttpClientFeatureHandler<Boolean>(TogetherWebSocketsFeature) {
    override fun applyToConfig(config: HttpClientConfig<*>, value: Boolean) {
        if (!value) return
        config.install(WebSockets)
    }
}
