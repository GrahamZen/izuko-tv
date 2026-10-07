/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 下载域名的节点: 连不上的跳过; 系统给的这组出过问题就把公共 DNS 的也拿来, 没出过问题的网段先试; 每次只给几个.
 */
class FailoverDnsTest {
    private val host = "dl.drive.test"

    private fun ip(subnet: Int, b: Int) = InetAddress.getByAddress(host, byteArrayOf(10, 0, subnet.toByte(), b.toByte()))

    /** 系统 DNS 给的一组, 都在 10.0.1.x */
    private val systemPool = (1..8).map { ip(1, it) }

    /** 公共 DNS 给的另一组, 10.0.2.x */
    private val publicPool = (1..8).map { ip(2, it) }

    private var now = 0L
    private var publicLookups = 0

    private fun dnsOf(answer: (String) -> List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = answer(hostname)
    }

    private val unresolvable = dnsOf { throw UnknownHostException(it) }

    private fun dns(system: Dns = dnsOf { systemPool }, alternatives: List<InetAddress> = publicPool) =
        FailoverDns(
            system, { publicLookups++; alternatives },
            maxAddresses = 3, forgetAfterMillis = 60_000, alternativesTtlMillis = 30_000, now = { now },
        )

    @Test
    fun `gives a few of the system addresses`() {
        val picked = dns().lookup(host)
        assertEquals(3, picked.size)
        assertTrue(systemPool.containsAll(picked))
        assertEquals(0, publicLookups)
    }

    @Test
    fun `once a node of the group fails, other subnets go first`() {
        val dns = dns()
        dns.markUnreachable(systemPool[0])
        val picked = dns.lookup(host)
        assertTrue(publicPool.containsAll(picked) && picked.size == 3, "$picked")
        assertEquals(1, publicLookups)

        // 公共 DNS 的回答留一会儿, 不是每次连接都去问
        dns.lookup(host)
        assertEquals(1, publicLookups)
        now += 31_000
        dns.lookup(host)
        assertEquals(2, publicLookups)

        // 过了记仇的时间, 系统那组又没问题了
        now += 30_000
        assertTrue(systemPool.containsAll(dns.lookup(host)))
        assertEquals(2, publicLookups)
    }

    @Test
    fun `the rest of a failing subnet is used when nothing else is left`() {
        val dns = dns(alternatives = emptyList())
        systemPool.drop(2).forEach(dns::markUnreachable)
        assertEquals(systemPool.take(2).toSet(), dns.lookup(host).toSet())
    }

    @Test
    fun `nodes of a failing subnet come after the healthy ones from public DNS`() {
        // 公共 DNS 也给了系统那组的节点
        val dns = dns(alternatives = systemPool.take(4) + publicPool.take(1))
        systemPool.take(2).forEach(dns::markUnreachable)
        val picked = dns.lookup(host)
        assertEquals(publicPool[0], picked.first())
        assertTrue(picked.drop(1).all { it in systemPool.drop(2) }, "$picked")
    }

    @Test
    fun `falls back to the system addresses when everything failed`() {
        val dns = dns(alternatives = emptyList())
        systemPool.forEach(dns::markUnreachable)
        assertTrue(systemPool.containsAll(dns.lookup(host)))
    }

    @Test
    fun `unresolvable host without public answers fails`() {
        val dns = dns(system = unresolvable, alternatives = emptyList())
        assertFailsWith<UnknownHostException> { dns.lookup(host) }
        // 系统解析不了但公共 DNS 有
        assertEquals(3, dns(system = unresolvable).lookup(host).size)
    }
}
