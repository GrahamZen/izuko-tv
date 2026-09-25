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
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.BangumiEndpointMode
import me.him188.ani.app.data.models.preference.EndpointSelectionMode
import me.him188.ani.app.data.repository.user.AccessTokenSession
import me.him188.ani.app.data.network.TmdbImageEndpoints
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.data.repository.user.TokenRepository
import me.him188.ani.app.data.repository.user.UserRepository
import me.him188.ani.app.domain.foundation.BangumiConnectivityProbe
import me.him188.ani.app.domain.foundation.BangumiEndpointProvider
import me.him188.ani.app.domain.foundation.BangumiMirrorConsent
import me.him188.ani.app.domain.foundation.BangumiMirrorListRepository
import me.him188.ani.app.domain.foundation.CandidatesCheck
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.Reachability
import me.him188.ani.app.domain.foundation.ReachabilityProbe
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.UserAgentFeature
import me.him188.ani.app.domain.foundation.withValue
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.domain.session.auth.BangumiOAuthManager
import me.him188.ani.app.ui.foundation.AbstractViewModel
import me.him188.ani.app.ui.user.SelfInfoStateProducer
import org.koin.core.component.KoinComponent
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

    /**
     * 检测用的客户端**不带任何地址改写** (只给 UA): 带着的话测镜像会被换回原站, 测图片入口会被换成选定的那个.
     * 代理照常生效 —— 每次借用都取当前的代理设置, 所以同一个实例在用户改了代理之后也会用上新代理.
     */
    private val probeClient = httpClientProvider.get(setOf(UserAgentFeature.withValue(ScopedHttpClientUserAgent.ANI)))
    private val bangumiProbe = BangumiConnectivityProbe({ probeClient }, mirrorListRepository.mirrors)
    private val reachabilityProbe = ReachabilityProbe({ probeClient })

    /** Bangumi 那一页: 官方与镜像. */
    val bangumi = OnboardingCheck(
        backgroundScope, BangumiConnectivityProbe.Result(), { it.completed }, bangumiProbe::run, rememberedBangumi,
    )

    /** TMDB 图片那一页: 清单里的每个入口. */
    val tmdbImages = OnboardingCheck(
        backgroundScope, CandidatesCheck(), { it.completed },
        { reachabilityProbe.checkCandidates(tmdbImageEndpoints) }, rememberedTmdbImages,
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

    private companion object {
        /** 这个进程里最近一次测完的结果. */
        val rememberedBangumi = OnboardingCheck.Remembered<BangumiConnectivityProbe.Result>()
        val rememberedTmdbImages = OnboardingCheck.Remembered<CandidatesCheck>()
    }

    /**
     * 选 [mode] 之前要不要先问 (已登录的人改用镜像, 见 [BangumiMirrorConsent]; 从旧版迁移过来的用户是登录着进引导的).
     */
    suspend fun mirrorConsentNeeded(mode: BangumiEndpointMode): Boolean {
        val current = settingsRepository.bangumiEndpointSettings.flow.first()
        val loggedIn = tokenRepository.session.first() is AccessTokenSession
        return BangumiMirrorConsent.check(current, current.copy(mode = mode), loggedIn) != null
    }

    /** 「退出登录并改用镜像」里的退出登录. */
    suspend fun logout() = userRepository.clearSelfInfo()

    /**
     * 存下选的连接方式, 返回登录那一步要不要按「经镜像」处理: 选了用镜像, 或选了官方连不上时用镜像
     * 而刚才测出来官方连不上 —— 后者还没有请求落到镜像上, 但授权登录注定失败.
     * 存完才返回: 调用方接着就换页, 之后的请求要按新设置走.
     *
     * @param allowCredentialsViaMirror 同时改「登录与收藏同步也经过镜像」(用户在询问里选了允许); `null` = 不动
     */
    suspend fun chooseMode(mode: BangumiEndpointMode, allowCredentialsViaMirror: Boolean? = null): Boolean {
        settingsRepository.bangumiEndpointSettings.update {
            copy(mode = mode, allowCredentialsViaMirror = allowCredentialsViaMirror ?: this.allowCredentialsViaMirror)
        }
        val result = bangumi.result.value
        return mode == BangumiEndpointMode.MIRROR || (mode == BangumiEndpointMode.AUTO &&
                result.origin == Reachability.Unreachable &&
                result.mirrorReachability is Reachability.Reachable)
    }

    /**
     * TMDB 图片: [load] = 自动选择入口 (按清单顺序用第一个连得上的), 否则不加载 (背景图改用条目封面).
     * 存完才返回, 理由同 [chooseMode].
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
 */
class OnboardingCheck<R>(
    private val scope: CoroutineScope,
    initial: R,
    private val completed: (R) -> Boolean,
    private val run: () -> Flow<R>,
    private val remembered: Remembered<R>,
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

    private var job: Job? = null

    /** @param quiet 只在测完那一刻更新 (已经摆着上次的结果时, 不让它中途变回「检测中」). */
    fun restart(quiet: Boolean) {
        job?.cancel()
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
 * 首次启动引导的第二步: 登录 (可以跳过). 盖在主页上面显示, 主页在下面照常加载.
 *
 * @param assumeViaMirror 第一步判定的「经镜像」(见 [TvOnboardingViewModel.chooseMode]); 与实际经镜像取或,
 *   只在连接方式还是第一步选的那一档时算数 (见 [viaMirror]).
 */
class TvOnboardingLoginViewModel(assumeViaMirror: Boolean) : AbstractViewModel(), KoinComponent {
    private val endpoints: BangumiEndpointProvider by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val oauthManager: BangumiOAuthManager by inject()
    private val sessionStateProvider: SessionStateProvider by inject()

    val loggedIn: StateFlow<Boolean> = sessionStateProvider.stateFlow
        .map { it is SessionState.Valid }
        .stateIn(backgroundScope, SharingStarted.Eagerly, false)

    /** 「已登录：昵称」用. */
    val selfInfo = SelfInfoStateProducer().flow

    val oauthState: StateFlow<BangumiOAuthManager.State> get() = oauthManager.state

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
        settingsRepository.bangumiEndpointSettings.flow.map { it.mode }.distinctUntilChanged().withIndex(),
    ) { via, mode -> via || (assumeViaMirror && mode.index == 0) }
        .stateIn(backgroundScope, SharingStarted.Eagerly, assumeViaMirror)

    init {
        // 上一次授权的结果不该挡住这一次: 单例的状态会一直停在成功/失败上
        oauthManager.resetIfFinished()
    }

    fun startTvLogin() {
        oauthManager.startInAppBrowser()
    }
}
