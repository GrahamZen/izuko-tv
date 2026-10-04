/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import me.him188.ani.app.domain.episode.EpisodeFetchSelectPlayState
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.episode.UnsafeEpisodeSessionApi
import me.him188.ani.app.domain.player.VideoLoadingState
import org.koin.core.Koin
import org.openani.mediamp.MediampPlayer


/**
 * An extension of the player.
 *
 * Instances are created by [EpisodePlayerExtensionFactory].
 */
abstract class PlayerExtension(
    val name: String,
) {

    /**
     * Called when the extension is allowed to launchpad background tasks.
     */
    open fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
    }

    /**
     * 选中的资源装进播放器之前问一次: 这一集从哪里开始播 (毫秒), 没有意见时返回 `null`.
     * 播放器直接从这里打开, 不用先在开头缓冲、开播后再跳过去. 各扩展都没有意见时从头播;
     * 原地重载 ([EpisodeFetchSelectPlayState.reloadCurrentMedia]) 不问, 直接回到重载给的位置.
     */
    open suspend fun startPositionMillis(episodeId: Int): Long? = null

    /**
     * Before [EpisodeFetchSelectPlayState.episodeIdFlow] switches.
     *
     * Old episode id can be obtained using [EpisodeFetchSelectPlayState.episodeIdFlow] in this method.
     */
    open suspend fun onBeforeSwitchEpisode(newEpisodeId: Int) {}

    /**
     * Called typically when view model is being cleared (user exiting the page).
     */
    open suspend fun onClose() {}
}


interface ExtensionBackgroundTaskScope {
    /**
     * Launch a background task.
     *
     * @param subName A name for the job for debugging purposes.
     */
    fun launch(subName: String, block: suspend CoroutineScope.() -> Unit): Job
}


/**
 * Currently synonymous to [EpisodeFetchSelectPlayState].
 */
interface PlayerExtensionContext {
    val subjectId: Int

    val player: MediampPlayer
    val videoLoadingStateFlow: Flow<VideoLoadingState>

    val sessionFlow: Flow<EpisodeSession>

    /**
     * A shared event channel for all extensions.
     */
    val broadcastEvent: SharedFlow<PlayerExtensionEvent>

    @UnsafeEpisodeSessionApi
    suspend fun getCurrentEpisodeId(): Int
    suspend fun switchEpisode(newEpisodeId: Int)

    suspend fun broadcast(event: PlayerExtensionEvent)

    /**
     * 把当前选中的资源原地重新装进播放器一次, 装好后回到 [positionMillis]. 选中的资源还没装进播放器时返回 `false`.
     */
    suspend fun reloadCurrentMedia(positionMillis: Long): Boolean = false

    /**
     * 自动换到了下一个资源, 交给界面显示 (见 [MediaAutoSwitchStatus]). 新资源播起来、或换集时由播放状态自己清掉.
     */
    fun reportAutoSwitch(status: MediaAutoSwitchStatus) {}
}

inline fun <reified T : PlayerExtensionEvent> PlayerExtensionContext.subscribeEvents(): Flow<T> {
    return broadcastEvent.filterIsInstance<T>()
}

interface PlayerExtensionEvent

fun interface EpisodePlayerExtensionFactory<T : PlayerExtension> {
    fun create(context: PlayerExtensionContext, koin: Koin): T
}
