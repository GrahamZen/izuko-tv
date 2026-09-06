/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.him188.ani.app.data.models.preference.DanmakuCacheStrategy
import me.him188.ani.app.data.persistent.database.dao.DanmakuDao
import me.him188.ani.app.data.persistent.database.dao.DanmakuEntity
import me.him188.ani.app.data.persistent.database.dao.LocalDanmakuProvider
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.episode.GetSubjectEpisodeInfoBundleFlowUseCase
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.media.cache.GetMediaCacheUseCase
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.app.ui.foundation.BackgroundScope
import me.him188.ani.app.ui.foundation.HasBackgroundScope
import me.him188.ani.danmaku.api.DanmakuContent
import me.him188.ani.danmaku.api.DanmakuInfo
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.danmaku.api.provider.DanmakuFetchResult
import me.him188.ani.danmaku.api.provider.DanmakuMatchInfo
import me.him188.ani.danmaku.api.provider.DanmakuMatchMethod
import me.him188.ani.danmaku.api.provider.DanmakuProvider
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
import me.him188.ani.danmaku.api.provider.MatchingDanmakuProvider
import me.him188.ani.danmaku.api.provider.SimpleDanmakuProvider
import me.him188.ani.danmaku.dandanplay.DandanplayDanmakuProvider
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * 管理多个弹幕源 [DanmakuProvider]
 */
