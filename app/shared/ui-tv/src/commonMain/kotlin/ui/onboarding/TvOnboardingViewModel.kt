/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import me.him188.ani.app.data.models.preference.BangumiConnectionPreset
import me.him188.ani.app.data.models.preference.BangumiRouteKind
import me.him188.ani.app.data.models.preference.EndpointSelectionMode
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.data.repository.user.AccessTokenSession
import me.him188.ani.app.data.network.TmdbImageEndpoints
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.data.repository.user.TokenRepository
import me.him188.ani.app.data.repository.user.UserRepository
import me.him188.ani.app.domain.foundation.BangumiConnectivityProbe
import me.him188.ani.app.domain.foundation.BangumiEchFeature
import me.him188.ani.app.domain.foundation.BangumiEndpointProvider
import me.him188.ani.app.domain.foundation.BangumiMirrorConsent
import me.him188.ani.app.domain.foundation.BangumiMirrorConsent.onlyThirdPartyRoutes
import me.him188.ani.app.domain.foundation.BangumiLoginPath
import me.him188.ani.app.domain.foundation.BangumiMirrorListRepository
import me.him188.ani.app.domain.foundation.CandidatesCheck
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.Reachability
import me.him188.ani.app.domain.foundation.ReachabilityProbe
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.UserAgentFeature
import me.him188.ani.app.domain.foundation.withValue
import me.him188.ani.app.domain.profile.LocalProfileConversion
import me.him188.ani.app.domain.profile.SelfCollectionRecords
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.domain.session.auth.BangumiOAuthManager
import me.him188.ani.app.domain.settings.ProxyProvider
import me.him188.ani.app.domain.settings.unreachableProxyOf
import me.him188.ani.app.ui.foundation.AbstractViewModel
import me.him188.ani.app.ui.remote.TvBangumiRelayLogin
import me.him188.ani.app.ui.user.SelfInfoStateProducer
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject

/**
 * 首次启动引导的第一步: 检测网络并选择怎么连 (必须做). 两页 —— Bangumi 连接方式、TMDB 图片 —— 共用**同一次检测**:
 * 一建就把两项一起测上 (那时还在欢迎页, 主页没加载, 不抢带宽), 翻到哪一页都已经有结果.
 */
class TvOnboardingViewModel : AbstractViewModel(), KoinComponent {
    private val settingsRepository: SettingsRepository by inject()
    private val tokenRepository: TokenRepository by inject()
    private val userRepository: UserRepository by inject()
    private val httpClientProvider: HttpClientProvider by inject()
    private val mirrorListRepository: BangumiMirrorListRepository by inject()
    private val tmdbImageEndpoints: TmdbImageEndpoints by inject()

    /** 这台设备能不能加密直连: 不能就不测、不列这一路. */
    val echAvailable: Boolean = get<BangumiEndpointProvider>().echAvailable

    /**
     * 检测用的客户端**不带任何地址改写** (只给 UA, 以及能连加密直连的域名): 带着的话测镜像会被换回原站, 测图片入口会被换成选定的那个.
     * 代理照常生效 —— 每次借用都取当前的代理设置, 所以同一个实例在用户改了代理之后也会用上新代理.
     */
    private val probeClient = httpClientProvider.get(
        setOf(UserAgentFeature.withValue(ScopedHttpClientUserAgent.ANI), BangumiEchFeature.withValue(true)),
    )
    private val bangumiProbe = BangumiConnectivityProbe({ probeClient }, mirrorListRepository.mirrors, echAvailable)
    private val reachabilityProbe = ReachabilityProbe({ probeClient })

    /** Bangumi 那一页: 官方、加密直连与镜像. */
    val bangumi = OnboardingCheck(
        backgroundScope, BangumiConnectivityProbe.Result(), { it.completed }, bangumiProbe::run, rememberedBangumi,
        maxDurationMillis = bangumiProbe.timeoutMillis,
    )

