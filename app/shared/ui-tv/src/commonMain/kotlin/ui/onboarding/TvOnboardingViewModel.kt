/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.onboarding

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.BangumiEndpointMode
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.foundation.BangumiConnectivityProbe
import me.him188.ani.app.domain.foundation.BangumiEndpointProvider
import me.him188.ani.app.domain.foundation.BangumiMirrorListRepository
import me.him188.ani.app.domain.foundation.HttpClientProvider
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
 * 首次启动引导的第一步: 检测连 Bangumi 的网络并选连接方式 (必须做).
 */
class TvOnboardingViewModel : AbstractViewModel(), KoinComponent {
    private val settingsRepository: SettingsRepository by inject()
    private val httpClientProvider: HttpClientProvider by inject()
    private val mirrorListRepository: BangumiMirrorListRepository by inject()

    /**
     * 检测用的客户端**不带镜像改写** (只给 UA): 带着的话测镜像会被换回原站. 代理照常生效 ——
     * 每次借用都取当前的代理设置, 所以同一个实例在用户改了代理之后也会用上新代理.
     */
    private val probeClient = httpClientProvider.get(setOf(UserAgentFeature.withValue(ScopedHttpClientUserAgent.ANI)))
    private val probe = BangumiConnectivityProbe({ probeClient }, mirrorListRepository.mirrors)

    // 从登录那一步返回时本页是新建的: 先摆上这次启动已经测完的结果 (选项不用再锁一遍), 后台照常重测
    private val _probeResult = MutableStateFlow(lastCompleted ?: BangumiConnectivityProbe.Result())
    val probeResult: StateFlow<BangumiConnectivityProbe.Result> = _probeResult.asStateFlow()
    private var probeJob: Job? = null

    /**
     * 连接方式要等**第一次**测完才能选 (检测不能跳过). 之后的重测 (按了重新检测 / 改了代理) 期间照样能选:
     * 那时焦点可能正停在某个选项上, 临时禁用会让它当场丢焦点.
     */
    val optionsUnlocked: StateFlow<Boolean> = _probeResult
        .map { it.completed }
        .runningFold(lastCompleted != null) { unlocked, completed -> unlocked || completed }
        .stateIn(backgroundScope, SharingStarted.Eagerly, lastCompleted != null)

    /** 代理设置变了 (用户在手机或电视设置里存了代理). 除了自动重测, 页面还据此关掉「设置代理」弹窗. */
    val proxyChanges: Flow<Any?> get() = httpClientProvider.configurationFlow.drop(1)

    init {
        recheck(quiet = lastCompleted != null)
        // 用户在手机上 (或电视设置里) 改了代理: 自动重测, 不用回来再按一次
        backgroundScope.launch {
            proxyChanges.collect { recheck() }
        }
    }

    /** @param quiet 只在测完那一刻更新 (已经摆着上次的结果时, 不让它中途变回「检测中」). */
    fun recheck(quiet: Boolean = false) {
        probeJob?.cancel()
        probeJob = backgroundScope.launch {
            probe.run().collect {
                if (quiet && !it.completed) return@collect
                _probeResult.value = it
                if (it.completed) lastCompleted = it
            }
        }
    }

    private companion object {
        /** 这个进程里最近一次测完的结果. */
        var lastCompleted: BangumiConnectivityProbe.Result? = null
    }

    /**
     * 存下选的连接方式, 返回登录那一步要不要按「经镜像」处理: 选了用镜像, 或选了官方连不上时用镜像
     * 而刚才测出来官方连不上 —— 后者还没有请求落到镜像上, 但授权登录注定失败.
     * 存完才返回: 调用方接着就换主页, 主页的请求要按新设置走.
     */
    suspend fun chooseMode(mode: BangumiEndpointMode): Boolean {
        settingsRepository.bangumiEndpointSettings.update { copy(mode = mode) }
        val result = _probeResult.value
        return mode == BangumiEndpointMode.MIRROR || (mode == BangumiEndpointMode.AUTO &&
                result.origin == BangumiConnectivityProbe.Reachability.Unreachable &&
                result.mirrorReachability is BangumiConnectivityProbe.Reachability.Reachable)
    }
}

/**
 * 首次启动引导的第二步: 登录 (可以跳过). 盖在主页上面显示, 主页在下面照常加载.
 *
 * @param assumeViaMirror 第一步判定的「经镜像」(见 [TvOnboardingViewModel.chooseMode]); 与实际经镜像取或.
 */
class TvOnboardingLoginViewModel(assumeViaMirror: Boolean) : AbstractViewModel(), KoinComponent {
    private val endpoints: BangumiEndpointProvider by inject()
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

    /** 按「经镜像」处理: 授权登录走不通, 只给个人令牌那条路 (手机控制台). */
    val viaMirror: StateFlow<Boolean> = endpoints.viaThirdPartyMirror
        .map { it || assumeViaMirror }
        .stateIn(backgroundScope, SharingStarted.Eagerly, assumeViaMirror)

    init {
        // 上一次授权的结果不该挡住这一次: 单例的状态会一直停在成功/失败上
        oauthManager.resetIfFinished()
    }

    fun startTvLogin() {
        oauthManager.startInAppBrowser()
    }
}
