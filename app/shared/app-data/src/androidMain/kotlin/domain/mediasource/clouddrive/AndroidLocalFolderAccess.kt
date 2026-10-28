/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import me.him188.ani.app.domain.media.cache.library.MediaLibraryAccess

/**
 * 安卓: 11 起用片库的「所有文件访问」([MediaLibraryAccess]). 7.1~10 用「存储」权限 (只读, `READ_EXTERNAL_STORAGE`),
 * 外接盘上的文件同样读得到; 安卓 10 还要清单里 `requestLegacyExternalStorage` 才能按路径读. 授权框由 [LocalFolderPermissionActivity] 弹.
 */
class AndroidLocalFolderAccess(
    private val context: Context,
    private val library: MediaLibraryAccess,
) : LocalFolderAccess {
    override val allFiles: Boolean get() = library.isSupported

    override fun isGranted(): Boolean = if (allFiles) {
        library.isGranted()
    } else {
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    override fun openGrant(): Boolean {
        if (allFiles) return library.openGrantPage() != null
        return try {
            context.startActivity(Intent(context, LocalFolderPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    override val adbGrantCommand: String
        get() = if (allFiles) {
            library.adbGrantCommand.orEmpty()
        } else {
            "adb shell pm grant ${context.packageName} ${Manifest.permission.READ_EXTERNAL_STORAGE}"
        }
}
