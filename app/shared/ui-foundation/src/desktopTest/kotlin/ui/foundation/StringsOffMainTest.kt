/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import kotlinx.coroutines.test.runTest
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_episode_video_loading_searching_sources
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

class StringsOffMainTest {
    private val template = Lang.subject_episode_video_loading_searching_sources

    @Test
    fun `a template read without arguments comes back as is like getString`() = runTest {
        // 文案预读不带参数读全部文案, 其中有带占位的模板: 不能拿空参数去替换
        assertEquals(getString(template), getStringOffMain(template))
    }

    @Test
    fun `arguments are filled in like getString`() = runTest {
        assertEquals(getString(template, 1, 2, 3), getStringOffMain(template, 1, 2, 3))
    }
}
