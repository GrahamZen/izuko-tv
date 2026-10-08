/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import me.him188.ani.app.data.network.RecommendationRepository
import me.him188.ani.app.data.network.TrendsRepository
import me.him188.ani.app.data.persistent.dataStores
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.repository.episode.AnimeScheduleRepository
import me.him188.ani.app.data.repository.episode.BangumiCommentRepository
import me.him188.ani.app.data.repository.episode.EpisodeCollectionRepository
import me.him188.ani.app.data.repository.episode.EpisodeCollectionSyncer
import me.him188.ani.app.data.repository.episode.EpisodeCommentRepository
import me.him188.ani.app.data.repository.episode.EpisodeProgressRepository
import me.him188.ani.app.data.repository.media.EpisodePreferencesRepository
import me.him188.ani.app.data.repository.media.EpisodePreferencesRepositoryImpl
import me.him188.ani.app.data.repository.media.SubjectTrackChoiceRepository
import me.him188.ani.app.data.repository.media.ManualBrowseMemoryRepository
import me.him188.ani.app.data.repository.media.ManualBrowseMemoryRepositoryImpl
import me.him188.ani.app.data.repository.media.MediaSourceInstanceRepository
import me.him188.ani.app.data.repository.media.MediaSourceInstanceRepositoryImpl
import me.him188.ani.app.data.repository.media.MediaSourceSubscriptionRepository
import me.him188.ani.app.data.repository.media.MikanIndexCacheRepository
import me.him188.ani.app.data.repository.media.MikanIndexCacheRepositoryImpl
import me.him188.ani.app.data.repository.media.SelectorMediaSourceEpisodeCacheRepository
import me.him188.ani.app.data.repository.person.PersonDetailsRepository
import me.him188.ani.app.data.repository.player.DanmakuRegexFilterRepository
import me.him188.ani.app.data.repository.player.DanmakuRegexFilterRepositoryImpl
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepository
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepositoryImpl
import me.him188.ani.app.data.repository.player.EpisodeScreenshotRepository
import me.him188.ani.app.data.repository.player.WhatslinkEpisodeScreenshotRepository
import me.him188.ani.app.data.repository.subject.DefaultSubjectRelationsRepository
import me.him188.ani.app.data.repository.subject.FollowedSubjectsRepository
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepositoryImpl
import me.him188.ani.app.data.repository.subject.SubjectRelationsRepository
import me.him188.ani.app.data.repository.subject.SubjectSearchCompletionRepository
import me.him188.ani.app.data.repository.subject.SubjectSearchHistoryRepository
import me.him188.ani.app.data.repository.subject.SubjectSearchRepository
import me.him188.ani.app.data.repository.torrent.peer.PeerFilterSubscriptionRepository
import me.him188.ani.app.data.repository.user.PreferencesRepositoryImpl
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.data.repository.user.TokenRepository
import me.him188.ani.app.data.repository.user.UserRepository
import me.him188.ani.app.domain.danmaku.DanmakuRepository
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.profile.UserProfile
import me.him188.ani.app.domain.profile.UserProfiles
import me.him188.ani.app.platform.Context
import me.him188.ani.app.platform.files
import me.him188.ani.utils.io.resolve
import me.him188.ani.datasources.bangumi.BangumiApiProvider
import org.koin.core.KoinApplication
import org.koin.core.scope.Scope
import org.koin.dsl.module


private val Scope.database get() = get<AniDatabase>()
private val Scope.settingsRepository get() = get<SettingsRepository>()

