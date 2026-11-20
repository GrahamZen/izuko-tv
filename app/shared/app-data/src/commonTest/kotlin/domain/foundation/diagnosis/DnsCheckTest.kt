/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation.diagnosis

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DnsCheckTest {
    @Test
    fun `teredo addresses from the system resolver are fake`() {
        assertTrue(DnsCheck("www.dmhy.org", system = listOf("2001::42dc:9512")).fakeAddress)
        assertTrue(DnsCheck("x", system = listOf("2001:0:53aa:64c:0:0:0:1")).fakeAddress)
        assertFalse(DnsCheck("x", system = listOf("2001:4860:4860::8888")).fakeAddress)
    }

    @Test
    fun `system answers sharing no address with DoH are a mismatch`() {
        // 2026-11-20 用户日志: 工单服务的域名被解析成 108.160.165.147
        assertTrue(DnsCheck("izuko-tickets.grahamzen.workers.dev", system = listOf("108.160.165.147"), doh = listOf("104.21.6.61", "172.67.134.140")).mismatch)
        assertFalse(DnsCheck("api.bgm.tv", system = listOf("104.21.6.61"), doh = listOf("104.21.6.61", "172.67.134.140")).mismatch)
    }

    @Test
    fun `missing answers on either side are not judged`() {
        assertFalse(DnsCheck("x", system = listOf("1.2.3.4")).mismatch)
        assertFalse(DnsCheck("x", doh = listOf("1.2.3.4")).mismatch)
    }
}
