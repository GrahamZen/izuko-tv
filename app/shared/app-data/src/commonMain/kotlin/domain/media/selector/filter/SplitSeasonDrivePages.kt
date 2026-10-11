/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector.filter

import me.him188.ani.app.domain.mediasource.clouddrive.DriveMediaFolders
import me.him188.ani.datasources.api.Media

/**
 * 拆分季的页面规则 ([SplitSeasonPageMatcher]) 对网盘资源按所在文件夹分页.
 *
 * 网盘资源的条目名是当前条目、线路是数据源名, 不分页的话同一个源的各个分享、各个文件夹并成一页: 有一个文件夹装着整季 (1~19),
 * 只装本篇 (1~8) 的文件夹里对的那一集也会按季内序号被拒; 整季文件夹不全 (只到 10) 时又被当成本篇自己的页, 认回前半的同号集.
 * 每个文件夹算一页, 文件夹名能认出是本篇或整季 (如「…第四季 夺还篇」「…第四季」) 时按它判断, 否则 (没写季、写的是别的)
 * 照旧当成当前条目自己的页 —— 文件已经由网盘匹配按季核对过.
 */
internal object SplitSeasonDrivePages {
    /** [media] 所在的网盘文件夹; 不是网盘资源 (或记录已被挤出 [DriveMediaFolders]) 时为 null, 照旧与同一个源的其他资源算一页. */
    fun folderOf(media: Media): DriveMediaFolders.Folder? = DriveMediaFolders.of(media.mediaId)

    /** 页名: 网盘文件夹名能认出是本季的本篇或整季时用它, 否则是当前条目的名字 [subjectName]. */
    fun pageName(matcher: SplitSeasonPageMatcher, folder: DriveMediaFolders.Folder?, subjectName: String): String {
        val name = folder?.name ?: return subjectName
        return when (matcher.classify(name).kind) {
            SplitSeasonPageMatcher.PageKind.OWN, SplitSeasonPageMatcher.PageKind.SEASON -> name
            SplitSeasonPageMatcher.PageKind.OTHER, SplitSeasonPageMatcher.PageKind.OTHER_SEASON -> subjectName
        }
    }
}
