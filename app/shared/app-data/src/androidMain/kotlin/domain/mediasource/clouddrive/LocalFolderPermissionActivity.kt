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
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 透明界面, 弹「存储」权限的授权框 (安卓 7.1~10 上的本地文件夹, 见 [AndroidLocalFolderAccess]), 有结果就关掉.
 *
 * 之前拒绝时勾了「不再询问」的, 系统不再弹框、直接回拒绝: 请求前后都不该解释原因 (`shouldShowRequestPermissionRationale` 两次都是 false)
 * 就是这种情况, 改开应用信息页, 在「权限」里打开. 这一次弹框里才勾的不算 (请求前是 true), 不接着跳设置页.
 */
class LocalFolderPermissionActivity : Activity() {
    private var rationaleBefore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // 重建时请求还在, 结果照样送到这里
            rationaleBefore = savedInstanceState.getBoolean(KEY_RATIONALE_BEFORE)
            return
        }
        rationaleBefore = shouldShowRequestPermissionRationale(PERMISSION)
        requestPermissions(arrayOf(PERMISSION), REQUEST_CODE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_RATIONALE_BEFORE, rationaleBefore)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        val blocked = !granted && !rationaleBefore && !shouldShowRequestPermissionRationale(PERMISSION)
        logger.info { "Storage permission for local folders: granted=$granted, blocked=$blocked" }
        if (blocked) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            } catch (_: ActivityNotFoundException) {
            }
        }
        finish()
    }

    private companion object {
        const val PERMISSION = Manifest.permission.READ_EXTERNAL_STORAGE
        const val REQUEST_CODE = 1
        const val KEY_RATIONALE_BEFORE = "rationaleBefore"
        val logger = logger<LocalFolderPermissionActivity>()
    }
}
