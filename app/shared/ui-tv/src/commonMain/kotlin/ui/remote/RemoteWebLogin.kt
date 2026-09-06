/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.app.domain.foundation.BangumiEndpointProvider
import me.him188.ani.app.domain.foundation.BangumiLoginPath
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.domain.session.auth.BangumiOAuthManager
import me.him188.ani.app.domain.session.auth.BangumiWebLogin
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.mp.KoinPlatform
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台账号卡片里的「输入账号密码登录」([BangumiLoginPath.TV_WEB_LOGIN]): 手机上填 Bangumi 的邮箱、密码与验证码,
 * 电视替用户登录网页并生成个人令牌 (见 [BangumiWebLogin]), 再用令牌登录. 经加密直连时只给这一条路: 手机多半打不开 bgm.tv,
 * 只连电视就行.
 *
 * 密码只在提交那一个请求里经过, 不存、不记日志.
 *
 * - `start`: 开始一次 (放弃正在等的其他登录), 回一张验证码;
 * - `captcha`: 换一张验证码;
 * - `submit`: 提交; 被拒时顺手换一张新验证码回来 (旧的提交一次就作废了);
 * - `cancel`: 放弃.
 */
internal object RemoteWebLogin {
    private val logger = logger<RemoteWebLogin>()

    private val webLogin: BangumiWebLogin get() = KoinPlatform.getKoin().get()
    private val oauthManager: BangumiOAuthManager get() = KoinPlatform.getKoin().get()
    private val endpoints: BangumiEndpointProvider get() = KoinPlatform.getKoin().get()
    private val sessionStateProvider: SessionStateProvider get() = KoinPlatform.getKoin().get()

    private const val PATH = "api/account/weblogin"

    /** 处理 `api/account/weblogin/` 下的请求 (只收 POST, 由 [RemoteAccount.handle] 转来); 路径不认识返回 null. */
    fun handle(request: LanHttpRequest): JsonObject? = when (request.path) {
        "$PATH/start" -> start()
        "$PATH/captcha" -> captcha()
        "$PATH/submit" -> submit(request.formFields())
        "$PATH/cancel" -> cancel()
        else -> null
    }

    /** 账号状态里的 `loginPath`: 网页上给哪一条登录路 (`browser` 授权 / `web` 账号密码 / `token` 粘贴令牌, 见 [BangumiLoginPath]). */
    fun putState(builder: JsonObjectBuilder) {
        builder.put(
            "loginPath",
            when (endpoints.loginPath.value) {
                BangumiLoginPath.BROWSER -> "browser"
                BangumiLoginPath.TV_WEB_LOGIN -> "web"
                BangumiLoginPath.PERSONAL_TOKEN -> "token"
            },
        )
    }

    private fun start(): JsonObject {
        if (loggedIn()) return result(false, tr("电视已经登录了"))
        // 同一时间只等一次: 正在等的手机授权作废
        RemoteAccount.cancelLogin()
        val captcha = try {
            runBlocking { withTimeout(START_TIMEOUT) { webLogin.start() } }
        } catch (e: Exception) {
            return failure(e)
        }
        logger.info { "Remote control started Bangumi web login" }
        return captchaResult(captcha)
    }

    private fun captcha(): JsonObject {
        val captcha = try {
            runBlocking { withTimeout(OP_TIMEOUT) { webLogin.newCaptcha() } }
        } catch (e: Exception) {
            return failure(e)
        }
        return captchaResult(captcha)
    }

    private fun submit(fields: Map<String, String>): JsonObject {
        val email = fields["email"].orEmpty().trim()
        val password = fields["password"].orEmpty()
        val code = fields["captcha"].orEmpty().trim()
        if (email.isEmpty() || password.isEmpty()) return result(false, tr("请填写邮箱和密码"))
        if (code.isEmpty()) return result(false, tr("请填写验证码"))
        if (loggedIn()) return result(false, tr("电视已经登录了"))
        val outcome = try {
            runBlocking { withTimeout(SUBMIT_TIMEOUT) { webLogin.submit(email, password, code) } }
        } catch (e: Exception) {
            return failure(e)
        }
        return when (outcome) {
            is BangumiWebLogin.Result.Token -> useToken(outcome)
            is BangumiWebLogin.Result.Rejected -> {
                // 验证码提交一次就作废, 顺手换一张新的回去; 换不到就让网页上点图再换
                val next = runCatching { runBlocking { withTimeout(OP_TIMEOUT) { webLogin.newCaptcha() } } }.getOrNull()
                buildJsonObject {
                    put("ok", false)
                    put("message", outcome.message?.let { tr("Bangumi 没让登录：{0}", it) } ?: tr("没有登录上，请检查邮箱、密码和验证码"))
                    next?.let { put("captcha", dataUrl(it)) }
                }
            }
            is BangumiWebLogin.Result.TokenFailed -> result(
                false,
                outcome.message?.let { tr("登录上了，但生成令牌没有成功：{0}", it) } ?: tr("登录上了，但生成令牌没有成功，请重新开始一次"),
            )
        }
    }

