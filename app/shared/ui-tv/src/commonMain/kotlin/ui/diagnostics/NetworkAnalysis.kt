/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.domain.foundation.diagnosis.NetworkReport
import me.him188.ani.app.domain.foundation.diagnosis.ProbeResult
import me.him188.ani.app.domain.foundation.diagnosis.RouteCheck
import me.him188.ani.app.ui.remote.tr
import java.util.Locale
import kotlin.math.abs

/**
 * 网络体检 ([TvNetworkDiagnostics]) 的结论: 从 [NetworkReport] 里挑出能说明问题的几句, 按严重程度排, 第一句当标题.
 * 纯逻辑, 单测见 `NetworkAnalysisTest`.
 */
internal object NetworkAnalysis {
    /** 一个请求中位耗时超过这么多毫秒算慢. */
    private const val SLOW_MEDIAN_MS = 3_000L

    /** 时钟差这么多秒以上, 加密连接 (证书有效期) 会出问题. */
    private const val CLOCK_SKEW_SECONDS = 300L

    /** 应用里同一个域名在途这么多个, 或最久的等了这么多秒, 算在排队. */
    private const val QUEUE_COUNT = 10
    private const val QUEUE_OLDEST_SECONDS = 60L

    fun findings(report: NetworkReport): List<PerfFinding> {
        val out = ArrayList<PerfFinding>()
        val env = report.environment
        if (!env.connected) {
            out += PerfFinding(PerfFinding.Level.BAD, tr("电视当前没有连上网络。"))
        } else if (env.validated == false) {
            out += PerfFinding(PerfFinding.Level.WARN, tr("系统检测这个网络上不了外网（可能要先在网页上登录，或者网络本身断了）。"))
        }
        env.clockSkewSeconds?.takeIf { abs(it) >= CLOCK_SKEW_SECONDS }?.let {
            out += PerfFinding(PerfFinding.Level.BAD, tr("电视的时间与服务器差 {0} 分钟：加密连接会失败。到系统设置里把时间改成自动校准。", abs(it) / 60))
        }

        routeFindings(report, out)
        echAddressFindings(report, out)

        val poisoned = pollutedHosts(report)
        if (poisoned.isNotEmpty()) {
            out += PerfFinding(
                PerfFinding.Level.WARN,
                tr("这些域名的系统 DNS 回答像是被污染了（是 2001:: 开头的假地址，或与加密 DNS 的回答对不上而且直连连不上）：{0}", poisoned.joinToString("、")),
            )
        }

        serviceFindings(report, out)
        listFindings(report, out)
        appFindings(report, out)

        val sorted = out.sortedBy { it.level.ordinal }
        // 没有问题时「网络正常」排第一 (它是报告列表里显示的标题), 提示性的几句跟在后面
        return if (sorted.none { it.level == PerfFinding.Level.BAD || it.level == PerfFinding.Level.WARN }) {
            listOf(PerfFinding(PerfFinding.Level.OK, tr("网络正常：Bangumi 与各项服务都连得上。"))) + sorted
        } else {
            sorted
        }
    }

