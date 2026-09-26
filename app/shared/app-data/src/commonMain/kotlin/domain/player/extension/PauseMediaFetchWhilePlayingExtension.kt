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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.media.fetch.pauseSearching
import me.him188.ani.app.domain.media.selector.MediaAutoSelector
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin

/**
 * 视频真正播起来 (时钟在走) 时, 暂停还没查完的数据源: 弱机上搜索和播放抢 CPU、网络与内存, 开播那一两分钟会卡.
 * 本地缓存不暂停. 还没有资源播起来之前所有数据源照常同时查询.
 *
 * [canPause] 返回 `false` 时不暂停: 选源面板开着 (开着期间一直查完), 或用户开了「完整搜索」.
 * 用户暂停播放、缓冲时不放开 (否则每次都要从头重查), 再播起来时照常判断.
 *
 * 被暂停的数据源在这些时候放开重新查: 打开选源面板、开「完整搜索」, 当前资源播放失败而手上没有可换的资源
 * (见 [PlayerLoadErrorHandler]), 或者之后的自动选源 (如切到下一集) 用不上已有的结果 (见 [MediaAutoSelector]).
 */
class PauseMediaFetchWhilePlayingExtension(
    private val context: PlayerExtensionContext,
    private val canPause: () -> Boolean,
) : PlayerExtension("PauseMediaFetchWhilePlaying") {
    override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
        backgroundTaskScope.launch("PauseMediaFetchWhilePlaying") {
            context.sessionFlow.flatMapLatest { it.fetchSelectFlow }.collectLatest { bundle ->
                if (bundle == null) return@collectLatest
                context.player.state.map { it.isPlaying }.distinctUntilChanged().filter { it }.collect {
                    if (!canPause()) return@collect
                    val paused = bundle.mediaFetchSession.pauseSearching(
                        keep = { it.kind == MediaSourceKind.LocalCache },
                    )
                    if (paused > 0) {
                        logger.info { "Playback started, paused $paused media sources that were still searching" }
                    }
                }
            }
        }
    }

    class Factory(
        private val canPause: () -> Boolean = { true },
    ) : EpisodePlayerExtensionFactory<PauseMediaFetchWhilePlayingExtension> {
        override fun create(context: PlayerExtensionContext, koin: Koin): PauseMediaFetchWhilePlayingExtension {
            return PauseMediaFetchWhilePlayingExtension(context, canPause)
        }
    }

    private companion object {
        private val logger = logger<PauseMediaFetchWhilePlayingExtension>()
    }
}
