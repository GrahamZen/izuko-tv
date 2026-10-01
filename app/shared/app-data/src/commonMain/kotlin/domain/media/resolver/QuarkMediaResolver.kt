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
import me.him188.ani.app.domain.mediasource.quark.QuarkAuthException
import me.him188.ani.app.domain.mediasource.quark.QuarkDriveService
import me.him188.ani.app.domain.mediasource.quark.QuarkMediaSource
import me.him188.ani.app.domain.mediasource.quark.QuarkShareFileRef
import me.him188.ani.app.domain.mediasource.quark.QuarkShareUnavailableException
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 播放夸克的资源, 取到播放地址后连同 Cookie 等请求头交给播放器:
 * - 夸克网盘数据源: 占位地址 (见 [QuarkMediaSource.uriOf]) 里是自己网盘的文件 id, 直接取地址;
 * - 夸克分享搜索数据源: 占位地址里是分享里的文件 (见 [QuarkShareFileRef]), 先转存到自己网盘再取地址.
 *
 * 必须排在 [HttpStreamingMediaResolver] 前面, 后者会接下所有 [ResourceLocation.HttpStreamingFile].
 */
class QuarkMediaResolver(
    private val service: QuarkDriveService,
) : MediaResolver {
    override fun supports(media: Media): Boolean {
        val download = media.download
        return download is ResourceLocation.HttpStreamingFile &&
                (QuarkMediaSource.fileIdOf(download.uri) != null || QuarkShareFileRef.parse(download.uri) != null)
    }

    override suspend fun resolve(media: Media, episode: EpisodeMetadata): MediaDataProvider<*> {
        val uri = media.download.uri
        val fileId = QuarkMediaSource.fileIdOf(uri)
        val shareRef = QuarkShareFileRef.parse(uri)
        val target = fileId ?: shareRef?.key ?: throw UnsupportedMediaException(media)
        val playback = try {
            when {
                fileId != null -> service.resolvePlayback(fileId)
                shareRef != null -> service.resolveSharePlayback(shareRef)
                else -> throw UnsupportedMediaException(media)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuarkAuthException) {
            logger.warn { "Quark login is required to play ${media.mediaId}: ${e.message}" }
            throw MediaResolutionException(ResolutionFailures.ENGINE_ERROR, e)
        } catch (e: QuarkShareUnavailableException) {
            logger.warn { "Quark share of ${media.mediaId} is unavailable: ${e.message}" }
            throw MediaResolutionException(ResolutionFailures.NO_MATCHING_RESOURCE, e)
        } catch (e: IOException) {
            logger.warn { "Failed to resolve Quark file $target: $e" }
            throw MediaResolutionException(ResolutionFailures.NETWORK_ERROR, e)
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to resolve Quark file $target" }
            throw MediaResolutionException(ResolutionFailures.ENGINE_ERROR, e)
        }
        return HttpStreamingMediaDataProvider(
            uri = playback.url,
            originalTitle = media.originalTitle,
            headers = playback.headers,
            extraFiles = media.extraFiles.toMediampMediaExtraFiles(),
        )
    }

    private companion object {
        private val logger = logger<QuarkMediaResolver>()
    }
}
