/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import me.him188.ani.app.domain.episode.EpisodeFetchSelectPlayState
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.episode.UnsafeEpisodeSessionApi
import me.him188.ani.app.domain.episode.getCurrentEpisodeId
import me.him188.ani.app.domain.episode.player
import me.him188.ani.app.domain.player.extension.EpisodePlayerExtensionFactory
import me.him188.ani.app.domain.player.extension.MediaAutoSwitchStatus
import me.him188.ani.app.domain.player.extension.PlayerExtension
import me.him188.ani.app.domain.player.extension.PlayerExtensionContext
import me.him188.ani.app.domain.player.extension.PlayerExtensionEvent
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.core.Koin
import org.openani.mediamp.MediampPlayer
import kotlin.coroutines.cancellation.CancellationException

class PlayerExtensionManager(
    factories: List<EpisodePlayerExtensionFactory<*>>,
    state: EpisodeFetchSelectPlayState,
    koin: Koin,
) {
    private val context = object : PlayerExtensionContext {
        override val subjectId: Int
            get() = state.subjectId

        override val player: MediampPlayer
            get() = state.player
        override val videoLoadingStateFlow: Flow<VideoLoadingState>
            get() = state.playerSession.videoLoadingState
        override val sessionFlow: Flow<EpisodeSession>
            get() = state.episodeSessionFlow

        override val broadcastEvent: MutableSharedFlow<PlayerExtensionEvent> =
            MutableSharedFlow(0, 1, BufferOverflow.DROP_OLDEST)

        @UnsafeEpisodeSessionApi
        override suspend fun getCurrentEpisodeId(): Int {
            return state.getCurrentEpisodeId()
        }

        @OptIn(UnsafeEpisodeSessionApi::class)
        override suspend fun switchEpisode(newEpisodeId: Int) {
            if (getCurrentEpisodeId() == newEpisodeId) {
                error("Cannot switch to the same episode: $newEpisodeId")
            }

            state.switchEpisode(newEpisodeId)
        }

        override suspend fun broadcast(event: PlayerExtensionEvent) {
            broadcastEvent.emit(event)
        }

        override suspend fun reloadCurrentMedia(positionMillis: Long): Boolean {
            return state.reloadCurrentMedia(positionMillis)
        }

        override fun reportAutoSwitch(status: MediaAutoSwitchStatus) {
            state.reportAutoSwitch(status)
        }
    }

    val extensions: List<PlayerExtension> by lazy {
        factories.map { it.create(context, koin) }
    }

    inline fun call(block: (PlayerExtension) -> Unit) {
        extensions.forEach {
            try {
                block(it)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }

                throw ExtensionException("Error calling extension ${it.name}, see cause", e)
            }
        }
    }

    /**
     * [episodeId] 从哪里开始播 (见 [PlayerExtension.startPositionMillis]): 取第一个给出意见的扩展. 扩展出错只当它没有意见,
     * 从头播总比播不了好.
     */
    suspend fun startPositionMillis(episodeId: Int): Long? {
        for (extension in extensions) {
            val positionMillis = try {
                extension.startPositionMillis(episodeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn(e) { "Extension ${extension.name} failed to give a start position for episode $episodeId" }
                null
            }
            if (positionMillis != null) return positionMillis
        }
        return null
    }

    private companion object {
        private val logger = logger<PlayerExtensionManager>()
    }
}

class ExtensionException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)