    /** TMDB 图片那一页: 清单里的每个入口. */
    val tmdbImages = OnboardingCheck(
        backgroundScope, CandidatesCheck(), { it.completed },
        { reachabilityProbe.checkCandidates(tmdbImageEndpoints) }, rememberedTmdbImages,
        maxDurationMillis = reachabilityProbe.timeoutMillis,
    )

    /** 代理设置变了 (用户在手机或电视设置里存了代理). 除了自动重测, 页面还据此关掉「设置代理」弹窗. */
    val proxyChanges: Flow<Any?> get() = httpClientProvider.configurationFlow.drop(1)

    init {
        // 从登录那一步返回时本页是新建的: 先摆上这次启动已经测完的结果 (选项不用再锁一遍), 后台静默重测
        bangumi.restart(quiet = bangumi.hasRemembered)
        tmdbImages.restart(quiet = tmdbImages.hasRemembered)
        // 用户在手机上 (或电视设置里) 改了代理: 自动重测, 不用回来再按一次
        backgroundScope.launch {
            proxyChanges.collect { recheck() }
        }
    }

    /** 重新检测: 两项一起重测 (两页的「重新检测」是同一次检测). */
    fun recheck() {
        bangumi.restart(quiet = false)
        tmdbImages.restart(quiet = false)
    }

    internal companion object {
        /** 这个进程里最近一次测完的结果. */
        val rememberedBangumi = OnboardingCheck.Remembered<BangumiConnectivityProbe.Result>()
        val rememberedTmdbImages = OnboardingCheck.Remembered<CandidatesCheck>()
    }

    /**
     * 选 [preset] 之前要不要先问 (已登录的人改成只剩镜像, 见 [BangumiMirrorConsent]; 从旧版迁移过来的用户是登录着进引导的).
     */
    suspend fun mirrorConsentNeeded(preset: BangumiConnectionPreset): Boolean {
        val current = settingsRepository.bangumiEndpointSettings.flow.first()
        val loggedIn = tokenRepository.session.first() is AccessTokenSession
        return BangumiMirrorConsent.check(current, preset.applyTo(current), loggedIn, echAvailable) != null
    }

    /** 「退出登录并改用镜像」里的退出登录. */
    suspend fun logout() = userRepository.clearSelfInfo()

    /**
     * 存下选的组合, 返回登录那一步要不要按「经镜像」处理: 开着的线路只剩镜像, 或刚才测出来官方与加密直连都连不上、
     * 只有镜像通 —— 后者还没有请求落到镜像上, 但授权登录注定失败.
     * 存完才返回: 调用方接着就换页, 之后的请求要按新设置走.
     *
     * @param allowCredentialsViaMirror 同时改「登录与收藏同步也经过镜像」(用户在询问里选了允许); `null` = 不动
     */
    suspend fun choosePreset(preset: BangumiConnectionPreset, allowCredentialsViaMirror: Boolean? = null): Boolean {
        settingsRepository.bangumiEndpointSettings.update {
            preset.applyTo(this).copy(allowCredentialsViaMirror = allowCredentialsViaMirror ?: this.allowCredentialsViaMirror)
        }
        val saved = settingsRepository.bangumiEndpointSettings.flow.first()
        return saved.onlyThirdPartyRoutes(echAvailable) || bangumi.result.value.landsOnMirror(preset)
    }

    /**
     * TMDB 图片: [load] = 自动选择入口 (按清单顺序用第一个连得上的), 否则不加载 (背景图改用条目封面).
     * 存完才返回, 理由同 [choosePreset].
     */
    suspend fun chooseTmdbImages(load: Boolean) {
        if (load) {
            settingsRepository.tmdbImageEndpoint.update { copy(mode = EndpointSelectionMode.AUTO) }
        }
        settingsRepository.tmdbImagesDisabled.set(!load)
    }
}

/**
 * 引导里的一项检测: 结果、「第一次测完才能选」, 以及这个进程里上次测完的结果 ([Remembered]) ——
 * 从登录层返回、本页重建时先摆上它, 后台静默重测.
 *
 * @param maxDurationMillis 一轮最多多久出结论 (各路并行测、各自封顶), 见 [deadlineMillis].
 */
