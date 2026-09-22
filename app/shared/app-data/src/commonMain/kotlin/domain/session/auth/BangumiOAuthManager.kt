/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.app.data.repository.user.AccessTokenSession
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.mediasource.web.captcha.CaptchaBrowser
import me.him188.ani.app.domain.mediasource.web.captcha.CaptchaBrowserFactory
import me.him188.ani.app.domain.session.AccessTokenPair
import me.him188.ani.app.domain.session.SessionManager
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.Uuid
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * bangumi 登录的唯一编排点 (进程内单例).
 *
 * 直连之后登录是标准 OAuth2: 打开 bangumi 授权页 → 用户点"授权" → bangumi 把浏览器导航到
 * `ani://bangumi-oauth-callback?code=...` → 我们拿 code 换 token。**回调有两条路进来**:
 *
 * 1. **应用内浏览器** ([startInAppBrowser]): 拦下那一跳, 不经过系统。电视上只有这条能用
 *    (遥控器没有外部浏览器可跳, 而且跳出去就回不来了)。
 * 2. **外部浏览器 + deep link** ([startExternalBrowser] + [submitCallbackUrl]): 手机上的兜底,
 *    由 manifest 的 intent-filter 接住后喂回来。
 *
 * 两条路共用同一个状态机, 所以界面只要看 [state]; 谁先送到 code 就算谁的。
 */
