/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.core.content.IntentCompat
import me.him188.ani.app.platform.AppRestarter

/**
 * 换用户时重启应用的中转站 (思路同 ProcessPhoenix): 跑在独立进程 ([PROCESS_SUFFIX]) 里, 结束主进程后重新打开主界面.
 *
 * 要中转是因为主进程没法自己结束了再把自己拉起来; 用 AlarmManager 定时拉起又会被 Android 10 起「后台不许启动界面」的限制拦下.
 * 这个 Activity 本身在前台, 由它启动主界面是允许的. 种子下载在自己的服务进程里, 不跟着主进程结束.
 */
class ProfileRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mainPid = intent.getIntExtra(EXTRA_MAIN_PID, -1)
        if (mainPid > 0) Process.killProcess(mainPid)
        IntentCompat.getParcelableExtra(intent, EXTRA_LAUNCH_INTENT, Intent::class.java)?.let { startActivity(it) }
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        /** 进程名后缀. 这个进程不做应用初始化 (见 AniApplication). */
        const val PROCESS_SUFFIX = "profile_restart"

        /** 重启后打开的主界面带着它: 刚选过人, 不再弹选人页. */
        const val EXTRA_PROFILE_CHOSEN = "me.him188.ani.profileChosen"

        private const val EXTRA_MAIN_PID = "mainPid"
        private const val EXTRA_LAUNCH_INTENT = "launchIntent"

        fun restart(context: Context) {
            val packageManager = context.packageManager
            val launch = packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
                ?: packageManager.getLaunchIntentForPackage(context.packageName)
                ?: error("No launch intent for ${context.packageName}")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            launch.putExtra(EXTRA_PROFILE_CHOSEN, true)
            context.startActivity(
                Intent(context, ProfileRestartActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_MAIN_PID, Process.myPid())
                    .putExtra(EXTRA_LAUNCH_INTENT, launch),
            )
        }
    }
}

class AndroidAppRestarter(private val context: Context) : AppRestarter {
    override val isSupported: Boolean get() = true
    override fun restart() = ProfileRestartActivity.restart(context)
}
