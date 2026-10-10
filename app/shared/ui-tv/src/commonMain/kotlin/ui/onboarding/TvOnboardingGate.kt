/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.onboarding

import android.content.Context

/**
 * 首次启动引导要不要出现: 没做过就出现, 做完 (登录或跳过) 记一个标记, 之后不再出现. 设置里「重新走一遍首次引导」把标记清掉再重启应用.
 *
 * 只看标记, 不看是不是全新安装: 从旧包名迁移过来的用户是先装落地版 1.0.0 (没有引导页)、再被它强制更新上来的,
 * 按安装时间判会被当成「升级」漏掉 —— 而他们正需要引导 (检测网络、确认登录). 本包名在 1.0.0 之前没有别的版本,
 * 所以「没有标记」就等于「还没做过引导」.
 */
object TvOnboardingGate {
    private const val PREFS = "tv_onboarding"
    private const val KEY_DONE = "done"

    fun isPending(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }

    /** 同 [markDone], 同步写盘: 换电视搬完设置后紧接着重启进程. */
    fun markDoneNow(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).commit()
    }

    /** 下次启动重新走引导. 紧接着要重启进程, 所以同步写盘. */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, false).commit()
    }
}