    private fun routeFindings(report: NetworkReport, out: MutableList<PerfFinding>) {
        val working = report.routes.filter { routeWorks(it) }
        for (route in report.routes) {
            val name = routeLabel(route.route)
            val sent = route.requests.filter { !it.skipped }
            val answered = sent.filter { it.answered }
            when {
                sent.isEmpty() -> Unit
                answered.isEmpty() -> {
                    val level = if (route.enabled) PerfFinding.Level.BAD else PerfFinding.Level.INFO
                    val first = sent.first()
                    out += PerfFinding(
                        level,
                        if (route.enabled) tr("{0}连不上：{1}", name, failureText(first)) else tr("{0}（设置里没开）连不上：{1}", name, failureText(first)),
                    )
                }

                answered.filter { it.name != "trace" }.let { api -> api.isNotEmpty() && api.all { it.redirected } } -> {
                    // 镜像搬家后旧域名整个跳到新域名: 应用不跟 API 的跳转 (跟过去会丢令牌), 这条线路等于用不上
                    val status = answered.first { it.name != "trace" }.status
                    out += PerfFinding(PerfFinding.Level.INFO, tr("{0}的请求都被跳转到了别的地址（HTTP {1}）：这条线路用不上，多半是搬家了。", name, status))
                }

                else -> {
                    for (r in sent.filter { !it.answered }) {
                        out += PerfFinding(PerfFinding.Level.WARN, tr("{0}上「{1}」失败：{2}", name, requestLabel(r.name), failureText(r)))
                    }
                    for (r in answered.filter { (it.status ?: 0) >= 500 }) {
                        out += PerfFinding(PerfFinding.Level.WARN, tr("{0}上「{1}」服务端出错（HTTP {2}）。", name, requestLabel(r.name), r.status))
                    }
                    answered.firstOrNull { it.name == "me" && it.status == 401 }?.let {
                        out += PerfFinding(PerfFinding.Level.WARN, tr("登录已失效：带令牌的请求经{0}返回 401，需要重新登录。", name))
                    }
                    val median = answered.mapNotNull { it.totalMs }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
                    if (median != null && median >= SLOW_MEDIAN_MS) {
                        out += PerfFinding(PerfFinding.Level.WARN, tr("{0}很慢：一个请求中位要 {1} 秒。", name, formatSeconds(median)))
                    }
                }
            }
            if (route.kind == "ech" && answered.isNotEmpty()) {
                when (route.trace["sni"]) {
                    "encrypted" -> out += PerfFinding(PerfFinding.Level.OK, tr("加密直连可用，确实用上了加密握手（sni=encrypted）。"))
                    null -> Unit
                    else -> out += PerfFinding(PerfFinding.Level.WARN, tr("加密直连连上了，但没用上加密握手（sni={0}）。", route.trace["sni"]))
                }
            }
        }
        val enabled = report.routes.filter { it.enabled }
        if (enabled.isNotEmpty() && enabled.none { routeWorks(it) }) {
            working.firstOrNull()?.let {
                out += PerfFinding(
                    PerfFinding.Level.BAD,
                    tr("现在开着的线路都连不上，而{0}能用：到「设置 → 代理 → Bangumi 连接方式」把它打开。", routeLabel(it.route)),
                )
            }
        } else {
            val origin = enabled.firstOrNull { it.kind == "origin" }
            if (origin != null && !routeWorks(origin) && report.environment.routes.firstOrNull() == "origin") {
                enabled.firstOrNull { it !== origin && routeWorks(it) }?.let {
                    out += PerfFinding(
                        PerfFinding.Level.WARN,
                        tr("官方直连连不上却排在第一条：每个请求要先等官方超时，才轮到{0}。可以在「Bangumi 连接方式」里关掉官方直连。", routeLabel(it.route)),
                    )
                }
            }
        }
    }

    /**
     * 加密直连清单里的地址逐个测的结果. 排在第一的连不上算警告: 加密直连按清单顺序连, 每次新建连接都要先等它超时才换下一个;
     * 后面的连不上只是少了替补, 列出来看清单该怎么排.
     */
    private fun echAddressFindings(report: NetworkReport, out: MutableList<PerfFinding>) {
        val checks = report.echAddressChecks
        val up = checks.filter { it.probe.answered }.map { it.address }
        val down = checks.filter { !it.probe.answered }
        if (up.isEmpty() || down.isEmpty()) return // 全不通时加密直连那条线路本身已经报了
        val first = checks.first()
        if (first in down) {
            out += PerfFinding(
                PerfFinding.Level.WARN,
                tr("加密直连排在第一的地址 {0} 连不上（{1}）：每次新建连接都要先等它超时才换下一个。能用的地址：{2}。", first.address, failureText(first.probe), up.joinToString("、")),
            )
        }
        val others = down.filter { it !== first }
        if (others.isNotEmpty()) {
            out += PerfFinding(PerfFinding.Level.INFO, tr("加密直连有的地址连不上：{0}；能用的：{1}。", others.joinToString("、") { it.address }, up.joinToString("、")))
        }
        checks.filter { it.probe.answered && it.sni != null && it.sni != "encrypted" }.forEach {
            out += PerfFinding(PerfFinding.Level.WARN, tr("加密直连经 {0} 连上了，但没用上加密握手（sni={1}）。", it.address, it.sni))
        }
    }

