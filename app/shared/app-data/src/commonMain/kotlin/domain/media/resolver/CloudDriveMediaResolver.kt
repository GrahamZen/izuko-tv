/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.resolver

import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveAuthException
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveRegistry
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveShareUnavailableException
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaExtraFiles
import me.him188.ani.datasources.api.Subtitle
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 播放网盘的资源, 取到播放地址后连同 Cookie 等请求头交给播放器:
 * - 自己网盘的数据源: 占位地址里是自己网盘的文件 id, 直接取地址;
 * - 分享搜索与添加的分享: 占位地址里是分享里的文件, 先转存到自己网盘再取地址.
 *
 * 占位地址属于哪个网盘按各网盘的协议认 (见 [CloudDriveRegistry.driveOf]). 视频旁边的外挂字幕一并交给播放器.
 *
 * 必须排在 [HttpStreamingMediaResolver] 前面, 后者会接下所有 [ResourceLocation.HttpStreamingFile].
 */
class CloudDriveMediaResolver(
    private val registry: CloudDriveRegistry,
) : MediaResolver {
    override fun supports(media: Media): Boolean {
        val download = media.download
        return download is ResourceLocation.HttpStreamingFile && registry.driveOf(download.uri) != null
    }

    override suspend fun resolve(media: Media, episode: EpisodeMetadata): MediaDataProvider<*> {
        val uri = media.download.uri
        val drive = registry.driveOf(uri) ?: throw UnsupportedMediaException(media)
        val shareRef = drive.placeholders.parseShareFile(uri)
        val fileId = if (shareRef == null) drive.placeholders.fileIdOf(uri) else null
        val target = fileId ?: shareRef?.key ?: throw UnsupportedMediaException(media)
        val playback = try {
            when {
                fileId != null -> drive.resolvePlayback(fileId)
                shareRef != null -> drive.resolveSharePlayback(shareRef)
                else -> throw UnsupportedMediaException(media)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudDriveAuthException) {
            logger.warn { "Cloud drive ${drive.driveId} login is required to play ${media.mediaId}: ${e.message}" }
            throw MediaResolutionException(ResolutionFailures.ENGINE_ERROR, e)
        } catch (e: CloudDriveShareUnavailableException) {
            logger.warn { "Share of ${media.mediaId} is unavailable: ${e.message}" }
            throw MediaResolutionException(ResolutionFailures.NO_MATCHING_RESOURCE, e)
        } catch (e: IOException) {
            logger.warn { "Failed to resolve ${drive.driveId} file $target: $e" }
            throw MediaResolutionException(ResolutionFailures.NETWORK_ERROR, e)
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to resolve ${drive.driveId} file $target" }
            throw MediaResolutionException(ResolutionFailures.ENGINE_ERROR, e)
        }
        return HttpStreamingMediaDataProvider(
            uri = playback.url,
            originalTitle = media.originalTitle,
            headers = playback.headers,
            extraFiles = MediaExtraFiles(
                subtitles = media.extraFiles.subtitles + playback.subtitles.map {
                    Subtitle(uri = it.url, mimeType = it.mimeType, language = it.language, label = it.label)
                },
            ).toMediampMediaExtraFiles(),
        )
    }

    private companion object {
        private val logger = logger<CloudDriveMediaResolver>()
    }
}
