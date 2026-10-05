/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.data.network.AniEpisodeCommentService
import me.him188.ani.app.data.network.AniSubjectSearchService
import me.him188.ani.app.data.network.BangumiBangumiCommentServiceImpl
import me.him188.ani.app.data.network.BangumiCommentService
import me.him188.ani.app.data.network.BangumiRelatedPeopleService
import me.him188.ani.app.data.network.BangumiSummaryService
import me.him188.ani.app.data.network.EpisodeService
import me.him188.ani.app.data.network.EpisodeServiceImpl
import me.him188.ani.app.data.network.GitHubDownloadMirrors
import me.him188.ani.app.data.network.RemoteSubjectService
import me.him188.ani.app.data.network.SequelSeasonTableRepository
import me.him188.ani.app.data.network.SubjectFeedbackService
import me.him188.ani.app.data.network.SubjectSeriesIndexService
import me.him188.ani.app.data.network.SubjectService
import me.him188.ani.app.data.network.TmdbImageEndpoints
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.network.TmdbSubjectMapRepository
import me.him188.ani.app.data.network.TrendsRepository
import me.him188.ani.app.data.network.schedule.BangumiScheduleSource
import me.him188.ani.app.data.persistent.dataStores
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.DeviceAniDatabase
import me.him188.ani.app.data.persistent.database.MIGRATION_19_20
import me.him188.ani.app.data.persistent.database.MIGRATION_21_22
import me.him188.ani.app.data.persistent.database.MIGRATION_24_25
import me.him188.ani.app.data.persistent.database.SelfRatingTagsRepair
import me.him188.ani.app.data.persistent.database.createDatabaseBuilder
import me.him188.ani.app.data.persistent.database.databaseFile
import me.him188.ani.app.data.repository.episode.EpisodeCollectionRepository
import me.him188.ani.app.data.repository.episode.EpisodeCollectionSyncer
import me.him188.ani.app.data.repository.media.MediaSourceSaves
import me.him188.ani.app.data.repository.media.MediaSourceSubscriptionRepository
import me.him188.ani.app.data.repository.repositoryModules
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.data.repository.subject.SubjectNsfw
import me.him188.ani.app.data.repository.torrent.peer.PeerFilterSubscriptionRepository
import me.him188.ani.app.data.repository.user.AccessTokenSession
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.data.repository.user.TokenRepository
import me.him188.ani.app.data.repository.user.UserRepository
import me.him188.ani.app.domain.foundation.AlternativeEndpointsFeature
import me.him188.ani.app.domain.foundation.AlternativeEndpointsFeatureHandler
import me.him188.ani.app.domain.foundation.BangumiEndpointProvider
import me.him188.ani.app.domain.foundation.BangumiMirrorConsent
import me.him188.ani.app.domain.foundation.BangumiMirrorConsentRequests
import me.him188.ani.app.domain.foundation.BangumiMirrorFeature
import me.him188.ani.app.domain.foundation.BangumiMirrorFeatureHandler
import me.him188.ani.app.domain.foundation.BangumiMirrorListRepository
import me.him188.ani.app.domain.foundation.ConvertSendCountExceedExceptionFeature
import me.him188.ani.app.domain.foundation.ConvertSendCountExceedExceptionFeatureHandler
import me.him188.ani.app.domain.foundation.CookieJarFeatureHandler
import me.him188.ani.app.domain.foundation.DefaultHttpClientProvider
import me.him188.ani.app.domain.foundation.DefaultHttpClientProvider.HoldingInstanceMatrix
import me.him188.ani.app.domain.foundation.DefaultVersionExpiryService
import me.him188.ani.app.domain.foundation.DeviceBrowserUserAgentHolder
import me.him188.ani.app.domain.foundation.DistributionChannelFeatureHandler
import me.him188.ani.app.domain.foundation.GlobalHttpEventBus
import me.him188.ani.app.domain.foundation.GlobalHttpEvents
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.MaxRequestsPerHostFeatureHandler
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.ServerListFeature
import me.him188.ani.app.domain.foundation.ServerListFeatureConfig
import me.him188.ani.app.domain.foundation.SseFeatureHandler
import me.him188.ani.app.domain.foundation.UseBangumiTokenFeatureHandler
import me.him188.ani.app.domain.foundation.UserAgentFeature
import me.him188.ani.app.domain.foundation.UserAgentFeatureHandler
import me.him188.ani.app.domain.foundation.VersionExpiryFeatureHandler
import me.him188.ani.app.domain.foundation.VersionExpiryService
import me.him188.ani.app.domain.foundation.WebSourceIdentityFeatureHandler
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.foundation.withValue
import me.him188.ani.app.domain.media.cache.PikPakWebM3uCacheMigration
import me.him188.ani.app.domain.media.cache.engine.AlwaysUseTorrentEngineAccess
import me.him188.ani.app.domain.media.cache.engine.HttpMediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.KtorPersistentHttpDownloader
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.cache.engine.PlaybackYieldingGate
import me.him188.ani.app.domain.media.cache.engine.TorrentMediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.createCacheDownloadDispatcher
import me.him188.ani.app.domain.media.cache.storage.HttpMediaCacheStorage
import me.him188.ani.app.domain.media.cache.storage.MediaSaveDirProvider
import me.him188.ani.app.domain.media.cache.storage.TorrentMediaCacheStorage
import me.him188.ani.app.domain.media.download.DownloadOperations
import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.media.fetch.MediaSourceManagerImpl
import me.him188.ani.app.domain.media.player.PlaybackActivity
import me.him188.ani.app.domain.mediasource.codec.MediaSourceCodecManager
import me.him188.ani.app.domain.mediasource.quark.QuarkAddedShareService
import me.him188.ani.app.domain.mediasource.quark.QuarkDriveService
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscriptionRequesterImpl
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscriptionUpdater
import me.him188.ani.app.domain.mediasource.web.PageEvaluator
import me.him188.ani.app.domain.mediasource.web.captcha.BrowserImageCaptchaSolver
import me.him188.ani.app.domain.mediasource.web.captcha.CaptchaBrowserFactory
import me.him188.ani.app.domain.mediasource.web.captcha.GirigiriSearchRoute
import me.him188.ani.app.domain.mediasource.web.captcha.ImageCaptchaRecognizer
import me.him188.ani.app.domain.mediasource.web.captcha.MacCmsImageCaptchaSolver
import me.him188.ani.app.domain.mediasource.web.captcha.WebSessionManager
import me.him188.ani.app.domain.mediasource.web.captcha.WebSourceCookieJar
import me.him188.ani.app.domain.mediasource.web.captcha.WebSourceIdentityRegistry
import me.him188.ani.app.domain.profile.LocalProfileConversion
import me.him188.ani.app.domain.profile.LocalProfileImporter
import me.him188.ani.app.domain.profile.ProfileArchiver
import me.him188.ani.app.domain.profile.SelfCollectionRecords
import me.him188.ani.app.domain.profile.UserProfile
import me.him188.ani.app.domain.profile.UserProfileManager
import me.him188.ani.app.domain.profile.UserProfileRegistry
import me.him188.ani.app.domain.profile.UserProfileSeeder
import me.him188.ani.app.domain.profile.UserProfiles
import me.him188.ani.app.domain.profile.fetchAllCollectedSubjectIds
import me.him188.ani.app.domain.session.BangumiSessionRefresher
import me.him188.ani.app.domain.session.SessionManager
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.domain.session.auth.BangumiOAuthClient
import me.him188.ani.app.domain.session.auth.BangumiOAuthManager
import me.him188.ani.app.domain.session.auth.BangumiOAuthRelayClient
import me.him188.ani.app.domain.settings.ProxyProvider
import me.him188.ani.app.domain.settings.SettingsBasedProxyProvider
import me.him188.ani.app.domain.torrent.TorrentEngineType
import me.him188.ani.app.domain.torrent.TorrentManager
import me.him188.ani.app.domain.torrent.engines.PikPakEngine
import me.him188.ani.app.domain.update.UpdateManager
import me.him188.ani.app.domain.usecase.useCaseModules
import me.him188.ani.app.platform.AppRestarter
import me.him188.ani.app.ui.subject.details.state.DefaultSubjectDetailsStateFactory
import me.him188.ani.app.ui.subject.details.state.SubjectDetailsStateFactory
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.bangumi.BangumiApiProvider
import me.him188.ani.datasources.bangumi.BangumiClient
import me.him188.ani.datasources.bangumi.BangumiClientImpl
import me.him188.ani.torrent.pikpak.PikPakCredentials
import me.him188.ani.torrent.pikpak.PikPakSessionStoreAdapter
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.coroutines.childScope
import me.him188.ani.utils.coroutines.childScopeContext
import me.him188.ani.utils.httpdownloader.HttpDownloader
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.core.KoinApplication
import org.koin.core.scope.Scope
import org.koin.dsl.module

