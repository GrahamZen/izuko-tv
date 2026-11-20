/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.diagnostics

import me.him188.ani.app.domain.foundation.diagnosis.AppNetworkState
import me.him188.ani.app.domain.foundation.diagnosis.DnsCheck
import me.him188.ani.app.domain.foundation.diagnosis.NetworkEnvironment
import me.him188.ani.app.domain.foundation.diagnosis.NetworkReport
import me.him188.ani.app.domain.foundation.diagnosis.ProbeResult
import me.him188.ani.app.domain.foundation.diagnosis.RouteCheck
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkAnalysisTest {
    private val names = listOf("trace", "subject", "characters", "staff", "cover")

    private fun ok(name: String, ms: Long = 400) = ProbeResult(name, "https://x/$name", "direct", status = 200, totalMs = ms)
    private fun timeout(name: String) =
        ProbeResult(name, "https://x/$name", "direct", error = "SocketTimeoutException: connect timed out", failedAt = "connect", totalMs = 6000)

    private fun route(route: String, kind: String, enabled: Boolean, requests: List<ProbeResult>, trace: Map<String, String> = emptyMap()) =
        RouteCheck(route, kind, enabled, requests, trace)

    private fun report(
        routes: List<RouteCheck>,
        environment: NetworkEnvironment = NetworkEnvironment(connected = true, validated = true, routes = routes.filter { it.enabled }.map { it.route }),
        dns: List<DnsCheck> = emptyList(),
        services: List<ProbeResult> = emptyList(),
        app: AppNetworkState = AppNetworkState(),
    ) = NetworkReport(environment, dns, routes, services, app, durationMs = 30_000)

    private fun texts(report: NetworkReport) = NetworkAnalysis.findings(report)

    @Test
    fun `everything answering is reported as normal`() {
        val findings = texts(report(listOf(route("origin", "origin", true, names.map { ok(it) }))))
        assertEquals(listOf(PerfFinding.Level.OK), findings.map { it.level })
    }

    @Test
    fun `enabled routes all down while a disabled one works is the headline`() {
        val findings = texts(
            report(
                listOf(
                    route("origin", "origin", true, listOf(timeout("trace"), timeout("subject"))),
                    route("ech", "ech", true, listOf(timeout("trace"), timeout("subject"))),
                    route("bangumi.lat", "mirror", false, names.map { ok(it) }),
                ),
            ),
        )
        assertEquals(PerfFinding.Level.BAD, findings.first().level)
        assertTrue(findings.any { it.level == PerfFinding.Level.BAD && "bangumi.lat" in it.text && "打开" in it.text }, findings.joinToString { it.text })
        assertTrue(findings.any { it.text.startsWith("官方直连连不上") && "连接失败" in it.text })
        assertTrue(findings.any { it.text.startsWith("加密直连连不上") })
    }

    @Test
    fun `origin listed first while blocked is pointed out when ech works`() {
        val findings = texts(
            report(
                listOf(
                    route("origin", "origin", true, listOf(timeout("trace"), timeout("subject"))),
                    route("ech", "ech", true, names.map { ok(it) }, trace = mapOf("sni" to "encrypted")),
                ),
            ),
        )
        assertTrue(findings.any { it.level == PerfFinding.Level.WARN && "排在第一条" in it.text })
        assertTrue(findings.any { it.level == PerfFinding.Level.OK && "sni=encrypted" in it.text })
    }

    @Test
    fun `single failing request on a working route and an expired login are listed`() {
        val requests = names.filter { it != "staff" }.map { ok(it) } + timeout("staff") +
            ProbeResult("me", "https://x/me", "ech", status = 401, totalMs = 300)
        val findings = texts(report(listOf(route("ech", "ech", true, requests))))
        assertTrue(findings.any { "「制作人员」失败" in it.text })
        assertTrue(findings.any { it.text.startsWith("登录已失效") })
    }

    @Test
    fun `slow routes polluted dns stuck queues and recent failures are warned about`() {
        val findings = texts(
            report(
                routes = listOf(route("ech", "ech", true, names.map { ok(it, ms = 4_500) })),
                dns = listOf(DnsCheck("izuko-tickets.grahamzen.workers.dev", system = listOf("108.160.165.147"), doh = listOf("104.21.6.61"))),
                services = listOf(
                    ProbeResult("tickets", "https://izuko-tickets.grahamzen.workers.dev/", "direct", error = "timeout", failedAt = "connect"),
                    ProbeResult("tickets", "https://izuko-tickets.grahamzen.workers.dev/", "ech", error = "timeout", failedAt = "tls"),
                ),
                app = AppNetworkState(
                    inFlight = listOf(AppNetworkState.HostInFlight("next.bgm.tv", 32, 210)),
                    recentFailures = listOf(AppNetworkState.RecentFailure(5, "ech", "next.bgm.tv", "/p1/subjects/{n}/characters", 30_000, "SocketTimeoutException")),
                    allFailedRecently = 120,
                ),
            ),
        )
        val all = findings.joinToString("\n") { it.text }
        assertTrue("很慢" in all, all)
        assertTrue("被污染" in all && "izuko-tickets.grahamzen.workers.dev" in all, all)
        assertTrue("故障反馈服务连不上" in all, all)
        assertTrue("32 个发往 next.bgm.tv" in all, all)
        assertTrue("120 个 Bangumi 请求所有线路都失败" in all && "next.bgm.tv/p1/subjects/{n}/characters" in all, all)
    }

    @Test
    fun `tmdb image hosts that fail are listed next to the ones that work`() {
        val findings = texts(
            report(
                routes = listOf(route("origin", "origin", true, names.map { ok(it) })),
                services = listOf(
                    ProbeResult("tmdb-image:images.tmdb.org", "u", "direct", status = 200, totalMs = 300),
                    ProbeResult("tmdb-image:image.tmdb.org", "u", "direct", error = "SSLException: Connection reset", failedAt = "tls"),
                ),
            ),
        )
        assertTrue(findings.any { "有的入口连不上：image.tmdb.org" in it.text && "能用的：images.tmdb.org" in it.text }, findings.joinToString { it.text })
    }

    @Test
    fun `a dns mismatch on a host that answers is not called polluted`() {
        // CDN 上的站按问的人在哪给不同节点: 系统 DNS 与加密 DNS 对不上, 但直连照样通
        val r = report(
            routes = listOf(route("origin", "origin", true, names.map { ok(it) })),
            dns = listOf(DnsCheck("api.github.com", system = listOf("140.82.112.5"), doh = listOf("140.82.114.5"))),
            services = listOf(ProbeResult("github", "https://api.github.com/repos/x/y/releases", "direct", status = 200, totalMs = 300)),
        )
        assertEquals(emptyList(), NetworkAnalysis.pollutedHosts(r))
        assertEquals(listOf(PerfFinding.Level.OK), texts(r).map { it.level })
    }

    @Test
    fun `a mirror that redirects everything does not count as working`() {
        val moved = names.map { if (it == "trace") ok(it) else ProbeResult(it, "https://x/$it", "direct", status = 301, totalMs = 300) }
        val findings = texts(
            report(
                listOf(
                    route("origin", "origin", true, listOf(timeout("trace"), timeout("subject"))),
                    route("bangumi.vip", "mirror", true, moved),
                ),
            ),
        )
        assertTrue(findings.any { it.level == PerfFinding.Level.INFO && "跳转" in it.text && "bangumi.vip" in it.text }, findings.joinToString { it.text })
        // 开着的两条都用不上: 官方连不上、镜像只跳转
        assertTrue(findings.any { it.text.startsWith("官方直连连不上") })
        assertTrue(findings.none { "排在第一条" in it.text })
    }

    @Test
    fun `a healthy network puts the all-good line first even with notes`() {
        val moved = names.map { ProbeResult(it, "https://x/$it", "direct", status = 301, totalMs = 300) }
        val findings = texts(
            report(listOf(route("origin", "origin", true, names.map { ok(it) }), route("bangumi.vip", "mirror", true, moved))),
        )
        assertTrue(findings.first().level == PerfFinding.Level.OK && findings.first().text.startsWith("网络正常"), findings.joinToString { it.text })
        assertTrue(findings.any { it.level == PerfFinding.Level.INFO && "bangumi.vip" in it.text })
    }

    @Test
    fun `no network and a skewed clock are bad`() {
        val findings = texts(
            report(
                routes = emptyList(),
                environment = NetworkEnvironment(connected = false, clockSkewSeconds = -3_600),
            ),
        )
        assertEquals(2, findings.count { it.level == PerfFinding.Level.BAD })
    }

    @Test
    fun `body keeps findings trigger labels and the raw report`() {
        val r = report(listOf(route("origin", "origin", true, names.map { ok(it) })), dns = listOf(DnsCheck("x", system = listOf("2001::1"))))
        val body = NetworkAnalysis.body(r, trigger = "test")
        assertEquals("test", body["trigger"]!!.jsonPrimitive.content)
        assertEquals("制作人员", body["labels"]!!.jsonObject["staff"]!!.jsonPrimitive.content)
        assertEquals("官方直连", body["labels"]!!.jsonObject["route:origin"]!!.jsonPrimitive.content)
        assertEquals(listOf("x"), body["suspiciousDns"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(30_000, body["network"]!!.jsonObject["durationMs"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["findings"]!!.jsonArray.isNotEmpty())
    }
}
