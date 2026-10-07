/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.media

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 网盘扫码弹窗「4:12 后过期」的倒计时文字 ([formatCountdown]).
 */
class CloudDriveQrCountdownTest {
    @Test
    fun `seconds are rounded up so it reads 0 00 exactly at expiry`() {
        assertEquals("5:00", formatCountdown(300_000))
        assertEquals("4:13", formatCountdown(252_300))
        assertEquals("4:12", formatCountdown(252_000))
        assertEquals("1:00", formatCountdown(59_001))
        assertEquals("0:01", formatCountdown(1))
        assertEquals("0:00", formatCountdown(0))
    }

    @Test
    fun `already expired stays at zero`() {
        assertEquals("0:00", formatCountdown(-5_000))
    }
}
