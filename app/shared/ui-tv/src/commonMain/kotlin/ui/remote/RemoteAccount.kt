/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.data.models.user.calculateDisplay
import me.him188.ani.app.data.repository.user.UserRepository
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.session.InvalidSessionReason
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
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
 * 账号就是 Bangumi 账号, 授权走 [BangumiOAuthManager] 的应用内浏览器那条路: Bangumi 授权完之后跳回的是
 * **电视本机的回环地址** (`127.0.0.1:41890`), 所以授权页只能在电视上打开 —— 手机上打开同一个链接, 授权完会跳到
 * 手机自己的回环端口, 电视永远收不到 code.
 *
 * 于是手机这边能做的是**遥控**: 点一下让电视弹出授权页 (授权页挂在应用根部的 `BangumiOAuthDialogHost`,
 * 不管电视当前在哪一页都能弹), 然后在电视上用遥控器完成; 手机上每 2 秒问一次状态, 成功了给个提示.
 *
 * 同一时间只等一次: 手机上再点一次 = 放弃上一次、重新开始. 等 [LOGIN_TIMEOUT] 还没结果就放弃.
 */
internal object RemoteAccount {
    private val logger = logger<RemoteAccount>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("RemoteAccount"))

    private val sessionStateProvider: SessionStateProvider get() = KoinPlatform.getKoin().get()
    private val userRepository: UserRepository get() = KoinPlatform.getKoin().get()
    private val oauthManager: BangumiOAuthManager get() = KoinPlatform.getKoin().get()

    /** 手机发起的那次登录走到哪了. 成功后回到 Idle (登录状态看会话本身). */
    private sealed interface Login {
        data object Idle : Login

        /** 电视上的授权页已经打开, 等电视上完成. */
        data object Waiting : Login

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
            putJsonObject("login") {
                when (current) {
                    Login.Idle -> put("state", "idle")
                    Login.Waiting -> put("state", "waiting")
                    is Login.Failed -> {
                        put("state", "failed")
                        put("message", current.message)
                    }
                }
            }
        }
    }

    /**
     * 让电视弹出 Bangumi 授权页 (放弃上一次), 之后在后台盯着结果, 结果用提示送到手机上.
     *
     * 只能这么做的原因见 [RemoteAccount] 的说明: 回调地址是电视本机的回环端口.
     */
    private fun startLogin(): JsonObject {
        val session = runBlocking { withTimeoutOrNull(STATE_TIMEOUT) { sessionStateProvider.stateFlow.first() } }
        if (session is SessionState.Valid) return result(false, tr("电视已经登录了"))
        val manager = oauthManager
        if (!manager.inAppBrowserSupported) {
            return result(false, tr("这台电视打不开授权页，请在电视的设置里登录"))
        }
        // 在锁里起协程并登记: 它第一次 update 要拿同一把锁, 那时 job 一定已经是它
        synchronized(lock) {
            job?.cancel()
            login = Login.Waiting
            // state 是进程内单例, 上一次的成功 / 失败会一直停在那儿, 不重置就再也起不来 (同电视授权页的做法)
            manager.resetIfFinished()
            manager.startInAppBrowser()
            scope.launch { awaitResult(coroutineContext.job, manager) }.also { job = it }
        }
        logger.info { "Remote control asked TV to start Bangumi OAuth" }
        return result(true, tr("电视上已经打开 Bangumi 授权页，请用遥控器完成授权"))
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

            // 电视上自己关掉了授权页
            BangumiOAuthManager.State.Idle -> update(me, Login.Idle)

            null -> {
                manager.cancel()
                update(me, Login.Failed(tr("等太久没有结果，请重新登录")))
                TvRemoteControl.postNotice(tr("登录没有完成：等太久没有结果，请重新登录"))
            }

            else -> {}
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

    /** 授权页打开之后等结果的上限: 电视授权页那边有人盯着不设上限, 这里没人盯着, 要有个头. */
    private val LOGIN_TIMEOUT = 10.minutes
}
