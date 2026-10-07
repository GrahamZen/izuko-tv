/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.ktor

import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 经几家公共 DNS 的 JSON 接口 (DoH) 查 IPv4 地址, 几家一起问. 接口地址直接写 IP, 系统 DNS 出问题时照样查得到.
 * 各家按自己所在的位置给 CDN 节点, 合并起来常能拿到与系统 DNS 不同的一组.
 */
object PublicDns {
    private val logger = logger<PublicDns>()

    private const val TIMEOUT_MILLIS = 4_000L

    private val ENDPOINTS = listOf(
        "https://223.5.5.5/resolve?type=1&name=", // 阿里
        "https://1.12.12.12/resolve?type=A&name=", // 腾讯
        "https://1.1.1.1/dns-query?type=A&name=", // Cloudflare
        "https://8.8.8.8/resolve?type=A&name=", // Google
    )

    private val ADDRESS = Regex(""""data"\s*:\s*"(\d{1,3}(?:\.\d{1,3}){3})"""")

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * @param gatherMillis 第一家答出地址后再等别家多久, 把各家的地址合起来; 0 表示拿到第一份地址就返回
     * @return 都没答出地址时为空
     */
    fun lookup(hostname: String, gatherMillis: Long = 0): List<InetAddress> {
        val startedAt = System.nanoTime()
        val results = ConcurrentHashMap<String, List<String>>()
        val firstAnswer = CompletableFuture<Unit>()
        val allAnswered = CompletableFuture<Unit>()
        val pending = AtomicInteger(ENDPOINTS.size)
        val calls = ENDPOINTS.map { endpoint ->
            val request = Request.Builder().url(endpoint + hostname).header("Accept", "application/dns-json").build()
            client.newCall(request).also { call ->
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) = done()

                        override fun onResponse(call: Call, response: Response) {
                            response.use {
                                val addresses = if (it.isSuccessful) parseAddresses(it.body?.string().orEmpty()) else emptyList()
                                if (addresses.isNotEmpty()) {
                                    results[endpoint] = addresses
                                    firstAnswer.complete(Unit)
                                }
                            }
                            done()
                        }

                        private fun done() {
                            if (pending.decrementAndGet() == 0) {
                                firstAnswer.complete(Unit)
                                allAnswered.complete(Unit)
                            }
                        }
                    },
                )
            }
        }
        try {
            firstAnswer.get(TIMEOUT_MILLIS + 500, TimeUnit.MILLISECONDS)
            if (gatherMillis > 0) allAnswered.get(gatherMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
        }
        calls.forEach { it.cancel() }
        val addresses = results.values.flatten().distinct()
        logger.info {
            "Public DNS for $hostname: ${addresses.joinToString()} " +
                    "(${results.size} of ${ENDPOINTS.size} answered in ${(System.nanoTime() - startedAt) / 1_000_000}ms)"
        }
        return addresses.mapNotNull { ip ->
            runCatching { InetAddress.getByAddress(hostname, ip.split('.').map { it.toInt().toByte() }.toByteArray()) }.getOrNull()
        }
    }

    /** DoH JSON 回答里的 IPv4 地址 (`"data":"1.2.3.4"`; CNAME 那几条不是地址, 不算). */
    internal fun parseAddresses(json: String): List<String> =
        ADDRESS.findAll(json).map { it.groupValues[1] }.filter { ip -> ip.split('.').all { it.toInt() in 0..255 } }.toList()
}
