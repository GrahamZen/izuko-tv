/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import android.content.Context

/**
 * 持久调试开关 ([DevSwitch.persistent]) 在 Android 上的存储: 一份 SharedPreferences. 只有带调试区的包 (debug 包 / `-Pani.devConsole=true`
 * 编的性能对比包) 才接上, 正式包不读存下的值.
 *
 * 在 `Application.attachBaseContext` 里调: 比 ContentProvider 与 `onCreate` 都早, 进程启动时就生效的开关读得到存下的值.
 */
object DevSwitchesAndroid {
    fun install(context: Context) {
        if (!currentAniBuildConfig.isDebug && !currentAniBuildConfig.devConsole) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        DevSwitches.attachStore(
            object : DevSwitchStore {
                override fun load(key: String): String? = prefs.getString(key, null)
                override fun save(key: String, raw: String) {
                    prefs.edit().putString(key, raw).apply()
                }
            },
        )
    }

    private const val PREFS_NAME = "dev_switches"
}
