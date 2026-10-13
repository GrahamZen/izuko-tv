/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import me.him188.ani.app.data.persistent.DataStoreJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 各线路的开关, 以及已发布版本里存下的「连接方式」怎么换算过来.
 */
class BangumiEndpointSettingsTest {
    private fun BangumiEndpointSettings.enabled(): Set<BangumiRouteKind> =
        BangumiRouteKind.entries.filterTo(mutableSetOf()) { isEnabled(it) }

    private fun decode(json: String) = DataStoreJson.decodeFromString(BangumiEndpointSettings.serializer(), json)

    @Test
    fun `新装 — 官方、镜像开着，自建关着`() {
        assertEquals(
            setOf(BangumiRouteKind.ORIGIN, BangumiRouteKind.MIRRORS),
            BangumiEndpointSettings.Default.enabled(),
        )
    }

    @Test
    fun `旧版的连接方式换算成线路`() {
        val expected = mapOf(
            BangumiEndpointMode.AUTO to setOf(BangumiRouteKind.ORIGIN, BangumiRouteKind.MIRRORS),
            BangumiEndpointMode.DIRECT to setOf(BangumiRouteKind.ORIGIN),
            BangumiEndpointMode.MIRROR to setOf(BangumiRouteKind.MIRRORS),
            BangumiEndpointMode.CUSTOM to setOf(BangumiRouteKind.CUSTOM),
        )
        for ((mode, routes) in expected) {
            assertEquals(routes, BangumiEndpointSettings(mode = mode).enabled(), "$mode")
        }
    }

    @Test
    fun `读旧版存下的数据`() {
        // 旧版默认是「官方连不上时用镜像」, 不写进文件
        assertEquals(BangumiEndpointSettings.Default.enabled(), decode("{}").enabled())
        val mirror = decode("""{"mode":"MIRROR","allowCredentialsViaMirror":true}""")
        assertEquals(setOf(BangumiRouteKind.MIRRORS), mirror.enabled())
        assertTrue(mirror.allowCredentialsViaMirror)
        val custom = decode("""{"mode":"CUSTOM","customBaseUrl":"https://my.example/"}""")
        assertEquals(setOf(BangumiRouteKind.CUSTOM), custom.enabled())
        assertEquals("my.example", custom.customRoot)
    }

    @Test
    fun `改一条线路 — 其余照原样写下来，之后不再看旧的连接方式`() {
        val changed = BangumiEndpointSettings(mode = BangumiEndpointMode.MIRROR).withRoute(BangumiRouteKind.ORIGIN, true)
        assertNull(changed.mode)
        assertEquals(setOf(BangumiRouteKind.ORIGIN, BangumiRouteKind.MIRRORS), changed.enabled())

        val json = DataStoreJson.encodeToString(BangumiEndpointSettings.serializer(), changed)
        assertEquals(changed, decode(json))
    }

    @Test
    fun `不认识的线路键不影响读`() {
        val settings = decode("""{"routes":{"origin":false,"future":true}}""")
        assertFalse(settings.isEnabled(BangumiRouteKind.ORIGIN))
        assertTrue(settings.isEnabled(BangumiRouteKind.MIRRORS))
    }

    @Test
    fun `确定官方连不上 — 关掉官方，只开着官方时不动`() {
        assertEquals(
            setOf(BangumiRouteKind.MIRRORS),
            BangumiEndpointSettings.Default.afterOriginUnreachable().enabled(),
        )
        val direct = BangumiEndpointSettings(mode = BangumiEndpointMode.DIRECT)
        assertSame(direct, direct.afterOriginUnreachable())
        val off = BangumiEndpointSettings.Default.afterOriginUnreachable()
        assertSame(off, off.afterOriginUnreachable())
    }

    @Test
    fun `一键组合 — 自建地址只有只连官方会关`() {
        val withCustom = BangumiEndpointSettings(customBaseUrl = "my.example").withRoute(BangumiRouteKind.CUSTOM, true)
        assertEquals(
            setOf(BangumiRouteKind.ORIGIN, BangumiRouteKind.CUSTOM, BangumiRouteKind.MIRRORS),
            BangumiConnectionPreset.AUTO.applyTo(withCustom).enabled(),
        )
        assertEquals(
            setOf(BangumiRouteKind.CUSTOM, BangumiRouteKind.MIRRORS),
            BangumiConnectionPreset.SKIP_ORIGIN.applyTo(withCustom).enabled(),
        )
        assertEquals(setOf(BangumiRouteKind.ORIGIN), BangumiConnectionPreset.DIRECT.applyTo(withCustom).enabled())
        // 自建地址本身不动
        assertEquals("my.example", BangumiConnectionPreset.DIRECT.applyTo(withCustom).customBaseUrl)
    }
}