private val Scope.client get() = get<BangumiClient>()
private val Scope.database get() = get<AniDatabase>()
/** 缓存索引那几张表只从整机库取, 见 [DeviceAniDatabase]. */
private val Scope.deviceDatabase get() = get<DeviceAniDatabase>().database
private val Scope.settingsRepository get() = get<SettingsRepository>()
private val Scope.bangumiApiProvider get() = get<BangumiApiProvider>()

/**
 * 各端共享的 Koin 装配，默认包含完整缓存/BT 模块。
 *
 * [enableMediaCache] 为 false 时只绑定空存储的 [MediaDownloadManager]，不注册 [HttpDownloader]。
 */
fun KoinApplication.getCommonKoinModule(
    getContext: () -> Context,
    coroutineScope: CoroutineScope,
    enableMediaCache: Boolean = true,
) = listOf(
    useCaseModules(),
    repositoryModules(getContext, coroutineScope),
    otherModules(getContext, coroutineScope, enableMediaCache),
)

private fun KoinApplication.otherModules(
    getContext: () -> Context,
    coroutineScope: CoroutineScope,
    enableMediaCache: Boolean,
) = module {
    // Application services
    single<ProxyProvider> { SettingsBasedProxyProvider(get(), coroutineScope) }
    single<SessionManager> {
        SessionManager(
            tokenRepository = get(),
            coroutineScope = coroutineScope,
            // 刷新走 bangumi 自己的 refresh_token; 它的 accessToken 只有 7 天
            refreshSession = BangumiSessionRefresher({ get<BangumiOAuthClient>() }, { get<BangumiOAuthRelayClient>() }),
            beforeNewLogin = {
                database.subjectCollection().resetAllLastFetched()
                database.episodeCollection().resetAllLastFetched()
            },
        )
    }
    single<BangumiOAuthClient> {
        // 换 token 这一步**不能带 bangumi token** (给 bgm.tv 的 Authorization 头是另一回事),
        // 所以要一个不装 UseBangumiTokenFeature 的裸客户端
        BangumiOAuthClient(get<HttpClientProvider>().get())
    }
    single<BangumiOAuthRelayClient> { BangumiOAuthRelayClient(get<HttpClientProvider>().get()) }
    single<BangumiOAuthManager> {
        BangumiOAuthManager(
            client = get(),
            sessionManager = get(),
            browserFactory = get(),
            scope = coroutineScope,
            trustedMirrorRoot = { get<BangumiEndpointProvider>().trustedMirrorRoot.value },
            // 多人共用时内置浏览器里可能还登着上一个人的 bgm 账号
            clearWebLoginBeforeInAppBrowser = { UserProfiles.registry.state.value.profiles.size > 1 },
            relay = get(),
        )
    }
    single<SessionStateProvider> {
        get<SessionManager>().stateProvider
    }
    // 数据源发请求时用得到本机浏览器 UA; 数据源由工厂创建拿不到 Context, 在这里装进去
    DeviceBrowserUserAgentHolder.install { getContext().deviceBrowserUserAgent() }

    single<BangumiMirrorListRepository> {
        BangumiMirrorListRepository(
            cache = get<SettingsRepository>().bangumiMirrorCache,
            // 惰性: HttpClientProvider 反过来要经 BangumiEndpointProvider 拿到本仓库, 见构造参数的说明
            client = { get<HttpClientProvider>().get() },
            scope = coroutineScope,
        )
    }
    single<TmdbImageEndpoints> {
        val settings = get<SettingsRepository>()
        TmdbImageEndpoints(
            selection = settings.tmdbImageEndpoint,
            listCache = settings.tmdbImageHostCache,
            // 惰性: HttpClientProvider 反过来要装经本对象换入口的处理器, 见 RepoHostedList 的构造参数
            client = { get<HttpClientProvider>().get() },
            scope = coroutineScope,
        )
    }
    single<GitHubDownloadMirrors> {
        GitHubDownloadMirrors(
            listCache = get<SettingsRepository>().githubDownloadMirrorCache,
            client = { get<HttpClientProvider>().get() },
            scope = coroutineScope,
        )
    }
    single<SubjectFeedbackService> {
        SubjectFeedbackService(
            listCache = get<SettingsRepository>().feedbackEndpointCache,
            client = { get<HttpClientProvider>().get() },
            scope = coroutineScope,
        )
    }
    single<BangumiMirrorConsentRequests> { BangumiMirrorConsentRequests() }
    single<BangumiEndpointProvider> {
        val settings = get<SettingsRepository>().bangumiEndpointSettings
        BangumiEndpointProvider(
            settings = settings.flow,
            mirrors = get<BangumiMirrorListRepository>().mirrors,
            scope = coroutineScope,
            switchToMirror = {
                val current = settings.flow.first()
                val loggedIn = get<TokenRepository>().session.first() is AccessTokenSession
                // 已登录而凭证不经过镜像: 不替用户改, 请界面问他 (见 BangumiMirrorConsent)
                if (BangumiMirrorConsent.check(current, current.afterOriginUnreachable(), loggedIn) != null) {
                    get<BangumiMirrorConsentRequests>().request()
                } else {
                    settings.update { afterOriginUnreachable() }
                }
            },
        )
    }
    single<HttpClientProvider> {
        val sessionManager by inject<SessionManager>()
        DefaultHttpClientProvider(
            get(), coroutineScope,
            featureHandlers = listOf(
                UserAgentFeatureHandler,
                // bangumi 镜像改写 + 原站不通时回落. 必须在这里注册 —— 没注册的特性会被
                // DefaultHttpClientProvider.extendWithNotSet 静默丢掉
                get<BangumiEndpointProvider>().let {
                    BangumiMirrorFeatureHandler(it.routing, it::reportSettled, it::reportOriginUnreachable)
                },
                // 可换入口的服务 (TMDB 图片…): 请求时换到选定 / 连得上的入口
                AlternativeEndpointsFeatureHandler(listOf(get<TmdbImageEndpoints>())),
                UseBangumiTokenFeatureHandler(
                    sessionManager.sessionFlow.map {
                        (it as? AccessTokenSession)?.tokens?.bangumiAccessToken
                    },
                ),
                DistributionChannelFeatureHandler { currentAniBuildConfig.distroChannel },
                ConvertSendCountExceedExceptionFeatureHandler,
                VersionExpiryFeatureHandler, // handle 426 Upgrade Required -> show blocking dialog
                SseFeatureHandler,
                CookieJarFeatureHandler, // web 数据源统一 cookie jar (构造时注入)
                WebSourceIdentityFeatureHandler, // web 数据源 per-host UA 对齐
                MaxRequestsPerHostFeatureHandler,
            ),
        )
    }
    // Web 数据源验证码处理 (docs/contributing/code/media/web-captcha.md)
    single<WebSourceCookieJar> { WebSourceCookieJar() }
    single<WebSourceIdentityRegistry> { WebSourceIdentityRegistry() }
    single<WebSessionManager> {
        val browserFactory = get<CaptchaBrowserFactory>()
        val evaluator = PageEvaluator()
        val recognizer = get<ImageCaptchaRecognizer>()
        val settingsRepository = get<SettingsRepository>()
        WebSessionManager(
            browserFactory = browserFactory,
            evaluator = evaluator,
            cookieJar = get(),
            identityRegistry = get(),
            client = get<HttpClientProvider>().get(
                userAgent = ScopedHttpClientUserAgent.BROWSER,
                cookieJar = get(),
                identityRegistry = get(),
            ),
            backgroundScope = coroutineScope,
            solvers = listOf(
                MacCmsImageCaptchaSolver(recognizer),
                BrowserImageCaptchaSolver(recognizer),
            ),
            solverEnabled = {
                settingsRepository.mediaSelectorSettings.flow.first().enableImageCaptchaAutoSolve
            },
            searchRoutes = listOf(GirigiriSearchRoute(evaluator)),
            maxSessions = browserFactory.recommendedMaxSessions,
            lowRamDevice = getContext().isLowRamDevice(),
        )
    }
    single<VersionExpiryService> { DefaultVersionExpiryService() }
    // Wire Global HTTP event bus to VersionExpiryService
    run {
        val service = koin.inject<VersionExpiryService>()
        GlobalHttpEventBus = object : GlobalHttpEvents {
            override fun onVersionExpired(latestVersion: String?) {
                service.value.onVersionExpired(latestVersion)
            }
        }
    }
    single<BangumiApiProvider> { BangumiApiProvider(get<HttpClientProvider>().get(useBangumiToken = true)) }
    single<BangumiClient> {
        BangumiClientImpl(
            get<HttpClientProvider>().get(
                userAgent = ScopedHttpClientUserAgent.ANI,
            ),
        )
    }

    single<AniSubjectSearchService> {
        AniSubjectSearchService(
            bangumiV0Api = bangumiApiProvider.v0Api,
        )
    }

    // Data layer network services
    single<SubjectService> {
        RemoteSubjectService(
            bangumiApiProvider.subjectApi,
            bangumiApiProvider.collectionApi,
            sessionManager = get(),
        )
    }
    single<EpisodeService> { EpisodeServiceImpl(bangumiApiProvider.v0Api) }

    single<BangumiRelatedPeopleService> { BangumiRelatedPeopleService(bangumiApiProvider.subjectApi) }
    single<BangumiScheduleSource> {
        BangumiScheduleSource(
            // 名册/分集/bangumi-data 都是公开数据, 用匿名客户端
            client = get<HttpClientProvider>().get(),
            store = getContext().dataStores.animeScheduleCacheStore,
        )
    }
    single<BangumiCommentService> { BangumiBangumiCommentServiceImpl(bangumiApiProvider.subjectApi) }
    single<AniEpisodeCommentService> { AniEpisodeCommentService(bangumiApiProvider.episodeApi, bangumiApiProvider.miscApi) }
    single(createdAtStart = true) {
        EpisodeCollectionSyncer(
            repository = get<EpisodeCollectionRepository>(),
            // 直连: 看过状态直接推给 Bangumi (EpisodeService 的 v0 批量端点)
            pusher = { subjectId, episodeIds, type ->
                get<EpisodeService>().setEpisodeCollection(subjectId, episodeIds, type)
            },
            sessionStateProvider = get(),
            scope = coroutineScope,
        ).also { it.start() }
    }
    single<SubjectSeriesIndexService> {
        SubjectSeriesIndexService(bangumiApiProvider.subjectApi, scope = coroutineScope)
    }

    // AnimeScheduleService (Ani 服务器的时间表接口) 已删, 时间表改直连 bangumi
    single<SequelSeasonTableRepository> {
        SequelSeasonTableRepository(
            cache = getContext().dataStores.sequelSeasonTableStore,
            tableFile = getContext().files.dataDir.resolve("bgm-sequel-seasons.tsv"),
            client = { get<HttpClientProvider>().get() },
            scope = coroutineScope,
        )
    }
    single<TmdbSubjectMapRepository> {
        TmdbSubjectMapRepository(
            cache = getContext().dataStores.tmdbSubjectMapStore,
            mapFile = getContext().files.dataDir.resolve("tmdb-subject-map.tsv"),
            client = { get<HttpClientProvider>().get() },
            enabled = get<SettingsRepository>().tmdbImagesDisabled.flow.map { !it },
            scope = coroutineScope,
        )
    }
    // TV 横版 backdrop / 分集剧照; 未配置 ani.tmdb.api.token 时自动关闭
    single<TmdbImageService> {
        // 系列索引传单例: 各建一份的话同一条目的 BFS 会算两遍, 见 TmdbImageService.seriesIndexService
        TmdbImageService(
            get(),
            getContext().dataStores.tmdbImageCacheStore,
            disabledByUserFlow = get<SettingsRepository>().tmdbImagesDisabled.flow,
            injectedSeriesIndexService = get(),
            subjectMap = get(),
            // 用时再取: 构造时就要 BangumiEndpointProvider 会与 HttpClientProvider 绕成环
            bangumiRouting = { get<BangumiEndpointProvider>().currentRouting },
        )
    }
    single<BangumiSummaryService> { BangumiSummaryService(get()) }

    single<UpdateManager> {
        UpdateManager(
            // Android FileProvider 共享整个 updates/ 目录, 见 file_paths.xml
            rootDir = getContext().files.cacheDir.resolve("updates"),
        )
    }

    // 当前用户的库; 缓存索引那几张表另从整机库取 (1 号用户在用时两者是同一个实例)
    single<AniDatabase> { buildAniDatabase(getContext(), UserProfiles.current.databaseFileName) }
    single<DeviceAniDatabase> {
        if (UserProfiles.current.isPrimary) {
            DeviceAniDatabase(get<AniDatabase>())
        } else {
            DeviceAniDatabase(buildAniDatabase(getContext(), UserProfile.PRIMARY_DATABASE_FILE_NAME))
        }
    }
    single<UserProfileRegistry> { UserProfiles.registry }
    single<UserProfileManager> {
        UserProfileManager(
            registry = get(),
            restarter = getOrNull<AppRestarter>() ?: AppRestarter.Unsupported,
            deleteFiles = { profile -> deleteUserProfileFiles(getContext(), profile) },
            beforeSwitch = { target -> get<UserProfileSeeder>().seed(target) },
        )
    }
    // 把本地用户的收藏加到当前登录的 Bangumi 账号 (只增不删), 见 LocalProfileImporter
    single<LocalProfileImporter> {
        LocalProfileImporter(
            deviceDatabase = get(),
            openDatabase = { fileName -> buildAniDatabase(getContext(), fileName) },
            fetchBangumiCollectedIds = { get<SubjectService>().fetchAllCollectedSubjectIds() },
            isCollectedOnBangumi = { subjectId ->
                // 条目接口带着当前账号对它的收藏 (interest), 没收藏时为 null
                val subject = checkNotNull(get<SubjectService>().getSubjectCollection(subjectId)) {
                    "Subject $subjectId is not accessible on Bangumi"
                }
                subject.interest != null
            },
            addBangumiCollection = { subjectId, update -> get<SubjectService>().patchSubjectCollection(subjectId, update) },
            markBangumiEpisodesWatched = { subjectId, episodeIds ->
                // 没登录 / 条目没收藏时它返回 false 而不抛, 同样算失败
                check(get<EpisodeService>().setEpisodeCollection(subjectId, episodeIds, UnifiedCollectionType.DONE)) {
                    "Bangumi did not accept watched episodes of subject $subjectId"
                }
            },
            afterImport = {
                database.subjectCollection().resetAllLastFetched()
                database.episodeCollection().resetAllLastFetched()
                get<SubjectService>().invalidateCollectionCounts()
            },
        )
    }
    // 本地用户导出成文件 / 从文件导入当前的本地用户 (Web 控制台), 见 ProfileArchiver
    single<ProfileArchiver> {
        ProfileArchiver(
            currentProfile = { UserProfiles.current },
            currentDatabase = database,
            deviceDatabase = get(),
            openDatabase = { fileName -> buildAniDatabase(getContext(), fileName) },
            setLocalCollectionType = { subjectId, type ->
                // 本地档的仓库只写本地库; 当前用户若是登录 Bangumi 的, 同一个调用会发请求改他的账号
                check(UserProfiles.current.isLocal) { "Archives can only be restored into a local profile" }
                get<SubjectCollectionRepository>().setSubjectCollectionTypeOrDelete(subjectId, type)
            },
            // 本地档的重取保留库里的收藏状态、评分与看过
            loadEpisodes = { subjectId -> get<SubjectCollectionRepository>().refreshSubjectCollection(subjectId) },
        )
    }
    // 当前用户自己的收藏记录 (计数 / 清除); 没登录的 1 号改成本地用户, 见 LocalProfileConversion
    single<SelfCollectionRecords> { SelfCollectionRecords(database) }
    single<LocalProfileConversion> {
        LocalProfileConversion(
            manager = get(),
            records = get(),
            // 刚启动时登录状态要等刷新令牌才有; 等不到按登录着算 (不改)
            isLoggedIn = {
                withTimeoutOrNull(5.seconds) { get<SessionStateProvider>().stateFlow.first() is SessionState.Valid } ?: true
            },
            clearSession = { get<UserRepository>().clearSelfInfo() },
        )
    }
    // 换人之前给对方垫上首页轮播那几部 (热度榜手上那份; 没有就算了, 不为这个等网络)
    single<UserProfileSeeder> {
        UserProfileSeeder(
            currentDatabase = get(),
            deviceDatabase = get(),
            openDatabase = { fileName -> buildAniDatabase(getContext(), fileName) },
            subjectIds = {
                withTimeoutOrNull(500.milliseconds) {
                    get<TrendsRepository>().getTrendsInfo().subjects
                        .take(TrendsRepository.HERO_CAROUSEL_SIZE)
                        .map { it.bangumiId }
                }.orEmpty()
            },
        )
    }

    // Bound even without media cache: SettingsViewModel, which TV also uses, injects it lazily.
    single<PikPakEngine> {
        val settings = get<SettingsRepository>()

        // Cache restoration reads isSupported synchronously; a default placeholder could discard valid records.
        val savedConfig = runBlocking { settings.pikpakConfig.flow.first() }
        val configState = settings.pikpakConfig.flow
            .stateIn(coroutineScope, SharingStarted.Eagerly, savedConfig)
        fun PikPakConfig.toCredentials() = takeIf {
            it.enabled && it.username.isNotEmpty() && (it.password.isNotEmpty() || it.refreshToken.isNotEmpty())
        }?.let { PikPakCredentials(it.username, it.password) }
        val credentials = configState
            .map { it.toCredentials() }
            .stateIn(coroutineScope, SharingStarted.Eagerly, savedConfig.toCredentials())

        PikPakEngine(
            config = configState,
            credentials = credentials,
            sessionStore = PikPakSessionStoreAdapter(
                readRefreshToken = { account ->
                    configState.value.takeIf { it.username == account }?.refreshToken.orEmpty()
                },
                // A request started before an account switch must not replace the new account's token.
                writeRefreshToken = { account, rt ->
                    settings.pikpakConfig.update {
                        if (username == account) copy(refreshToken = rt) else this
                    }
                },
            ),
            client = get<HttpClientProvider>().get(ScopedHttpClientUserAgent.NONE),
            saveDir = Path(get<MediaSaveDirProvider>().saveDir, TorrentEngineType.PikPak.id).inSystem,
            parentCoroutineContext = coroutineScope.coroutineContext,
        )
    }

    single {
        DownloadOperations(
            downloadManager = get(),
            deleteCache = get(),
            executionScope = coroutineScope.childScope(),
        )
    }

    // 正在播放什么: 播放页上报, 缓存下载据此给播放让路
    single { PlaybackActivity() }

    if (enableMediaCache) {
        single<HttpDownloader> {
            KtorPersistentHttpDownloader(
                dao = deviceDatabase.httpCacheDownloadStateDao(),
                get<HttpClientProvider>().get(),
                fileSystem = SystemFileSystem,
                baseSaveDir = get<MediaSaveDirProvider>().saveDir
                    .let { Path(it).resolve(HttpMediaCacheEngine.MEDIA_CACHE_DIR) },
                // 专用低优先级线程, 不与驱动界面的数据流抢协程池 (见 createCacheDownloadDispatcher)
                ioDispatcher = createCacheDownloadDispatcher(),
                scope = coroutineScope,
                // 播放时按缓冲余量给播放让路
                throughputGate = get<PlaybackActivity>().let { activity -> PlaybackYieldingGate(activity.current) { activity.currentValue } },
            )
        }

        single<MediaDownloadManager> {
            val id = MediaDownloadManager.LOCAL_FS_MEDIA_SOURCE_ID
            val engines = get<TorrentManager>().engines
            val metadataStore = getContext().dataStores.mediaCacheMetadataStore

            MediaDownloadManager(
                storages = buildList(capacity = engines.size) {
                    /*if (currentAniBuildConfig.isDebug) {
                        // 注意, 这个必须要在第一个, 见 [DefaultTorrentManager.engines] 注释
                        add(
                            @Suppress("DEPRECATION")
                            TorrentMediaCacheStorage(
                                mediaSourceId = "test-in-memory",
                                store = metadataStore,
                                engine = DummyMediaCacheEngine("test-in-memory"),
                                "[debug]dummy",
                                coroutineScope.childScopeContext(),
                            ),
                        )
                    }*/
                    for (engine in engines) {
                        val isPikPak = engine.type == TorrentEngineType.PikPak
                        add(
                            @Suppress("DEPRECATION")
                            TorrentMediaCacheStorage(
                                mediaSourceId = id,
                                store = metadataStore,
                                torrentEngine = TorrentMediaCacheEngine(
                                    mediaSourceId = id,
                                    engineKey = MediaCacheEngineKey(engine.type.id),
                                    torrentEngine = engine,
                                    // PikPak runs in-process and must not start Android's BT foreground service.
                                    engineAccess = if (isPikPak) AlwaysUseTorrentEngineAccess else get(),
                                    dao = deviceDatabase.torrentCacheInfoDao(),
                                    baseSaveDirProvider = get(),
                                ),
                                displayName = "LocalTorrent",
                                parentCoroutineContext = coroutineScope.childScopeContext(),
                                engineAvailability = (engine as? PikPakEngine)?.availability ?: flowOf(true),
                                shareRatioLimitFlow = if (isPikPak) flowOf(0f)
                                else settingsRepository.anitorrentConfig.flow.map { it.shareRatioLimit },
                            ),
                        )
                    }
                    add(
                        @Suppress("DEPRECATION")
                        HttpMediaCacheStorage(
                            mediaSourceId = id,
                            store = metadataStore,
                            dao = deviceDatabase.httpCacheDownloadStateDao(),
                            httpEngine = get<HttpMediaCacheEngine>(),
                            displayName = "LocalWebM3u",
                            coroutineScope.childScopeContext(),
                        ),
                    )
                },
                backgroundScope = coroutineScope.childScope(),
                playbackActivity = get(),
            )
        }
    } else {
        single<MediaDownloadManager> {
            MediaDownloadManager(
                storages = emptyList(),
                backgroundScope = coroutineScope.childScope(),
            )
        }
    }

    // Media source services
    single<MediaSourceCodecManager> {
        MediaSourceCodecManager()
    }
    single<QuarkDriveService> { QuarkDriveService(get<SettingsRepository>().quarkConfig, get<TmdbSubjectMapRepository>()) }
    single<QuarkAddedShareService> { QuarkAddedShareService(get<SettingsRepository>().quarkAddedShares, get<QuarkDriveService>()) }
    single<MediaSourceManager> {
        MediaSourceManagerImpl(
            additionalSources = {
                get<MediaDownloadManager>().storages.map { it.cacheMediaSource }
            },
        )
    }
    single<MediaSourceSubscriptionUpdater> {
        val settings = koin.get<ProxyProvider>()
        val client = get<HttpClientProvider>().get(ScopedHttpClientUserAgent.ANI)
        MediaSourceSubscriptionUpdater(
            get<MediaSourceSubscriptionRepository>(),
            get<MediaSourceManager>(),
            get<MediaSourceCodecManager>(),
            requester = MediaSourceSubscriptionRequesterImpl(client),
        )
    }

    // Caching
    single<MeteredNetworkDetector> { createMeteredNetworkDetector(getContext()) }
    single<SubjectDetailsStateFactory> { DefaultSubjectDetailsStateFactory() }
}

