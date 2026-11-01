/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package me.him188.ani.app.ui.foundation.lan

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetInterpolationQuality
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGInterpolationNone
import platform.CoreImage.CIContext
import platform.CoreImage.CIFilter
import platform.CoreImage.createCGImage
import platform.CoreImage.filterWithName
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Foundation.setValue

/**
 * 用系统的 CoreImage (CIQRCodeGenerator, 纠错 M 档) 编码: 生成的图一个模块一个像素, 画进 RGBA 位图后按亮度读出模块.
 * 网盘扫码登录要用手机扫 iPad 屏幕上的码, 所以 iOS 上也要画得出来.
 */
actual fun encodeQrCode(content: String): QrCodeMatrix? {
    val bytes = content.encodeToByteArray()
    if (bytes.isEmpty()) return null
    val data = bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert())
    }
    // filterWithName 与 createCGImage 在 CIFilter / CIContext 的分类里, Kotlin/Native 导成要单独 import 的扩展
    val filter = CIFilter.filterWithName("CIQRCodeGenerator") ?: return null
    filter.setValue(data, forKey = "inputMessage")
    filter.setValue("M", forKey = "inputCorrectionLevel")
    val image = filter.outputImage ?: return null
    val size = image.extent.useContents { size.width.toInt() }
    if (size <= 0) return null
    val cgImage = CIContext().createCGImage(image, fromRect = image.extent) ?: return null
    val rgba = ByteArray(size * size * 4)
    val colorSpace = CGColorSpaceCreateDeviceRGB()
    try {
        rgba.usePinned { pinned ->
            val context = CGBitmapContextCreate(
                data = pinned.addressOf(0),
                width = size.convert(),
                height = size.convert(),
                bitsPerComponent = 8.convert(),
                bytesPerRow = (size * 4).convert(),
                space = colorSpace,
                bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
            ) ?: return null
            try {
                CGContextSetInterpolationQuality(context, kCGInterpolationNone)
                CGContextDrawImage(context, CGRectMake(0.0, 0.0, size.toDouble(), size.toDouble()), cgImage)
            } finally {
                @Suppress("DEPRECATION")
                CGContextRelease(context)
            }
        }
    } finally {
        CGColorSpaceRelease(colorSpace)
        CGImageRelease(cgImage)
    }
    // 位图第一行是图的顶边; 不透明的暗像素是黑模块
    return QrCodeMatrix(size, BooleanArray(size * size) { index ->
        val offset = index * 4
        val red = rgba[offset].toInt() and 0xff
        val alpha = rgba[offset + 3].toInt() and 0xff
        alpha > 127 && red < 128
    })
}
