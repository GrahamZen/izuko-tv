/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.pip

import android.app.Activity
import android.app.ActivityManager
import android.content.pm.PackageManager
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

private val logger = logger("PictureInPictureSupport")

/**
 * 这台设备能不能用画中画. 很多电视 / 盒子没有画中画这项系统功能; 低内存设备上系统也不开 (多窗口被关掉, 声明了功能也一样).
 * 不能用时设置小窗参数、进入小窗, 系统都直接抛 IllegalStateException ("Device doesn't support picture-in-picture mode"),
 * 而播放页一打开就会设置小窗参数.
 */
internal fun Activity?.supportsPictureInPicture(): Boolean {
    if (this == null) return false
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
    return getSystemService(ActivityManager::class.java)?.isLowRamDevice != true
}

/**
 * 调画中画的系统接口. [supportsPictureInPicture] 之外系统仍可能拒绝 (厂商改过的系统关了多窗口), 拒绝时记一行日志, 不让它带走整个应用.
 */
internal fun runPictureInPictureCall(action: String, block: () -> Unit) {
    try {
        block()
    } catch (e: IllegalStateException) {
        logger.warn(e) { "Picture-in-picture refused by the system: $action" }
    }
}
