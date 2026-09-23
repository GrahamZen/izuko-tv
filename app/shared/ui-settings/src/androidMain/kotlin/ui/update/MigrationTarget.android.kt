/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import me.him188.ani.app.platform.ContextMP
import me.him188.ani.app.platform.DistributionApplicationIds
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.logger

private val logger = logger("MigrationTarget")

/** Android 11 起包可见性受限, 要在 manifest 的 `<queries>` 里列出新包名, 否则一律查不到. */
internal actual fun ContextMP.isMigrationTargetInstalled(): Boolean = try {
    packageManager.getPackageInfo(migrationTargetPackage(), 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}

internal actual fun ContextMP.launchMigrationTarget(): Boolean {
    val target = migrationTargetPackage()
    // 电视主屏认的是 LEANBACK_LAUNCHER 入口, 手机形态只有 LAUNCHER
    val intent = packageManager.getLeanbackLaunchIntentForPackage(target)
        ?: packageManager.getLaunchIntentForPackage(target)
        ?: return false
    return try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        logger.error(e) { "Failed to launch migration target $target" }
        false
    }
}

private fun Context.migrationTargetPackage(): String = DistributionApplicationIds.currentOf(packageName)
