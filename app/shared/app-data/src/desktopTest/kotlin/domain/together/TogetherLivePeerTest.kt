/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.him188.ani.utils.ktor.asScopedHttpClient
import me.him188.ani.utils.ktor.createDefaultHttpClient
import java.io.File
import kotlin.test.Test

/**
 * 给设备对测用的桌面对端 (平时不跑): 用真实的公共代理进一个房间, 按一个命令文件里陆续追加的命令扮演房主或成员, 把收到的房间快照打出来。
 * 它没有播放器, 「播放状态」是自己按时钟推的。
 *
 * 环境变量:
 * - `IZUKO_TOGETHER_PEER_ROOM`: 房间号与密码, 如 `28689131 482193` (不设就不跑)
 * - `IZUKO_TOGETHER_PEER_HOST=1`: 开这个房间当房主; 不设时加入, 当成员
 * - `IZUKO_TOGETHER_PEER_COMMANDS`: 命令文件; 每秒读一次新追加的行
 * - `IZUKO_TOGETHER_PEER_SECONDS`: 最多跑多久, 默认 300
 * - `IZUKO_TOGETHER_PEER_EXTRA`: 另外再带几个只占位的成员 (`Desktop 1`、`Desktop 2`……, 不发命令), 给成员多的界面用; 默认 0
 *
 * 命令 (一行一条):
 * - `watch <条目 id> <分集 id> <集序号> <位置毫秒>`: 开始播这一集
 * - `pause` / `play` / `seek <位置毫秒>` / `speed <倍速>` / `idle` (退出播放页)
 * - `follow 0|1` / `quit` (离开房间并结束)
 */
class TogetherLivePeerTest {
    @Test
    fun `plays a scripted peer in a room`() = runBlocking {
        val room = System.getenv("IZUKO_TOGETHER_PEER_ROOM")?.let { TogetherInvite.parse(it) } ?: return@runBlocking
        val asHost = System.getenv("IZUKO_TOGETHER_PEER_HOST") == "1"
        val commands = System.getenv("IZUKO_TOGETHER_PEER_COMMANDS")?.let(::File)
        val seconds = System.getenv("IZUKO_TOGETHER_PEER_SECONDS")?.toIntOrNull() ?: 300
        val extra = System.getenv("IZUKO_TOGETHER_PEER_EXTRA")?.toIntOrNull() ?: 0
        val client = createDefaultHttpClient(bare = true) { install(WebSockets) }
        try {
            val gateway = ChannelTogetherRoomGateway(TogetherChannelFactory(client = { client.asScopedHttpClient() }), scope = this)
            var following = true
            var watching: TogetherWatchingInfo? = null
            val joined = gateway.join(room, memberId = "desktop-peer", self = TogetherSelfReport("Desktop", following = following), asHost = asHost)
            println("[peer] 进了房间 ${room.number}, ${if (joined.isHost) "是房主" else "是成员"}; ${describe(joined.initialSnapshot, joined.clock.now())}")
            val extras = (1..extra).map { i ->
                gateway.join(room, memberId = "desktop-peer-$i", self = TogetherSelfReport("Desktop $i", following = true), asHost = false)
            }
            if (extras.isNotEmpty()) println("[peer] 另外 ${extras.size} 个占位成员进了房间")
            val printer = launch {
                joined.events.collect { event ->
                    when (event) {
                        is TogetherRoomEvent.Snapshot -> println("[peer] 快照 ${describe(event.snapshot, joined.clock.now())}")
                        is TogetherRoomEvent.Connectivity -> println("[peer] 连接 ${if (event.online) "连上" else "断开"}")
                        TogetherRoomEvent.HostChanged -> println("[peer] 让位给另一个房主")
                        TogetherRoomEvent.Rejected -> println("[peer] 房主说密码不对")
                    }
                }
                println("[peer] 房间结束")
            }

            suspend fun report() {
                joined.report(TogetherSelfReport("Desktop", following = following, watching = watching))
            }

            fun now() = joined.clock.now()
            var linesRead = 0
            var quit = false
            repeat(seconds) {
                if (quit) return@repeat
                val lines = commands?.takeIf { it.exists() }?.readLines().orEmpty()
                for (line in lines.drop(linesRead)) {
                    val parts = line.trim().split(Regex("\\s+"))
                    println("[peer] 命令: $line")
                    when (parts[0]) {
                        "watch" -> watching = TogetherWatchingInfo(
                            subjectId = parts[1].toInt(), episodeId = parts[2].toInt(), subjectName = "desktop subject",
                            episodeSort = parts[3], episodeName = "desktop episode",
                            positionMillis = parts[4].toLong(), positionAtMillis = now(), durationMillis = 1_440_000, paused = false,
                        )

                        "pause" -> watching = watching?.let { it.copy(positionMillis = it.positionAt(now()), positionAtMillis = now(), paused = true) }
                        "play" -> watching = watching?.let { it.copy(positionMillis = it.positionAt(now()), positionAtMillis = now(), paused = false) }
                        "seek" -> watching = watching?.copy(positionMillis = parts[1].toLong(), positionAtMillis = now())
                        "speed" -> watching = watching?.let {
                            it.copy(positionMillis = it.positionAt(now()), positionAtMillis = now(), playbackRate = parts[1].toFloat())
                        }

                        "idle" -> watching = null
                        "follow" -> following = parts[1] == "1"
                        "quit" -> quit = true
                        else -> println("[peer] 不认识的命令")
                    }
                    if (!quit) report()
                }
                linesRead = lines.size
                delay(1000)
            }
            extras.forEach { it.leave() }
            joined.leave()
            printer.cancelAndJoin()
        } finally {
            client.close()
        }
    }

    private fun describe(snapshot: TogetherRoomSnapshot, roomNow: Long): String {
        val members = snapshot.members.joinToString { member ->
            buildString {
                append(member.nickname.ifEmpty { member.memberId.take(4) })
                if (member.isHost) append("(房主)")
                append(' ').append(member.state.name.lowercase())
                if (!member.following && !member.isHost) append(" 自由观看")
                member.watching?.let { append(" ").append(describe(it, roomNow)) }
            }
        }
        val playback = snapshot.playback?.info?.let { describe(it, roomNow) } ?: "房主没在播"
        return "v${snapshot.version % 100000} ${snapshot.status} | 房主: $playback | 成员: $members"
    }

    private fun describe(info: TogetherWatchingInfo, roomNow: Long): String = buildString {
        append("条目 ").append(info.subjectId).append(" 分集 ").append(info.episodeId)
        append(" 位置 ").append(info.positionMillis / 1000).append("s→现在 ").append(info.positionAt(roomNow) / 1000).append("s")
        if (info.paused) append(" 暂停")
        if (info.buffering) append(" 缓冲")
        if (info.loading) append(" 加载")
        if (info.playbackRate != 1f) append(" x").append(info.playbackRate)
    }
}
