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
import kotlin.test.assertEquals

/** 数据源胶囊里暂无结果的那些默认收起, 暂停着的前面有「⏸」(见网页里的 emptySrcChips / srcChipMark). */
class RemoteSourceChipFoldTest {
    private val page = renderRemoteControlPage(initialTab = "player", searchFormHtml = "", requestSectionHtml = "")

    @Test
    fun `player and cache chip rows both fold empty sources`() {
        assertEquals(2, Regex("""window\.emptySrcChips\(""").findAll(page).count(), "播放器与缓存选资源两排都要接上")
        assertEquals(2, Regex("""\+ fold\.toggle \+""").findAll(page).count())
        assertContains(page, ".chip.fold {")
        assertEquals(2, Regex("""window\.srcChipMark\(x, """).findAll(page).count(), "两排的胶囊名字前都要有记号")
    }

    @Test
    fun `fold chip texts are translated`() {
        for (key in listOf("展开 {0} 个暂无结果", "收起 {0} 个暂无结果")) {
            assertContains(page, "T('$key'")
            val row = REMOTE_I18N_TABLE.single { it.zh == key }
            assertContains(row.en, "{0}")
        }
    }
}
