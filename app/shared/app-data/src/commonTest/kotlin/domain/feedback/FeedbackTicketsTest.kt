/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.feedback

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.data.models.feedback.FeedbackDevice
import me.him188.ani.app.data.models.feedback.FeedbackKind
import me.him188.ani.app.data.models.feedback.FeedbackLogSpec
import me.him188.ani.app.data.models.feedback.FeedbackPhoto
import me.him188.ani.app.data.models.feedback.FeedbackRecord
import me.him188.ani.app.data.models.feedback.FeedbackRecords
import me.him188.ani.app.data.models.feedback.FeedbackReply
import me.him188.ani.app.data.models.feedback.FeedbackState
import me.him188.ani.app.data.models.feedback.FeedbackWindow
import me.him188.ani.app.data.network.FeedbackTicketService
import me.him188.ani.app.data.network.FeedbackTicketService.CreateResult
import me.him188.ani.app.data.network.FeedbackTicketService.Outcome
import me.him188.ani.app.data.network.SubjectFeedbackService.IssueStatus
import me.him188.ani.app.data.repository.feedback.FeedbackRecordRepository
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.feedback.FeedbackTickets.SubmitResult
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 故障工单: 草稿里攒附件 (现场录制、性能报告、照片) → 开单 / 补充 → 后台逐个传附件, 查处理结果合进本机记录. 工单服务用假的 (按路径回). */
class FeedbackTicketsTest {
    private companion object {
        const val START = 1_760_533_200_000L // 2025-10-15 21:00:00 +08:00
        const val DESCRIPTION = "打开 4K 片源播放十分钟左右画面变黑, 声音还在"
        val JPEG = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1)
    }

    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    private class Harness(scope: TestScope) {
        var now = START

        /** 按 (方法, 路径) 给 (状态码, 回应体). */
        var replies: (method: HttpMethod, path: String) -> Pair<Int, String> = { method, path ->
            when {
                method == HttpMethod.Post && path == "/tickets" -> 200 to """{"issue": 7, "token": "abc"}"""
                path.endsWith("/reply") -> 200 to """{"ok": true, "prefix": "r1"}"""
                else -> 200 to "{}"
            }
        }
        val requests = mutableListOf<Triple<HttpMethod, String, String>>()

        /** 每个请求的 X-Izuko-Ticket 头. */
        val tokens = mutableListOf<String?>()
        val settings = MemorySettings(FeedbackRecords.Empty)

        /** 修正请求的处理结果 (中转的回答); 默认都还开着. */
        var subjectStatuses: (List<Int>) -> List<IssueStatus>? = { ids -> ids.map { IssueStatus(it, state = "open") } }

        private val client = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(scope.testScheduler)
                addHandler { request ->
                    val body = when (val content = request.body) {
                        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
                        else -> ""
                    }
                    requests += Triple(request.method, request.url.encodedPath, body)
                    tokens += request.headers["X-Izuko-Ticket"]
                    val (status, text) = replies(request.method, request.url.encodedPath)
                    respond(text, HttpStatusCode.fromValue(status))
                }
            }
        }

        val tickets = FeedbackTickets(
            FeedbackRecordRepository(settings),
            FeedbackTicketService({ client.asScopedHttpClient() }, endpoints = { listOf("https://tickets.example.com") }),
            FeedbackDevice("0123456789abcdef", mapOf("机型" to "Test TV")),
            scope,
            clock = { now },
            timeZone = { TimeZone.of("Asia/Shanghai") },
            subjectStatus = { subjectStatuses(it) },
        )

        /** 打包过的日志 (按什么打的). */
        val logs = mutableListOf<FeedbackLogSpec>()

        /** 删掉的照片. */
        val deleted = mutableListOf<String>()
        val sources = object : FeedbackTickets.Sources {
            override fun perfRow(id: String) = "设备体检 $id" to "存储快满了"
            override suspend fun buildLog(spec: FeedbackLogSpec, header: List<Pair<String, String>>): ByteArray {
                logs += spec
                return byteArrayOf(0x1f, 0x8b.toByte(), 1, 2)
            }

            override suspend fun photo(id: String) = JPEG
            override fun deletePhoto(id: String) {
                deleted += id
            }
        }

        /** 给 [target] 现场录一段: 开始, 过 [minutes] 分钟结束. */
        suspend fun recordFor(target: Int, minutes: Int) {
            assertTrue(tickets.startRecording(target))
            now += minutes * 60_000L
            tickets.stopRecording()
        }

        fun ticket(issue: Int) = settings.state.value.items.single { it.issue == issue }

        fun draft(target: Int) = settings.state.value.draft(target)

        fun bodyOf(index: Int): JsonObject = Json.parseToJsonElement(requests[index].third).jsonObject

        fun puts() = requests.filter { it.first == HttpMethod.Put }.map { it.second }
    }

    @Test
    fun `新工单要带现场录制 — 没录、还在录都不开单`() = runTest {
        val h = Harness(this)
        assertEquals(SubmitResult.NoRecording, h.tickets.submit("播放黑屏了", DESCRIPTION, h.sources))
        h.tickets.startRecording(FeedbackTickets.NEW)
        assertEquals(SubmitResult.StillRecording, h.tickets.submit("播放黑屏了", DESCRIPTION, h.sources))
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun `现场录制 — 同时只有一段，录满上限按到点结束`() = runTest {
        val h = Harness(this)
        assertTrue(h.tickets.startRecording(FeedbackTickets.NEW))
        assertFalse(h.tickets.startRecording(5))
        h.now += 7 * 60_000
        h.tickets.expireRecording()
        assertNull(h.settings.state.value.recording)
        assertEquals(FeedbackWindow(START, START + FeedbackTickets.MAX_RECORDING.inWholeMilliseconds), h.draft(FeedbackTickets.NEW).recorded)
        // 重新录: 录好的那段作废
        h.tickets.startRecording(FeedbackTickets.NEW)
        assertNull(h.draft(FeedbackTickets.NEW).recorded)
    }

    @Test
    fun `开新工单 — 声明附件，时间段含日志往前多截的，草稿清掉，附件在后台逐个传`() = runTest {
        val h = Harness(this)
        h.recordFor(FeedbackTickets.NEW, 3)
        h.tickets.setLogLead(FeedbackTickets.NEW, 1)
        h.tickets.attachPerf(FeedbackTickets.NEW, "20251015-205000-health", true)
        assertTrue(h.tickets.addPhoto(FeedbackTickets.NEW, FeedbackPhoto("p1", 1234, "jpg")))
        h.now += 10 * 60_000

        assertEquals(SubmitResult.Submitted(7), h.tickets.submit("播放黑屏了", DESCRIPTION, h.sources))
        val body = h.bodyOf(0)
        assertEquals("2025-10-15T20:59:00+08:00", body["window"]!!.jsonObject["from"]!!.jsonPrimitive.content)
        assertEquals("2025-10-15T21:03:00+08:00", body["window"]!!.jsonObject["to"]!!.jsonPrimitive.content)
        assertEquals(listOf("log.gz", "photo-1.jpg"), body["files"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals("存储快满了", body["perf"]!!.jsonObject["设备体检 20251015-205000-health"]!!.jsonPrimitive.content)
        assertEquals("Test TV", body["env"]!!.jsonObject["机型"]!!.jsonPrimitive.content)
        assertEquals(FeedbackRecords.Empty.drafts, h.settings.state.value.drafts)
        with(h.ticket(7)) {
            assertEquals(DESCRIPTION, description)
            assertEquals(listOf("log.gz", "photo-1.jpg"), files)
            assertEquals("abc", token)
        }

        advanceUntilIdle()
        assertEquals(listOf("/tickets/7/files/log.gz", "/tickets/7/files/photo-1.jpg"), h.puts())
        assertTrue(h.tokens.drop(1).all { it == "abc" })
        assertEquals(listOf(FeedbackLogSpec(FeedbackWindow(START, START + 3 * 60_000), 1, listOf("20251015-205000-health"))), h.logs)
        assertEquals(emptyList(), h.ticket(7).uploads)
        assertEquals(listOf("p1"), h.deleted)
    }

    @Test
    fun `附件没传上去 — 留在队列里，重传后清掉`() = runTest {
        val h = Harness(this)
        var status = 502
        h.replies = { method, path ->
            when {
                method == HttpMethod.Post && path == "/tickets" -> 200 to """{"issue": 7, "token": "abc"}"""
                else -> status to "{}"
            }
        }
        h.recordFor(FeedbackTickets.NEW, 1)
        h.tickets.submit("播放黑屏了", DESCRIPTION, h.sources)
        advanceUntilIdle()
        assertEquals(listOf("log.gz"), h.ticket(7).uploads.map { it.name })

        status = 200
        assertTrue(h.tickets.retryUploads(7, h.sources))
        advanceUntilIdle()
        assertEquals(emptyList(), h.ticket(7).uploads)
        assertFalse(h.tickets.retryUploads(7, h.sources))
    }

    @Test
    fun `附件被限频 — 隔一会儿自动再传`() = runTest {
        val h = Harness(this)
        var first = true
        h.replies = { method, path ->
            when {
                method == HttpMethod.Post && path == "/tickets" -> 200 to """{"issue": 7, "token": "abc"}"""
                else -> (if (first) 429 else 200).also { first = false } to "{}"
            }
        }
        h.recordFor(FeedbackTickets.NEW, 1)
        h.tickets.submit("播放黑屏了", DESCRIPTION, h.sources)
        advanceUntilIdle()
        assertEquals(emptyList(), h.ticket(7).uploads)
        assertEquals(2, h.puts().size)
    }

    @Test
    fun `开着的单太多 — 不开单，录好的还在`() = runTest {
        val h = Harness(this)
        h.replies = { _, _ -> 429 to """{"error": "too_many_open", "issues": [3, 4]}""" }
        h.recordFor(FeedbackTickets.NEW, 1)
        val result = h.tickets.submit("播放黑屏了", DESCRIPTION, h.sources)
        assertEquals(SubmitResult.NotCreated(CreateResult.TooManyOpen(listOf(3, 4))), result)
        assertEquals(FeedbackWindow(START, START + 60_000), h.draft(FeedbackTickets.NEW).recorded)
        assertTrue(h.settings.state.value.items.isEmpty())
    }

    @Test
    fun `照片 — 一条最多 4 张`() = runTest {
        val h = Harness(this)
        repeat(FeedbackTickets.MAX_PHOTOS) { assertTrue(h.tickets.addPhoto(3, FeedbackPhoto("p$it", 10, "jpg"))) }
        assertFalse(h.tickets.addPhoto(3, FeedbackPhoto("p9", 10, "jpg")))
        h.tickets.removePhoto(3, "p0")
        assertEquals(listOf("p1", "p2", "p3"), h.draft(3).photos.map { it.id })
    }

    private fun ticket(issue: Int, state: FeedbackState = FeedbackState.OPEN, since: String? = null) = FeedbackRecord(
        kind = FeedbackKind.TICKET,
        issue = issue,
        summary = "问题 $issue",
        submittedAt = 0,
        state = state,
        token = "t$issue",
        remoteUpdated = since,
    )

    @Test
    fun `补充 — 只有文字时不带附件，带照片时名字加服务给的前缀，记在本机`() = runTest {
        val h = Harness(this)
        h.settings.state.value = FeedbackRecords(listOf(ticket(7)))
        assertEquals(SubmitResult.Empty, h.tickets.reply(7, " ", h.sources))
        assertEquals(SubmitResult.Submitted(7), h.tickets.reply(7, "装了测试包还是黑屏", h.sources))
        assertEquals(emptyList(), h.bodyOf(0)["files"]!!.jsonArray)

        h.tickets.addPhoto(7, FeedbackPhoto("p1", 10, "jpg"))
        assertEquals(SubmitResult.Submitted(7), h.tickets.reply(7, "", h.sources))
        advanceUntilIdle()
        assertEquals(listOf("/tickets/7/files/r1-photo-1.jpg"), h.puts())
        assertEquals(
            listOf(
                FeedbackReply(false, "2025-10-15T21:00:00+08:00", "装了测试包还是黑屏"),
                FeedbackReply(false, "2025-10-15T21:00:00+08:00", "", listOf("r1-photo-1.jpg")),
            ),
            h.ticket(7).replies,
        )
        assertTrue(h.draft(7).isEmpty)
    }

    @Test
    fun `补充 — 单已经关了时提示，下次查拿全量`() = runTest {
        val h = Harness(this)
        h.settings.state.value = FeedbackRecords(listOf(ticket(2, since = "x")))
        h.replies = { _, _ -> 409 to """{"error": "closed"}""" }
        assertEquals(SubmitResult.NotSent(Outcome.CONFLICT), h.tickets.reply(2, "再补一句", h.sources))
        assertNull(h.ticket(2).remoteUpdated)
    }

    @Test
    fun `查结果 — 只查开着的单，带上次的更新时间，新回复与关单标成未读`() = runTest {
        val h = Harness(this)
        h.settings.state.value = FeedbackRecords(
            listOf(
                ticket(1, since = "2025-10-15T10:00:00Z"),
                ticket(2),
                ticket(3, state = FeedbackState.ACCEPTED),
                FeedbackRecord(FeedbackKind.LOGO, 4, submittedAt = 0),
            ),
        )
        h.replies = { _, _ ->
            200 to """{"tickets": [
                {"issue": 1, "state": "open", "reason": null, "updated": "2025-10-15T10:00:00Z", "unchanged": true},
                {"issue": 2, "state": "closed", "reason": "completed", "updated": "2025-10-16T09:00:00Z",
                 "replies": [{"from": "maintainer", "at": "2025-10-16T08:00:00Z", "body": "装这个试试 https://x.example.com/a.apk"},
                             {"from": "user", "at": "2025-10-16T08:30:00Z", "body": "好了", "files": ["r1-log.gz"]}],
                 "apks": ["https://x.example.com/a.apk"], "digests": {"https://x.example.com/a.apk": "abc123"}}
            ]}"""
        }
        assertTrue(h.tickets.sync())
        // 凭证不进地址 (地址会进日志)
        assertEquals("/tickets/status", h.requests.single().second)
        assertEquals(
            """{"tickets":[{"issue":1,"token":"t1","since":"2025-10-15T10:00:00Z"},{"issue":2,"token":"t2"}]}""",
            h.requests.single().third,
        )

        assertEquals(FeedbackState.OPEN, h.ticket(1).state)
        assertFalse(h.ticket(1).unread)
        with(h.ticket(2)) {
            assertEquals(FeedbackState.ACCEPTED, state)
            assertTrue(unread)
            assertEquals("2025-10-16T09:00:00Z", remoteUpdated)
            assertEquals(
                listOf(
                    FeedbackReply(true, "2025-10-16T08:00:00Z", "装这个试试 https://x.example.com/a.apk"),
                    FeedbackReply(false, "2025-10-16T08:30:00Z", "好了", listOf("r1-log.gz")),
                ),
                replies,
            )
            assertEquals(listOf("https://x.example.com/a.apk"), apks)
            assertEquals(mapOf("https://x.example.com/a.apk" to "abc123"), apkSha256)
        }
        assertEquals(h.now, h.settings.state.value.ticketsCheckedAt)

        h.tickets.markRead()
        assertFalse(h.ticket(2).unread)
    }

    @Test
    fun `查结果 — 单不在了当作结束，这次没查成的下次再查`() = runTest {
        val h = Harness(this)
        h.settings.state.value = FeedbackRecords(listOf(ticket(1), ticket(2)))
        h.replies = { _, _ -> 200 to """{"tickets": [{"issue": 1, "error": "not_found"}, {"issue": 2, "error": "upstream"}]}""" }
        assertTrue(h.tickets.sync())
        assertEquals(FeedbackState.CLOSED, h.ticket(1).state)
        assertTrue(h.ticket(1).unread)
        assertEquals(ticket(2), h.ticket(2))
    }

    @Test
    fun `删记录 — 只删已结束的，连同那张单的补充草稿`() = runTest {
        val h = Harness(this)
        h.settings.state.value = FeedbackRecords(listOf(ticket(1), ticket(2, FeedbackState.CLOSED)))
        assertTrue(h.tickets.addPhoto(2, FeedbackPhoto("p2", 10, "jpg")))
        assertFalse(h.tickets.delete(FeedbackKind.TICKET, 1))
        assertFalse(h.tickets.delete(FeedbackKind.LOGO, 2))
        assertTrue(h.tickets.delete(FeedbackKind.TICKET, 2))
        assertEquals(listOf(1), h.settings.state.value.items.map { it.issue })
        assertTrue(h.draft(2).isEmpty)
    }

    @Test
    fun `查结果 — 标题 logo 与对应作品修正经中转查，记下采纳、不采纳的理由`() = runTest {
        val h = Harness(this)
        h.settings.state.value = FeedbackRecords(
            listOf(
                FeedbackRecord(FeedbackKind.LOGO, 65, submittedAt = 0),
                FeedbackRecord(FeedbackKind.ENTRY, 70, submittedAt = 0),
                FeedbackRecord(FeedbackKind.LOGO, 71, submittedAt = 0),
                FeedbackRecord(FeedbackKind.LOGO, 72, submittedAt = 0),
                FeedbackRecord(FeedbackKind.LOGO, 73, submittedAt = 0, state = FeedbackState.ACCEPTED),
            ),
        )
        val asked = mutableListOf<List<Int>>()
        h.subjectStatuses = { ids ->
            asked += ids
            listOf(
                IssueStatus(65, "closed", "completed"),
                IssueStatus(70, "closed", "not_planned", note = "这张 logo 是另一季的", noteAt = "2025-10-16T06:20:00Z"),
                IssueStatus(71, "open"),
                IssueStatus(72, error = "upstream"),
            )
        }
        assertTrue(h.tickets.sync())
        // 只问开着的; 没有工单就不找工单服务
        assertEquals(listOf(listOf(65, 70, 71, 72)), asked)
        assertTrue(h.requests.isEmpty())
        assertEquals(FeedbackState.ACCEPTED, h.ticket(65).state)
        assertTrue(h.ticket(65).unread)
        with(h.ticket(70)) {
            assertEquals(FeedbackState.REJECTED, state)
            assertEquals(listOf(FeedbackReply(true, "2025-10-16T06:20:00Z", "这张 logo 是另一季的")), replies)
            assertTrue(unread)
        }
        assertEquals(FeedbackState.OPEN, h.ticket(71).state)
        assertFalse(h.ticket(71).unread)
        assertEquals(FeedbackRecord(FeedbackKind.LOGO, 72, submittedAt = 0), h.ticket(72))
    }

    @Test
    fun `查结果 — 修正请求的中转连不上时不动记录`() = runTest {
        val h = Harness(this)
        val records = FeedbackRecords(listOf(FeedbackRecord(FeedbackKind.LOGO, 65, submittedAt = 0)))
        h.settings.state.value = records
        h.subjectStatuses = { null }
        assertFalse(h.tickets.sync())
        assertEquals(records, h.settings.state.value)
    }

    @Test
    fun `查结果 — 连不上时不动记录`() = runTest {
        val h = Harness(this)
        h.settings.state.value = FeedbackRecords(listOf(ticket(1)))
        h.replies = { _, _ -> 502 to "{}" }
        assertFalse(h.tickets.sync())
        assertEquals(FeedbackRecords(listOf(ticket(1))), h.settings.state.value)
    }
}
