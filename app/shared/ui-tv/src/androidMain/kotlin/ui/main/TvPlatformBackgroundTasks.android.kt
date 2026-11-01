/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_background_task_device_migration
import me.him188.ani.app.ui.lang.tv_background_task_device_migration_stop
import me.him188.ani.app.ui.lang.tv_background_task_device_migration_stopped
import me.him188.ani.app.ui.lang.tv_background_task_profile_import
import me.him188.ani.app.ui.lang.tv_background_task_profile_import_waiting
import me.him188.ani.app.ui.profile.ProfileImportSession
import me.him188.ani.app.ui.remote.RemoteDeviceMigration
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import org.jetbrains.compose.resources.stringResource

/** 导入收藏与换电视 (都是 Web 控制台发起、电视上跑的): 导入停不了; 换电视在下载阶段能停. */
@Composable
internal actual fun rememberPlatformBackgroundTasks(): List<TvBackgroundTask> {
    val import by ProfileImportSession.state.collectAsState()
    val migration by RemoteDeviceMigration.progress.collectAsState()
    val toaster = LocalToaster.current
    val tasks = mutableListOf<TvBackgroundTask>()
    (import as? ProfileImportSession.State.Running)?.let { s ->
        tasks += TvBackgroundTask(
            key = "profile-import",
            title = stringResource(Lang.tv_background_task_profile_import, s.sourceName),
            detail = if (s.waitingForRateLimit) {
                stringResource(Lang.tv_background_task_profile_import_waiting)
            } else {
                "${s.done}/${s.total}"
            },
            progress = if (s.total > 0) s.done.toFloat() / s.total else null,
        )
    }
    migration?.let { m ->
        val stoppedText = stringResource(Lang.tv_background_task_device_migration_stopped)
        tasks += TvBackgroundTask(
            key = "device-migration",
            title = stringResource(Lang.tv_background_task_device_migration),
            detail = if (m.total > 0) "${m.text} ${m.done.bytes} / ${m.total.bytes}" else m.text,
            progress = if (m.total > 0) (m.done.toFloat() / m.total).coerceIn(0f, 1f) else null,
            // 开始写入之后停不了 (停在中间会留下一半), 那时不给 ✕. 手机上的进度页自己会看到「已停止」
            stop = if (m.cancellable) {
                { if (RemoteDeviceMigration.stop()) toaster.toast(stoppedText) }
            } else {
                null
            },
            stopLabel = stringResource(Lang.tv_background_task_device_migration_stop),
        )
    }
    return tasks
}
