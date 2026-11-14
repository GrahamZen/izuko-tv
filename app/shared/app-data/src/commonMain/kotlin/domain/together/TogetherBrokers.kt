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
import me.him188.ani.app.domain.foundation.ScopedHttpClientFeatureHandler
import me.him188.ani.app.domain.foundation.ScopedHttpClientFeatureKey

/**
 * 一起看用的公共 MQTT 代理 (MQTT over WebSocket)。三家互不相干, 都不用注册; 一起看同时连着它们 (见 [MqttTogetherChannel])。
 */
object TogetherBrokers {
    val BUILT_IN: List<String> = listOf(
        "wss://broker.emqx.io:8084/mqtt",
        "wss://broker.hivemq.com:8884/mqtt",
        "wss://test.mosquitto.org:8081/mqtt",
    )
}

/** 给 HTTP 客户端装上 WebSocket 插件; 一起看连代理用 */
val TogetherWebSocketsFeature = ScopedHttpClientFeatureKey<Boolean>("TogetherWebSockets")

data object TogetherWebSocketsFeatureHandler : ScopedHttpClientFeatureHandler<Boolean>(TogetherWebSocketsFeature) {
    override fun applyToConfig(config: HttpClientConfig<*>, value: Boolean) {
        if (!value) return
        config.install(WebSockets)
    }
}