class OnboardingCheck<R>(
    private val scope: CoroutineScope,
    initial: R,
    private val completed: (R) -> Boolean,
    private val run: () -> Flow<R>,
    private val remembered: Remembered<R>,
    private val maxDurationMillis: Long,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    class Remembered<R> {
        var value: R? = null
    }

    private val _result = MutableStateFlow(remembered.value ?: initial)
    val result: StateFlow<R> = _result.asStateFlow()

    val hasRemembered: Boolean get() = remembered.value != null

    /**
     * 选项要等**第一次**测完才能按 (检测不能跳过). 之后的重测 (按了重新检测 / 改了代理) 期间照样能选:
     * 那时焦点可能正停在某个选项上, 临时禁用会让它当场丢焦点.
     */
    val unlocked: StateFlow<Boolean> = _result
        .map(completed)
        .runningFold(hasRemembered) { unlocked, completed -> unlocked || completed }
        .stateIn(scope, SharingStarted.Eagerly, hasRemembered)

    private val _deadlineMillis = MutableStateFlow(0L)

    /**
     * 这一轮最晚什么时候出结论 (时间戳, 毫秒); 页面据此写「最多再等 N 秒」, 免得人对着「检测中」干等.
     * 静默重测不改它 (那时摆着的是上次的结论, 不显示检测中).
     */
    val deadlineMillis: StateFlow<Long> = _deadlineMillis.asStateFlow()

    private var job: Job? = null

    /** @param quiet 只在测完那一刻更新 (已经摆着上次的结果时, 不让它中途变回「检测中」). */
    fun restart(quiet: Boolean) {
        job?.cancel()
        if (!quiet) _deadlineMillis.value = clock() + maxDurationMillis
        job = scope.launch {
            run().collect {
                if (quiet && !completed(it)) return@collect
                _result.value = it
                if (completed(it)) remembered.value = it
            }
        }
    }
}

/**
 * 首次启动引导的登录 (可以跳过) 与之后的「外观与操作」. 盖在主页上面显示, 主页在下面照常加载.
 *
 * @param assumeViaMirror 第一步判定的「经镜像」(见 [TvOnboardingViewModel.choosePreset]); 与实际经镜像取或,
 *   只在连接方式还是第一步选的那一档时算数 (见 [viaMirror]).
 */
class TvOnboardingLoginViewModel(assumeViaMirror: Boolean) : AbstractViewModel(), KoinComponent {
    private val endpoints: BangumiEndpointProvider by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val oauthManager: BangumiOAuthManager by inject()
    private val sessionStateProvider: SessionStateProvider by inject()
    private val localProfileConversion: LocalProfileConversion by inject()
    private val proxyProvider: ProxyProvider by inject()

    val loggedIn: StateFlow<Boolean> = sessionStateProvider.stateFlow
        .map { it is SessionState.Valid }
        .stateIn(backgroundScope, SharingStarted.Eagerly, false)

    /** 「已登录：昵称」用. */
    val selfInfo = SelfInfoStateProducer().flow

    val oauthState: StateFlow<BangumiOAuthManager.State> get() = oauthManager.state

    /**
     * 上一次登录没成功是因为连不上电视上的代理时, 那个代理的地址 (见 unreachableProxyOf); 否则 null.
     * 记住到登录成功或不再用这个代理为止: 失败后码当场换新, 授权状态马上就不是「失败」了, 只看当前状态的话提示一闪就没.
     */
    val unreachableProxy: StateFlow<String?> = combine(oauthManager.state, proxyProvider.proxy) { state, proxy -> state to proxy }
        .runningFold(null as String?) { last, (state, proxy) ->
            when {
                proxy == null || state == BangumiOAuthManager.State.Success -> null
                state is BangumiOAuthManager.State.Failed -> unreachableProxyOf(state.cause, proxy)
                else -> last
            }
        }
        .stateIn(backgroundScope, SharingStarted.Eagerly, null)

    /** 电视上能不能授权登录 (应用内浏览器; 电视上跳出去就回不来). */
    val tvLoginSupported: Boolean get() = oauthManager.inAppBrowserSupported