    private fun serviceFindings(report: NetworkReport, out: MutableList<PerfFinding>) {
        val byName = report.services.groupBy { it.name }
        fun down(name: String) = byName[name]?.let { list -> list.isNotEmpty() && list.none { it.answered } } == true
        if (down("tickets")) {
            out += PerfFinding(PerfFinding.Level.WARN, tr("故障反馈服务连不上（直连和加密直连都不行）：工单暂时发不出去。"))
        }
        if (down("relay")) {
            out += PerfFinding(PerfFinding.Level.WARN, tr("反馈中转连不上：标题 logo 修正与手机扫码登录用不了。"))
        }
        val lists = report.services.filter { it.name.startsWith("list:") }
        if (lists.isNotEmpty() && lists.none { it.answered }) {
            out += PerfFinding(PerfFinding.Level.WARN, tr("GitHub 上的清单（经 jsDelivr 与 GitHub）都取不到：镜像清单、推荐等用内置的旧版本，检查更新也可能失败。"))
        }
        // TMDB 图片的各个域名是同一个 CDN 区: 全不通 = 背景图会缺; 部分不通时列出来, 看清单里该排哪个
        val images = report.services.filter { it.name.startsWith(TMDB_IMAGE) }
        val imagesUp = images.filter { it.answered }.map { it.name.removePrefix(TMDB_IMAGE) }
        val imagesDown = images.filter { !it.answered }.map { it.name.removePrefix(TMDB_IMAGE) }
        when {
            images.isEmpty() -> Unit
            imagesUp.isEmpty() -> out += PerfFinding(PerfFinding.Level.INFO, tr("TMDB 图片的各个入口都连不上：背景图与标题 logo 会缺。"))
            imagesDown.isNotEmpty() -> out += PerfFinding(
                PerfFinding.Level.INFO,
                tr("TMDB 图片有的入口连不上：{0}；能用的：{1}。", imagesDown.joinToString("、"), imagesUp.joinToString("、")),
            )
        }
    }

    private const val TMDB_IMAGE = "tmdb-image:"

    /** 体检开始时有清单没拉到最新的: 用的是旧的或包里内置的那份, 仓库里改过的地址这台电视还没用上. */
    private fun listFindings(report: NetworkReport, out: MutableList<PerfFinding>) {
        val stale = report.lists.filter { !it.refreshed }
        if (stale.isEmpty()) return
        out += PerfFinding(
            PerfFinding.Level.INFO,
            tr("这些清单没拉到最新的，用的是之前存下的或安装包里自带的：{0}", stale.joinToString("、") { it.file }),
        )
    }

    private fun appFindings(report: NetworkReport, out: MutableList<PerfFinding>) {
        val app = report.app
        app.inFlight.filter { it.count >= QUEUE_COUNT || it.oldestSeconds >= QUEUE_OLDEST_SECONDS }.forEach {
            out += PerfFinding(
                PerfFinding.Level.WARN,
                tr("应用里有 {0} 个发往 {1} 的请求还没结束，最久的已等 {2} 秒：前面的请求挂住时，后面的都在排队。", it.count, it.host, it.oldestSeconds),
            )
        }
        if (app.allFailedRecently > 0) {
            val last = app.recentFailures.firstOrNull()
            out += PerfFinding(
                PerfFinding.Level.WARN,
                if (last != null) {
                    tr("最近 10 分钟有 {0} 个 Bangumi 请求所有线路都失败；最近一次失败在{1}（{2}）：{3}", app.allFailedRecently, routeLabel(last.route), last.host + last.path, last.cause)
                } else {
                    tr("最近 10 分钟有 {0} 个 Bangumi 请求所有线路都失败。", app.allFailedRecently)
                },
            )
        }
    }

