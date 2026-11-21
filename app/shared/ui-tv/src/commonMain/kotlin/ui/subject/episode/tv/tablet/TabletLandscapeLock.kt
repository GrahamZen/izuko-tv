/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * 平板模式全屏时把屏幕锁成横向 (随重力在两个横向之间转): 竖着拿平板点全屏, 画面转成横的铺满, 不在竖屏里留上下两大块黑边.
 * [locked] 变 false (退出全屏、进小窗) 或离开播放页时还原成锁之前的方向.
 *
 * 只改屏幕方向, 不碰保持亮屏 (上游的 setRequestFullScreen 退出时会清掉 FLAG_KEEP_SCREEN_ON, 分栏播放时屏幕就会按系统超时熄掉).
 */
@Composable
internal fun TabletLandscapeLock(locked: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, locked) {
        if (!locked) return@DisposableEffect onDispose {}
        val before = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity.requestedOrientation = before }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