@Suppress("UnusedReceiverParameter")
fun KoinApplication.repositoryModules(
    getContext: () -> Context,
    coroutineScope: CoroutineScope,
) = module {
    single<UserRepository> {
        UserRepository(
            getContext().dataStores.selfInfoStore,
            // "我是谁"改由 bangumi 的 /p1/me 回答
            get<BangumiApiProvider>().miscApi,
            get(),
            get(),
        )
    }

    single<TokenRepository> { TokenRepository(getContext().dataStores.tokenStore) }

    single<EpisodePreferencesRepository> {
        EpisodePreferencesRepositoryImpl(
            getContext().dataStores.preferredAllianceStore,
            database.preferredWebMediaSourceDao(),
        )
    }
    single<SubjectTrackChoiceRepository> { SubjectTrackChoiceRepository(getContext().dataStores.preferredAllianceStore) }

    single<SubjectCollectionRepository> {
        SubjectCollectionRepositoryImpl(
            subjectService = get(),
            subjectCollectionDao = database.subjectCollection(),
//            characterDao = database.character(),
//            characterActorDao = database.characterActor(),
//            personDao = database.person(),
//            subjectCharacterRelationDao = database.subjectCharacterRelation(),
//            subjectPersonRelationDao = database.subjectPersonRelation(),
            subjectRelationsDao = database.subjectRelations(),
            animeScheduleRepository = get(),
            episodeService = get(),
            episodeCollectionDao = database.episodeCollection(),
            sessionManager = get(),
            nsfwModeSettingsFlow = settingsRepository.uiSettings.flow.map { it.searchSettings.nsfwMode },
            getEpisodeTypeFiltersUseCase = get(),
            scope = coroutineScope,
            localProfile = UserProfiles.current.isLocal,
        )
    }

    single<FollowedSubjectsRepository> {
        FollowedSubjectsRepository(
            subjectCollectionRepository = get(),
            animeScheduleRepository = get(),
            settingsRepository = get(),
            sessionManager = get(),
        )
    }

    single<SubjectSearchRepository> {
        SubjectSearchRepository(
            aniSubjectSearchService = get(),
        )
    }

    single<SubjectSearchCompletionRepository> {
        SubjectSearchCompletionRepository(
            aniSubjectSearchService = get(),
            settingsRepository = get(),
        )
    }

    single<SubjectSearchHistoryRepository> {
        SubjectSearchHistoryRepository(database.searchHistory(), database.searchTag())
    }

    single<SubjectRelationsRepository> {
        DefaultSubjectRelationsRepository(
            database.subjectCollection(),
            database.subjectRelations(),
            subjectService = get(),
            subjectCollectionRepository = get(),
            subjectSeriesIndexService = get(),
            scope = coroutineScope,
        )
    }

    single<PersonDetailsRepository> {
        PersonDetailsRepository(
            personsApi = get<BangumiApiProvider>().personApi,
            charactersApi = get<BangumiApiProvider>().characterApi,
        )
    }

    single<AnimeScheduleRepository> { AnimeScheduleRepository(get()) }

    single<BangumiCommentRepository> {
        BangumiCommentRepository(
            get(),
            database.subjectReviews(),
        )
    }

    single<EpisodeCollectionRepository> {
        EpisodeCollectionRepository(
            subjectDao = database.subjectCollection(),
            episodeCollectionDao = database.episodeCollection(),
            pendingOpDao = database.episodeCollectionPendingOpDao(),
            episodeService = get(),
            animeScheduleRepository = get(),
            subjectCollectionRepository = inject(),
            getEpisodeTypeFiltersUseCase = get(),
            onDirtyChanged = { get<EpisodeCollectionSyncer>().requestSync() },
            localProfile = UserProfiles.current.isLocal,
        )
    }

    single<EpisodeProgressRepository> {
        EpisodeProgressRepository(
            episodeCollectionRepository = get(),
            downloadManager = get(),
        )
    }

    single<EpisodeScreenshotRepository> { WhatslinkEpisodeScreenshotRepository() }

    single<EpisodeCommentRepository> { EpisodeCommentRepository(aniCommentService = get()) }

    single<MediaSourceInstanceRepository> {
        MediaSourceInstanceRepositoryImpl(getContext().dataStores.mediaSourceSaveStore)
    }

    single<MediaSourceSubscriptionRepository> {
        MediaSourceSubscriptionRepository(getContext().dataStores.mediaSourceSubscriptionStore)
    }

    single<EpisodePlayHistoryRepository> {
        EpisodePlayHistoryRepositoryImpl(
            dataStore = getContext().dataStores.episodeHistoryStore,
            playbackHistoryDao = database.playbackHistoryDao(),
        )
    }

    single<PeerFilterSubscriptionRepository> {
        PeerFilterSubscriptionRepository(
            dataStore = getContext().dataStores.peerFilterSubscriptionStore,
            ruleSaveDir = getContext().files.dataDir.resolve("peerfilter-subs"),
            httpClient = get<HttpClientProvider>().get(ScopedHttpClientUserAgent.ANI),
        )
    }

    single<TrendsRepository> {
        TrendsRepository(
            get<BangumiApiProvider>().trendingApi,
            cacheFile = getContext().files.cacheDir.resolve("trending.json"),
            backgroundScope = coroutineScope,
        )
    }

    single<RecommendationRepository> {
        RecommendationRepository(
            get<BangumiApiProvider>().subjectApi,
            database.subjectCollection(),
            get(),
            database.recommendationFeedDao(),
            get(),
            get(),
            seriesIndexService = get(),
            sessionStateProvider = get(),
            scope = coroutineScope,
            cacheDir = getContext().files.cacheDir,
            collectionsCacheFileName = UserProfiles.current.scopedFileName(UserProfile.RECOMMENDATION_COLLECTIONS_FILE_NAME),
            localProfile = UserProfiles.current.isLocal,
        )
    }

    single<DanmakuRepository> {
        DanmakuRepository(
            parentCoroutineContext = coroutineScope.coroutineContext,
            danmakuDao = database.danmakuDao(),
            httpClientProvider = get(),
            getMediaCacheUseCase = get(),
            getSubjectEpisodeInfoBundleFlowUseCase = get(),
            settingsRepository = get(),
        )
    }

    single<SettingsRepository> { PreferencesRepositoryImpl(getContext().dataStores.preferencesStore) }

    single<DanmakuRegexFilterRepository> { DanmakuRegexFilterRepositoryImpl(getContext().dataStores.danmakuFilterStore) }

    single<MikanIndexCacheRepository> { MikanIndexCacheRepositoryImpl(getContext().dataStores.mikanIndexStore) }

    single<ManualBrowseMemoryRepository> {
        ManualBrowseMemoryRepositoryImpl(getContext().dataStores.manualBrowseMemoryStore)
    }

    single<SelectorMediaSourceEpisodeCacheRepository> {
        SelectorMediaSourceEpisodeCacheRepository(
            dao = database.webSearchSessionCacheDao(),
            userTtlFlow = get<SettingsRepository>().mediaSelectorSettings.flow.map { it.webSearchCacheTtl },
        )
    }
}
