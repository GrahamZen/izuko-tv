/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.ktor

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 系统 DNS 时好时坏: 失败时先用最近一次解析出的地址, 没有再问公共 DNS; 公共 DNS 的结果 (查不到也算) 留一阵.
 */
class FallbackDnsTest {
    private val host = "cdn.video.test"

    private fun ip(last: Int) = InetAddress.getByAddress(host, byteArrayOf(10, 0, 0, last.toByte()))

    private var now = 0L
    private var systemWorks = true
    private var publicAnswer = listOf(ip(9))
    private var publicLookups = 0

    private val system = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (systemWorks) listOf(ip(1)) else throw UnknownHostException("Unable to resolve host \"$hostname\": No address associated with hostname")
    }

    private val dns = FallbackDns(
        system,
        { publicLookups++; publicAnswer },
        lastGoodTtlMillis = 60 * 60_000L, publicTtlMillis = 60_000L, now = { now },
    )

    @Test
    fun `uses the system answer while it works`() {
        assertEquals(listOf(ip(1)), dns.lookup(host))
        assertEquals(0, publicLookups)
    }

    @Test
    fun `system failure falls back to the last resolved addresses`() {
        dns.lookup(host)
        systemWorks = false
        now += 10 * 60_000L
        assertEquals(listOf(ip(1)), dns.lookup(host))
        assertEquals(0, publicLookups)
    }

    @Test
    fun `last resolved addresses expire, then public DNS answers`() {
        dns.lookup(host)
        systemWorks = false
        now += 2 * 60 * 60_000L
        assertEquals(listOf(ip(9)), dns.lookup(host))
        assertEquals(1, publicLookups)
    }

    @Test
    fun `never resolved - public DNS answers and is reused for a while`() {
        systemWorks = false
        assertEquals(listOf(ip(9)), dns.lookup(host))
        assertEquals(listOf(ip(9)), dns.lookup(host))
        assertEquals(1, publicLookups)
        now += 2 * 60_000L
        dns.lookup(host)
        assertEquals(2, publicLookups)
    }

    @Test
    fun `dead host throws the system error and is not asked again right away`() {
        systemWorks = false
        publicAnswer = emptyList()
        val error = assertFailsWith<UnknownHostException> { dns.lookup(host) }
        assertEquals(true, error.message?.contains("No address associated"))
        assertFailsWith<UnknownHostException> { dns.lookup(host) }
        assertEquals(1, publicLookups)
    }

    @Test
    fun `parses addresses from DNS JSON answers`() {
        val json = """
            {"Status":0,"Answer":[
              {"name":"dl.drive.test.","type":5,"TTL":300,"data":"dl.drive.test.cdn.example."},
              {"name":"dl.drive.test.cdn.example.","type":1,"TTL":60,"data":"27.221.66.122"},
              {"name":"dl.drive.test.cdn.example.","type":1,"TTL":60, "data" : "27.221.66.128"}
            ]}
        """.trimIndent()
        assertEquals(listOf("27.221.66.122", "27.221.66.128"), PublicDns.parseAddresses(json))
        assertEquals(emptyList(), PublicDns.parseAddresses("""{"Status":3}"""))
    }
}
