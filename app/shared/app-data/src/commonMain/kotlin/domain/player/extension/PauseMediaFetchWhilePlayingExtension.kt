/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchResult
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.media.fetch.pauseSearching
import me.him188.ani.app.domain.media.selector.MediaAutoSelector
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveAddedShareMediaSource
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin

/**
 * 视频真正播起来 (时钟在走) 时, 暂停还没查完的数据源: 弱机上搜索和播放抢 CPU、网络与内存, 开播那一两分钟会卡.
 * 本地缓存与「我添加的分享」不暂停: 都很轻 (后者只读用户给这部番添加的那几个分享), 而且后者常在开播的同时刚加上分享、正要查.
 * 还没有资源播起来之前所有数据源照常同时查询.
 *
 * 只认播放状态从没在播变成在播的那一刻: 换了查询会话 (如重新搜索) 重新挂上时视频已经在播, 不算开始播放.
 *
 * [canPause] 返回 `false` 时不暂停: 选源面板开着 (开着期间一直查完), 或用户开了「完整搜索」.
 * [selectorOpened] 返回 `true` (这个查询会话里打开过选源面板) 时只暂停还没开始查的, 正在查的让它查完 (见 [pauseSearchingWhilePlaying]).
 * 用户暂停播放、缓冲时不放开 (否则每次都要从头重查), 再播起来时照常判断.
 *
 * 被暂停的数据源在这些时候放开重新查: 打开选源面板、开「完整搜索」, 当前资源播放失败而手上没有可换的资源
 * (见 [PlayerLoadErrorHandler]), 或者之后的自动选源 (如切到下一集) 用不上已有的结果 (见 [MediaAutoSelector]).
 */
class PauseMediaFetchWhilePlayingExtension(
    private val context: PlayerExtensionContext,
    private val canPause: () -> Boolean,
    private val selectorOpened: (MediaFetchSession) -> Boolean,
) : PlayerExtension("PauseMediaFetchWhilePlaying") {
    override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
        backgroundTaskScope.launch("PauseMediaFetchWhilePlaying") {
            context.sessionFlow.flatMapLatest { it.fetchSelectFlow }.collectLatest { bundle ->
                if (bundle == null) return@collectLatest
                context.player.playbackStarts().collect {
                    if (!canPause()) return@collect
                    val session = bundle.mediaFetchSession
                    val paused = session.pauseSearchingWhilePlaying(selectorOpened(session), keep = ::keepsSearching)
                    if (paused > 0) {
                        logger.info { "Playback started, paused $paused media sources that were still searching" }
                    }
                }
            }
        }
    }

    private fun keepsSearching(result: MediaSourceFetchResult): Boolean =
        result.kind == MediaSourceKind.LocalCache || result.sourceInfo == CloudDriveAddedShareMediaSource.INFO

    class Factory(
        private val canPause: () -> Boolean = { true },
        private val selectorOpened: (MediaFetchSession) -> Boolean = { false },
    ) : EpisodePlayerExtensionFactory<PauseMediaFetchWhilePlayingExtension> {
        override fun create(context: PlayerExtensionContext, koin: Koin): PauseMediaFetchWhilePlayingExtension {
            return PauseMediaFetchWhilePlayingExtension(context, canPause, selectorOpened)
        }
    }

    private companion object {
        private val logger = logger<PauseMediaFetchWhilePlayingExtension>()
    }
}

/**
 * 正在播放时暂停搜索 (开播、关选源面板时): 这个查询会话里没开过选源面板 ([selectorOpened] 为 `false`) 时, 还在查的全停下,
 * 直到打开面板或开「完整搜索」; 开过面板后只停还没开始查的, 正在查的让它查完 —— 用户看过面板要的就是这些结果,
 * 打断了下次放开又要从头多发一遍. [keep] 返回 `true` 的不停.
 *
 * @return 这次新暂停的数据源个数
 */
fun MediaFetchSession.pauseSearchingWhilePlaying(
    selectorOpened: Boolean,
    keep: (MediaSourceFetchResult) -> Boolean,
): Int = pauseSearching(keep = { keep(it) || (selectorOpened && it.state.value == MediaSourceFetchState.Working) })
