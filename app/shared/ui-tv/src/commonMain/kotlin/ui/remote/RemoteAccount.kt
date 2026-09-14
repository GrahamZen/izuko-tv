/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.data.models.user.calculateDisplay
import me.him188.ani.app.data.network.AniApiProvider
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.app.data.repository.RepositoryNetworkException
import me.him188.ani.app.data.repository.RepositoryRateLimitedException
import me.him188.ani.app.data.repository.RepositoryRequestError
import me.him188.ani.app.data.repository.RepositoryServiceUnavailableException
import me.him188.ani.app.data.repository.user.UserRepository
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.session.InvalidSessionReason
import me.him188.ani.app.domain.session.SessionManager
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.domain.session.auth.BangumiOAuthClient
import me.him188.ani.app.domain.session.auth.OAuthConfigurator
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import org.koin.mp.KoinPlatform
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台「设置」标签顶上的**账号**: 电视登录的是谁, 以及**用手机登录**.
 *
 * 电视上登录要打开浏览器授权, 可电视上的浏览器要么没有、要么是个壳, 还吃内存 —— 实测 Shield 上一开 TCL 浏览器, 退到后台的
 * Animeko 就被系统低内存杀掉, 等授权结果的轮询跟着没了, 这次登录永远完不成. 手机上有现成的浏览器 (多半还登录着 Bangumi).
 *
 * 流程与电视登录页同一套 ([OAuthConfigurator]; 注册还是绑定按当前 Ani 会话有没有效来分, 同 BangumiAuthorizeScreen):
 * 电视向 Ani 服务器登记一个请求号、拿到 Bangumi 授权链接, **链接交给手机打开**; 授权完成后 Bangumi 跳回的是 Ani 服务器,
 * 电视照常每秒问一次结果, 拿到就 setSession —— 全程电视不开浏览器、不离开应用. (只在 main 成立: 直连分支的回调是本机
 * 回环地址, 手机上授权完跳不回电视.)
 *
 * 同一时间只等一次: 手机上再点一次 = 放弃上一次、重新开始. 等 [LOGIN_TIMEOUT] 还没结果就放弃 (用户多半把授权页关了).
 */
