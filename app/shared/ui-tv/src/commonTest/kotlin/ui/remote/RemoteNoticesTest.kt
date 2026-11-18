/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * 控制台上的通知 (见 [RemoteNotices]): 脚本与样式在页面里, 每 2 秒的轮询交给它, 网页上的文案都有译文.
 */
class RemoteNoticesTest {
    private val page = renderRemoteControlPage(
        initialTab = "search",
        searchFormHtml = "",
        requestSectionHtml = "",
    )

    @Test
    fun `notice script and style are on the page and fed by the poll`() {
        assertContains(page, NOTICE_SCRIPT)
        assertContains(page, NOTICE_STYLE)
        assertContains(page, "if (window.onNoticePoll) window.onNoticePoll(n);")
        // 铃铛插在「?」左边, 列表与横幅点了都打开通知列表
        assertContains(NOTICE_SCRIPT, "header.insertBefore(btn, help);")
        assertContains(NOTICE_SCRIPT, "banner.addEventListener('click', open);")
        // 电视通知弹窗上的码带的就是这个
        assertContains(NOTICE_SCRIPT, "if (location.hash === '#notices') setTimeout(open, 0);")
    }

    @Test
    fun `notice texts are translated`() {
        val keys = REMOTE_I18N_TABLE.map { it.zh }.toSet()
        val texts = Regex("""(?<![\w.])T\('([^']+)'""").findAll(NOTICE_SCRIPT).map { it.groupValues[1] }.toList()
        assertTrue(texts.size > 10)
        for (t in texts) assertTrue(t in keys, "没有译文：$t")
    }

    @Test
    fun `server messages are translated`() {
        val keys = REMOTE_I18N_TABLE.map { it.zh }.toSet()
        for (t in listOf("这条通知已经处理过了", "这条通知只是告知，没有要打开的内容", "电视上的 Izuko 界面还没打开", "已在电视上打开")) {
            assertTrue(t in keys, "没有译文：$t")
        }
    }
}
