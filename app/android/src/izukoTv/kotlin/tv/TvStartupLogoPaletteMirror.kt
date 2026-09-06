/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.tv

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import me.him188.ani.android.activity.WindowBackgroundMirror
import me.him188.ani.app.ui.main.TvStartupLogoColors

/**
 * 启动页颜色 (见 `tvStartupLogoColors`) 的同步镜像: 真值要等主题设置读出来, 而启动页一打开应用就要画 (见 FormFactorStartupPlaceholder),
 * 所以每次颜色变了往 SharedPreferences 抄一份, 下次冷启动先用它. 底色就是窗口底色 ([WindowBackgroundMirror]), 这里只存进度条的两个颜色.
 * 从没抄过 (首次安装) 时是清单主题的纯黑底配白色进度条 —— 首次安装走引导, 本来就不画进度条.
 */
internal object TvStartupLogoPaletteMirror {
    private const val PREFERENCES_NAME = "tv_startup_logo"
    private const val KEY_TRACK = "track"
    private const val KEY_FILL = "fill"

    fun read(context: Context): TvStartupLogoColors {
        val background = WindowBackgroundMirror.read(context)?.let { Color(it) } ?: Color.Black
        val preferences = runCatching { context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }.getOrNull()
        fun color(key: String, fallback: Color): Color =
            if (preferences?.contains(key) == true) Color(preferences.getInt(key, 0)) else fallback
        return TvStartupLogoColors(
            background = background,
            track = color(KEY_TRACK, Color.White.copy(alpha = 0.12f)),
            fill = color(KEY_FILL, Color.White),
        )
    }

    fun write(context: Context, colors: TvStartupLogoColors) {
        runCatching {
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_TRACK, colors.track.toArgb())
                .putInt(KEY_FILL, colors.fill.toArgb())
                .apply()
        }
    }
}
