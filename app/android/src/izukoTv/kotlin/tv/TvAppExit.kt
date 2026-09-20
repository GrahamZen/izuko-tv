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
import android.content.Intent
import me.him188.ani.android.supportsTorrentServiceProcess
import me.him188.ani.app.domain.torrent.service.AniTorrentService
import me.him188.ani.app.platform.AniComponentActivity
import me.him188.ani.app.platform.AppTerminator
import me.him188.ani.app.platform.findActivity
import me.him188.ani.app.ui.remote.TvRemoteControl

/**
 * 退出确认弹窗 / 快捷菜单的「退出」. 平时 = [AppTerminator] (收掉 torrent 服务再退进程). 网页设置里开了「退出 Ani 后保留
 * Web 控制台」(见 TvRemoteControl.keepAliveOnExit) 时只收界面与 torrent 服务, 进程和网页服务留着 —— 以前一律 System.exit,
 * 手机随即连不上. 界面销毁后网页上要界面的操作会先把 Ani 拉起来 (见 TvRemoteControl.awaitUi).
 */
internal fun exitTvApp(context: Context, terminator: AppTerminator) {
    if (!TvRemoteControl.keepAliveOnExit()) terminator.exitApp(context, 0)
    (context.findActivity() as? AniComponentActivity)?.finishAffinity()
    if (supportsTorrentServiceProcess) {
        // 同 AppTerminator: 光 finish Activity 的话 :torrent_service 进程还挂着
        context.startService(Intent(context, AniTorrentService.actualServiceClass).apply { putExtra("stopService", true) })
    }
}
