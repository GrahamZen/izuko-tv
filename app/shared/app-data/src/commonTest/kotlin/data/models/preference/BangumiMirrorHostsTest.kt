/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BangumiMirrorHostsTest {
    @Test
    fun `认得出 bangumi 的域名族`() {
        assertTrue(BangumiMirrorHosts.isBangumiHost("bgm.tv"))
        assertTrue(BangumiMirrorHosts.isBangumiHost("api.bgm.tv"))
        assertTrue(BangumiMirrorHosts.isBangumiHost("lain.bgm.tv"))
        assertFalse(BangumiMirrorHosts.isBangumiHost("bangumi.tv"))
        // 后缀像但不是: 不能把别人的域名也改写过去
        assertFalse(BangumiMirrorHosts.isBangumiHost("notbgm.tv"))
        assertFalse(BangumiMirrorHosts.isBangumiHost("bgm.tv.evil.com"))
    }

    @Test
    fun `子域按通配换算，新子域零配置`() {
        assertEquals("bangumi.pro", BangumiMirrorHosts.mirrorHostOf("bgm.tv", "bangumi.pro"))
        assertEquals("api.bangumi.pro", BangumiMirrorHosts.mirrorHostOf("api.bgm.tv", "bangumi.pro"))
        assertEquals("next.bangumi.pro", BangumiMirrorHosts.mirrorHostOf("next.bgm.tv", "bangumi.pro"))
        // 图床也一起换 —— 不然封面全是空图
        assertEquals("lain.bangumi.pro", BangumiMirrorHosts.mirrorHostOf("lain.bgm.tv", "bangumi.pro"))
        // 词表里没有的子域照样能换
        assertEquals("brandnew.bangumi.pro", BangumiMirrorHosts.mirrorHostOf("brandnew.bgm.tv", "bangumi.pro"))
    }

    @Test
    fun `不是 bangumi 的域名不换算`() {
        assertNull(BangumiMirrorHosts.mirrorHostOf("api.themoviedb.org", "bangumi.pro"))
    }

    @Test
    fun `镜像上的域名换回原站`() {
        assertEquals("lain.bgm.tv", BangumiMirrorHosts.originHostOf("lain.bangumi.vip", "bangumi.vip"))
        assertEquals("bgm.tv", BangumiMirrorHosts.originHostOf("bangumi.vip", "bangumi.vip"))
        assertNull(BangumiMirrorHosts.originHostOf("lain.notbangumi.vip", "bangumi.vip"))
        assertNull(BangumiMirrorHosts.originHostOf("lain.bgm.tv", "bangumi.vip"))
    }

    @Test
    fun `用户输入的各种形状都要归一`() {
        val expected = "bangumi.example.com"
        for (input in listOf(
            "bangumi.example.com",
            "https://bangumi.example.com",
            "http://bangumi.example.com/",
            "  https://bangumi.example.com/p1/subjects  ",
            "HTTPS://Bangumi.Example.COM",
        )) {
            assertEquals(expected, BangumiMirrorHosts.normalizeMirrorRoot(input), "输入: $input")
        }
    }

    @Test
    fun `多填了一层 bangumi 子域时取根`() {
        // 填 api 子域是很自然的误解 (照着"把 api.bgm.tv 换成 api.x.com"想)
        assertEquals("x.com", BangumiMirrorHosts.normalizeMirrorRoot("api.x.com"))
        assertEquals("x.com", BangumiMirrorHosts.normalizeMirrorRoot("https://next.x.com/p1"))
        // 不在子域词表里的照原样留着 —— 那可能真是他的域名
        assertEquals("bgm.mydomain.com", BangumiMirrorHosts.normalizeMirrorRoot("bgm.mydomain.com"))
    }

    @Test
    fun `填不成样子的一律认不出来（调用方据此退回直连）`() {
        for (input in listOf("", "   ", "localhost", "not a host", "https://", "x.com:8080")) {
            assertNull(BangumiMirrorHosts.normalizeMirrorRoot(input), "输入: $input")
        }
        // 端口那条单说: 镜像是整族通配子域, "x.com:8080" 换算不出 "api.x.com:8080" 该长什么样,
        // 所以宁可认不出来让用户看见提示, 也别拿一个换不对的地址去打
        assertNull(BangumiMirrorHosts.normalizeMirrorRoot("https://x.com:8080/"))
    }
}