/**
 * 会在非 preview 环境调用. 用来初始化一些模块
 */
fun KoinApplication.startCommonKoinModule(
    context: Context,
    coroutineScope: CoroutineScope,
): KoinApplication {
    // Start the proxy provider very soon (before initialization of any other components)
    runBlocking {
        koin.get<SessionManager>().clearLegacySessionOnStartup()
        koin.get<SessionManager>().clearSessionIfAccessTokenExpired()
        // We have to block here to read the saved proxy settings
        when (val proxyProvider = koin.get<HttpClientProvider>()) {
            // compile-safe type cast
            is DefaultHttpClientProvider -> proxyProvider.startProxyListening(holdingInstanceMatrixSequence())
        }
    }
    // Now, the proxy settings is ready. Other components can use http clients.

    coroutineScope.launch {
        // Without media cache (TV) there is no HttpDownloader, and nothing below applies.
        val httpDownloader = koin.getOrNull<HttpDownloader>() ?: return@launch
        val startupLogger = logger("ani-startup")
        httpDownloader.init() // restore http download states first

        // Migration changes engine ownership and must finish before cache restoration dispatches
        // records. A migration failure is isolated so records remain restorable by their current engine.
        try {
            PikPakWebM3uCacheMigration(
                metadataStore = context.dataStores.mediaCacheMetadataStore,
                httpDao = koin.get<AniDatabase>().httpCacheDownloadStateDao(),
                torrentDao = koin.get<AniDatabase>().torrentCacheInfoDao(),
                baseSaveDirProvider = koin.get(),
                pikpakSaveDir = koin.get<PikPakEngine>().saveDir,
            ).migrate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            startupLogger.warn(e) { "Failed to migrate legacy PikPak caches; restoring them as they are." }
        }

        try {
            koin.get<PikPakEngine>().sweepLeftoversOnStartup()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            startupLogger.warn(e) { "Failed to sweep leftover PikPak cloud objects on startup." }
        }

        val manager = koin.get<MediaDownloadManager>()
        for (storage in manager.storages) {
            storage.restorePersistedCaches()
        }
    }

    coroutineScope.launch {
        val subscriptionUpdater = koin.get<MediaSourceSubscriptionUpdater>()
        while (currentCoroutineContext().isActive) {
            val nextDelay = subscriptionUpdater.updateAllOutdated()
            // updateAllOutdated 失败后会返回很短的重试间隔 (启动头几秒网络还没就绪是常态), 这里只兜底防止空转
            delay(nextDelay.coerceAtLeast(10.seconds))
        }
    }

    coroutineScope.launch {
        val currentSaves = context.dataStores.mediaSourceSaveStore.data.first()
        val defaultInstanceIds = MediaSourceSaves.Default.instances.map { it.instanceId }
        // 如果当前的数据源列表的 instance ids 都在默认列表里, 说明用户没有自定义过数据源, 直接写入默认源
        if (currentSaves.instances.all { it.instanceId in defaultInstanceIds }) {
            context.dataStores.mediaSourceSaveStore.updateData { MediaSourceSaves.Default }
        }
    }

    coroutineScope.launch {
        val peerFilterRepo = koin.get<PeerFilterSubscriptionRepository>()
        peerFilterRepo.updateOrLoadAll()
    }

    // 作品是不是 NSFW 的登记表 (见 SubjectNsfw): 读回落盘的、从收藏库补齐, 跟上设置
    SubjectNsfw.attach(
        coroutineScope,
        store = context.dataStores.subjectNsfwStore,
        modeFlow = koin.get<SettingsRepository>().uiSettings.flow.map { it.searchSettings.nsfwMode }.distinctUntilChanged(),
        seed = { koin.get<AniDatabase>().subjectCollection().nsfwSubjectIds() },
    )

    // 选人页上显示各人的 Bangumi 头像: 本进程的用户登录状态一变就记下来
    coroutineScope.launch {
        val profiles = koin.get<UserProfileManager>()
        koin.get<UserRepository>().selfInfoFlow
            .map { it?.avatarUrl }
            .distinctUntilChanged()
            .collect { profiles.updateAvatar(it) }
    }

    koin.get<SessionManager>().startBackgroundJob()
    return this
}