    /** 拿到令牌: 同粘贴个人令牌那条, 校验一次后写进会话, 到期退出登录. */
    private fun useToken(token: BangumiWebLogin.Result.Token): JsonObject {
        // 包一层: 登录函数成功时返回 null, 直接交给 withTimeoutOrNull 就分不清成功与超时 (同 RemoteAccount.tokenLogin)
        val outcome = runBlocking {
            withTimeoutOrNull(OP_TIMEOUT) { TokenLoginOutcome(oauthManager.loginWithPersonalToken(token.token, token.validDays)) }
        } ?: return result(false, tr("令牌生成好了，但校验超时，请重新开始一次"))
        val error = outcome.error ?: run {
            logger.info { "Remote control web login succeeded" }
            return result(true, tr("登录成功，电视已登录"))
        }
        return result(false, tr("令牌生成好了，但电视没能用它登录：{0}", RemoteAccount.errorText(error)))
    }

    private class TokenLoginOutcome(val error: LoadError?)

    private fun cancel(): JsonObject {
        runBlocking { webLogin.cancel() }
        return result(true, tr("已取消"))
    }

    private fun failure(e: Exception): JsonObject = when (e) {
        is BangumiWebLogin.NoRouteException ->
            result(false, tr("现在没有不经第三方、能连到 Bangumi 的线路。请在设置的「Bangumi 连接方式」里打开「加密直连」"))
        // 网页上据此收起表单, 回到开始
        is BangumiWebLogin.NotStartedException -> buildJsonObject {
            put("ok", false)
            put("expired", true)
            put("message", tr("这次登录已经失效，请重新开始"))
        }
        is TimeoutCancellationException, is IOException -> {
            logger.info { "Remote web login failed: ${e::class.simpleName}: ${e.message}" }
            result(false, tr("电视连不上 Bangumi 的登录页，稍后再试"))
        }
        else -> throw e
    }

    private fun loggedIn(): Boolean =
        runBlocking { withTimeoutOrNull(STATE_TIMEOUT) { sessionStateProvider.stateFlow.first() } } is SessionState.Valid

    private fun captchaResult(captcha: BangumiWebLogin.Captcha): JsonObject = buildJsonObject {
        put("ok", true)
        put("captcha", dataUrl(captcha))
    }

