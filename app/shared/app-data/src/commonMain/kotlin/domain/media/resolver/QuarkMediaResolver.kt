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
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 播放夸克网盘数据源的资源: 用占位地址 (见 [QuarkMediaSource.uriOf]) 里的文件 id 现取播放地址, 连同 Cookie 等请求头交给播放器.
 *
 * 必须排在 [HttpStreamingMediaResolver] 前面, 后者会接下所有 [ResourceLocation.HttpStreamingFile].
 */
class QuarkMediaResolver(
    private val service: QuarkDriveService,
) : MediaResolver {
    override fun supports(media: Media): Boolean {
        val download = media.download
        return download is ResourceLocation.HttpStreamingFile && QuarkMediaSource.fileIdOf(download.uri) != null
    }

    override suspend fun resolve(media: Media, episode: EpisodeMetadata): MediaDataProvider<*> {
        val fileId = QuarkMediaSource.fileIdOf(media.download.uri) ?: throw UnsupportedMediaException(media)
        val playback = try {
            service.resolvePlayback(fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuarkAuthException) {
            logger.warn { "Quark login is required to play ${media.mediaId}: ${e.message}" }
            throw MediaResolutionException(ResolutionFailures.ENGINE_ERROR, e)
        } catch (e: IOException) {
            logger.warn { "Failed to resolve Quark file $fileId: $e" }
            throw MediaResolutionException(ResolutionFailures.NETWORK_ERROR, e)
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to resolve Quark file $fileId" }
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