class BangumiOAuthManager(
    private val client: BangumiOAuthClient,
    private val sessionManager: SessionManager,
    private val browserFactory: CaptchaBrowserFactory,
    private val scope: CoroutineScope,
    /**
     * 当前**可信**镜像的根域名; `null` = 用原站.
     *
     * 授权页要丢给浏览器/内嵌 WebView 打开, 那条路不经过 HttpClient, 所以镜像改写只能在拼地址时做.
     * **只接可信镜像** (用户自建, 或用户允许凭证经过的): 那个页面上用户要输 bangumi 的账号密码, 第三方反代原样看得到.
     */
    private val trustedMirrorRoot: () -> String? = { null },
    private val random: Random = Random.Default,
) {
    private val logger = logger<BangumiOAuthManager>()

    sealed interface State {
        /** 没有正在进行的授权. */
        data object Idle : State

        /** 这个构建没带 OAuth 凭据 (见 [BangumiOAuthClient.isConfigured]), 授权页打开也是报错. */
        data object NotConfigured : State

        /**
         * 授权中. [browser] 非 null 时界面要把它画出来 (应用内浏览器那条路).
         */
        data class Authorizing(
            val url: String,
            val browser: CaptchaBrowser?,
        ) : State

        /** 拿到 code, 正在换 token. */
        data object Exchanging : State

        data object Success : State

        data class Failed(val error: LoadError) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 本次授权的 `state` 参数, 用来认回调是不是自己发起的那次. */
    private var pendingState: String? = null
    private var browserJob: Job? = null

    /**
     * 当前这个浏览器实例. **不能从 [state] 里读**: 关它的时机 (换 token 那一刻) 状态已经翻到
     * [State.Exchanging], 那时再去 `state as? Authorizing` 只会拿到 null, 浏览器就漏在那儿了
     * —— 电视上等于一个看不见的 WebView 常驻着.
     */
    private var currentBrowser: CaptchaBrowser? = null
    private val lock = Mutex()

    /** 应用内浏览器能不能用. 不能的话界面只能给"用外部浏览器"这条路. */
    val inAppBrowserSupported: Boolean get() = browserFactory.isSupported

    /**
     * 用应用内浏览器授权: 创建浏览器 → 装导航拦截 → 打开授权页.
     *
     * 界面观察 [state], 见到 [State.Authorizing] 且 `browser != null` 就把
     * [CaptchaBrowser.View] 画成全屏.
     */
    /** 外部浏览器那条的回环监听; 见 [OAuthLoopbackServer]. */
    private var loopbackServer: OAuthLoopbackServer? = null
    private var loopbackJob: Job? = null

    private fun stopLoopback() {
        loopbackServer?.stop()
        loopbackServer = null
        loopbackJob?.cancel()
        loopbackJob = null
    }

    fun startInAppBrowser() {
        if (!client.isConfigured) {
            _state.value = State.NotConfigured
            return
        }
        cancel()
        val oauthState = Uuid.random(random).toString()
        pendingState = oauthState
        val url = BangumiOAuthConstants.authorizeUrl(state = oauthState, mirrorRoot = trustedMirrorRoot())
        _state.value = State.Authorizing(url, browser = null)

        browserJob = scope.launch {
            val browser = try {
                browserFactory.create()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "bgm-direct: oauth 创建应用内浏览器失败" }
                _state.value = State.Failed(LoadError.fromException(e))
                return@launch
            }
            // 拦回调那一跳: 自定义 scheme 让浏览器自己加载只会得到 ERR_UNKNOWN_URL_SCHEME
            browser.setNavigationInterceptor { navUrl ->
                if (BangumiOAuthConstants.isCallback(navUrl)) {
                    scope.launch { submitCallbackUrl(navUrl) }
                    true
                } else {
                    false
                }
            }
            currentBrowser = browser
            _state.value = State.Authorizing(url, browser)
            logger.info { "bgm-direct: oauth 打开授权页 (应用内浏览器)" }
            browser.navigate(url)
        }
    }

    /**
     * 用外部浏览器授权: 只生成地址, 打开由调用方做 (平台各异), 回调靠 deep link 走
     * [submitCallbackUrl] 回来.
     */
    fun startExternalBrowser(): String? {
        if (!client.isConfigured) {
            _state.value = State.NotConfigured
            return null
        }
        cancel()
        val oauthState = Uuid.random(random).toString()
        pendingState = oauthState
        val url = BangumiOAuthConstants.authorizeUrl(state = oauthState, mirrorRoot = trustedMirrorRoot())
        _state.value = State.Authorizing(url, browser = null)
        // 回环监听接住浏览器里的回调 (见 OAuthLoopbackServer): 电视浏览器不把自定义 scheme
        // 交给系统, deep link 那条在那边收不到. 起不来 (端口被占/iOS) 就还是等 deep link.
        val server = OAuthLoopbackServer(BangumiOAuthConstants.CALLBACK_PORT)
        loopbackServer = server
        loopbackJob = scope.launch {
            server.start { callbackUrl ->
                // **另起一个协程**, 不能直接在这里 await: submitCallbackUrl 里的 closeBrowser()
                // 会 stopLoopback() 把 loopbackJob 取消掉, 而这个回调正跑在那个 job 上 ——
                // 于是它取消了自己, 换 token 的请求当场夭折 (实测 POST access_token
                // "CANCELLED in 1ms"), 状态卡在 Exchanging, 界面永远显示"正在等待结果"
                // (2026-09-06). 应用内浏览器那条一直是对的, 正是因为它这么写.
                scope.launch { submitCallbackUrl(callbackUrl) }
            }
        }
        logger.info { "bgm-direct: oauth 打开授权页 (外部浏览器)" }
        return url
    }

    /**
     * 收到回调地址 (两条路共用). 认 `state`, 取 `code`, 换 token, 写进 session.
     *
     * does not throw
     */
    suspend fun submitCallbackUrl(url: String) {
        lock.withLock {
            if (_state.value is State.Exchanging || _state.value is State.Success) return
            val expected = pendingState
            val actual = BangumiOAuthConstants.extractState(url)
            if (!BangumiOAuthConstants.stateMatches(expected, actual)) {
                // **缺一边也要拒**: state 是这里唯一能证明"这次回调是我发起的"的东西. 放过不带 state 的
                // 回调等于没有防护 —— 攻击者拿自己的授权码拼一个地址, 骗用户在手机控制台上粘进来
                // (见 RemoteAccount 的粘贴登录), 电视就登录到他的账号上了.
                //
                // expected 为空还意味着本进程根本没发起过授权 (例如授权中途 app 被系统杀掉),
                // 那时收到的回调同样不该认, 让用户重新发起一次.
                logger.info {
                    "bgm-direct: oauth 回调的 state 不匹配 (expected=${expected != null}, actual=${actual != null}), 忽略"
                }
                return
            }
            val code = BangumiOAuthConstants.extractCode(url)
            if (code == null) {
                logger.info { "bgm-direct: oauth 回调里没有 code (用户拒绝授权?), url=$url" }
                _state.value = State.Failed(LoadError.UnknownError(null))
                closeBrowser()
                return
            }

            _state.value = State.Exchanging
            closeBrowser()
            try {
                val result = client.exchangeCode(code)
                sessionManager.setSession(
                    session = AccessTokenSession(tokens = result.tokens),
                    refreshToken = result.refreshToken,
                )
                pendingState = null
                _state.value = State.Success
                logger.info { "bgm-direct: oauth 登录成功" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val wrapped = RepositoryException.wrapOrThrowCancellation(e)
                logger.error(wrapped) { "bgm-direct: oauth 换 token 失败" }
                _state.value = State.Failed(LoadError.fromException(wrapped))
            }
        }
    }

    /**
     * 用 Bangumi 个人令牌登录 (在 [BangumiOAuthConstants.PERSONAL_TOKEN_PAGE] 生成): 不打开授权页, 也不换 token,
     * 只用令牌请求一次 API 子域确认它能用. 镜像站把 bgm.tv 主站 (授权页与换 token 都在那) 挡在反爬验证页后面,
     * 经镜像时只能这样登录. 令牌没有 refresh token, [validDays] 天后退出登录, 需要重新生成.
     *
     * does not throw
     *
     * @return 失败原因; `null` = 登录成功
     */
    suspend fun loginWithPersonalToken(token: String, validDays: Int): LoadError? {
        val trimmed = token.trim().removePrefix("Bearer ").trim()
        return try {
            val username = client.verifyToken(trimmed)
            sessionManager.setSession(
                session = AccessTokenSession(
                    tokens = AccessTokenPair(
                        aniAccessToken = "",
                        expiresAtMillis = (Clock.System.now() + validDays.days).toEpochMilliseconds(),
                        bangumiAccessToken = trimmed,
                    ),
                ),
                refreshToken = null,
            )
            logger.info { "bgm-direct: 个人令牌登录成功 ($username), $validDays 天后到期" }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val wrapped = RepositoryException.wrapOrThrowCancellation(e)
            logger.info { "bgm-direct: 个人令牌登录失败: $wrapped" }
            LoadError.fromException(wrapped)
        }
    }

    /**
     * 回到"可以再授权一次"的状态.
     *
     * [state] 是**进程内单例**的, 一次成功之后会一直停在 [State.Success] —— 于是退出登录再进
     * 授权页, 界面照旧显示"已授权"且按钮是禁用的, 根本进不去浏览器 (2026-09-06 用户实测)。
     * 授权页每次进来调一次即可。**不动在途的授权**: 外部浏览器那条路正等着 deep link 回调,
     * 此时重置会把它的 `state` 参数一起丢掉。
     */
    fun resetIfFinished() {
        when (_state.value) {
            is State.Success, is State.Failed, is State.NotConfigured -> {
                _state.value = State.Idle
            }

            else -> {}
        }
    }

    /** 用户放弃授权 / 界面离开. */
    fun cancel() {
        pendingState = null
        closeBrowser()
        _state.value = State.Idle
    }

    private fun closeBrowser() {
        // 回环监听与浏览器同生共死: 换一条路 / 取消 / 换到 token 了都要把端口放掉
        stopLoopback()
        browserJob?.cancel()
        browserJob = null
        currentBrowser?.let { browser ->
            browser.setNavigationInterceptor(null)
            runCatching { browser.close() }
        }
        currentBrowser = null
    }
}
