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
import java.io.File

/**
 * 安装助手 (app/installer, 与本应用同包名) 把下好的安装包放在缓存目录的这个子目录里 (见 `InstallFlow.downloadDir`).
 * 本应用装上来是同包名升级, 数据目录原样保留, 那份几十 MB 的安装包就留了下来; 启动时删掉.
 */
private const val INSTALLER_DOWNLOAD_DIR = "install-izuko"

internal fun deleteInstallerLeftovers(context: Context) {
    val dir = File(context.cacheDir, INSTALLER_DOWNLOAD_DIR)
    if (dir.exists()) dir.deleteRecursively()
}
