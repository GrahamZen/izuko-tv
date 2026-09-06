/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.episode.displayName
import me.him188.ani.app.data.models.player.EpisodeHistory
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepository
import me.him188.ani.app.domain.episode.EpisodeFetchSelectPlayState
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.episode.SubjectEpisodeInfoBundle
import me.him188.ani.app.domain.episode.UnsafeEpisodeSessionApi
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.source.MediaData
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 记忆播放进度.
 *
 * 在以下情况时保存播放进度:
 * - 开始或恢复播放 5 秒后
 * - 播放中每分钟
 * - 切换数据源
 * - 暂停
 * - 播放完成
 *
 * 播放完成时位置照常保存 (钳到时长以内), 记录不会被删除; 恢复时由 [EpisodeHistory.isFinished] 判断是否从头播放.
 */
class RememberPlayProgressExtension(
    private val context: PlayerExtensionContext,
    koin: Koin,
    private val periodicReportInterval: Duration = 1.minutes,
    private val initialReportDelay: Duration = 5.seconds,
) : PlayerExtension(name = "SaveProgressExtension") {
    private val playProgressRepository: EpisodePlayHistoryRepository by koin.inject()
    private val latestInfoBundleMutex = Mutex()
    private val latestInfoBundles = mutableMapOf<Int, SubjectEpisodeInfoBundle>()

    override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
        val mediaLoaded = CompletableDeferred<Unit>()
        backgroundTaskScope.launch("MediaLoadedListener") {
            context.subscribeEvents<EpisodeFetchSelectPlayState.MediaLoadedEvent>().collectLatest { event ->
                if (event.episodeId == episodeSession.episodeId && mediaLoaded.isActive) {
                    mediaLoaded.complete(Unit)
                }
            }
        }

        /**
         * 原地重载 (见 [EpisodeFetchSelectPlayState.MediaReloadEvent]) 要回到的位置: 重载前播放器出了错, 没法按常规存进度,
         * 而存下的进度最久是一分钟前的. 下一次恢复进度用它, 用完即清.
         */
        val reloadPositionMillis = MutableStateFlow<Long?>(null)
        backgroundTaskScope.launch("MediaReloadListener") {
            context.subscribeEvents<EpisodeFetchSelectPlayState.MediaReloadEvent>().collect { event ->
                if (event.episodeId == episodeSession.episodeId) {
                    reloadPositionMillis.value = event.positionMillis
                }
            }
        }

        backgroundTaskScope.launch("InfoBundleCache") {
            episodeSession.infoBundleFlow.filterNotNull().collect { info ->
                latestInfoBundleMutex.withLock {
                    latestInfoBundles[info.episodeId] = info
                }
            }
        }

        backgroundTaskScope.launch("MediaSelectorListener") {
            mediaLoaded.await() // 播放器开始播放了再跑这个 extension
            episodeSession.fetchSelectFlow.collectLatest inner@{ fetchSelect ->
                if (fetchSelect == null) return@inner

                fetchSelect.mediaSelector.events.onBeforeSelect.collect {
                    // 切换 数据源 前保存播放进度
                    savePlayProgressOrRemove(episodeSession)
                }
            }
        }

        backgroundTaskScope.launch("PlaybackStateListener") {
            val player = context.player
            var haveResumedOnce = false
            // 已经恢复过记忆进度的媒体. 播放器在原位置重新打开同一个 MediaData 时 (Android 上视频输出超时后恢复播放),
            // 保留播放器打开时的位置, 不跳回记忆的进度.
            var resumedMediaData: MediaData? = null

            /**
             * 恢复历史进度, 至多一次.
             *
             * **不要求播放器正在播**: 能不能 seek 的真正前提是"时长已知"(下面 `first` 等的就是它),
             * `isPlaying` 只是个凑巧同时成立的代理条件. 而在电视的保留会话里它恰恰不成立 ——
             * 退出播放页会把播放器按成暂停, 后台于是永远到不了 isPlaying, 进度也就永远不恢复:
             * 用户回到页面才开播、才 seek、才二次缓冲, "后台就绪"的提示因此必然说谎
             * (2026-08-11 真机确认: 本地文件已就绪 1 分 43 秒, 播放器一直没动, 回页面那一秒才开播).
             * 见 `RetainedPlaybackSessionHolder` 的第 3 条.
             */
            suspend fun restoreSavedPositionOnce() {
                val mediaData = player.mediaData.value
                if (mediaData != null && mediaData === resumedMediaData) {
                    haveResumedOnce = true
                }
                if (haveResumedOnce) return
                val positionMillis = reloadPositionMillis.getAndUpdate { null }
                    ?: playProgressRepository.getResumePositionMillisByEpisodeId(episodeSession.episodeId)
                if (positionMillis == null) {
                    logger.info { "Did not find saved position" }
                    haveResumedOnce = true
                    resumedMediaData = mediaData
                    return
                }
                logger.info { "Loaded saved position: $positionMillis, waiting for video properties" }
                player.mediaProperties.first { (it?.durationMillis ?: 0L) > 0L }
                withContext(Dispatchers.Main + NonCancellable) { // android must call in main thread
                    logger.info { "Video properties ready, seeking to saved position: $positionMillis" }
                    player.seekTo(positionMillis)
                    // seek 引起的状态变化会取消本次收集, 标记必须在 NonCancellable 内完成
                    haveResumedOnce = true
                    resumedMediaData = mediaData
                }
            }

            player.state.collectLatest { state ->
                when {
                    state.mediaStatus == MediaStatus.Opening -> {
                        // 新媒体正在打开, 重置恢复进度标记
                        haveResumedOnce = false
                    }

                    state.isPlaying -> {
                        // Some backends (notably desktop mpv) report playing before the loaded file accepts seeks.
                        // Restore once metadata is ready, but only report after playback remains active for 5 seconds.
                        restoreSavedPositionOnce()

                        delay(initialReportDelay)
                        savePlayProgressOrRemove(episodeSession, allowZeroPosition = true)

                        if (periodicReportInterval != Duration.INFINITE) {
                            while (true) {
                                delay(periodicReportInterval)
                                savePlayProgressOrRemove(episodeSession, allowZeroPosition = true)
                            }
                        }
                    }

                    state.mediaStatus == MediaStatus.Ready && !state.playWhenReady -> { // 暂停
                        // 媒体已经打开就可以恢复进度了, 不必等它真的播起来 —— 电视的保留会话
                        // 在后台就一直停在这个状态 (见 restoreSavedPositionOnce).
                        // 放在 await 之前: 下面那句会一直挂到播放器至少播过一次为止.
                        restoreSavedPositionOnce()
                        mediaLoaded.await() // 播放器开始播放了一次之后再保存状态
                        savePlayProgressOrRemove(episodeSession)
                    }

                    state.mediaStatus == MediaStatus.Ended -> { // 播放完成
                        mediaLoaded.await() // 播放器开始播放了一次之后再保存状态
                        savePlayProgressOrRemove(episodeSession)
                    }

                    else -> Unit
                }
            }

        }
    }

    @OptIn(UnsafeEpisodeSessionApi::class)
    override suspend fun onBeforeSwitchEpisode(newEpisodeId: Int) {
        savePlayProgressOrRemove(context.getCurrentEpisodeId())
    }

    @OptIn(UnsafeEpisodeSessionApi::class)
    override suspend fun onClose() {
        savePlayProgressOrRemove(context.getCurrentEpisodeId())
    }

    private suspend fun savePlayProgressOrRemove(
        episodeSession: EpisodeSession,
        allowZeroPosition: Boolean = false,
    ) {
        savePlayProgressOrRemove(episodeSession.episodeId, episodeSession, allowZeroPosition)
    }

    private suspend fun savePlayProgressOrRemove(
        episodeId: Int
    ) {
        savePlayProgressOrRemove(episodeId, null)
    }

    private suspend fun savePlayProgressOrRemove(
        episodeId: Int,
        episodeSession: EpisodeSession?,
        allowZeroPosition: Boolean = false,
    ) {
        val player = context.player
        val mediaStatus = player.state.value.mediaStatus
        val videoDurationMillis = player.mediaProperties.value?.durationMillis

        if (videoDurationMillis == null || videoDurationMillis <= 0L) {
            return
        }

        // 只在媒体已加载 (Ready/Ended) 时保存
        if (mediaStatus != MediaStatus.Ready && mediaStatus != MediaStatus.Ended) {
            return
        }

        val currentPositionMillis = player.currentPositionMillis.value

        if (currentPositionMillis < 0L || (currentPositionMillis == 0L && !allowZeroPosition)) {
            return
        }

        // 有些后端上报的位置会略微超过时长 (#1506), 钳到时长以内, 保证进度比例不超过 1 且能被识别为已看完.
        val info = latestInfoBundle(episodeId, episodeSession)
        playProgressRepository.saveOrUpdate(
            episodeId = episodeId,
            positionMillis = currentPositionMillis.coerceAtMost(videoDurationMillis),
            subjectId = info?.subjectId,
            episodeSort = info?.episodeInfo?.sort?.number,
            subjectName = info?.subjectInfo?.displayName,
            subjectImageUrl = info?.subjectInfo?.imageLarge,
            episodeName = info?.episodeInfo?.displayName,
            durationMillis = videoDurationMillis,
        )
    }

    private suspend fun latestInfoBundle(
        episodeId: Int,
        episodeSession: EpisodeSession?,
    ): SubjectEpisodeInfoBundle? {
        episodeSession?.infoBundleFlow?.replayCache?.lastOrNull()?.let { return it }

        return latestInfoBundleMutex.withLock {
            latestInfoBundles[episodeId]
        }
    }

    companion object : EpisodePlayerExtensionFactory<RememberPlayProgressExtension> {
        override fun create(context: PlayerExtensionContext, koin: Koin): RememberPlayProgressExtension =
            RememberPlayProgressExtension(context, koin)

        private val logger = logger<RememberPlayProgressExtension>()
    }
}
