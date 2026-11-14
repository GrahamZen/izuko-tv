/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together.mqtt

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal enum class MqttLinkState {
    CONNECTING,
    CONNECTED,

    /** 上一次没连上或断了, 在等下一次重连 */
    WAITING,
}

/**
 * 与一家代理之间的一条连接: 连上后订阅 [topic], 收到的消息交给 [onMessage]; 断了按退避间隔重连, 直到 [run] 被取消。
 *
 * - **遗嘱**: 每次连接时向 [will] 要一份内容, 交给代理保管; 连接异常断开 (断网、进程没了) 时代理把它发到 [topic]。
 *   [run] 被取消时先发 DISCONNECT 再关连接, 代理就不发遗嘱。
 * - **判断连接还活着**: 每 [keepAlive] 发一次 PINGREQ, 半个 [keepAlive] 内没等到 PINGRESP 就当连接已死、重连。
 *   这是协议层的两个字节, 不算房间里的消息。
 * - **重连间隔**: 连续失败时逐次加长; 一次连接要撑到第一个心跳回应才算连成过, 之前就断的 (被代理限流、
 *   一连上就被踢) 照样算失败, 不会每秒重连一次。
 * - 不排队: 没连上时 [publish] 直接返回 `false`。一起看的消息只在状态变化时发, 错过的靠重连后重新对一次状态补上。
 */
internal class MqttLink(
    val url: String,
    private val topic: String,
    private val connector: MqttConnector,
    private val will: () -> ByteArray?,
    private val onMessage: suspend (ByteArray) -> Unit,
    private val keepAlive: Duration = 30.seconds,
    private val random: Random = Random.Default,
) {
    private val _state = MutableStateFlow(MqttLinkState.CONNECTING)
    val state: StateFlow<MqttLinkState> = _state.asStateFlow()

    private val current = atomic<MqttTransport?>(null)

    /** 这次连接收到过几个心跳回应; 收包循环加、心跳协程读 */
    private val pongs = atomic(0)

    /** 发给房间。这条连接此刻没连上、或发的途中断了, 返回 `false` */
    suspend fun publish(payload: ByteArray): Boolean {
        val transport = current.value ?: return false
        return try {
            transport.send(MqttEncoder.publish(topic, payload))
            true
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            false
        } catch (_: Exception) {
            false
        }
    }

    suspend fun run(): Nothing {
        var failures = 0
        while (true) {
            _state.value = MqttLinkState.CONNECTING
            // 只在本协程里读写: session 的收包循环与这里是同一个协程
            var proved = false
            try {
                session(proved = { proved = true })
                logger.debug { "[一起看] ${hostOf(url)} 的连接被对端关了" }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                logger.debug { "[一起看] ${hostOf(url)} 的连接超时" }
            } catch (e: Exception) {
                logger.debug { "[一起看] ${hostOf(url)} 的连接断了: ${e::class.simpleName}: ${e.message}" }
            }
            _state.value = MqttLinkState.WAITING
            failures = if (proved) 1 else failures + 1
            delay(retryDelay(failures))
        }
    }

    /**
     * 一次连接从握手到断开; 正常返回 = 对端关了连接。
     *
     * @param proved 收到第一个心跳回应时调用
     */
    private suspend fun session(proved: () -> Unit) {
        val transport = connector.connect(url)
        var cancelled = false
        try {
            val reader = PacketReader(transport)
            val willPayload = will()
            transport.send(
                MqttEncoder.connect(
                    clientId = randomClientId(),
                    keepAliveSeconds = keepAlive.inWholeSeconds.toInt(),
                    will = willPayload?.let { MqttWill(topic, it) },
                ),
            )
            val connAck = withTimeout(HANDSHAKE_TIMEOUT) { reader.await<MqttPacket.ConnAck>() }
            if (!connAck.accepted) throw MqttProtocolException("代理拒绝连接, 返回码 ${connAck.returnCode}")
            transport.send(MqttEncoder.subscribe(SUBSCRIBE_PACKET_ID, topic))
            val subAck = withTimeout(HANDSHAKE_TIMEOUT) { reader.await<MqttPacket.SubAck>() }
            if (!subAck.granted) throw MqttProtocolException("代理拒绝订阅")

            current.value = transport
            _state.value = MqttLinkState.CONNECTED
            logger.info { "[一起看] 连上 ${hostOf(url)}" }

            pongs.value = 0
            coroutineScope {
                val pinger = launch {
                    while (isActive) {
                        delay(keepAlive / 2)
                        val before = pongs.value
                        transport.send(MqttEncoder.pingReq())
                        delay(keepAlive / 2)
                        if (pongs.value == before) throw MqttProtocolException("心跳没有回应")
                    }
                }
                while (true) {
                    when (val packet = reader.next() ?: break) {
                        is MqttPacket.Publish -> if (packet.topic == topic) onMessage(packet.payload)
                        MqttPacket.PingResp -> if (pongs.incrementAndGet() == 1) proved()
                        else -> Unit
                    }
                }
                pinger.cancel()
            }
        } catch (e: CancellationException) {
            cancelled = !currentCoroutineContext().isActive
            throw e
        } finally {
            current.compareAndSet(transport, null)
            withContext(NonCancellable) {
                if (cancelled) {
                    // 主动离开: 告诉代理这是正常断开, 别发遗嘱
                    withTimeoutOrNull(GOODBYE_TIMEOUT) { runCatching { transport.send(MqttEncoder.disconnect()) } }
                }
                transport.close()
            }
        }
    }

    /** 连成过的连接断了、或第 1 次失败等 1 秒, 之后每次翻倍, 封顶 30 秒; 各加减两成, 免得同一时刻断开的客户端一起涌回去 */
    private fun retryDelay(failures: Int): Duration {
        val base = RETRY_DELAYS_MILLIS[(failures - 1).coerceIn(0, RETRY_DELAYS_MILLIS.lastIndex)]
        val jitter = (base * 0.2).toLong()
        return (base + random.nextLong(-jitter, jitter + 1)).milliseconds
    }

    private fun randomClientId(): String =
        buildString(CLIENT_ID_LENGTH) { repeat(CLIENT_ID_LENGTH) { append(CLIENT_ID_CHARS[random.nextInt(CLIENT_ID_CHARS.length)]) } }

    private class PacketReader(private val transport: MqttTransport) {
        private val decoder = MqttDecoder()
        private val pending = ArrayDeque<MqttPacket>()

        /** 下一个报文; 对端关了连接时为 `null` */
        suspend fun next(): MqttPacket? {
            while (pending.isEmpty()) {
                val bytes = transport.receive() ?: return null
                pending.addAll(decoder.feed(bytes))
            }
            return pending.removeFirst()
        }

        /** 等到一个 [T] 类型的报文, 之前收到的别的报文丢掉 (握手阶段还没订阅, 不会有消息) */
        suspend inline fun <reified T : MqttPacket> await(): T {
            while (true) {
                val packet = next() ?: throw MqttProtocolException("握手途中连接被关")
                if (packet is T) return packet
            }
        }
    }

    private companion object {
        private val logger = logger<MqttLink>()
        val HANDSHAKE_TIMEOUT = 10.seconds
        val GOODBYE_TIMEOUT = 1.seconds
        const val SUBSCRIBE_PACKET_ID = 1
        val RETRY_DELAYS_MILLIS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)

        // 代理看得到客户端标识, 用不带应用特征的随机串
        const val CLIENT_ID_LENGTH = 16
        const val CLIENT_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

        fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore(':')
    }
}
