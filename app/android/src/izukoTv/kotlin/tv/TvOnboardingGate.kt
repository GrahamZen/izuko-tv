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

/**
 * 首次启动引导要不要出现: 没做过就出现, 做完 (登录或跳过) 记一个标记, 之后不再出现.
 *
 * 只看标记, 不看是不是全新安装: 从旧包名迁移过来的用户是先装落地版 1.0.0 (没有引导页)、再被它强制更新上来的,
 * 按安装时间判会被当成「升级」漏掉 —— 而他们正需要引导 (检测网络、确认登录). 本包名在 1.0.0 之前没有别的版本,
 * 所以「没有标记」就等于「还没做过引导」.
 */
internal object TvOnboardingGate {
    private const val PREFS = "tv_onboarding"
    private const val KEY_DONE = "done"

    fun isPending(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }
}