private fun buildAniDatabase(context: Context, fileName: String): AniDatabase =
    context.createDatabaseBuilder(fileName)
        .fallbackToDestructiveMigrationOnDowngrade(true)
        .fallbackToDestructiveMigrationFrom(
            dropAllTables = true,
            startVersions = buildList {
                addAll(1..15) // 16 is destructive
            }.toIntArray(),
        )
        .addMigrations(MIGRATION_19_20, MIGRATION_21_22, MIGRATION_24_25)
        // 旧版评分把标签列写成了纯文本, 那样的行一读就抛异常: 打开时修掉 (见 SelfRatingTagsRepair)
        .addCallback(SelfRatingTagsRepair)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO_)
        .build()

/** 删用户: 他的库 (连同 SQLite 旁边的几个文件与 Room 的文件锁)、按人的配置、推荐快照. */
private suspend fun deleteUserProfileFiles(context: Context, profile: UserProfile) = withContext(Dispatchers.IO_) {
    for (suffix in listOf("", "-wal", "-shm", "-journal", ".lck")) {
        context.databaseFile(profile.databaseFileName + suffix).delete()
    }
    for (name in UserProfile.SCOPED_DATASTORE_NAMES) {
        context.dataStores.resolveDataStoreFile(profile.scopedFileName(name)).delete()
    }
    context.files.cacheDir.resolve(profile.scopedFileName(UserProfile.RECOMMENDATION_COLLECTIONS_FILE_NAME)).delete()
}

