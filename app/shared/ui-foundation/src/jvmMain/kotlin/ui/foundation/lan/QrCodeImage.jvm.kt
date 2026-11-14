/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.lan

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

actual fun encodeQrCode(content: String): QrCodeMatrix? {
    // 宽高给 0 = 只要原始模块矩阵, 缩放留给 Canvas 按实际尺寸做; 留白也自己画 (MARGIN 0)
    val hints = buildMap {
        put(EncodeHintType.MARGIN, 0)
        put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
        // zxing 默认按 ISO-8859-1 编, 中文会变成「?」: 有 ASCII 以外的字时按 UTF-8 编 (码里带一段 ECI 标明);
        // 纯 ASCII (网址) 不加, 码与原来一样
        if (content.any { it.code > 0x7F }) put(EncodeHintType.CHARACTER_SET, "UTF-8")
    }
    val bitMatrix = runCatching {
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    }.getOrNull() ?: return null // 内容超长 (几 KB 的 URL) 会抛 WriterException
    val n = bitMatrix.width
    val bits = BooleanArray(n * n)
    for (y in 0 until n) {
        for (x in 0 until n) {
            bits[y * n + x] = bitMatrix.get(x, y)
        }
    }
    return QrCodeMatrix(n, bits)
}
