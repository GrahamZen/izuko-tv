/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 加密直连的域名改写: bangumi 用线路自己的域名根, 其余的站接后缀; 换回来要能认出两种. */
class EchSitesTest {
    @Test
    fun `bangumi 用线路的域名根`() {
        assertEquals("api.bgm-tv.ech.invalid", EchSites.echHostOf("api.bgm.tv"))
        assertEquals("bgm-tv.ech.invalid", EchSites.echHostOf("bgm.tv"))
        assertEquals("api.bgm.tv", EchSites.originHostOf("api.bgm-tv.ech.invalid"))
        assertEquals("bgm.tv", EchSites.originHostOf("bgm-tv.ech.invalid"))
    }

    @Test
    fun `其余的站接后缀`() {
        val worker = "report.someone.workers.dev"
        assertEquals("$worker.ech.invalid", EchSites.echHostOf(worker))
        assertEquals(worker, EchSites.originHostOf("$worker.ech.invalid"))
        assertEquals(
            "https://$worker.ech.invalid/logo-report",
            EchSites.echUrlOf("https://$worker/logo-report"),
        )
    }

    @Test
    fun `不是加密直连的域名`() {
        assertNull(EchSites.originHostOf("api.bgm.tv"))
        assertNull(EchSites.originHostOf("report.someone.workers.dev"))
        assertNull(EchSites.originHostOf("foo.ech.invalid"))
        assertNull(EchSites.originHostOf("ech.invalid"))
    }

    @Test
    fun `图床另走一条连接`() {
        assertTrue(EchSites.isImageHost("lain.bgm.tv"))
        assertFalse(EchSites.isImageHost("api.bgm.tv"))
        assertFalse(EchSites.isImageHost("next.bgm.tv"))
        assertFalse(EchSites.isImageHost("bgm.tv"))
    }

    @Test
    fun `只有 https 能走加密直连`() {
        assertNull(EchSites.echUrlOf("http://report.someone.workers.dev/logo-report"))
        assertNull(EchSites.echUrlOf("not a url"))
    }
}
