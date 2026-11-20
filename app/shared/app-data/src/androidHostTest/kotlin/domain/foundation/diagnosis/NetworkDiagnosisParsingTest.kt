/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation.diagnosis

import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkDiagnosisParsingTest {
    @Test
    fun `cloudflare trace lines become a map`() {
        val trace = NetworkDiagnosis.parseTrace("fl=12\nh=api.bgm.tv\nip=1.2.3.4\nsni=encrypted\nloc=CN\n\n")
        assertEquals("encrypted", trace["sni"])
        assertEquals("CN", trace["loc"])
        assertEquals(5, trace.size)
    }

    @Test
    fun `doh answers are filtered by record type`() {
        val body = """{"Status":0,"Answer":[{"name":"a.com.","type":5,"data":"b.com."},{"name":"b.com.","type":1,"data":"1.2.3.4"},""" +
            """{"name":"b.com.","type":1,"data":"5.6.7.8"}]}"""
        assertEquals(listOf("1.2.3.4", "5.6.7.8"), NetworkDiagnosis.parseDohAnswers(body, 1))
        assertEquals(emptyList(), NetworkDiagnosis.parseDohAnswers(body, 28))
        assertEquals(emptyList(), NetworkDiagnosis.parseDohAnswers("not json", 1))
    }

    @Test
    fun `app proxy urls become java proxies and credentials are stripped for the report`() {
        val http = NetworkDiagnosis.parseProxy("http://user:pass@192.168.1.2:7890")!!
        assertEquals(Proxy.Type.HTTP, http.type())
        assertEquals(7890, (http.address() as InetSocketAddress).port)
        assertEquals(Proxy.Type.SOCKS, NetworkDiagnosis.parseProxy("socks5://127.0.0.1:1080")!!.type())
        assertNull(NetworkDiagnosis.parseProxy("not a proxy"))
        assertEquals("http://192.168.1.2:7890", NetworkDiagnosis.withoutUserInfo("http://user:pass@192.168.1.2:7890"))
    }
}
