/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/** 设备号取 `ANDROID_ID` (Android 8 起按签名与用户区分, 重装不变) 的前 4 位: 只为区分同一个人的几台电视. */
@SuppressLint("HardwareIds")
actual fun Context.deviceLabel(): String {
    val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty().take(4)
    return "${Build.MODEL} $id".trim()
}

/** `ANDROID_ID` 加盐取 SHA-256 的前 16 位; 个别盒子拿不到 `ANDROID_ID` 时退回型号与系统指纹 (同型号同固件会撞, 只能这样). */
@SuppressLint("HardwareIds")
actual fun Context.feedbackDeviceId(): String {
    val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        .ifBlank { "${Build.MODEL}|${Build.FINGERPRINT}" }
    return MessageDigest.getInstance("SHA-256").digest("izuko-feedback:$id".encodeToByteArray())
        .take(8).joinToString("") { "%02x".format(it) }
}

actual fun Context.feedbackEnvironment(): Map<String, String> {
    val memory = ActivityManager.MemoryInfo().also { (getSystemService(ActivityManager::class.java))?.getMemoryInfo(it) }
    val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
    return buildMap {
        put("机型", "${Build.MANUFACTURER} ${Build.MODEL}")
        put("系统", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        put("芯片", "$soc · ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}")
        if (memory.totalMem > 0) put("内存", "${memory.totalMem / (1024 * 1024)} MB")
        put("包名", packageName)
    }
}
