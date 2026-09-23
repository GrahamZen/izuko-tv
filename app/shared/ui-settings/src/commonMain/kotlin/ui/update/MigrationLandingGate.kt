/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import kotlinx.coroutines.delay
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds

/**
 * 落地版 (见 `AniBuildConfig.isMigrationLanding`) 等迁移彻底结束才检查更新: 接管数据、搬缓存、卸载旧版的提示都处理完.
 * 中途装上新版本会把没搬完的东西丢在半路, 卸载提示也不会再出现; 更新提示也不该压在迁移与首次引导的界面上.
 *
 * app/android 启动时接上 [migrationPending] (迁移的界面没处理完, 或本形态首次打开的设置还没做完), 其它平台恒为 `false`.
 */
object MigrationLandingGate {
    @Volatile
    var migrationPending: () -> Boolean = { false }

    suspend fun awaitMigrationDone() {
        while (migrationPending()) delay(POLL_INTERVAL)
    }

    private val POLL_INTERVAL = 2.seconds
}
