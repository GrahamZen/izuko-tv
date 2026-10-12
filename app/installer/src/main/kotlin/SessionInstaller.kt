/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/**
 * 「换一种方式安装」: 用 [PackageInstaller] 的安装会话装, 字节直接写进系统, 不经文件 ——
 * 部分盒子的系统安装界面读不到别的应用交来的文件, 报「解析错误」; 结果带系统给的具体原因 ([PackageInstaller.EXTRA_STATUS_MESSAGE]).
 * 不是默认的方式: 用户确认之后系统在后台装, 助手进程马上被结束 (要替换的就是它), 屏幕上看不出在装, 装好了也没有提示.
 */
object SessionInstaller {
    /**
     * 把 [apk] 写进一个会话并提交; 结果送到 [InstallStatusReceiver]. 提交之前把会话 id 交给 [onSession]:
     * 作废的旧会话也会回结果, 接收方按 id 只认这一次的.
     */
    fun install(context: Context, apk: File, onSession: (sessionId: Int) -> Unit) {
        val installer = context.packageManager.packageInstaller
        // 上一次没装完的 (确认界面被关掉而没回结果的) 作废, 不留着占空间
        for (old in installer.mySessions) {
            runCatching { installer.abandonSession(old.sessionId) }
        }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("base.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output, 64 * 1024) }
                session.fsync(output)
            }
            onSession(sessionId)
            val intent = Intent(context, InstallStatusReceiver::class.java)
            // 系统要往里填结果, 所以是可变的; 显式指向本应用的接收器
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }
}

/**
 * 会话的结果. 要用户确认时系统给一个确认界面的 Intent, 原样打开 (本应用这时在前台, 允许从接收器里开界面);
 * 其余结果交给 [InstallFlow].
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        InstallFlow.log("Install status $status of session $sessionId: $message")
        if (!InstallFlow.isCurrentSession(sessionId)) return
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm == null) {
                InstallFlow.onInstallResult(PackageInstaller.STATUS_FAILURE, message)
                return
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(confirm)
                InstallFlow.onAwaitingConfirmation(confirm)
            } catch (e: Exception) {
                InstallFlow.onInstallResult(PackageInstaller.STATUS_FAILURE, e.describe())
            }
            return
        }
        InstallFlow.onInstallResult(status, message)
    }
}