    /**
     * 按「经镜像」处理: 授权登录走不通, 只给个人令牌那条路 (手机控制台).
     *
     * 第一步的判定只在连接方式没再变过时算数: 第一步存完设置才进这一步, 读到的头一个值就是那时选的; 之后用户在手机控制台
     * 改了连接方式 (码就在这一步上), 只看实际是否经镜像.
     */
    val viaMirror: StateFlow<Boolean> = combine(
        endpoints.viaThirdPartyMirror,
        settingsRepository.bangumiEndpointSettings.flow
            .map { settings -> BangumiRouteKind.entries.map(settings::isEnabled) }.distinctUntilChanged().withIndex(),
    ) { via, mode -> via || (assumeViaMirror && mode.index == 0) }
        .stateIn(backgroundScope, SharingStarted.Eagerly, assumeViaMirror)

    init {
        // 上一次授权的结果不该挡住这一次: 单例的状态会一直停在成功/失败上
        oauthManager.resetIfFinished()
    }

    /**
     * 第一步测到 Bangumi 官方连不上 (这个进程里测过的话): 手机扫码授权一样打不开官方的授权页, 登录那一步给 Web 控制台那条路
     * (个人令牌).
     */
    val originUnreachable: Boolean = TvOnboardingViewModel.rememberedBangumi.value?.origin == Reachability.Unreachable

    /**
     * 怎么登录 (见 [BangumiLoginPath]): 只有 [BangumiLoginPath.BROWSER] 给手机扫码授权与电视上的浏览器; 加密直连给 Web 控制台的码,
     * 在那里填账号密码由电视登录.
     */
    val loginPath: StateFlow<BangumiLoginPath> get() = endpoints.loginPath

    /** 最近一次发起的是哪一种登录: 授权进行到哪一步只在发起的那一边显示. */
    val lastStart: StateFlow<LoginStart?> get() = _lastStart
    private val _lastStart = MutableStateFlow<LoginStart?>(null)

    enum class LoginStart { Tv, Phone }

    fun startTvLogin() {
        _lastStart.value = LoginStart.Tv
        oauthManager.startInAppBrowser()
    }

    /** 手机扫码登录 (见 [TvBangumiRelayLogin]): 发起一次, 返回要画成码的授权页地址或起不了的原因. */
    fun startPhoneLogin(): TvBangumiRelayLogin.Start {
        _lastStart.value = LoginStart.Phone
        return TvBangumiRelayLogin.start()
    }

    /** 最后一步「外观与操作」改主题设置: 当场生效 (这一层与下面的主页跟着变). */
    fun updateTheme(transform: ThemeSettings.() -> ThemeSettings) {
        backgroundScope.launch { settingsRepository.themeSettings.update(transform) }
    }

    /** 能选「不登录，收藏存在这台电视上」: 当前还是 Bangumi 用户, 这台设备能重启应用 (见 [LocalProfileConversion]). */
    val localOffered: Boolean get() = localProfileConversion.isOffered

    /** 之前登录留下的收藏记录 (从老版本升级上来、登录过又退出的有). */
    suspend fun leftoverRecords(): SelfCollectionRecords.Counts = localProfileConversion.leftoverRecords()

    /** 改成本地用户没成的原因; 成了应用会重启, 不会有值. */
    val localError = MutableStateFlow<String?>(null)

    /** 改成本地用户, 应用随后重启; [defaultName] 见 [LocalProfileConversion.convert]. 没成时在主线程调 [onFailed]. */
    fun useLocal(clearRecords: Boolean, defaultName: String, onFailed: () -> Unit) {
        localError.value = null
        backgroundScope.launch {
            try {
                localProfileConversion.convert(clearRecords, defaultName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to convert the user profile to local" }
                localError.value = e.message ?: e::class.simpleName.orEmpty()
                withContext(Dispatchers.Main) { onFailed() }
            }
        }
    }

    private companion object {
        private val logger = logger<TvOnboardingLoginViewModel>()
    }
}