class DanmakuRepository(
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
    private val danmakuDao: DanmakuDao,
    httpClientProvider: HttpClientProvider,
    private val getMediaCacheUseCase: GetMediaCacheUseCase,
    private val getSubjectEpisodeInfoBundleFlowUseCase: GetSubjectEpisodeInfoBundleFlowUseCase,
    private val settingsRepository: SettingsRepository,
) : HasBackgroundScope by BackgroundScope(parentCoroutineContext) {

    private val localProvider = LocalDanmakuProvider(danmakuDao)

    /**
     * bangumi 分集 id -> dandanplay 的弹幕库 id, 由每次拉弹幕时记下 (见 [fetchFromAllRemotes]).
     *
     * 发弹幕是发到**弹幕库**的, 而弹幕库 id 只有匹配那一步知道; 两者之间没有换算关系, 所以只能
     * 在匹配成功时顺手记账。没记到就发不了 (界面提示"先等弹幕加载完")。
     */
    private val dandanplayEpisodeIds = mutableMapOf<Int, Long>()

    /**
     * 远程弹幕源. Ani 自己的弹幕池 (以及往里发弹幕) 随 Ani 服务器一起没了, 现在只剩 dandanplay.
     */
    private val remoteProviders by lazy {
        listOf(
            DandanplayDanmakuProvider(
                dandanplayAppId = currentAniBuildConfig.dandanplayAppId,
                dandanplayAppSecret = currentAniBuildConfig.dandanplayAppSecret,
                httpClientProvider.get(),
            ),
        )
    }

    /**
     * "这条弹幕是我发的"的判据. 弹幕只能来自 dandanplay 了, 那里没有"我"这个概念, 恒为 null.
     */
    val selfId: Flow<String?> get() = flowOf(null)

    fun getInteractiveDanmakuFetcherOrNull(providerId: DanmakuProviderId): DanmakuFetcher? {
        return remoteProviders
            .firstOrNull { it is MatchingDanmakuProvider && it.providerId == providerId }
            ?.let { DanmakuFetcher(it) }
    }

    fun fetchFromAllRemotes(request: DanmakuFetchRequest): Flow<List<DanmakuFetchResult>> {
        return flow {
            val fetchers = remoteProviders.map { DanmakuFetcher(it) }
            val results = fetchers.map { fetcher ->
                fetcher.fetch(request)
            }
            val flattened = results.flatten()
            // 记下这一集对应的弹幕库 id, 发弹幕要用 (见 dandanplayEpisodeIds)
            flattened.firstNotNullOfOrNull { it.matchInfo.sourceEpisodeId }
                ?.let { dandanplayEpisodeIds[request.episodeId] = it }
            emit(flattened)
        }
    }

    fun fetchFromLocal(request: DanmakuFetchRequest): Flow<List<DanmakuFetchResult>> {
        return flow {
            val result = localProvider.fetchAutomatic(request)
            emit(result)
        }
    }

    fun cacheDanmakuIfNeeded(request: DanmakuFetchRequest) = backgroundScope.launch {
        if (shouldCache(request.subjectId, request.episodeId)) {
            val remotes = fetchFromAllRemotes(request)
            saveToLocal(request.subjectId, request.episodeId, remotes.first())
        }
    }

    fun cacheDanmakuIfNeeded(subjectId: Int, episodeId: Int, list: List<DanmakuFetchResult>) = backgroundScope.launch {
        if (shouldCache(subjectId, episodeId)) {
            saveToLocal(subjectId, episodeId, list)
        }
    }

    fun deleteDanmakuIfDontNeeded(subjectId: Int, episodeId: Int) = backgroundScope.launch {
        if (!shouldCache(subjectId, episodeId)) {
            logger.info { "deleteBySubjectAndEpisode, subjectId: $subjectId, episodeId: $episodeId" }
            danmakuDao.deleteBySubjectAndEpisode(subjectId, episodeId)
        }
    }

    private suspend fun saveToLocal(
        subjectId: Int,
        episodeId: Int,
        list: List<DanmakuFetchResult>
    ) {
        val entriesWithoutLocalSource = list
            .filter { it.providerId != DanmakuProviderId.Local }
            .flatMap { it.toEntityList(subjectId, episodeId) }
        logger.info { "saveToLocal, subjectId: $subjectId, episodeId: $episodeId, danmaku size: ${entriesWithoutLocalSource.size}" }

        if (entriesWithoutLocalSource.isNotEmpty()) {
            danmakuDao.upsertAll(entriesWithoutLocalSource)
            // todo: db 里可能有已经被删除的弹幕, 可能需要清理一下
        }
    }

    /**
     * 是否需要缓存这个剧集的弹幕到本地.
     * 
     * * 如果 strategy 不是 [DanmakuCacheStrategy.DON_NOT_CACHE], 只要有对应的 [MediaCache] 就一定缓存.
     * * 如果 strategy 是 [DanmakuCacheStrategy.CACHE_ON_MEDIA_CACHE], 只考虑是否有 [MediaCache].
     * * 如果 strategy 是 [DanmakuCacheStrategy.CACHE_ON_COLLECTION_DOING_MEDIA_PLAY], 不仅要考虑 [MediaCache], 还要考虑 [UnifiedCollectionType].
     */
    private suspend fun shouldCache(subjectId: Int, episodeId: Int): Boolean {
        val strategy = settingsRepository.mediaCacheSettings.flow.first().danmakuCacheStrategy
        val hasMediaCache = getMediaCacheUseCase(subjectId, episodeId).isNotEmpty()
        val isCollectionDoing = getSubjectEpisodeInfoBundleFlowUseCase(
            flowOf(GetSubjectEpisodeInfoBundleFlowUseCase.SubjectIdAndEpisodeId(subjectId, episodeId)),
        ).first().subjectCollectionInfo.collectionType == UnifiedCollectionType.DOING

        logger.debug {
            "shouldCache, subjectId: ${subjectId}, episodeId: ${episodeId}, strategy: $strategy, " +
                    "hasMediaCache: $hasMediaCache, isCollectionDoing: $isCollectionDoing"
        }

        return when (strategy) {
            DanmakuCacheStrategy.DON_NOT_CACHE -> false
            DanmakuCacheStrategy.CACHE_ON_MEDIA_CACHE -> hasMediaCache
            DanmakuCacheStrategy.CACHE_ON_COLLECTION_DOING_MEDIA_PLAY -> hasMediaCache || isCollectionDoing
        }
    }

    /**
     * 发一条弹幕.
     *
     * 直连之前是发到 Ani 自己的弹幕池, 现在发到 **dandanplay 的开放弹幕网络** (应用弹幕):
     * 只用应用自己的 AppId/AppSecret, 不需要用户再去登录 dandanplay, 昵称由调用方给
     * (用 bangumi 的昵称).
     *
     * @param episodeId bangumi 的分集 id (不是弹幕库 id, 见 [dandanplayEpisodeIds])
     */
    suspend fun post(
        episodeId: Int,
        danmaku: DanmakuContent,
        userName: String,
    ): DanmakuInfo {
        val provider = remoteProviders.filterIsInstance<DandanplayDanmakuProvider>().firstOrNull()
            ?: throw UnsupportedOperationException("No danmaku provider that supports sending")
        val sourceEpisodeId = dandanplayEpisodeIds[episodeId]
            ?: throw IllegalStateException("尚未匹配到弹幕库, 无法发送弹幕 (episodeId=$episodeId)")
        return try {
            provider.postDanmaku(sourceEpisodeId, danmaku, userName).also {
                logger.info { "Posted danmaku to dandanplay library $sourceEpisodeId (episodeId=$episodeId)" }
            }
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to post danmaku to dandanplay library $sourceEpisodeId" }
            throw RepositoryException.wrapOrThrowCancellation(e)
        }
    }

    private fun DanmakuFetchResult.toEntityList(subjectId: Int, episodeId: Int): List<DanmakuEntity> {
        return list.map {
            DanmakuEntity(
                id = it.id,
                subjectId = subjectId,
                episodeId = episodeId,
                serviceId = it.serviceId,
                presentationServiceId = matchInfo.serviceId,
                senderId = it.senderId,
                content = it.content,
            )
        }
    }


    companion object {
        private val logger = logger<DanmakuRepository>()
    }
}


