/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Point
import android.view.Surface
import android.view.WindowManager

/**
 * 这台设备是不是手机. 助手是给电视用的, 有人会误装到手机上; 手机上也装得了、跑得起来, 所以只提醒, 不拦.
 *
 * 不声明 leanback、不报电视模式、还声明触摸屏的国产电视与盒子很多, 只看这几项会把它们当成手机. 手机独有的是自然方向竖屏:
 * 电视与盒子的屏幕天生是横的; 平板有的也是竖的, 靠最短边 600dp 分开 (平板上用电视界面是支持的, 不提醒).
 */
internal object FormFactor {
    private const val TABLET_SMALLEST_WIDTH_DP = 600

    fun isPhone(
        televisionMode: Boolean,
        leanback: Boolean,
        touchscreen: Boolean,
        smallestWidthDp: Int,
        naturalWidth: Int,
        naturalHeight: Int,
    ): Boolean = !televisionMode && !leanback && touchscreen &&
        smallestWidthDp in 1 until TABLET_SMALLEST_WIDTH_DP &&
        naturalHeight > naturalWidth

    fun isPhone(context: Context): Boolean {
        val configuration = context.resources.configuration
        val packages = context.packageManager
        val (naturalWidth, naturalHeight) = naturalSize(context)
        return isPhone(
            televisionMode = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION,
            leanback = packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
            touchscreen = packages.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
            smallestWidthDp = configuration.smallestScreenWidthDp,
            naturalWidth = naturalWidth,
            naturalHeight = naturalHeight,
        )
    }

    /** 屏幕转回自然方向时的宽高: 手机横着拿时也算竖屏. */
    @Suppress("DEPRECATION")
    private fun naturalSize(context: Context): Pair<Int, Int> {
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val size = Point()
        display.getRealSize(size)
        val rotated = display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270
        return if (rotated) size.y to size.x else size.x to size.y
    }
}
