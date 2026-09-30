/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.activity

import android.content.Context

/**
 * 窗口底色 (用户主题的外壳底色, 见 `AniThemeDefaults.shellBackgroundColor`) 的同步镜像.
 *
 * 真值要等主题设置 (DataStore) 读出来、界面组合时才知道, 而窗口一建好就要有底色: 应用画出第一帧之前, 以及界面留空的地方,
 * 露出来的都是它. 所以每次颜色变了往 SharedPreferences 抄一份, [MainActivity.onCreate] 同步读这份先铺上;
 * 从没抄过 (首次安装) 时是清单主题的纯黑 (`Theme.Izuko`).
 */
internal object WindowBackgroundMirror {
    private const val PREFERENCES_NAME = "ani_window_background"
    private const val KEY_COLOR = "color"

    /** 上次记下的颜色 (ARGB); 没有时为 `null`. */
    fun read(context: Context): Int? = runCatching {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (preferences.contains(KEY_COLOR)) preferences.getInt(KEY_COLOR, 0) else null
    }.getOrNull()

    fun write(context: Context, color: Int) {
        runCatching {
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_COLOR, color)
                .apply()
        }
    }
}
