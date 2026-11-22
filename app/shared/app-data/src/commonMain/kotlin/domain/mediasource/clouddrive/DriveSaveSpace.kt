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
 * 转存前网盘的剩余空间放不下时, 从转存文件夹里挑出要删的副本 (见 CloudDriveService 的转存).
 *
 * 那些副本是之前播放时转存的, 留着下次播同一集不用再转存, 所以先算清楚: 这次用不到的副本全删掉也放不下就一个都不删;
 * 放得下就从最早转存的删起, 删到放得下为止. 一份副本是一个视频连同按名字归它的字幕 (同一次转存过来的), 一起删;
 * 没有视频可归的字幕各算一份.
 *
 * @param folder 转存文件夹里的文件
 * @param keep 这次要用的文件 id: 不删, 归它们的字幕也不删
 * @param needed 这次要转存的文件一共多大
 * @param free 网盘剩余空间, 超额时是负数 (超出的部分也要腾出来)
 * @return 要删的文件; 本来就放得下, 或全删也放不下时为空
 */
internal fun pickSavedCopiesToFree(folder: List<DriveFile>, keep: Set<String>, needed: Long, free: Long): List<DriveFile> {
    if (free >= needed) return emptyList()
    val (subtitles, videos) = folder.partition { DriveSidecarSubtitles.isSubtitle(it.fileName) }
    val videoNames = videos.map { it.fileName }
    val subtitlesByOwner = subtitles.filter { it.fid !in keep }.groupBy { DriveSidecarSubtitles.ownerOf(it.fileName, videoNames) }
    val keptNames = videos.filter { it.fid in keep }.mapTo(HashSet()) { it.fileName }
    val copies = videos.filter { it.fid !in keep }.map { video ->
        listOf(video) + if (video.fileName in keptNames) emptyList() else subtitlesByOwner[video.fileName].orEmpty()
    } + subtitlesByOwner[null].orEmpty().map { listOf(it) }

    fun sizeOf(copy: List<DriveFile>) = copy.sumOf { it.size.coerceAtLeast(0) }
    if (free + copies.sumOf(::sizeOf) < needed) return emptyList()
    val picked = ArrayList<DriveFile>()
    var available = free
    for (copy in copies.sortedBy { it.first().updatedAt }) {
        if (available >= needed) break
        picked += copy
        available += sizeOf(copy)
    }
    return picked
}