/**
 * 需要一直持有的 http client 实例列表
 */
private fun holdingInstanceMatrixSequence() = sequence {
    for (userAgent in ScopedHttpClientUserAgent.entries) {
        yield(
            HoldingInstanceMatrix(
                setOf(
                    UserAgentFeature.withValue(userAgent),
                    ServerListFeature.withValue(ServerListFeatureConfig.Default),
                    BangumiMirrorFeature.withValue(true),
                    AlternativeEndpointsFeature.withValue(true),
                    ConvertSendCountExceedExceptionFeature.withValue(true),
                ),
            ),
        )
    }

    yield(
        HoldingInstanceMatrix(
            setOf(
                UserAgentFeature.withValue(ScopedHttpClientUserAgent.ANI),
                ServerListFeature.withValue(ServerListFeatureConfig.Default),
                BangumiMirrorFeature.withValue(true),
                AlternativeEndpointsFeature.withValue(true),
                ConvertSendCountExceedExceptionFeature.withValue(true),
            ),
        ),
    )
}

fun createAppRootCoroutineScope(): CoroutineScope {
    val logger = logger("ani-root")
    return CoroutineScope(
        CoroutineExceptionHandler { coroutineContext, throwable ->
            logger.warn(throwable) {
                "Uncaught exception in coroutine $coroutineContext"
            }
        } + SupervisorJob() + Dispatchers.Default,
    )
}
