/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.episode.MediaFetchSelectBundle
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.fetch.pauseSearching
import me.him188.ani.app.domain.media.fetch.resumePausedSources
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.datasources.api.isLocalCache
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin
import org.openani.mediamp.MediaStatus
import kotlin.time.Duration.Companion.seconds

/**
 * 「有缓存时直接播, 不搜索」(设置 - 存储, `MediaCacheSettings.playCacheWithoutSearching`): 这一集有**下载完**的缓存时,
 * 缓存以外的数据源一个都不查, 自动选源直接选中缓存. 以下情况放开, 照常搜:
 * - 缓存播不了 (解析失败、播放器报错);
 * - 选中的不是缓存 (换了别的源), 或一直没选中任何资源;
 * - 打开选源面板 (由 EpisodeViewModel 的 onMediaSelectorShown 放开, 同开播后暂停的那一套).
 *
 * 暂停那一步不在这里: 数据源被订阅了才开始查, 而会话一交出去自动选源、界面就会订阅 —— 所以由建会话的地方在交出去之前做
 * ([holdSearchingForFinishedCache]), 这里只管什么时候放开.
 *
 * @param canHold 返回 false 时放开 (选源面板开着: 用户正要看全部结果)
 * @param isEnabled 设置里开没开
 * @param hasFinishedCache 这一集 (条目, 剧集) 有没有下载完的缓存
 */
class PlayCacheWithoutSearchingExtension(
    private val context: PlayerExtensionContext,
    private val canHold: () -> Boolean,
    private val isEnabled: suspend () -> Boolean,
    private val hasFinishedCache: suspend (subjectId: Int, episodeId: Int) -> Boolean,
) : PlayerExtension("PlayCacheWithoutSearching") {

    override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
        backgroundTaskScope.launch("PlayCacheWithoutSearching") {
            context.sessionFlow.collectLatest { session ->
                session.fetchSelectFlow.collectLatest { bundle ->
                    if (bundle != null) hold(session.episodeId, bundle)
                }
            }
        }
    }

    private suspend fun hold(episodeId: Int, bundle: MediaFetchSelectBundle) {
        if (!isEnabled()) return
        if (withTimeoutOrNull(CACHE_LOOKUP_TIMEOUT) { hasFinishedCache(context.subjectId, episodeId) } != true) return
        val session = bundle.mediaFetchSession
        if (!canHold()) {
            if (session.resumePausedSources()) logger.info { "Resumed searching: the source panel is open" }
            return
        }
        val selector = bundle.mediaSelector
        // 选中了缓存就一直不搜, 直到换成别的源或缓存播不了 (会话结束 = 切集、离开播放页, 随之取消)
        val reason = if (withTimeoutOrNull(SELECTION_TIMEOUT) { selector.selected.filterNotNull().first() } == null) {
            "nothing was selected"
        } else {
            merge(
                selector.selected.filterNotNull().filter { !it.isLocalCache() }.map { "selected ${it.mediaSourceId}" },
                loadFailures().map { "the cache failed to play: $it" },
            ).first()
        }
        if (session.resumePausedSources()) logger.info { "Resumed searching: $reason" }
    }

    /** 选中的资源播不了: 解析失败或播放器报错 (同 SwitchMediaOnPlayerErrorExtension 的判据). */
    private fun loadFailures(): Flow<String> = combine(context.videoLoadingStateFlow, context.player.state) { loading, player ->
        val status = player.mediaStatus
        when {
            loading is VideoLoadingState.Failed -> loading.toString()
            status is MediaStatus.Error -> "code=${status.error.code}"
            else -> null
        }
    }.filterNotNull()

    /** [isEnabled] / [hasFinishedCache] 不给时取设置与下载管理器 (测试里换成假的). */
    class Factory(
        private val canHold: () -> Boolean = { true },
        private val isEnabled: (suspend () -> Boolean)? = null,
        private val hasFinishedCache: (suspend (subjectId: Int, episodeId: Int) -> Boolean)? = null,
    ) : EpisodePlayerExtensionFactory<PlayCacheWithoutSearchingExtension> {
        override fun create(context: PlayerExtensionContext, koin: Koin): PlayCacheWithoutSearchingExtension =
            PlayCacheWithoutSearchingExtension(
                context,
                canHold,
                isEnabled ?: { koin.get<SettingsRepository>().mediaCacheSettings.flow.first().playCacheWithoutSearching },
                hasFinishedCache ?: { subjectId, episodeId -> koin.get<MediaDownloadManager>().hasFinishedCache(subjectId, episodeId) },
            )
    }

    private companion object {
        private val logger = logger<PlayCacheWithoutSearchingExtension>()

        /** 有缓存却这么久都没选中任何资源: 放开照常搜. */
        private val SELECTION_TIMEOUT = 10.seconds
    }
}

/** [episodeId] 这一集有没有下载完的缓存 (各存储读出列表后才回答; BT 缓存边下边播也算能播, 所以要看状态是不是下完). */
internal suspend fun MediaDownloadManager.hasFinishedCache(subjectId: Int, episodeId: Int): Boolean {
    val episode = episodeId.toString()
    return snapshots(subjectId).first().any {
        it.metadata.episodeId == episode && it.status == MediaCacheState.COMPLETED && it.canPlay
    }
}

/**
 * 「有缓存时直接播, 不搜索」的暂停那一步: CreateMediaFetchSelectBundleFlowUseCase 在把会话交出去之前调 —— 那时还没人订阅数据源的结果,
 * 停住的源一次都不查. 开着选项且这一集有下载完的缓存时, 暂停缓存以外的数据源; 何时放开见 [PlayCacheWithoutSearchingExtension].
 */
internal suspend fun holdSearchingForFinishedCache(
    session: MediaFetchSession,
    settingsRepository: SettingsRepository,
    downloads: MediaDownloadManager,
    subjectId: Int,
    episodeId: Int,
): Int = session.holdForFinishedCache(
    episodeId,
    isEnabled = { settingsRepository.mediaCacheSettings.flow.first().playCacheWithoutSearching },
    hasFinishedCache = { downloads.hasFinishedCache(subjectId, episodeId) },
)

/** 同 [holdSearchingForFinishedCache], 判断换成参数 (测试用). 返回这次暂停了几个数据源. */
internal suspend fun MediaFetchSession.holdForFinishedCache(
    episodeId: Int,
    isEnabled: suspend () -> Boolean,
    hasFinishedCache: suspend () -> Boolean,
): Int {
    if (!isEnabled()) return 0
    if (withTimeoutOrNull(CACHE_LOOKUP_TIMEOUT) { hasFinishedCache() } != true) return 0
    val paused = pauseSearching(keep = { it.kind == MediaSourceKind.LocalCache })
    holdLogger.info { "Episode $episodeId has a finished cache, not searching $paused other media sources" }
    return paused
}

private val holdLogger = logger<PlayCacheWithoutSearchingExtension>()

/** 缓存列表一般一瞬间就读出来; 超过这么久就当没有缓存, 照常搜. */
private val CACHE_LOOKUP_TIMEOUT = 3.seconds
