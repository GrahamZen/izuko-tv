/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.data.models.preference.BangumiMirrorHosts
import me.him188.ani.app.data.models.user.calculateDisplay
import me.him188.ani.app.data.repository.user.UserRepository
import me.him188.ani.app.domain.foundation.BangumiMirrorListRepository
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.session.InvalidSessionReason
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.domain.session.auth.BangumiOAuthConstants
import me.him188.ani.app.domain.session.auth.BangumiOAuthManager
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台「设置」标签顶上的**账号**: 电视登录的是谁, 以及**从手机上发起电视的登录**.
 *
 * 账号就是 Bangumi 账号. 授权完 Bangumi 跳回的是**电视本机的回环地址** (`127.0.0.1:41890`,
 * 见 [BangumiOAuthConstants]), 而回环由**浏览器所在的那台设备**解析 —— 手机上打开同一个链接, 授权完跳到的是
 * 手机自己的那个端口, 电视永远收不到 code. 走 Ani 服务器的那版能让手机直接登录, 靠的正是服务器接回调再中转,
 * 直连之后没有这个中转站.
 *
 * 所以这里给三条路:
 * - **手机授权** (默认): 把授权链接交给手机打开, 授权完那一跳必然失败, 但地址栏里带着 code ——
 *   整个地址粘回来 (`api/account/login/callback`), 电视拿它换 token ([BangumiOAuthManager.submitCallbackUrl]).
 * - **电视授权** (`where=tv`): 让电视弹出授权页 (挂在应用根部的 `BangumiOAuthDialogHost`, 电视在哪一页都能弹),
 *   用遥控器完成. 电视上打字麻烦, 所以不是默认.
 * - **个人令牌** (`api/account/token`): 在网页上生成令牌粘过来, 不经过授权页与换 token.
 *   中国大陆经镜像只能走这条, 见 [BangumiOAuthManager.loginWithPersonalToken].
 *
 * 同一时间只等一次: 再点一次 = 放弃上一次、重新开始. 等 [LOGIN_TIMEOUT] 还没结果就放弃.
 */
