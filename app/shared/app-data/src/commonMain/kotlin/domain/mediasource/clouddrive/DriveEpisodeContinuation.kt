/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import me.him188.ani.app.domain.media.continuation.DriveFolderLineage
import me.him188.ani.app.domain.media.continuation.DriveShareLineage
import me.him188.ani.app.domain.media.continuation.EpisodeContinuation
import me.him188.ani.app.domain.media.continuation.LineageResolution
import me.him188.ani.app.domain.media.continuation.PlaybackLineage
import me.him188.ani.app.domain.media.continuation.coversEpisodeOf
import me.him188.ani.app.domain.mediasource.instance.MediaSourceInstance
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.topic.ResourceLocation

/**
 * 网盘资源怎么顺着上一集往下播: 分享里的文件 (分享搜索、我添加的分享) 顺着同一个分享的同一个文件夹找, 自己网盘的文件顺着同一个文件夹找.
 *
 * 线索记的位置来自搜到这个文件的时候 (见 [CloudDriveService.shareLocationOf] / [CloudDriveService.folderLocationOf]).
 * 自己网盘里直接被搜到的文件 (不在按名字搜到的文件夹里) 记不了: 它所在的可能是放着各种视频的「下载」之类.
 */
class DriveEpisodeContinuation(
    private val registry: CloudDriveRegistry,
    private val instances: Flow<List<MediaSourceInstance>>,
) : EpisodeContinuation {
    override fun owns(media: Media): Boolean = media.driveUri?.let { registry.driveOf(it) } != null

    override suspend fun lineageOf(media: Media): PlaybackLineage? {
        val uri = media.driveUri ?: return null
        val drive = registry.driveOf(uri) ?: return null
        drive.placeholders.parseShareFile(uri)?.let { ref ->
            return drive.shareLocationOf(ref)?.let { DriveShareLineage(media.mediaSourceId, drive.driveId, it) }
        }
        val folder = drive.placeholders.fileIdOf(uri)?.let { drive.folderLocationOf(it) } ?: return null
        return DriveFolderLineage(media.mediaSourceId, drive.driveId, folder)
    }

    override fun continues(lineage: PlaybackLineage, candidate: Media): Boolean {
        val uri = candidate.driveUri ?: return false
        return when (lineage) {
            is DriveShareLineage -> registry.service(lineage.driveId)?.placeholders?.parseShareFile(uri)
                ?.let { it.shareId == lineage.share.shareId && it.folderId == lineage.share.folderId } == true

            is DriveFolderLineage -> registry.service(lineage.driveId)?.placeholders?.fileIdOf(uri) != null &&
                    DriveMediaFolders.of(candidate.mediaId)?.key == lineage.folder.fid

            else -> false
        }
    }

    override suspend fun resolve(lineage: PlaybackLineage, request: MediaFetchRequest): LineageResolution {
        val sources = instances.first().filter { it.isEnabled }.map { it.source }
        val medias = when (lineage) {
            is DriveShareLineage -> {
                // 记下线索的源停用了: 同一个网盘的分享搜索源都打得开这个分享
                val source = sources.firstOrNull { it.mediaSourceId == lineage.mediaSourceId }
                    ?.takeIf { it is CloudDriveAddedShareMediaSource || it is CloudDriveShareSearchMediaSource }
                    ?: sources.filterIsInstance<CloudDriveShareSearchMediaSource>().firstOrNull { it.driveId == lineage.driveId }
                when (source) {
                    is CloudDriveAddedShareMediaSource -> source.lineageMatches(request, lineage.share)
                    is CloudDriveShareSearchMediaSource -> source.lineageMatches(request, lineage.share)
                    else -> return LineageResolution.NotFound
                }
            }

            is DriveFolderLineage -> {
                val source = sources.filterIsInstance<CloudDriveMediaSource>().firstOrNull { it.driveId == lineage.driveId }
                    ?: return LineageResolution.NotFound
                source.lineageMatches(request, lineage.folder)
            }

            else -> return LineageResolution.NotFound
        } ?: return LineageResolution.Gone
        val media = medias.firstOrNull { it.coversEpisodeOf(request) } ?: return LineageResolution.NotFound
        return LineageResolution.Found(media)
    }

    private val Media.driveUri: String? get() = (download as? ResourceLocation.HttpStreamingFile)?.uri
}
