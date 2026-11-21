/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlinx.io.Buffer
import me.him188.ani.utils.io.DigestAlgorithm
import me.him188.ani.utils.io.readAndDigest
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIDevice

actual fun Context.deviceLabel(): String = "iOS"

/**
 * 同安卓 (SHA-256 后取前 8 个字节, 16 位十六进制): 底子是系统给本应用开发者的设备标识 (identifierForVendor, 同一台设备上不变, 删光同一开发者的应用后才换);
 * 拿不到时用第一次生成后存下的随机标识.
 */
actual fun Context.feedbackDeviceId(): String {
    val id = UIDevice.currentDevice.identifierForVendor?.UUIDString ?: storedRandomId()
    val digest = Buffer().apply { write("izuko-feedback:$id".encodeToByteArray()) }.readAndDigest(DigestAlgorithm.SHA256)
    return digest.take(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

private fun storedRandomId(): String {
    val defaults = NSUserDefaults.standardUserDefaults
    defaults.stringForKey(FEEDBACK_RANDOM_ID_KEY)?.let { return it }
    val id = NSUUID().UUIDString
    defaults.setObject(id, forKey = FEEDBACK_RANDOM_ID_KEY)
    return id
}

private const val FEEDBACK_RANDOM_ID_KEY = "izuko.feedbackRandomId"

actual fun Context.feedbackEnvironment(): Map<String, String> {
    val device = UIDevice.currentDevice
    return buildMap {
        put("机型", device.model)
        put("系统", "${device.systemName} ${device.systemVersion}")
        put("内存", "${NSProcessInfo.processInfo.physicalMemory / (1024uL * 1024uL)} MB")
    }
}
