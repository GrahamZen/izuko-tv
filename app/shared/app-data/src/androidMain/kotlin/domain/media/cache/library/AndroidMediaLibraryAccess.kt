/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import kotlinx.io.files.Path
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocation
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 安卓: 片库要「所有文件访问」(`MANAGE_EXTERNAL_STORAGE`, 安卓 11 起). 片库放在各盘根目录下 (内部存储是 `/storage/emulated/0`,
 * 外接盘是 `/storage/<UUID>`), 由缓存位置的私有目录 (`<盘>/Android/data/<包名>/files/…`) 倒推出盘的根目录.
 *
 * 给权限的页面依次试: 只管这个应用的授权页、所有应用的列表、应用信息页. 电视系统常常前两个都没有 (如索尼),
 * 应用信息页里「权限 → 文件和媒体」有「允许管理所有文件」; 连应用信息页都打不开时只能用 adb.
 */
class AndroidMediaLibraryAccess(private val context: Context) : MediaLibraryAccess {
    override val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun isGranted(): Boolean = isSupported && Environment.isExternalStorageManager()

    override fun libraryRootOf(location: MediaCacheLocation): SystemPath? {
        val volumeRoot = if (location.id == null) {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            location.root.absolutePath.substringBefore(APP_DIR_MARKER, missingDelimiterValue = "").takeIf { it.isNotEmpty() }
        } ?: return null
        return Path(volumeRoot).inSystem.resolve(MediaLibraryAccess.LIBRARY_DIR_NAME)
    }

    override fun openGrantPage(): MediaLibraryAccess.GrantPage? {
        if (!isSupported) return null
        val packageUri = Uri.fromParts("package", context.packageName, null)
        val pages = listOf(
            MediaLibraryAccess.GrantPage.APP to Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
            MediaLibraryAccess.GrantPage.LIST to Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            MediaLibraryAccess.GrantPage.APP_DETAILS to Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        )
        for ((page, intent) in pages) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                logger.info { "Opened the all files access page: $page" }
                return page
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return null
    }

    override val adbGrantCommand: String
        get() = "adb shell appops set --uid ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"

    private companion object {
        const val APP_DIR_MARKER = "/Android/data/"
        val logger = logger<AndroidMediaLibraryAccess>()
    }
}
