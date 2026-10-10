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
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 换电视: 手机粘过来的旧电视地址整理成控制台根地址. */
class RemoteDeviceMigrationTest {
    @Test
    fun `取控制台根地址 去掉标签与多余的路径`() {
        assertEquals(
            "http://192.168.1.10:41892/oldToken456/",
            RemoteDeviceMigration.parseSource("http://192.168.1.10:41892/oldToken456/#settings/maintain"),
        )
        assertEquals(
            "http://192.168.1.10:41892/oldToken456/",
            RemoteDeviceMigration.parseSource("http://192.168.1.10:41892/oldToken456"),
        )
    }

    @Test
    fun `粘的文字里夹着地址也认`() {
        assertEquals(
            "http://10.0.0.5:41892/abc/",
            RemoteDeviceMigration.parseSource("旧电视 http://10.0.0.5:41892/abc/ 复制来的"),
        )
        assertEquals(
            "http://10.0.0.5:41892/abc/",
            RemoteDeviceMigration.parseSource("地址是http://10.0.0.5:41892/abc，记得粘"),
        )
    }

    @Test
    fun `没有 token 或不是地址 不认`() {
        assertNull(RemoteDeviceMigration.parseSource("http://10.0.0.5:41892/"))
        assertNull(RemoteDeviceMigration.parseSource("10.0.0.5:41892"))
        assertNull(RemoteDeviceMigration.parseSource(""))
    }
}
