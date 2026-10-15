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
import android.os.Build
import android.provider.Settings

/** 设备号取 `ANDROID_ID` (Android 8 起按签名与用户区分, 重装不变) 的前 4 位: 只为区分同一个人的几台电视. */
@SuppressLint("HardwareIds")
actual fun Context.deviceLabel(): String {
    val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty().take(4)
    return "${Build.MODEL} $id".trim()
}