class DanmakuFetcher(
    private val provider: DanmakuProvider
) {
    suspend fun fetch(
        request: DanmakuFetchRequest
    ): List<DanmakuFetchResult> {
        return flow {
            emit(
                withTimeout(60.seconds) {
                    when (provider) {
                        is MatchingDanmakuProvider -> {
                            provider.fetchAutomatic(request)
                        }

                        is SimpleDanmakuProvider -> {
                            provider.fetchAutomatic(request = request)
                        }
                    }
                },
            )
        }.retry(1) {
            if (it is CancellationException && !currentCoroutineContext().isActive) {
                // collector was cancelled
                return@retry false
            }
            logger.error(it) { "Failed to fetch danmaku from service '${provider.mainServiceId}'" }
            true
        }.catch {
            emit(
                listOf(
                    DanmakuFetchResult(
                        provider.providerId,
                        DanmakuMatchInfo(
                            provider.mainServiceId,
                            0,
                            DanmakuMatchMethod.NoMatch,
                        ),
                        list = emptyList(),
                    ),
                ),
            )// 忽略错误, 否则一个源炸了会导致所有弹幕都不发射了
            // 必须要 emit 一个, 否则下面 .first 会出错
        }.first()
    }

    val providerId get() = provider.providerId
    val supportsInteractiveMatching get() = provider is MatchingDanmakuProvider

    fun startInteractiveMatch(): MatchingDanmakuProvider {
        check(provider is MatchingDanmakuProvider) {
            "Provider $provider does not support interactive matching"
        }
        return provider
    }

    private companion object {
        private val logger = logger<DanmakuFetcher>()
    }
}

