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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
import kotlin.time.Duration.Companion.seconds

/**
 * 给正在播的资源登记「地址被拒时怎么换新的」([PlaybackLinks]): 播放器的数据源被拒时来这里重新解析原资源, 换上新地址接着读.
 * 一个播放会话 ([PlayerSession]) 用一个, 同一时刻只登记正在播的那一条.
 */
internal class PlaybackLinkTracker(
    private val mediaResolver: () -> MediaResolver,
) {
    private class Tracked(val uri: String, val link: RefreshablePlaybackLink)

    private val current = MutableStateFlow<Tracked?>(null)

    /**
     * 正在播的这一条接不上了: 数据源断流后接着读、换地址都没读到数据 (见 [RefreshablePlaybackLink.unreachable]); 换了资源从 `false` 重新算.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val unreachable: Flow<Boolean> = current.flatMapLatest { it?.link?.unreachable ?: flowOf(false) }

    /** [unreachable] 现在的值 */
    val isUnreachable: Boolean get() = current.value?.link?.unreachable?.value == true

    /**
     * 正在播的这一条的地址被服务器拒了, 数据源又换不了 (见 [RefreshablePlaybackLink.rejected]): 要重新解析这条资源; 换了资源从 `false` 重新算.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val rejected: Flow<Boolean> = current.flatMapLatest { it?.link?.rejected ?: flowOf(false) }

    /** [rejected] 现在的值 */
    val isRejected: Boolean get() = current.value?.link?.rejected?.value == true

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
        current.value = Tracked(resolved.uri, link)
    }

    /** 停止播放: 注销 */
    fun clear() {
        val tracked = current.value ?: return
        current.value = null
        PlaybackLinks.unregister(tracked.uri, tracked.link)
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
