/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.CancellationException
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.domain.media.resolver.resolveFreshLink
import me.him188.ani.app.videoplayer.link.PlaybackLink
import me.him188.ani.app.videoplayer.link.PlaybackLinks
import me.him188.ani.app.videoplayer.link.RefreshablePlaybackLink
import me.him188.ani.datasources.api.Media
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.UriMediaData
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds

/**
 * 给正在播的资源登记「地址被拒时怎么换新的」([PlaybackLinks]): 播放器的数据源被拒时来这里重新解析原资源, 换上新地址接着读.
 * 一个播放会话 ([PlayerSession]) 用一个, 同一时刻只登记正在播的那一条.
 */
internal class PlaybackLinkTracker(
    private val mediaResolver: () -> MediaResolver,
) {
    private class Tracked(val uri: String, val link: RefreshablePlaybackLink)

    @Volatile
    private var tracked: Tracked? = null

    /**
     * 开始播 [media]: [resolved] 是解析出来的, [played] 是交给播放器的.
     * 两者不是同一个时 (经本机代理播放, 上游由代理去取) 不登记; 解析不出新地址的资源 (见 [resolveFreshLink]) 登记了也只是换不了.
     */
    fun track(media: Media, episode: EpisodeMetadata, resolved: MediaData, played: MediaData) {
        clear()
        if (resolved !is UriMediaData || played !== resolved) return
        val link = RefreshablePlaybackLink(PlaybackLink(resolved.uri, resolved.headers)) {
            val fresh = try {
                mediaResolver().resolveFreshLink(media, episode, timeout = RESOLVE_TIMEOUT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to resolve a fresh link of ${media.mediaId}" }
                null
            }
            logger.info {
                "Playback link of ${media.mediaId} was rejected, " +
                        if (fresh == null) "no fresh one is available" else "resolved a fresh one"
            }
            fresh?.let { PlaybackLink(it.uri, it.headers) }
        }
        PlaybackLinks.register(resolved.uri, link)
        tracked = Tracked(resolved.uri, link)
    }

    /** 停止播放: 注销 */
    fun clear() {
        val current = tracked ?: return
        tracked = null
        PlaybackLinks.unregister(current.uri, current.link)
    }

    private companion object {
        private val logger = logger<PlaybackLinkTracker>()

        /**
         * 重新解析最多等这么久: 被拒的那个请求这时停着, 而被拒不一定是过期 (偶尔一次, 原地址再试就好), 解析卡住时不能让它跟着等一分钟.
         * 网盘取直链平时一两秒.
         */
        private val RESOLVE_TIMEOUT = 15.seconds
    }
}
