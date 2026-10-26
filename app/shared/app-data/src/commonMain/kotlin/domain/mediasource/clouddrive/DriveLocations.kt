/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.serialization.Serializable

/**
 * 自己网盘里放着一部番的文件夹 (播放线索记下它, 见 [me.him188.ani.app.domain.media.continuation.DriveFolderLineage]).
 *
 * @property path 从搜到的那个文件夹到这个文件夹的名字 (从外到里, 最后一个是它自己): 再列它时照自动搜索一样按这些名字认季
 */
@Serializable
data class DriveFolderLocation(val fid: String, val path: List<String>)

/**
 * 网盘分享里放着一部番的文件夹 (播放线索记下它, 见 [me.him188.ani.app.domain.media.continuation.DriveShareLineage]).
 *
 * @property siteTitle 站点上的剧名, 认季用 (同搜到时)
 * @property folderId 分享里的文件夹; 文件直接放在分享根上时是根
 * @property path 分享里从根到这个文件夹的名字: 再列它时照搜到时一样按这些名字认季、认部
 */
@Serializable
data class DriveShareLocation(
    val shareId: String,
    val passcode: String,
    val siteTitle: String,
    val folderId: String,
    val path: List<String>,
)
