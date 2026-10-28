/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

/**
 * 网盘满了转存不进去时, 从转存文件夹里挑出要删的副本 (见 CloudDriveService 的转存).
 *
 * 那些副本是之前播放时转存的, 留着下次播同一集不用再转存, 所以先按剩余空间 [free] 算好要腾出多少, 只删够用的, 先删最早转存的;
 * 删光也放不下就一个都不删. 算不清时也不删: 不知道剩余空间, 或者按剩余空间本来就放得下 (网盘的数对不上, 删了也不知道有没有用).
 *
 * @param clearable 转存文件夹里这次用不到的副本
 * @param needed 这次要转存的文件一共多大
 * @param free 网盘剩余空间, 超额时是负数 (超出的部分也要腾出来); 不知道时为 `null`
 * @return 要删的文件; 为空表示不删, 直接报空间不够
 */
internal fun pickSavedCopiesToFree(clearable: List<DriveFile>, needed: Long, free: Long?): List<DriveFile> {
    if (free == null || free >= needed) return emptyList()
    if (free + clearable.sumOf { it.size } < needed) return emptyList()
    val picked = ArrayList<DriveFile>()
    var available = free
    for (file in clearable.sortedBy { it.updatedAt }) {
        if (available >= needed) break
        picked += file
        available += file.size
    }
    return picked
}
