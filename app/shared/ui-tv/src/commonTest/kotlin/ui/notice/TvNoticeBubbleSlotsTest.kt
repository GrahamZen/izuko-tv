/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import me.him188.ani.app.navigation.MainScreenPage
import me.him188.ani.app.navigation.NavRoutes
import kotlin.test.Test
import kotlin.test.assertEquals

class TvNoticeBubbleSlotsTest {
    @Test
    fun `no banner slot means the banner cannot show`() {
        val page = Any()
        assertEquals(false, TvNoticeBubbleSlots().any)
        // 页面挂上横幅位: 画得出; 撤掉 (侧边栏不在了) 又画不出
        val mounted = TvNoticeBubbleSlots().attached(page)
        assertEquals(true, mounted.any)
        assertEquals(false, mounted.detached(page).any)
    }

    @Test
    fun `the page mounted last draws the banner`() {
        val home = Any()
        val details = Any()
        // 换页的过渡里两页的横幅位都在: 后挂上的那一页画
        val both = TvNoticeBubbleSlots().attached(home).attached(details)
        assertEquals(false, both.draws(home))
        assertEquals(true, both.draws(details))
        // 同一页重复挂不换先后
        assertEquals(true, both.attached(home).draws(details))
    }

    @Test
    fun `unmounting the top page hands the banner back to the page below`() {
        val home = Any()
        val details = Any()
        val back = TvNoticeBubbleSlots().attached(home).attached(details).detached(details)
        assertEquals(true, back.draws(home))
        assertEquals(true, back.any)
        // 撤一个没挂着的不动
        assertEquals(true, back.detached(details).draws(home))
    }

    @Test
    fun `banner pages are the ones with the side rail`() {
        assertEquals(true, tvNoticeBannerRoute(NavRoutes.Main(MainScreenPage.Exploration)))
        assertEquals(true, tvNoticeBannerRoute(NavRoutes.SubjectSearch(keyword = "a")))
        assertEquals(true, tvNoticeBannerRoute(NavRoutes.SubjectDetail(1)))
        // 播放页、设置上不弹; 返回栈还没有时也不弹
        assertEquals(false, tvNoticeBannerRoute(NavRoutes.EpisodeDetail(1, 2)))
        assertEquals(false, tvNoticeBannerRoute(NavRoutes.Settings()))
        assertEquals(false, tvNoticeBannerRoute(null))
    }
}
