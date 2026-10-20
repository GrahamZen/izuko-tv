/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode

import me.him188.ani.app.data.models.subject.SubjectInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SubjectPresentationTest {
    @Test
    fun `toPresentation_sets_title_and_originalTitle_from_displayName_and_name`() {
        val info = SubjectInfo.Empty.copy(name = "ぼっち・ざ・ろっく！", nameCn = "孤独摇滚！")
        val presentation = info.toPresentation()
        assertEquals("孤独摇滚！", presentation.title)
        assertEquals("ぼっち・ざ・ろっく！", presentation.originalTitle)
    }

    @Test
    fun `originalTitle_falls_back_to_title_when_name_is_blank`() {
        val info = SubjectInfo.Empty.copy(name = "", nameCn = "孤独摇滚！")
        val presentation = info.toPresentation()
        assertEquals("孤独摇滚！", presentation.title)
        assertEquals("孤独摇滚！", presentation.originalTitle)
    }

    @Test
    fun `presentations of the same subject are equal`() {
        val info = SubjectInfo.Empty.copy(subjectId = 328609, name = "ぼっち・ざ・ろっく！", nameCn = "孤独摇滚！")
        // 页面状态每次重新组合都会新做一份: 内容相同就得相等, 页面状态才去得了重
        assertEquals(info.toPresentation(), info.toPresentation())
        assertEquals(info.toPresentation().hashCode(), info.toPresentation().hashCode())
    }

    @Test
    fun `presentations of different subjects are not equal`() {
        val a = SubjectInfo.Empty.copy(subjectId = 1, name = "a")
        val b = SubjectInfo.Empty.copy(subjectId = 2, name = "a")
        assertNotEquals(a.toPresentation(), b.toPresentation())
    }
}
