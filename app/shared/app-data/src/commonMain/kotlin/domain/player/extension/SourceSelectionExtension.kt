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
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.episode.MediaFetchSelectBundle
import me.him188.ani.app.domain.media.cache.CachePlayRequests
import me.him188.ani.app.domain.media.continuation.EpisodeCacheSelection
import me.him188.ani.app.domain.media.continuation.PlaybackLineageSelection
import me.him188.ani.app.domain.media.selector.AutoSelectTrace
import me.him188.ani.app.domain.media.selector.MediaSelectorAutoSelectUseCase
import me.him188.ani.app.domain.media.selector.ReplayBrowseMemoryUseCase
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.core.Koin
import kotlin.coroutines.cancellation.CancellationException

/**
 * 切集时自动选资源. 在同一协程里按顺序串行决定, 前一步没选上才走下一步, 不会互相覆盖:
 *
 * 1. 缓存 ([EpisodeCacheSelection]): 下完的缓存先播; 只有没下完的 (BT 边下边播) 时看设置
 *    [me.him188.ani.app.data.models.preference.MediaCacheSettings.playUnfinishedCacheFirst];
 *    从缓存页点进来的这一集 ([CachePlayRequests]) 没下完的也播;
 * 2. 播放线索 ([PlaybackLineageSelection]): 顺着这部番在看的版本找这一集网上的资源; 缓存拿不准 (有记录但没查出来) 而缓存该先播时
 *    ([EpisodeCacheSelection.Choice.Unknown.cachesAllowed]) 跳过: 自动选源会等缓存并先选它;
 * 3. 浏览记忆回放 ([ReplayBrowseMemoryUseCase]); 同上, 缓存拿不准而缓存该先播时不回放 (回放不能反转); 缓存本来就不能先选时照常回放;
 * 4. 自动选源 ([MediaSelectorAutoSelectUseCase]): 第 1 步先不播的缓存这里不先选, 别的都没有才选; 还没选上时播第 1 步留下的.
 *
 * 选了哪条缓存都不改线索 (见 PlaybackLineageExtension).
 * 任务挂在 session 的 sessionScope 下, 未捕获的异常会终止任务 (本集及之后 bundle 重建都失去自动选择), 所以每一步自行捕获非取消异常, 失败只是往下走.
 */
class SourceSelectionExtension(
    private val context: PlayerExtensionContext,
    koin: Koin,
) : PlayerExtension("SourceSelection") {
    private val autoSelect: MediaSelectorAutoSelectUseCase by koin.inject()
    private val replayBrowseMemory: ReplayBrowseMemoryUseCase by koin.inject()
    private val lineageSelection: PlaybackLineageSelection by koin.inject()
    private val cacheSelection: EpisodeCacheSelection by koin.inject()

    override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
        backgroundTaskScope.launch("SourceSelection") {
            AutoSelectTrace.probeDefaultDispatcher(this)
            // 任务挂在本 session 的 sessionScope 下, switchEpisode 先取消旧 scope 再换 session, 任务内不可能观察到别的 session
            episodeSession.fetchSelectFlow.collectLatest { fetchSelect ->
                if (fetchSelect == null) return@collectLatest
                AutoSelectTrace.step("fetch session ready")
                // fetchSelect 由 infoBundleFlow 派生, 拿到非空 bundle 时 replayCache 已有对应的 infoBundle.
                // infoBundleFlow 首值 null, 网络错误后也会回到 null; 用 replayCache 而不是 filterNotNull().first(), 否则会连自动选择一起挂住.
                val episode = episodeSession.infoBundleFlow.replayCache.lastOrNull()?.episodeInfo
                if (episode == null) {
                    autoSelect(fetchSelect.mediaFetchSession, fetchSelect.mediaSelector)
                    return@collectLatest
                }
                select(episode, fetchSelect)
            }
        }
    }

    private suspend fun select(episode: EpisodeInfo, bundle: MediaFetchSelectBundle) {
        val session = bundle.mediaFetchSession
        val selector = bundle.mediaSelector
        if (selector.selected.value != null) return
        val fromCachePage = CachePlayRequests.take(context.subjectId, episode.episodeId)
        val caches = step("cache") {
            cacheSelection.select(context.subjectId, episode, session, selector, fromCachePage)
        } ?: EpisodeCacheSelection.Choice.Unknown(cachesAllowed = true)
        AutoSelectTrace.step("caches: $caches")
        if (caches == EpisodeCacheSelection.Choice.Selected) return

        val cacheFirst = caches is EpisodeCacheSelection.Choice.Unknown && caches.cachesAllowed
        if (!cacheFirst && step("lineage") { lineageSelection.select(context.subjectId, episode, session, selector) } == true) return
        AutoSelectTrace.step("lineage not continued")

        val replayed = !cacheFirst &&
                step("browse memory") { replayBrowseMemory(context.subjectId, episode, selector).isHit } == true
        AutoSelectTrace.step("browse memory replayed=$replayed")
        if (replayed) return

        val deferred = (caches as? EpisodeCacheSelection.Choice.Deferred)?.unfinished.orEmpty()
        val selectCache = when (caches) {
            is EpisodeCacheSelection.Choice.Deferred -> false
            is EpisodeCacheSelection.Choice.Unknown -> caches.cachesAllowed
            else -> true
        }
        autoSelect(session, selector, selectCache = selectCache)
        if (selector.selected.value == null && deferred.isNotEmpty()) {
            logger.info { "Nothing else for episode ${episode.sort} of subject ${context.subjectId}, playing its unfinished cache" }
            selector.selectAutomatically(deferred.first(), expectedSelection = null)
        }
    }

    /** 跑一步, 出错 (非取消) 时记一笔并返回 null, 交给下一步. */
    private suspend fun <T> step(name: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(e) { "Selecting by $name failed, going on with the next step" }
        null
    }

    companion object : EpisodePlayerExtensionFactory<SourceSelectionExtension> {
        private val logger = logger<SourceSelectionExtension>()

        override fun create(context: PlayerExtensionContext, koin: Koin): SourceSelectionExtension = SourceSelectionExtension(context, koin)
    }
}
