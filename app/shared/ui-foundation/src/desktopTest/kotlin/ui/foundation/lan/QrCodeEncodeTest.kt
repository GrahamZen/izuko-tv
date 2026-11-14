/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.lan

import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class QrCodeEncodeTest {
    /** 用 zxing 的解码器把模块矩阵读回文字 (不经图片) */
    private fun decode(matrix: QrCodeMatrix): String {
        val bits = BitMatrix(matrix.size)
        for (y in 0 until matrix.size) for (x in 0 until matrix.size) if (matrix[x, y]) bits.set(x, y)
        return Decoder().decode(bits).text
    }

    @Test
    fun `chinese text survives the round trip`() {
        val text = "Izuko TV 一起看：房间号 2868 9131，密码 482 193。在电视上打开「一起看」输入。"
        assertEquals(text, decode(assertNotNull(encodeQrCode(text))))
    }

    @Test
    fun `ascii addresses encode as before`() {
        val url = "http://192.168.1.20:41893/abcdef012345678/"
        val matrix = assertNotNull(encodeQrCode(url))
        assertEquals(url, decode(matrix))
        // 42 字节正好装满版本 3 (29 模块); 多一段 ECI 就得用版本 4
        assertEquals(42, url.length)
        assertEquals(29, matrix.size)
    }
}