internal object RemoteAccount {
    private val logger = logger<RemoteAccount>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("RemoteAccount"))

    private val sessionStateProvider: SessionStateProvider get() = KoinPlatform.getKoin().get()
    private val userRepository: UserRepository get() = KoinPlatform.getKoin().get()
    private val oauthManager: BangumiOAuthManager get() = KoinPlatform.getKoin().get()
    private val mirrorList: BangumiMirrorListRepository get() = KoinPlatform.getKoin().get()

    /** 手机发起的那次登录走到哪了. 成功后回到 Idle (登录状态看会话本身). */
    private sealed interface Login {
        data object Idle : Login

        /**
         * 等授权完成.
         * @param url 交给手机打开的授权链接; `null` = 授权页开在电视上, 手机只需要等
         */
        class Waiting(val url: String?) : Login

        class Failed(val message: String) : Login
    }

    private val lock = Any()

    @Volatile
    private var login: Login = Login.Idle

    /** 当前这一次登录的协程; 状态只认它写的 (见 [update]). */
    private var job: Job? = null

    /** 处理 `api/account` 下的请求; 路径或方法不认识返回 null. */
    fun handle(request: LanHttpRequest): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return runCatching {
            when {
                request.path == "api/account" && get -> state()
                !post -> null
                request.path == "api/account/login" -> startLogin(request)
                request.path == "api/account/login/callback" -> submitCallback(request)
                request.path == "api/account/login/cancel" -> cancelLogin()
                request.path == "api/account/token" -> tokenLogin(request)
                request.path == "api/account/logout" -> logout()
                else -> null
            }
        }.getOrElse {
            logger.warn(it) { "Remote account request failed: ${request.method} ${request.path}" }
            result(false, tr("操作失败：{0}", it.message ?: it::class.simpleName))
        }
    }

    private fun state(): JsonObject = runBlocking {
        val session = withTimeoutOrNull(STATE_TIMEOUT) { sessionStateProvider.stateFlow.first() }
        // selfInfoFlow 的第一个值是本地缓存的那份, 不走网络 (等授权时每 2 秒轮询也无妨); 刚登录完可能还是 null, 下一次就有了
        val self = if (session is SessionState.Valid) {
            withTimeoutOrNull(STATE_TIMEOUT) { userRepository.selfInfoFlow.first() }
        } else {
            null
        }
        val current = login
        val mirrors = if (session is SessionState.Valid) {
            emptyList()
        } else {
            withTimeoutOrNull(STATE_TIMEOUT) { mirrorList.mirrors.first() }.orEmpty()
        }
        // 挂起调用都在 buildJsonObject 外面 (它的构建块不是协程)
        buildJsonObject {
            put("ok", true)
            // 账号就是 Bangumi 账号: 网页上不要出现改昵称 / 邮箱登录这些只有 Ani 账号体系才有的入口
            put("direct", true)
            put("loggedIn", session is SessionState.Valid)
            put("bangumi", session is SessionState.Valid)
            // 连不上 bangumi 而判成无效 (不是真的没登录): 单独说, 别让人以为被登出了
            put("offline", session is SessionState.Invalid && session.reason == InvalidSessionReason.NETWORK_ERROR)
            if (self != null) {
                put("name", self.calculateDisplay().title)
                put("nickname", self.nickname)
                put("avatar", self.avatarUrl)
                put("bgmName", self.bangumiUsername)
            }
            // 生成个人令牌的页面: 官方, 以及镜像清单里每个镜像上的同一个页面 (没登录时才要)
            putJsonArray("tokenPages") { tokenPages(mirrors).forEach { add(it) } }
            putJsonObject("login") {
                when (current) {
                    Login.Idle -> put("state", "idle")
                    is Login.Waiting -> {
                        put("state", "waiting")
                        // 非空 = 手机授权那条路, 网页要把链接打开并让用户把回调地址粘回来
                        put("url", current.url)
                    }
                    is Login.Failed -> {
                        put("state", "failed")
                        put("message", current.message)
                    }
                }
            }
        }
    }

    /**
     * 发起一次授权 (放弃上一次), 之后在后台盯着结果, 结果用提示送到手机上.
     *
     * 表单 `where=tv` 时让电视弹授权页, 否则 (默认) 把授权链接交给手机打开, 见 [RemoteAccount] 的说明.
     */
    private fun startLogin(request: LanHttpRequest): JsonObject {
        val session = runBlocking { withTimeoutOrNull(STATE_TIMEOUT) { sessionStateProvider.stateFlow.first() } }
        if (session is SessionState.Valid) return result(false, tr("电视已经登录了"))
        val manager = oauthManager
        val onTv = request.formFields()["where"] == "tv"
        if (onTv && !manager.inAppBrowserSupported) {
            return result(false, tr("这台电视打不开授权页，改用手机授权"))
        }
        var url: String? = null
        var configured = true
        // 在锁里起协程并登记: 它第一次 update 要拿同一把锁, 那时 job 一定已经是它
        synchronized(lock) {
            job?.cancel()
            // state 是进程内单例, 上一次的成功 / 失败会一直停在那儿, 不重置就再也起不来 (同电视授权页的做法)
            manager.resetIfFinished()
            if (onTv) {
                manager.startInAppBrowser()
            } else {
                url = manager.startExternalBrowser()
                configured = url != null
            }
            if (configured) {
                login = Login.Waiting(url)
                scope.launch { awaitResult(coroutineContext.job, manager) }.also { job = it }
            }
        }
        if (!configured) return result(false, tr("这个版本没有带 Bangumi 授权凭据，登录不了"))
        logger.info { "Remote control started Bangumi OAuth (${if (onTv) "on TV" else "on phone"})" }
        val authorizeUrl = url
        return buildJsonObject {
            put("ok", true)
            if (authorizeUrl != null) {
                put("url", authorizeUrl)
                put("message", tr("请在打开的页面里授权"))
            } else {
                put("message", tr("电视上已经打开 Bangumi 授权页，请用遥控器完成授权"))
            }
        }
    }

    /**
     * 手机上授权完, 把浏览器地址栏里那个**打不开的**地址整个粘回来.
     *
     * 那一跳的目标是电视本机的回环端口, 在手机上必然连接失败 —— 但失败页的地址栏里带着 `code`,
     * 交给电视就能换 token. 这是直连版让手机完成登录的唯一办法, 见 [RemoteAccount] 的说明.
     */
    private fun submitCallback(request: LanHttpRequest): JsonObject {
        val url = request.formFields()["url"].orEmpty().trim()
        if (url.isEmpty()) return result(false, tr("请粘贴授权完成后跳转到的那个地址"))
        if (!BangumiOAuthConstants.isCallback(url)) {
            return result(false, tr("这不像授权回调地址，它以 {0} 开头", BangumiOAuthConstants.CALLBACK_URL))
        }
        val manager = oauthManager
        // submitCallbackUrl 自己不抛异常, 成败只体现在 state 上
        runBlocking { withTimeoutOrNull(OP_TIMEOUT) { manager.submitCallbackUrl(url) } }
            ?: return result(false, tr("换取登录凭据超时，请重试"))
        return when (val state = manager.state.value) {
            BangumiOAuthManager.State.Success -> result(true, tr("登录成功，电视已登录"))
            is BangumiOAuthManager.State.Failed -> result(
                false,
                // 换 token 失败最常见的是授权码过期或已经用过 (bangumi 回 invalid_grant),
                // 那时 LoadError 只是个 UnknownError, 照实报"未知错误"对用户没有任何帮助
                if (state.error is LoadError.UnknownError) {
                    tr("这个地址没换到登录凭据，多半是过期了或者已经用过一次。请重新开始一次。")
                } else {
                    errorText(state.error)
                },
            )
            // 粘错了上一次的回调 (state 对不上) 会被原样忽略, 这里只能说它没生效
            else -> result(false, tr("这个地址没能完成登录，请重新开始一次"))
        }
    }

    /** 盯着 [BangumiOAuthManager.state] 直到有结果. */
    private suspend fun awaitResult(me: Job, manager: BangumiOAuthManager) {
        val outcome = withTimeoutOrNull(LOGIN_TIMEOUT) {
            manager.state.first {
                it !is BangumiOAuthManager.State.Authorizing && it !is BangumiOAuthManager.State.Exchanging
            }
        }
        when (outcome) {
            BangumiOAuthManager.State.Success -> {
                update(me, Login.Idle)
                logger.info { "Remote control login succeeded" }
                TvRemoteControl.postNotice(tr("登录成功，电视已登录"))
            }

            is BangumiOAuthManager.State.Failed -> {
                val message = errorText(outcome.error)
                update(me, Login.Failed(message))
                TvRemoteControl.postNotice(tr("登录没有完成：{0}", message))
            }

            BangumiOAuthManager.State.NotConfigured -> {
                update(me, Login.Failed(tr("这个版本没有带 Bangumi 授权凭据，登录不了")))
            }

            // 授权页被关掉了 (电视上关的, 或者又点了一次登录)
            BangumiOAuthManager.State.Idle -> update(me, Login.Idle)

            null -> {
                manager.cancel()
                update(me, Login.Failed(tr("等太久没有结果，请重新登录")))
                TvRemoteControl.postNotice(tr("登录没有完成：等太久没有结果，请重新登录"))
            }

            else -> {}
        }
    }

    /**
     * 用个人令牌登录 (表单 `token` + `days` = 令牌的有效天数, 与生成时选的一致). 正在等的授权作废.
     */
    private fun tokenLogin(request: LanHttpRequest): JsonObject {
        val fields = request.formFields()
        val token = fields["token"].orEmpty().trim()
        if (token.isEmpty()) return result(false, tr("请先粘贴令牌"))
        val days = fields["days"]?.toIntOrNull()?.takeIf { it in 1..MAX_TOKEN_DAYS }
            ?: return result(false, tr("有效期不对"))
        val session = runBlocking { withTimeoutOrNull(STATE_TIMEOUT) { sessionStateProvider.stateFlow.first() } }
        if (session is SessionState.Valid) return result(false, tr("电视已经登录了"))
        cancelLogin()
        // 包一层: 登录函数成功时返回 null, 直接交给 withTimeoutOrNull 就分不清成功与超时
        val outcome = runBlocking {
            withTimeoutOrNull(OP_TIMEOUT) { TokenLoginOutcome(oauthManager.loginWithPersonalToken(token, days)) }
        } ?: return result(false, tr("校验令牌超时，请重试"))
        val error = outcome.error ?: return result(true, tr("登录成功，电视已登录"))
        return result(
            false,
            when (error) {
                LoadError.RequiresLogin -> tr("令牌无效或已过期，请重新生成")
                LoadError.NetworkError ->
                    tr("电视连不上 Bangumi。在中国大陆请在「Bangumi 连接方式」里打开「登录与收藏同步也经过镜像」，或者设置代理")
                else -> errorText(error)
            },
        )
    }

    private class TokenLoginOutcome(val error: LoadError?)

    private fun tokenPages(mirrors: List<String>): List<String> {
        val official = Url(BangumiOAuthConstants.PERSONAL_TOKEN_PAGE)
        return listOf(official.toString()) + mirrors.mapNotNull { root ->
            BangumiMirrorHosts.mirrorHostOf(official.host, root)?.let { host ->
                URLBuilder(official).apply { this.host = host }.buildString()
            }
        }
    }

    private fun cancelLogin(): JsonObject {
        synchronized(lock) {
            job?.cancel()
            job = null
            login = Login.Idle
        }
        oauthManager.cancel()
        return result(true, tr("已取消"))
    }

    /**
     * 退出登录 (网页上先确认): 同 App 设置里的「退出登录」—— [UserRepository.clearSelfInfo] 清掉本地用户信息, 并连会话一起清.
     * 顺带放弃正在等的登录.
     */
    private fun logout(): JsonObject {
        cancelLogin()
        runBlocking { withTimeoutOrNull(OP_TIMEOUT) { userRepository.clearSelfInfo() } }
            ?: return result(false, tr("退出登录超时，请重试"))
        logger.info { "Logged out from remote control" }
        return result(true, tr("电视已退出登录"))
    }

    /** 只有还是「当前这一次」时才改状态: 被新的一次顶掉的旧协程别把新状态冲掉. */
    private fun update(owner: Job, state: Login) {
        synchronized(lock) { if (job === owner) login = state }
    }

    private fun errorText(error: LoadError): String = when (error) {
        LoadError.NetworkError -> tr("网络错误，电视连不上 Bangumi")
        LoadError.ServiceUnavailable -> tr("Bangumi 暂时不可用，稍后再试")
        LoadError.RateLimited -> tr("操作太频繁，稍后再试")
        LoadError.RequiresLogin -> tr("登录状态有问题，请重试")
        LoadError.NoResults -> tr("没有拿到登录结果，请重试")
        is LoadError.RequestError -> error.localized
        is LoadError.UnknownError -> tr("未知错误") + (error.throwable?.message?.let { "：$it" } ?: "")
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    private val STATE_TIMEOUT = 3.seconds

    /** 退出登录等操作最多等这么久. */
    private val OP_TIMEOUT = 15.seconds

    /** 个人令牌有效期的上限 (天). */
    private const val MAX_TOKEN_DAYS = 3650

    /** 授权页打开之后等结果的上限: 电视授权页那边有人盯着不设上限, 这里没人盯着, 要有个头. */
    private val LOGIN_TIMEOUT = 10.minutes
}
