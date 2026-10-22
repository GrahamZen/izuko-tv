/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FullSearchModeTest {
    @Test
    fun `not chosen follows the device`() {
        val settings = MediaSelectorSettings.Default
        assertNull(settings.fullSearchMode)
        assertEquals(FullSearchMode.Manual, settings.effectiveFullSearchMode(lowRamDevice = true))
        assertEquals(FullSearchMode.OnPanel, settings.effectiveFullSearchMode(lowRamDevice = false))
    }

    @Test
    fun `a chosen mode wins on every device`() {
        for (mode in FullSearchMode.entries) {
            val settings = MediaSelectorSettings.Default.copy(fullSearchMode = mode)
            assertEquals(mode, settings.effectiveFullSearchMode(lowRamDevice = true))
            assertEquals(mode, settings.effectiveFullSearchMode(lowRamDevice = false))
        }
    }

    @Test
    fun `stored settings from the old switch read as not chosen`() {
        // 存储层 ignoreUnknownKeys (见 DataStoreMP): 旧的布尔「始终完整搜索」读不出来, 落回按设备定
        val json = Json { ignoreUnknownKeys = true }
        val settings = json.decodeFromString(MediaSelectorSettings.serializer(), """{"alwaysFullSearch":true}""")
        assertNull(settings.fullSearchMode)
    }
}
