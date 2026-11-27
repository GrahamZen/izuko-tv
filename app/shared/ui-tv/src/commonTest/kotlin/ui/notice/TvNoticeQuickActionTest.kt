/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/** 通知横幅上短按直接做哪一个 ([tvNoticeQuickAction]; docs/contributing/code/notices.md 5.1). */
class TvNoticeQuickActionTest {
    private val run = TvNoticeDestination.Run { }

    @Test
    fun `a short press does the first action when it allows that`() {
        val play = TvNoticeAction("播放第 8 集", run, quick = "播放")
        val details = TvNoticeAction("作品详情", run)
        assertSame(play, tvNoticeQuickAction(listOf(play, details)))
    }

    @Test
    fun `a first action that needs reading first leaves the short press to open the notices`() {
        // 如新版本的「自动更新」: 后面的「查看详情」能一按就做也不往后找
        val update = TvNoticeAction("自动更新", run)
        val details = TvNoticeAction("查看详情", run, quick = "查看")
        assertNull(tvNoticeQuickAction(listOf(update, details)))
        assertNull(tvNoticeQuickAction(emptyList()))
    }

}