internal object RemoteAccount {
    private val logger = logger<RemoteAccount>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("RemoteAccount"))

    private val sessionStateProvider: SessionStateProvider get() = KoinPlatform.getKoin().get()
    private val sessionManager: SessionManager get() = KoinPlatform.getKoin().get()
    private val aniApiProvider: AniApiProvider get() = KoinPlatform.getKoin().get()
    private val userRepository: UserRepository get() = KoinPlatform.getKoin().get()

    /** 手机发起的那次登录走到哪了. 成功后回到 Idle (登录状态看会话本身). */
    private sealed interface Login {
        data object Idle : Login

        /** @param url 交给手机的授权链接; null = 还在向服务器要 */
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
                request.path == "api/account/login" -> startLogin()
                request.path == "api/account/login/cancel" -> cancelLogin()
                request.path == "api/account/logout" -> logout()
                request.path == "api/account/nickname" -> setNickname(request)
                request.path == "api/account/email/send" -> sendEmailOtp(request)
                request.path == "api/account/email/verify" -> verifyEmailOtp(request)
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
        // 挂起调用都在 buildJsonObject 外面 (它的构建块不是协程)
        buildJsonObject {
            put("ok", true)
            put("loggedIn", session is SessionState.Valid)
            put("bangumi", session is SessionState.Valid && session.bangumiConnected)
            // 连不上服务器而判成无效 (不是真的没登录): 单独说, 别让人以为被登出了
            put("offline", session is SessionState.Invalid && session.reason == InvalidSessionReason.NETWORK_ERROR)
            if (self != null) {
                put("name", self.calculateDisplay().title)
                put("nickname", self.nickname)
                put("avatar", self.avatarUrl)
                put("bgmName", self.bangumiUsername)
                put("email", self.email)
            }
            putJsonObject("login") {
                when (current) {
                    Login.Idle -> put("state", "idle")
                    is Login.Waiting -> {
                        put("state", "waiting")
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
     * 开始一次登录 (放弃上一次): 等服务器给出授权链接就回给手机打开, 之后在后台等结果, 结果用提示送到手机上.
     * 要链接这一步就失败 (连不上服务器) 的当场回错误.
     */
    private fun startLogin(): JsonObject {
        val session = runBlocking { withTimeoutOrNull(STATE_TIMEOUT) { sessionStateProvider.stateFlow.first() } }
        if (session is SessionState.Valid && session.bangumiConnected) return result(false, tr("电视已经登录了"))
        // 同电视登录页: 没有有效的 Ani 会话 = 用 Bangumi 注册 / 登录 (新用户、老用户重新登录都走这条); 有会话只是没连 Bangumi = 绑定
        val isRegister = session !is SessionState.Valid
        val link = CompletableDeferred<String>()
        val configurator = OAuthConfigurator(
            client = BangumiOAuthClient(aniApiProvider.bangumiApi, sessionStateProvider),
            sessionManager = sessionManager,
            sessionStateProvider = sessionStateProvider,
        )
        // 在锁里起协程并登记: 它第一次 update 要拿同一把锁, 那时 job 一定已经是它
        val current = synchronized(lock) {
            job?.cancel()
            login = Login.Waiting(null)
            scope.launch {
                val me = coroutineContext.job
                var handedOut = false
                val outcome = withTimeoutOrNull(LOGIN_TIMEOUT) {
                    configurator.auth(isRegister) { url ->
                        handedOut = true
                        update(me, Login.Waiting(url))
                        link.complete(url)
                    }
                }
                when (outcome) {
                    is OAuthConfigurator.State.Success -> {
                        update(me, Login.Idle)
                        logger.info { "Remote control login succeeded" }
                        TvRemoteControl.postNotice(tr("登录成功，电视已登录"))
                    }

                    is OAuthConfigurator.State.Failed -> {
                        val message = errorText(outcome.error)
                        update(me, Login.Failed(message))
                        // 链接已经交出去了 (手机那头正在授权): 结果只能靠提示送过去; 没交出去的由 startLogin 当场回
                        if (handedOut) TvRemoteControl.postNotice(tr("登录没有完成：{0}", message))
                    }

                    null -> {
                        update(me, Login.Failed(tr("等太久没有结果，请重新登录")))
                        TvRemoteControl.postNotice(tr("登录没有完成：等太久没有结果，请重新登录"))
                    }

                    else -> {} // Idle / AwaitingResult: auth 正常返回时不会是这两种
                }
            }.also { job = it }
        }
        val url = runBlocking {
            withTimeoutOrNull(LINK_TIMEOUT) {
                select<String?> {
                    link.onAwait { it }
                    current.onJoin { null }
                }
            }
        }
        if (url != null) {
            logger.info { "Remote control login started (${if (isRegister) "register" else "bind"}), link handed to phone" }
            return buildJsonObject {
                put("ok", true)
                put("message", tr("请在打开的 Bangumi 页面里授权"))
                put("url", url)
            }
        }
        // 协程已经结束 = 要链接时就失败了, 原因在状态里; 否则是超时
        (login as? Login.Failed)?.let { return result(false, it.message) }
        current.cancel()
        update(current, Login.Failed(tr("电视连不上登录服务器，请稍后再试")))
        return result(false, tr("电视连不上登录服务器，请稍后再试"))
    }

    private fun cancelLogin(): JsonObject {
        synchronized(lock) {
            job?.cancel()
            job = null
            login = Login.Idle
        }
        return result(true, tr("已取消"))
    }

    /**
     * 退出登录 (网页上先确认): 同 App 设置里的「退出登录」(ProfileViewModel.logout → 清掉本地用户信息 + 清会话).
     * 顺带放弃正在等的手机登录.
     */
    private fun logout(): JsonObject {
        cancelLogin()
        emailOtp = null
        runBlocking { withTimeoutOrNull(OP_TIMEOUT) { userRepository.clearSelfInfo() } }
            ?: return result(false, tr("退出登录超时，请重试"))
        logger.info { "Logged out from remote control" }
        return result(true, tr("电视已退出登录"))
    }

    /** 改昵称: 规则同 App 的资料编辑 (ProfileViewModel.validateNickname): 中日文 / 字母 / 数字 / 下划线, 6~20 个字符, 非 ASCII 算 2 个. */
    private fun setNickname(request: LanHttpRequest): JsonObject {
        val nickname = request.formFields()["nickname"].orEmpty().trim()
        if (!NICKNAME.matches(nickname) || nickname.sumOf { if (it.code < 256) 1 else 2 } !in 6..20) {
            return result(false, tr("昵称要 6–20 个字符（汉字、假名算 2 个），只能用中日文、字母、数字和下划线"))
        }
        val session = runBlocking { withTimeoutOrNull(STATE_TIMEOUT) { sessionStateProvider.stateFlow.first() } }
        if (session !is SessionState.Valid) return result(false, tr("电视还没登录"))
        runBlocking { withTimeoutOrNull(OP_TIMEOUT) { userRepository.updateProfile(nickname) } }
            ?: return result(false, tr("改昵称超时，请重试"))
        logger.info { "Nickname changed from remote control" }
        return result(true, tr("昵称已改成「{0}」", nickname))
    }

    /** 手机上要的邮箱验证码: 发到哪个邮箱、服务器给的 otpId、什么时候发的 (30 秒内不再发, 同 App). */
    private class EmailOtp(val email: String, val id: String, val sentAt: Long)

    @Volatile
    private var emailOtp: EmailOtp? = null

    /**
     * **邮箱登录 / 注册 Animeko 账号** (App 登录页的「邮箱」那条路, 同 EmailLoginViewModel), 第一步: 发验证码.
     * 不用浏览器, 电视直接跟 Ani 服务器说话. 已经登录时同一套流程是绑定 / 更换邮箱 (见 [verifyEmailOtp]).
     */
    private fun sendEmailOtp(request: LanHttpRequest): JsonObject {
        val email = request.formFields()["email"].orEmpty().trim()
        if (!EMAIL.matches(email)) return result(false, tr("邮箱格式不对"))
        val session = runBlocking { withTimeoutOrNull(STATE_TIMEOUT) { sessionStateProvider.stateFlow.first() } }
        if (session is SessionState.Invalid && session.reason == InvalidSessionReason.NETWORK_ERROR) {
            return result(false, tr("电视连不上 Animeko 服务器，稍后再试"))
        }
        val wait = emailOtp?.let { RESEND_INTERVAL.inWholeMilliseconds - (currentTimeMillis() - it.sentAt) } ?: 0
        if (wait > 0) return result(false, tr("{0} 秒后才能重新发送", (wait + 999) / 1000))
        val info = try {
            runBlocking { withTimeoutOrNull(OP_TIMEOUT) { userRepository.sendEmailOtpForLogin(email) } }
                ?: return result(false, tr("发送超时，请重试"))
        } catch (e: RepositoryException) {
            return result(false, repositoryErrorText(e))
        }
        emailOtp = EmailOtp(email, info.otpId, currentTimeMillis())
        logger.info { "Email OTP sent from remote control (existing user: ${info.hasExistingUser})" }
        return buildJsonObject {
            put("ok", true)
            put("message", tr("验证码已发到 {0}", email))
            info.hasExistingUser?.let { put("existing", it) }
        }
    }

    /** 第二步: 交验证码. 同 EmailLoginViewModel: 没有有效会话 = 登录 (新邮箱直接注册); 已登录 = 绑定或更换邮箱. 成功后服务器给的会话直接生效. */
    private fun verifyEmailOtp(request: LanHttpRequest): JsonObject {
        val code = request.formFields()["code"].orEmpty().filterNot { it.isWhitespace() }
        if (code.isEmpty()) return result(false, tr("请填写验证码"))
        val otp = emailOtp ?: return result(false, tr("先发送验证码"))
        val session = runBlocking { withTimeoutOrNull(STATE_TIMEOUT) { sessionStateProvider.stateFlow.first() } }
        val bind = session is SessionState.Valid
        val outcome = try {
            runBlocking {
                withTimeoutOrNull(OP_TIMEOUT) {
                    if (bind) userRepository.bindOrReBindEmail(otp.id, code) else userRepository.registerOrLoginByEmailOtp(otp.id, code)
                }
            } ?: return result(false, tr("服务器没有回应，请重试"))
        } catch (e: RepositoryException) {
            return result(false, repositoryErrorText(e))
        }
        return when (outcome) {
            is UserRepository.SendOtpResult.Success -> {
                emailOtp = null
                if (!bind) cancelLogin() // 手机上还挂着的 Bangumi 登录不要了
                logger.info { "Email ${if (bind) "bind" else "login"} succeeded from remote control" }
                result(true, if (bind) tr("邮箱已改成 {0}", otp.email) else tr("登录成功，电视已登录"))
            }

            UserRepository.SendOtpResult.InvalidOtp -> result(false, tr("验证码不对或已经过期"))
            UserRepository.SendOtpResult.EmailAlreadyExist -> result(false, tr("这个邮箱已经绑在别的账号上了"))
        }
    }

    private fun repositoryErrorText(e: RepositoryException): String = when (e) {
        is RepositoryRequestError -> e.localizedMessage ?: tr("请求有误")
        is RepositoryRateLimitedException -> tr("操作太频繁，稍后再试")
        is RepositoryNetworkException -> tr("网络错误，电视连不上 Animeko 服务器")
        is RepositoryServiceUnavailableException -> tr("服务器暂时不可用，稍后再试")
        else -> tr("出错了：") + (e.message ?: e::class.simpleName)
    }

    /** 只有还是「当前这一次」时才改状态: 被新的一次顶掉的旧协程别把新状态冲掉. */
    private fun update(owner: Job, state: Login) {
        synchronized(lock) { if (job === owner) login = state }
    }

    private fun errorText(error: LoadError): String = when (error) {
        LoadError.NetworkError -> tr("网络错误，电视连不上登录服务器")
        LoadError.ServiceUnavailable -> tr("登录服务器暂时不可用，稍后再试")
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

    /** 退出登录 / 改昵称等服务器回话最多等这么久. */
    private val OP_TIMEOUT = 15.seconds

    /** 只挡明显不是邮箱的 (服务器还会再验一遍, 格式不对回「邮箱格式不正确」). */
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    /** 同 EmailLoginViewModel: 发过一次验证码 30 秒内不再发. */
    private val RESEND_INTERVAL = 30.seconds

    /** 同 ProfileViewModel.NICKNAME_MATCHER. */
    private val NICKNAME = Regex("^[一-鿿぀-ゟ゠-ヿa-zA-Z\\d_]+$")

    /** 向 Ani 服务器要授权链接最多等这么久. */
    private val LINK_TIMEOUT = 20.seconds

    /** 链接交出去之后等结果的上限: 电视登录页那边有人盯着不设上限, 这里没人盯着, 要有个头. */
    private val LOGIN_TIMEOUT = 10.minutes
}
