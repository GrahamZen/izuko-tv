/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 建连失败才把节点记成连不上: 读的一方不要了而取消的请求, 正在建的连接被关掉, 节点本身没问题.
 */
class RangeConnectLoggerTest {
    private val host = "dl.drive.test"

    private fun ip(subnet: Int, b: Int) = InetAddress.getByAddress(host, byteArrayOf(10, 0, subnet.toByte(), b.toByte()))

    /** 系统 DNS 给的一组 */
    private val systemPool = (1..3).map { ip(1, it) }

    /** 公共 DNS 给的另一组 */
    private val publicPool = (1..3).map { ip(2, it) }

    private val dns = FailoverDns(
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = systemPool
        },
        { publicPool },
        maxAddresses = 3,
    )

    private val client = OkHttpClient()

    private fun call(cancelled: Boolean): Call =
        client.newCall(Request.Builder().url("https://$host/file").build()).also { if (cancelled) it.cancel() }

    private fun RangeHttpClients.ConnectLogger.failConnecting(call: Call, address: InetAddress, error: IOException) =
        connectFailed(call, InetSocketAddress(address, 443), Proxy.NO_PROXY, null, error)

    @Test
    fun `a connect cut short by cancelling the request leaves the node usable`() {
        dns.markConnected(host, systemPool[0])
        // 关掉读取时还在建连的请求: 连接被关, 报 Socket closed
        systemPool.forEach { RangeHttpClients.ConnectLogger(dns).failConnecting(call(cancelled = true), it, SocketException("Socket closed")) }
        val picked = dns.lookup(host)
        assertTrue(systemPool.containsAll(picked), "$picked")
        // 刚连上过的那个还排最前
        assertEquals(systemPool[0], picked.first())
    }

    @Test
    fun `a connect that fails on its own marks the node unreachable`() {
        systemPool.forEach {
            RangeHttpClients.ConnectLogger(dns).failConnecting(call(cancelled = false), it, SocketTimeoutException("failed to connect"))
        }
        val picked = dns.lookup(host)
        assertTrue(publicPool.containsAll(picked), "$picked")
    }
}
