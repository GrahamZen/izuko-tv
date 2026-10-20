/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.BangumiEndpointMode
import me.him188.ani.app.data.models.preference.BangumiEndpointSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 交给浏览器的 Bangumi 网页跟着当前线路换站 ([BangumiEndpointProvider.webLink]): 条目页、分集页、评论里的链接都走这里.
 */
class BangumiWebLinkTest {
    private fun TestScope.provider(settings: MutableStateFlow<BangumiEndpointSettings>) = BangumiEndpointProvider(
        settings = settings,
        mirrors = flowOf(listOf("bangumi.vip")),
        scope = backgroundScope,
    ).also { runCurrent() }

    @Test
    fun `只连官方 — 官方地址不动，存下来的镜像地址换回官方`() = runTest {
        val provider = provider(MutableStateFlow(BangumiEndpointSettings(mode = BangumiEndpointMode.DIRECT)))
        assertEquals("https://bgm.tv/subject/1", provider.webLink("https://bgm.tv/subject/1"))
        assertEquals("https://lain.bgm.tv/pic/a.jpg", provider.webLink("https://lain.bangumi.vip/pic/a.jpg"))
    }

    @Test
    fun `用镜像 — 换到镜像上，子域照着换，凭证开没开都一样`() = runTest {
        for (cred in listOf(false, true)) {
            val provider = provider(
                MutableStateFlow(BangumiEndpointSettings(mode = BangumiEndpointMode.MIRROR, allowCredentialsViaMirror = cred)),
            )
            assertEquals("https://bangumi.vip/ep/2?a=1", provider.webLink("https://bgm.tv/ep/2?a=1"))
            assertEquals("https://next.bangumi.vip/p1/x", provider.webLink("https://next.bgm.tv/p1/x"))
        }
    }

    @Test
    fun `官方连不上时用镜像 — 落到镜像上才换`() = runTest {
        val provider = provider(MutableStateFlow(BangumiEndpointSettings(mode = BangumiEndpointMode.AUTO)))
        assertEquals("https://bgm.tv/subject/1", provider.webLink("https://bgm.tv/subject/1"))
        provider.reportSettled(provider.currentRouting!!, BangumiRoute.Mirror("bangumi.vip"))
        runCurrent()
        assertEquals("https://bangumi.vip/subject/1", provider.webLink("https://bgm.tv/subject/1"))
    }

    @Test
    fun `落在加密直连上 — 浏览器做不了加密直连，换到镜像网页`() = runTest {
        // 旧版的「用镜像」= 关着官方, 加密直连排在镜像前面
        val provider = BangumiEndpointProvider(
            settings = flowOf(BangumiEndpointSettings(mode = BangumiEndpointMode.MIRROR)),
            mirrors = flowOf(listOf("bangumi.vip")),
            scope = backgroundScope,
            echAvailable = true,
        ).also { runCurrent() }
        assertEquals(listOf(BangumiRoute.Ech, BangumiRoute.Mirror("bangumi.vip")), provider.currentRouting!!.routes)
        // 还没落地: 官方关着, 浏览器打不开它
        assertEquals("https://bangumi.vip/subject/1", provider.webLink("https://bgm.tv/subject/1"))
        provider.reportSettled(provider.currentRouting!!, BangumiRoute.Ech)
        runCurrent()
        assertEquals("https://bangumi.vip/subject/1", provider.webLink("https://bgm.tv/subject/1"))
    }

    @Test
    fun `cover image moves to the mirror once requests settle on ECH with origin enabled`() = runTest {
        // 默认的官方 → 加密直连 → 镜像: 交给系统主屏的封面 (主屏自己下载图) 在落到加密直连后换到镜像图床
        val provider = BangumiEndpointProvider(
            settings = flowOf(BangumiEndpointSettings(mode = BangumiEndpointMode.AUTO)),
            mirrors = flowOf(listOf("bangumi.vip")),
            scope = backgroundScope,
            echAvailable = true,
        ).also { runCurrent() }
        assertEquals("https://lain.bgm.tv/pic/cover/l/a.jpg", provider.webLink("https://lain.bgm.tv/pic/cover/l/a.jpg"))
        provider.reportSettled(provider.currentRouting!!, BangumiRoute.Ech)
        runCurrent()
        assertEquals("bangumi.vip", provider.webMirrorRoot.value)
        assertEquals("https://lain.bangumi.vip/pic/cover/l/a.jpg", provider.webLink("https://lain.bgm.tv/pic/cover/l/a.jpg"))
        provider.reportSettled(provider.currentRouting!!, BangumiRoute.Origin)
        runCurrent()
        assertEquals("https://lain.bgm.tv/pic/cover/l/a.jpg", provider.webLink("https://lain.bangumi.vip/pic/cover/l/a.jpg"))
    }

    @Test
    fun `切换连接方式后立刻按新的来`() = runTest {
        val settings = MutableStateFlow(BangumiEndpointSettings(mode = BangumiEndpointMode.MIRROR))
        val provider = provider(settings)
        assertEquals("https://bangumi.vip/person/3", provider.webLink("https://bgm.tv/person/3"))
        settings.value = BangumiEndpointSettings(mode = BangumiEndpointMode.DIRECT)
        runCurrent()
        assertEquals("https://bgm.tv/person/3", provider.webLink("https://bgm.tv/person/3"))
    }

    @Test
    fun `不是 Bangumi 的地址原样返回`() = runTest {
        val provider = provider(MutableStateFlow(BangumiEndpointSettings(mode = BangumiEndpointMode.MIRROR)))
        assertEquals("https://example.com/a", provider.webLink("https://example.com/a"))
        assertEquals("not a url", provider.webLink("not a url"))
    }
}
