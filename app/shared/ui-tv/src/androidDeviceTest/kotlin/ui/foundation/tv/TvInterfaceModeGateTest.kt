/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 有触摸屏的设备默认哪种模式 ([TvInterfaceModeGate]): 没选过时跟系统是否声明为电视, 选过就按选的.
 */
class TvInterfaceModeGateTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun clearChoice() {
        context.getSharedPreferences("tv_interface_mode", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @BeforeTest
    fun setUp() = clearChoice()

    @AfterTest
    fun tearDown() = clearChoice()

    @Test
    fun `without a stored choice the mode follows the leanback feature`() {
        assertEquals(
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
            TvInterfaceModeGate.isTvMode(context),
        )
    }

    @Test
    fun `a stored choice wins over the leanback feature`() {
        TvInterfaceModeGate.setTvMode(context, false)
        assertFalse(TvInterfaceModeGate.isTvMode(context))

        TvInterfaceModeGate.setTvMode(context, true)
        assertTrue(TvInterfaceModeGate.isTvMode(context))
    }
}
