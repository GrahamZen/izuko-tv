/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import me.him188.ani.app.tools.update.FileDownloaderState
import me.him188.ani.utils.io.name

/**
 * 下载器停下来之后 ([downloader]), 查到的最新版 [version] 算什么状态:
 * - 用户取消了: 不算失败, 还是「有新版本」. 电视上更新状态只有一份, 各页共用, 不能停在「还没检查过」: 那样之后怎么查都显示不出结果;
 * - 下好了, 但不是这一版的安装包 (见 [isPackageOfVersion]): 还是「有新版本」, 不拿它去装.
 */
internal fun settledUpdateState(
    version: NewVersion,
    downloader: FileDownloaderState.Completed,
    preparedUrls: List<String>,
): AppUpdateState = when (downloader) {
    is FileDownloaderState.Cancelled -> AppUpdateState.HasUpdate(version)
    is FileDownloaderState.Failed -> AppUpdateState.DownloadFailed(version, downloader.throwable)
    is FileDownloaderState.Succeed ->
        if (isPackageOfVersion(downloader.file.name, version.downloadUrlAlternatives, preparedUrls)) {
            AppUpdateState.Downloaded(version, downloader.file)
        } else {
            AppUpdateState.HasUpdate(version)
        }
}

/**
 * 下好的文件 [fileName] 是不是这一版的安装包. 不是 (换了更新类型、下好之后又发了新版) 就不算「已下载」, 不拿它去装.
 *
 * 只看文件名: [packageUrls] 是这一版的安装包地址, [preparedUrls] 是实际去下的地址 (桌面端会换成别的格式), 两边的文件名都认.
 */
internal fun isPackageOfVersion(fileName: String, packageUrls: List<String>, preparedUrls: List<String>): Boolean =
    (packageUrls + preparedUrls).any { it.substringAfterLast("/", "") == fileName }
