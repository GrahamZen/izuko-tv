/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.concurrent.Volatile
import me.him188.ani.app.domain.together.mqtt.MqttConnector
import me.him188.ani.app.domain.together.mqtt.MqttLink
import me.him188.ani.app.domain.together.mqtt.MqttLinkState
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 一起看房间里的消息通道: 发出去的消息房间里其他人都收得到, 通道不关心消息内容。
 *
 * 消息只在连着的时候送达, 不补发: 断线期间别人发的收不到。上层靠 [links] 知道什么时候重新连上了, 再跟房间对一次状态。
 *
 * 同一个房间号、密码不同的人在同一个话题上, 互相解不开对方的消息。[answersStrangers] 开着的一方 (房主) 收到解不开的消息时
 * 回一张回执, 发那条消息的人从 [rejections] 知道自己的密码不对。
 */
interface TogetherChannel {
    /** 现在连着几家代理 */
    val links: StateFlow<TogetherLinks>

    /** 房间里其他人发来的消息。自己发的、重复送到的不在里面 */
    val incoming: Flow<ByteArray>

    /** 这边发出的消息被房间里的人回了「解不开」: 这边的密码不对 */
    val rejections: Flow<Unit>

    /** 收到解不开的消息时回「解不开」; 房主开着, 成员不开 (免得一句话收到好几张回执) */
    var answersStrangers: Boolean

    /** 发给房间里其他人。一家代理都没连上时返回 `false` */
    suspend fun send(message: ByteArray): Boolean
}

data class TogetherLinks(val connected: Int, val total: Int) {
    val online: Boolean get() = connected > 0
}

/**
 * 用公共 MQTT 代理当转发管道的通道。
 *
 * - **同时连几家, 每家都发**: 公共代理是免费的测试服务, 随时可能限流或重启; 房间里的人必须落在同一家才互相看得到,
 *   所以不是「连不上再换下一家」(两边会换到不同的家), 而是每家都连、每条消息每家都发, 收到重复的丢掉。
 * - **代理看不到内容**: 话题名与密钥都由房间码算出 (见 [TogetherRoomKey]), 线上只有密文。
 * - **异常掉线**: 每条连接都向代理留一份遗嘱 ([farewell] 的密文), 这条连接异常断开时代理把它发给房间。
 *   几家代理各留各的, 所以其中一家的连接断了、别家还连着时, 房间里也会收到一份; 上层要把它当「可能掉线了」处理。
 *
 * - **密码不对的人**: 与房间里的人在同一个话题上 (话题名只由房间号算出), 消息互相解不开。[answersStrangers] 开着时,
 *   解不开的消息按它的标识回一张不加密的回执 ([TogetherEnvelope.deny]), 同一条只回一次, 一分钟最多回 [MAX_DENIALS_PER_MINUTE] 张;
 *   收到的回执说的是这边发过的消息时, 从 [rejections] 报出去。
 *
 * [run] 期间通道是活的, 取消它就断开全部连接 (正常断开, 代理不发遗嘱)。
 */
internal class MqttTogetherChannel(
    private val key: TogetherRoomKey,
    brokerUrls: List<String>,
    connector: MqttConnector,
    private val farewell: () -> ByteArray?,
    keepAlive: Duration = 30.seconds,
    random: Random = Random.Default,
    private val nowMillis: () -> Long = ::currentTimeMillis,
) : TogetherChannel {
    init {
        require(brokerUrls.isNotEmpty()) { "至少要有一家代理" }
    }

    private val mqttLinks = brokerUrls.map { url ->
        MqttLink(
            url = url,
            topic = key.topic,
            connector = connector,
            will = { farewell()?.let { TogetherEnvelope.seal(key, it) } },
            onMessage = ::onEnvelope,
            keepAlive = keepAlive,
            random = random,
        )
    }

    private val _links = MutableStateFlow(TogetherLinks(connected = 0, total = mqttLinks.size))
    override val links: StateFlow<TogetherLinks> = _links.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    private val _rejections = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val rejections: Flow<Unit> = _rejections.asSharedFlow()

    @Volatile
    override var answersStrangers: Boolean = false

    /** 最近见过的消息标识 (自己发的也记), 用来丢掉代理送回来的回声和几家代理各送一次的重复 */
    private val seen = LinkedHashSet<String>()

    /** 最近自己发出的消息标识, 认回执用 */
    private val sent = LinkedHashSet<String>()

    /** 这一分钟回过几张回执 */
    private var denialsWindowStart = 0L
    private var denialsInWindow = 0
    private val seenLock = Mutex()

    suspend fun run(): Nothing = coroutineScope {
        for (link in mqttLinks) launch { link.run() }
        combine(mqttLinks.map { it.state }) { states -> states.count { it == MqttLinkState.CONNECTED } }
            .collect { connected -> _links.value = TogetherLinks(connected, mqttLinks.size) }
        error("unreachable")
    }

    override suspend fun send(message: ByteArray): Boolean {
        val envelope = TogetherEnvelope.seal(key, message)
        val id = TogetherEnvelope.nonceOf(envelope)!!
        remember(id)
        seenLock.withLock {
            sent.add(id.toKey())
            if (sent.size > SENT_CAPACITY) sent.remove(sent.first())
        }
        return publishAll(envelope)
    }

    private suspend fun publishAll(frame: ByteArray): Boolean = coroutineScope {
        mqttLinks.map { link -> async { link.publish(frame) } }.awaitAll().any { it }
    }

    private suspend fun onEnvelope(envelope: ByteArray) {
        TogetherEnvelope.deniedNonceOf(envelope)?.let { denied ->
            // 几家代理各送一张: 只算第一张
            if (seenLock.withLock { sent.remove(denied.toKey()) }) _rejections.tryEmit(Unit)
            return
        }
        val id = TogetherEnvelope.nonceOf(envelope) ?: return
        if (seenLock.withLock { id.toKey() in seen }) return
        // 验过是这个房间的消息才记进去: 别人往话题里塞的乱码占不了位置
        val opened = TogetherEnvelope.open(key, envelope)
        if (opened == null) {
            if (answersStrangers && shouldDeny(id)) publishAll(TogetherEnvelope.deny(id))
            return
        }
        if (!remember(id)) return
        _incoming.tryEmit(opened.plaintext)
    }

    /** 这条解不开的消息要不要回: 同一条 (几家代理各送一次) 只回一次, 一分钟内回的张数有上限 */
    private suspend fun shouldDeny(id: ByteArray): Boolean = seenLock.withLock {
        if (!seen.add(id.toKey())) return@withLock false
        if (seen.size > SEEN_CAPACITY) seen.remove(seen.first())
        val now = nowMillis()
        if (now - denialsWindowStart >= 60_000L) {
            denialsWindowStart = now
            denialsInWindow = 0
        }
        if (denialsInWindow >= MAX_DENIALS_PER_MINUTE) return@withLock false
        denialsInWindow++
        true
    }

    /** @return 之前没见过 */
    private suspend fun remember(id: ByteArray): Boolean = seenLock.withLock {
        val added = seen.add(id.toKey())
        if (seen.size > SEEN_CAPACITY) seen.remove(seen.first())
        added
    }

    private fun ByteArray.toKey(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val SEEN_CAPACITY = 512
        const val SENT_CAPACITY = 64
        const val MAX_DENIALS_PER_MINUTE = 20
    }
}
