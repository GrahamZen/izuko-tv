/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.domain.together.TogetherChannelFactory
import me.him188.ani.app.domain.together.TogetherConnection
import me.him188.ani.app.domain.together.TogetherRoomId
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.together.TvTogetherEntry
import me.him188.ani.utils.platform.currentTimeMillis
import org.koin.mp.KoinPlatform
import kotlin.time.Duration.Companion.seconds

/**
 * 一起看通道的调试接口 (只在有调试区的包上, 由 [RemoteDebugActions] 转进来): 不经播放器, 直接进一个房间收发文本,
 * 用来在设备上验通道本身 (连不连得上各家代理、加密、断线重连、遗嘱)。
 *
 * - `POST api/debug/together/join` `number=<房间号> password=<密码> [farewell=<文本>]`: 进房间; 已经在别的房间时先离开。
 *   `farewell` 是留给代理的遗嘱。
 * - `POST api/debug/together/send` `text=<文本>`: 发给房间里其他人。
 * - `GET api/debug/together/state`: 连着几家代理、收到过的消息 (最近 [MAX_RECEIVED] 条, `agoMs` 是多久之前收到的)。
 * - `POST api/debug/together/leave`: 离开。
 * - `POST api/debug/together/dialog`: 打开电视上的「一起看」弹窗 (与上面几条无关, 走查与设备测试用)。
 */
internal object RemoteDebugTogether {
    private const val MAX_RECEIVED = 100
    private val factory: TogetherChannelFactory get() = KoinPlatform.getKoin().get()

    private class Room(
        val id: TogetherRoomId,
        val connection: TogetherConnection,
        val collector: Job,
        /** (收到的时刻, 文本); 读写都锁它自己 */
        val received: ArrayDeque<Pair<Long, String>>,
    )

    private var room: Room? = null

    @Synchronized
    fun handle(request: LanHttpRequest, scope: CoroutineScope): JsonObject? {
        val post = request.method == "POST"
        return when (request.path) {
            "api/debug/together/join" -> if (post) join(request.formFields(), scope) else null
            "api/debug/together/send" -> if (post) send(request.formFields()["text"].orEmpty()) else null
            "api/debug/together/leave" -> if (post) leave() else null
            "api/debug/together/state" -> state()
            "api/debug/together/dialog" -> if (post) openDialog() else null
            else -> null
        }
    }

    /** 打开电视上的「一起看」弹窗 (走查与设备测试用, 不用从动作面板一步步按过去) */
    private fun openDialog(): JsonObject {
        TvTogetherEntry.open()
        return result(true, "opened")
    }

    private fun join(form: Map<String, String>, scope: CoroutineScope): JsonObject {
        val id = TogetherRoomId.of(form["number"].orEmpty(), form["password"].orEmpty())
            ?: return result(false, "number (8 digits) and password (6 digits) are required")
        leave()
        val farewell = form["farewell"]?.takeIf { it.isNotEmpty() }
        val connection = runBlocking { factory.connect(id, scope, farewell = { farewell?.encodeToByteArray() }) }
        val received = ArrayDeque<Pair<Long, String>>()
        val collector = scope.launch {
            connection.channel.incoming.collect { message ->
                synchronized(received) {
                    received.addLast(currentTimeMillis() to message.decodeToString())
                    while (received.size > MAX_RECEIVED) received.removeFirst()
                }
            }
        }
        room = Room(id, connection, collector, received)
        return state()
    }

    private fun send(text: String): JsonObject {
        val current = room ?: return result(false, "not in a room")
        val sent = runBlocking { withTimeoutOrNull(5.seconds) { current.connection.channel.send(text.encodeToByteArray()) } }
        return result(sent == true, if (sent == true) "sent" else "no broker connected")
    }

    private fun leave(): JsonObject {
        val current = room ?: return result(true, "not in a room")
        room = null
        current.collector.cancel()
        runBlocking { withTimeoutOrNull(5.seconds) { current.connection.close() } }
        return result(true, "left")
    }

    private fun state(): JsonObject = buildJsonObject {
        val current = room
        put("ok", true)
        put("joined", current != null)
        if (current == null) return@buildJsonObject
        put("number", current.id.number)
        val links = current.connection.channel.links.value
        putJsonObject("links") {
            put("connected", links.connected)
            put("total", links.total)
        }
        val now = currentTimeMillis()
        putJsonArray("received") {
            synchronized(current.received) {
                for ((at, text) in current.received) {
                    addJsonObject {
                        put("text", text)
                        put("agoMs", now - at)
                    }
                }
            }
        }
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }
}
