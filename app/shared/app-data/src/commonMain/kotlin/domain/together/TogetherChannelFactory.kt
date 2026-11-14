/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.together.mqtt.WebSocketMqttConnector
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.time.measureTimedValue

fun interface TogetherChannelOpener {
    /**
     * 进 [room] 这个房间; 连接在 [scope] 里跑, 这里不等连上就返回 (连没连上看 [TogetherChannel.links])。
     *
     * @param farewell 连接异常断开时由代理代发给房间的那条消息; 每次 (重新) 连接时取一次, `null` 表示不留
     */
    suspend fun connect(room: TogetherRoomId, scope: CoroutineScope, farewell: () -> ByteArray?): TogetherConnection
}

/**
 * 按房间号与密码开公共代理上的通道。
 *
 * @param client 装了 WebSocket 插件的 HTTP 客户端 (特性 [TogetherWebSocketsFeature])
 * @param brokerUrls 要连的代理, 每次开通道时取一次
 */
class TogetherChannelFactory(
    private val client: () -> ScopedHttpClient,
    private val brokerUrls: () -> List<String> = { TogetherBrokers.BUILT_IN },
) : TogetherChannelOpener {
    private val keys = TogetherRoomKeyCache()

    override suspend fun connect(room: TogetherRoomId, scope: CoroutineScope, farewell: () -> ByteArray?): TogetherConnection {
        val channel = MqttTogetherChannel(
            key = keys.get(room),
            brokerUrls = brokerUrls(),
            connector = WebSocketMqttConnector(client),
            farewell = farewell,
        )
        return TogetherConnection(channel, scope.launch(CoroutineName("TogetherChannel")) { channel.run() })
    }
}

/**
 * 记着最近一个房间算出的话题名与密钥。慢哈希在慢的电视上一次约一秒 (CPU 被占着时更久), 而同一个房间会反复进:
 * 断网时自动回房每隔一会儿重试一次, 房主让位后重新进, 应用还开着时退出又进。房主换了密码时话题名不用重算。
 */
internal class TogetherRoomKeyCache(
    private val deriveTopic: suspend (number: String) -> String = { TogetherRoomKey.deriveTopic(it) },
    private val deriveKey: suspend (room: TogetherRoomId) -> ByteArray = { TogetherRoomKey.deriveEncryptionKey(it) },
) {
    private val lock = Mutex()
    private var topic: Pair<String, String>? = null
    private var key: Pair<TogetherRoomId, ByteArray>? = null

    suspend fun get(room: TogetherRoomId): TogetherRoomKey = lock.withLock {
        // 算的途中调用方不等了也把结果留下: 慢哈希停不下来, 白算一遍下次还得再算
        withContext(NonCancellable) {
            val (result, took) = measureTimedValue {
                val t = topic?.takeIf { it.first == room.number }?.second
                    ?: deriveTopic(room.number).also { topic = room.number to it }
                val k = key?.takeIf { it.first == room }?.second
                    ?: deriveKey(room).also { key = room to it }
                TogetherRoomKey(encryptionKey = k, topic = t)
            }
            // 记下用时, 好分清进房慢在哪一步
            logger.info { "[一起看] 取房间密钥用了 ${took.inWholeMilliseconds} ms" }
            result
        }
    }

    private companion object {
        private val logger = logger<TogetherRoomKeyCache>()
    }
}

class TogetherConnection internal constructor(
    val channel: TogetherChannel,
    private val job: Job,
) {
    /** 主动离开: 断开全部连接并等它断完。代理不发遗嘱 */
    suspend fun close() {
        job.cancelAndJoin()
    }
}
