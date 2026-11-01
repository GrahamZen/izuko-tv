/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * 电视系统限制了 Ani 在后台运行没有 (Android 9 起的「后台限制」, 即应用权限 `RUN_ANY_IN_BACKGROUND` 被关; 不少电视盒子
 * 对第三方应用默认就开着, 也可能是在省电 / 后台管理里关的).
 *
 * 限制了的话 Ani 一退到后台 (屏保、切到别的应用), BT 服务当场被降成普通后台服务, 约 1 分钟后被系统停掉
 * (系统日志 `Stopping service due to app idle`), 缓存跟着停, 回到 Ani 才重新开始. 应用绕不过, 只能用户在电视上放开.
 * 厂商自己的后台清理不走这个开关, 查不出来.
 */
internal fun isBackgroundRestricted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    return runCatching { am.isBackgroundRestricted }.getOrDefault(false)
}
