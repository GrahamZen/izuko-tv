/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * 手机控制台夸克卡片的「在夸克 App 中确认」按钮必须用夸克 App 的唤起链接打开二维码里的确认页.
 *
 * 确认页只在夸克 App 内可用: 直接链到二维码里的 https 链接, 手机的其他浏览器会转到夸克网盘的下载页,
 * 下载页唤起 App 时也不带这次登录的 token, 登录完成不了.
 */
class RemoteControlPageQuarkTest {
    private val page = renderRemoteControlPage(
        initialTab = "player",
        searchFormHtml = "",
        requestSectionHtml = "",
    )

    @Test
    fun `确认按钮用夸克 App 的唤起链接打开确认页`() {
        assertContains(page, "'qklink://www.uc.cn/' + appkey + '?action=open_url&url=' + encodeURIComponent(link)")
        assertContains(page, "esc(quarkAppLink(q.link))")
        assertFalse(
            page.contains("href=\"' + esc(q.link) + '\""),
            "按钮直接链到二维码链接, 手机浏览器里只会落到夸克网盘下载页",
        )
    }

    @Test
    fun `安卓与 iOS 各用夸克 App 自己的 appkey`() {
        assertContains(page, "ios ? '656bdcddd4758b39206a8181c91a7d14' : 'b20b84fd735a8dd3f7541129bacc4e9a'")
    }
}