///**
// * 手动选择弹幕条目的会话.
// *
// * This is not thread-safe.
// */
//class InteractiveDanmakuMatchSession(
//    private val provider: MatchingDanmakuProvider,
//    private val defaultDispatcher: CoroutineContext = Dispatchers.Default,
//) {
//    private val _subjectState = MutableStateFlow<SubjectState>(SubjectState.Waiting)
//    val subjectState = _subjectState.asStateFlow()
//    private val _episodeState = MutableStateFlow<EpisodeState>(EpisodeState.Waiting)
//    val episodeState = _episodeState.asStateFlow()
//
//    suspend fun setSubjectName(name: String) {
//        withContext(defaultDispatcher) {
//            _subjectState.value = SubjectState.Fetching(name)
//            try {
//                val subjects = provider.fetchSubjectList(name)
//                _subjectState.value = SubjectState.Success(subjects)
//            } catch (e: CancellationException) {
//                _subjectState.value = SubjectState.Waiting
//                throw e
//            } catch (e: Throwable) {
//                _subjectState.value = SubjectState.Failed(e)
//            }
//        }
//    }
//
//    fun dismissSubject() {
//        _subjectState.value = SubjectState.Waiting
//    }
//
//    suspend fun setEpisodeName(subject: DanmakuSubject) {
//        withContext(defaultDispatcher) {
//            _episodeState.value = EpisodeState.Fetching
//            try {
//                val episodes = provider.fetchEpisodeList(subject)
//                _episodeState.value = EpisodeState.Success(episodes)
//            } catch (e: CancellationException) {
//                _episodeState.value = EpisodeState.Waiting
//                throw e
//            } catch (e: Throwable) {
//                _episodeState.value = EpisodeState.Failed(e)
//            }
//        }
//    }
//
//    fun dismissEpisode() {
//        _episodeState.value = EpisodeState.Waiting
//    }
//
//    sealed class SubjectState {
//        data object Waiting : SubjectState()
//
//        data class Fetching(
//            val query: String,
//        ) : SubjectState()
//
//        data class Success(
//            val subjects: List<DanmakuSubject>,
//        ) : SubjectState()
//
//        data class Failed(
//            val error: Throwable,
//        ) : SubjectState()
//    }
//
//    sealed class EpisodeState {
//        data object Waiting : EpisodeState()
//
//        data object Fetching : EpisodeState()
//
//        data class Success(
//            val episodes: List<DanmakuEpisode>,
//        ) : EpisodeState()
//
//        data class Failed(
//            val error: Throwable,
//        ) : EpisodeState()
//    }
//}
//
//@Immutable
//data class InteractiveDanmakuMatchSessionPresentation(
//    val showInputQuery: Boolean,
//    val showSelectSubjects: Boolean,
//    val subjects: List<DanmakuSubject>,
//    val showSelectEpisodes: Boolean,
//    val episodes: List<DanmakuEpisode>,
//    val showSearching: Boolean,
//
//    val error: Throwable?,
//)
//
//class InteractiveDanmakuMatchSessionPresenter(
//    private val session: InteractiveDanmakuMatchSession,
//) {
//    suspend fun setQuery(name: String) {
//        session.setSubjectName(name)
//    }
//
//    fun dismissError() {
//        session.dismissSubject()
//        session.dismissEpisode()
//    }
//
//    val presentationFlow = combine(
//        session.subjectState,
//        session.episodeState,
//    ) { subjectState, episodeState ->
//        val showInputQuery = subjectState is InteractiveDanmakuMatchSession.SubjectState.Waiting
//        val showSelectSubjects = subjectState is InteractiveDanmakuMatchSession.SubjectState.Success
//        val subjects = (subjectState as? InteractiveDanmakuMatchSession.SubjectState.Success)?.subjects ?: emptyList()
//        val showSelectEpisodes = episodeState is InteractiveDanmakuMatchSession.EpisodeState.Success
//        val episodes = (episodeState as? InteractiveDanmakuMatchSession.EpisodeState.Success)?.episodes ?: emptyList()
//        val showSearching = subjectState is InteractiveDanmakuMatchSession.SubjectState.Fetching ||
//                episodeState is InteractiveDanmakuMatchSession.EpisodeState.Fetching
//
//        val error = when {
//            subjectState is InteractiveDanmakuMatchSession.SubjectState.Failed -> subjectState.error
//            episodeState is InteractiveDanmakuMatchSession.EpisodeState.Failed -> episodeState.error
//            else -> null
//        }
//
//        InteractiveDanmakuMatchSessionPresentation(
//            showInputQuery,
//            showSelectSubjects,
//            subjects,
//            showSelectEpisodes,
//            episodes,
//            showSearching,
//            error,
//        )
//    }.shareIn(CoroutineScope(Dispatchers.Default), started = SharingStarted.WhileSubscribed(), replay = 1)
//}
