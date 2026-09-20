/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.him188.ani.app.ui.remote.TvRemoteControl
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * Web 控制台「退出 Ani 后保留」(网页设置里的开关, 默认关, 见 TvRemoteControl.keepAliveOnExit) 的前台服务, 只为抬高进程优先级:
 * 只剩网页服务的后台进程在别的应用要内存时最先被回收 (实测正式版 status=9 importance=400), 进程一死手机就连不上.
 * 真被回收了系统会按 START_STICKY 重启本服务: 这时顺带把 Web 控制台的监听起起来 (没有界面, 网页照样能连, 能「切到前台」拉起 Ani).
 * 只在 Ani 在前台时启动 (Android 12 起后台起不了前台服务), 开关一关就停. 强制停止 / 覆盖安装挡不住.
 */
class RemoteKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!TvRemoteControl.keepAliveEnabled(applicationContext)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val started = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        }.onFailure { logger.warn(it) { "Failed to start remote keep-alive foreground service" } }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 被回收后由系统重启进来的: 没有界面, 把 Web 控制台的监听起起来 (界面在时这是空操作)
        TvRemoteControl.keepAliveService = { on -> set(applicationContext, on) }
        TvRemoteControl.ensureStarted(applicationContext)
        logger.info { "Remote keep-alive foreground service running" }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val name = getString(me.him188.ani.R.string.service_remote_keep_alive_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_MIN))
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(me.him188.ani.R.mipmap.ic_launcher)
            .setContentTitle(name)
            .setContentText(getString(me.him188.ani.R.string.service_remote_keep_alive_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private val logger = logger<RemoteKeepAliveService>()
        private const val CHANNEL_ID = "remote_keep_alive"
        private const val NOTIFICATION_ID = 41893

        /** 起 / 停常驻服务 (TvRemoteControl.keepAliveService 调); 起只在 Ani 在前台时调. */
        fun set(context: Context, on: Boolean) {
            val intent = Intent(context, RemoteKeepAliveService::class.java)
            if (on) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
                    .onFailure { logger.warn(it) { "Failed to start remote keep-alive service" } }
            } else {
                context.stopService(intent)
            }
        }
    }
}