    /** 验证码图片直接放进 `<img src>`: 手机连不上 bgm, 不能让它自己去取. 类型只认图片, 别的一律按 gif. */
    private fun dataUrl(captcha: BangumiWebLogin.Captcha): String {
        val type = captcha.contentType.substringBefore(';').trim().takeIf { IMAGE_TYPE.matches(it) } ?: "image/gif"
        return "data:$type;base64," + Base64.getEncoder().encodeToString(captcha.bytes)
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    private val IMAGE_TYPE = Regex("image/[a-z0-9.+-]+")

    private val STATE_TIMEOUT = 3.seconds

    /** 开始时可能要先试一遍连不上的官方 (缩短的超时) 再换线路; 网页那边的请求 45 秒放弃. */
    private val START_TIMEOUT = 40.seconds

    /** 提交登录与生成令牌 (两个请求); 校验令牌另算 [OP_TIMEOUT], 合起来不超过网页那边的 45 秒. */
    private val SUBMIT_TIMEOUT = 25.seconds

    private val OP_TIMEOUT = 15.seconds
}

/** 账号卡片「输入账号密码登录」的文案 (见 [RemoteWebLogin], 译文表见 [REMOTE_I18N_TABLE]). */
internal fun webLoginTexts() = listOf(
    RemoteText("输入账号密码登录", "Sign in with email and password", "輸入帳號密碼登錄", "輸入帳號密碼登入"),
    RemoteText(
        "电视替你登录 Bangumi：邮箱和密码只经过这台手机和电视，由电视直接发给 Bangumi，不会存下来。",
        "The TV signs in to Bangumi for you. Your email and password only pass through this phone and the TV, go straight to Bangumi, and are not stored.",
        "電視替你登錄 Bangumi：郵箱和密碼只經過這台手機和電視，由電視直接發給 Bangumi，不會存下來。",
        "電視替你登入 Bangumi：郵箱和密碼只經過這台手機和電視，由電視直接傳給 Bangumi，不會存下來。",
    ),
    RemoteText("看不清？点图换一张", "Can't read it? Tap the image for a new one", "看不清？點圖換一張", "看不清？點圖換一張"),
    RemoteText("正在连接 Bangumi…", "Connecting to Bangumi…", "正在連接 Bangumi…", "正在連線 Bangumi…"),
    RemoteText("请填写邮箱和密码", "Enter your email and password", "請填寫郵箱和密碼", "請填寫郵箱和密碼"),
    RemoteText("请填写验证码", "Enter the verification code", "請填寫驗證碼", "請填寫驗證碼"),
    RemoteText("Bangumi 没让登录：{0}", "Bangumi refused the sign-in: {0}", "Bangumi 沒讓登錄：{0}", "Bangumi 沒讓登入：{0}"),
    RemoteText(
        "没有登录上，请检查邮箱、密码和验证码",
        "Couldn't sign in. Check your email, password and verification code.",
        "沒有登錄上，請檢查郵箱、密碼和驗證碼",
        "沒有登入成功，請檢查郵箱、密碼和驗證碼",
    ),
    RemoteText("登录上了，但生成令牌没有成功：{0}", "Signed in, but the token couldn't be created: {0}", "登錄上了，但生成令牌沒有成功：{0}", "登入成功，但產生令牌沒有成功：{0}"),
    RemoteText(
        "登录上了，但生成令牌没有成功，请重新开始一次",
        "Signed in, but the token couldn't be created. Please start over.",
        "登錄上了，但生成令牌沒有成功，請重新開始一次",
        "登入成功，但產生令牌沒有成功，請重新開始一次",
    ),
    RemoteText(
        "令牌生成好了，但校验超时，请重新开始一次",
        "The token was created, but checking it timed out. Please start over.",
        "令牌生成好了，但校驗超時，請重新開始一次",
        "令牌產生好了，但驗證逾時，請重新開始一次",
    ),
    RemoteText(
        "令牌生成好了，但电视没能用它登录：{0}",
        "The token was created, but the TV couldn't sign in with it: {0}",
        "令牌生成好了，但電視沒能用它登錄：{0}",
        "令牌產生好了，但電視沒能用它登入：{0}",
    ),
    RemoteText(
        "电视替你登录 Bangumi，并生成一个一年有效的令牌；到期后再登录一次就行。",
        "The TV signs in to Bangumi for you and creates a token that lasts a year. Sign in again when it expires.",
        "電視替你登錄 Bangumi，並生成一個一年有效的令牌；到期後再登錄一次就行。",
        "電視替你登入 Bangumi，並產生一個一年有效的令牌；到期後再登入一次就行。",
    ),
    RemoteText(
        "这里用不了手机扫码授权，请在「设置 → 账号」里输入账号密码登录",
        "Phone authorization isn't available here. Sign in with email and password under Settings → Account.",
        "這裡用不了手機掃碼授權，請在「設置 → 帳號」裡輸入帳號密碼登錄",
        "這裡用不了手機掃碼授權，請在「設定 → 帳號」裡輸入帳號密碼登入",
    ),
    RemoteText(
        "现在经加密直连连接 Bangumi，手机打不开授权页。请在「设置 → 账号」里输入账号密码登录",
        "Bangumi is reached through encrypted direct, so your phone can't open the authorization page. Sign in with email and password under Settings → Account.",
        "現在經加密直連連接 Bangumi，手機打不開授權頁。請在「設置 → 帳號」裡輸入帳號密碼登錄",
        "現在經加密直連連線 Bangumi，手機打不開授權頁。請在「設定 → 帳號」裡輸入帳號密碼登入",
    ),
    RemoteText(
        "现在没有不经第三方、能连到 Bangumi 的线路。请在设置的「Bangumi 连接方式」里打开「加密直连」",
        "No route reaches Bangumi without a third party right now. Turn on “Encrypted direct” under “Bangumi connection” in Settings.",
        "現在沒有不經第三方、能連到 Bangumi 的線路。請在設置的「Bangumi 連接方式」裡打開「加密直連」",
        "現在沒有不經第三方、能連到 Bangumi 的線路。請在設定的「Bangumi 連線方式」裡開啟「加密直連」",
    ),
    RemoteText("这次登录已经失效，请重新开始", "This sign-in has expired. Please start over.", "這次登錄已經失效，請重新開始", "這次登入已經失效，請重新開始"),
    RemoteText("电视连不上 Bangumi 的登录页，稍后再试", "The TV can't reach the Bangumi sign-in page. Try again later.", "電視連不上 Bangumi 的登錄頁，稍後再試", "電視連不上 Bangumi 的登入頁，稍後再試"),
)