    /** 线路上发出去的请求过半拿到了回应 (跳转不算: 应用不跟 API 的跳转). */
    private fun routeWorks(route: RouteCheck): Boolean {
        val sent = route.requests.filter { !it.skipped && it.name != "trace" }
        return sent.isNotEmpty() && sent.count { it.answered && !it.redirected } * 2 > sent.size
    }

    private val ProbeResult.redirected: Boolean get() = status in 300..399

    /**
     * 系统 DNS 像是被污染的域名: 给了 `2001::` 开头的假地址; 或者与加密 DNS 的回答对不上, **而且**直连这个域名的请求都没拿到回应 ——
     * CDN 上的站按问的人在哪给不同节点, 只是对不上不说明什么.
     */
    fun pollutedHosts(report: NetworkReport): List<String> {
        val direct = (report.routes.flatMap { it.requests } + report.services).filter { it.via != "ech" && !it.skipped }
        fun unreachable(host: String): Boolean {
            val probes = direct.filter { hostOf(it.url) == host }
            return probes.isNotEmpty() && probes.none { it.answered }
        }
        return report.dns.filter { it.fakeAddress || (it.mismatch && unreachable(it.host)) }.map { it.host }
    }

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')

    /** 没拿到回应的请求: 失败在哪一段 + 原因. */
    fun failureText(r: ProbeResult): String {
        val phase = when (r.failedAt) {
            "dns" -> tr("域名解析失败")
            "connect" -> tr("连接失败")
            "tls" -> tr("加密握手失败")
            "request", "response" -> tr("连上了但等不到回应")
            "setup" -> tr("这台设备用不了")
            else -> tr("失败")
        }
        val time = r.totalMs?.let { tr("（{0} 秒）", formatSeconds(it)) }.orEmpty()
        return phase + time + (r.error?.let { tr("：{0}", it) }.orEmpty())
    }

    fun routeLabel(route: String): String = when (route) {
        "origin" -> tr("官方直连")
        "ech" -> tr("加密直连")
        else -> tr("线路 {0}", route)
    }

    fun requestLabel(name: String): String = when {
        name == "trace" -> tr("Cloudflare 检测")
        name == "subject-v0" -> tr("条目（v0）")
        name == "episodes" -> tr("分集")
        name == "subject" -> tr("条目详情")
        name == "characters" -> tr("角色")
        name == "staff" -> tr("制作人员")
        name == "comments" -> tr("评论")
        name == "cover" -> tr("封面")
        name == "me" -> tr("登录状态")
        name == "tickets" -> tr("故障反馈服务")
        name == "relay" -> tr("反馈中转")
        name == "github" -> tr("GitHub")
        name == "tmdb-api" -> tr("TMDB 接口")
        name.startsWith(TMDB_IMAGE) -> tr("TMDB 图片 {0}", name.removePrefix(TMDB_IMAGE))
        name == "doh-alidns" -> tr("阿里加密 DNS")
        name.startsWith("list:") -> tr("清单 {0}", name.removePrefix("list:"))
        else -> name
    }

    private fun formatSeconds(ms: Long): String = String.format(Locale.ROOT, "%.1f", ms / 1000.0)

    /**
     * 存进报告的内容: 结论、触发原因 (自动体检时)、整份结果, 以及网页显示用的名字 (线路、请求、服务按当前语言).
     */
    fun body(report: NetworkReport, trigger: String?): JsonObject = buildJsonObject {
        putJsonArray("findings") {
            for (f in findings(report)) addJsonObject {
                put("level", f.level.name.lowercase(Locale.ROOT))
                put("text", f.text)
            }
        }
        trigger?.let { put("trigger", it) }
        put("network", Json.encodeToJsonElement(NetworkReport.serializer(), report))
        putJsonArray("suspiciousDns") { pollutedHosts(report).forEach { add(it) } }
        putJsonObject("labels") {
            val names = (report.routes.flatMap { it.requests } + report.services).map { it.name }.distinct()
            for (n in names) put(n, requestLabel(n))
            for (r in report.routes) put("route:" + r.route, routeLabel(r.route))
            for (f in report.app.recentFailures) put("route:" + f.route, routeLabel(f.route))
        }
    }
}
