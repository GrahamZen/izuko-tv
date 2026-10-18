/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.oauth

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.foundation.BangumiEndpointProvider
import me.him188.ani.app.domain.foundation.BangumiLoginPath
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.session.auth.BangumiOAuthConstants
import me.him188.ani.app.domain.session.auth.BangumiOAuthManager
import me.him188.ani.app.domain.session.auth.BangumiWebLogin
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.platform.navigation.rememberAsyncBrowserNavigator
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_network_bangumi_endpoint
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_credentials
import me.him188.ani.app.ui.lang.settings_network_bangumi_route_ech
import me.him188.ani.app.ui.lang.touch_login_captcha
import me.him188.ani.app.ui.lang.touch_login_captcha_refresh
import me.him188.ani.app.ui.lang.touch_login_connecting
import me.him188.ani.app.ui.lang.touch_login_email
import me.him188.ani.app.ui.lang.touch_login_expired
import me.him188.ani.app.ui.lang.touch_login_fill_captcha
import me.him188.ani.app.ui.lang.touch_login_fill_email_password
import me.him188.ani.app.ui.lang.touch_login_mirror_credentials
import me.him188.ani.app.ui.lang.touch_login_mirror_credentials_enable
import me.him188.ani.app.ui.lang.touch_login_no_route
import me.him188.ani.app.ui.lang.touch_login_password
import me.him188.ani.app.ui.lang.touch_login_rejected
import me.him188.ani.app.ui.lang.touch_login_rejected_unknown
import me.him188.ani.app.ui.lang.touch_login_retry
import me.him188.ani.app.ui.lang.touch_login_submit
import me.him188.ani.app.ui.lang.touch_login_token
import me.him188.ani.app.ui.lang.touch_login_token_days
import me.him188.ani.app.ui.lang.touch_login_token_days_value
import me.him188.ani.app.ui.lang.touch_login_token_empty
import me.him188.ani.app.ui.lang.touch_login_token_failed
import me.him188.ani.app.ui.lang.touch_login_token_failed_unknown
import me.him188.ani.app.ui.lang.touch_login_token_intro
import me.him188.ani.app.ui.lang.touch_login_token_invalid
import me.him188.ani.app.ui.lang.touch_login_token_network
import me.him188.ani.app.ui.lang.touch_login_token_open_page
import me.him188.ani.app.ui.lang.touch_login_token_steps
import me.him188.ani.app.ui.lang.touch_login_token_timeout
import me.him188.ani.app.ui.lang.touch_login_token_unusable
import me.him188.ani.app.ui.lang.touch_login_unreachable
import me.him188.ani.app.ui.lang.touch_login_web_intro
import me.him188.ani.app.ui.search.renderLoadErrorMessage
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

/**
 * 授权登录走不通 ([loginPath] 不是 [BangumiLoginPath.BROWSER]) 时就在登录页里登录, 给没有右侧手机控制台码的端 (平板模式等) 用:
 * - 加密直连 ([BangumiLoginPath.TV_WEB_LOGIN]): 填邮箱、密码与验证码, 这台设备替用户登录 Bangumi 网页并生成一年的令牌 (见 [BangumiWebLogin]);
 * - 经镜像 ([BangumiLoginPath.PERSONAL_TOKEN]): 粘贴在官网生成的个人令牌 ([BangumiOAuthManager.loginWithPersonalToken]).
 *
 * 与 Web 控制台账号卡片的两张表单同一套流程与提示. 登录成功后会话变了, 登录页照常收到新登录事件退出. 密码不存、不记日志.
 */
@Composable
fun BangumiDirectLoginForm(loginPath: BangumiLoginPath, modifier: Modifier = Modifier) {
    when (loginPath) {
        BangumiLoginPath.TV_WEB_LOGIN -> BangumiWebLoginForm(modifier)
        BangumiLoginPath.PERSONAL_TOKEN -> BangumiTokenLoginForm(modifier)
        BangumiLoginPath.BROWSER -> {}
    }
}

/** 表单下面那一句 (组合里才换成字). */
private sealed interface FormMessage {
    class Res(val res: StringResource, vararg val args: String) : FormMessage
    data object NoRoute : FormMessage
    data object TokenNetwork : FormMessage
    class Load(val error: LoadError) : FormMessage
    class TokenUnusable(val error: LoadError) : FormMessage
}

@Composable
private fun renderFormMessage(message: FormMessage): String = when (message) {
    is FormMessage.Res -> stringResource(message.res, *message.args)
    FormMessage.NoRoute -> stringResource(
        Lang.touch_login_no_route,
        stringResource(Lang.settings_network_bangumi_endpoint),
        stringResource(Lang.settings_network_bangumi_route_ech),
    )

    FormMessage.TokenNetwork -> stringResource(
        Lang.touch_login_token_network,
        stringResource(Lang.settings_network_bangumi_endpoint),
        stringResource(Lang.settings_network_bangumi_mirror_credentials),
    )

    is FormMessage.Load -> renderLoadErrorMessage(message.error)
    is FormMessage.TokenUnusable -> stringResource(Lang.touch_login_token_unusable, renderLoadErrorMessage(message.error))
}

/** 包一层: 令牌登录成功时返回 null, 直接交给 withTimeoutOrNull 就分不清成功与超时. */
private class TokenOutcome(val error: LoadError?)

// ---- 邮箱 + 密码 + 验证码 ----

@Stable
private class WebLoginFormState(private val scope: CoroutineScope) {
    private val webLogin: BangumiWebLogin get() = GlobalKoin.get()
    private val oauthManager: BangumiOAuthManager get() = GlobalKoin.get()

    var captcha by mutableStateOf<ImageBitmap?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<FormMessage?>(null)
        private set

    /** 开始一次 (取第一张验证码). 失败时 [captcha] 留空, 页面给「重新开始」. */
    fun start() = launchBusy {
        captcha = null
        captcha = withTimeout(START_TIMEOUT) { webLogin.start() }.decode()
        logger.info { "Touch login started Bangumi web login" }
    }

    fun refreshCaptcha() = launchBusy {
        captcha = withTimeout(OP_TIMEOUT) { webLogin.newCaptcha() }.decode()
    }

    fun submit(email: String, password: String, code: String) {
        when {
            email.isBlank() || password.isEmpty() -> message = FormMessage.Res(Lang.touch_login_fill_email_password)
            code.isBlank() -> message = FormMessage.Res(Lang.touch_login_fill_captcha)
            else -> launchBusy {
                when (val outcome = withTimeout(SUBMIT_TIMEOUT) { webLogin.submit(email.trim(), password, code.trim()) }) {
                    is BangumiWebLogin.Result.Token -> useToken(outcome)
                    is BangumiWebLogin.Result.Rejected -> {
                        message = outcome.message?.let { FormMessage.Res(Lang.touch_login_rejected, it) }
                            ?: FormMessage.Res(Lang.touch_login_rejected_unknown)
                        // 验证码提交一次就作废, 顺手换一张新的; 换不到就让用户点图再换
                        captcha = withTimeoutOrNull(OP_TIMEOUT) { runCatching { webLogin.newCaptcha() }.getOrNull() }?.decode()
                    }

                    is BangumiWebLogin.Result.TokenFailed ->
                        message = outcome.message?.let { FormMessage.Res(Lang.touch_login_token_failed, it) }
                            ?: FormMessage.Res(Lang.touch_login_token_failed_unknown)
                }
            }
        }
    }

    /** 拿到令牌: 同粘贴个人令牌那条, 校验一次后写进会话, 到期退出登录. */
    private suspend fun useToken(token: BangumiWebLogin.Result.Token) {
        val outcome = withTimeoutOrNull(OP_TIMEOUT) { TokenOutcome(oauthManager.loginWithPersonalToken(token.token, token.validDays)) }
        val error = outcome?.error
        message = when {
            outcome == null -> FormMessage.Res(Lang.touch_login_token_timeout)
            error != null -> FormMessage.TokenUnusable(error)
            else -> {
                logger.info { "Touch web login succeeded" }
                null
            }
        }
    }

    private fun launchBusy(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                block()
            } catch (e: TimeoutCancellationException) {
                message = FormMessage.Res(Lang.touch_login_unreachable)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BangumiWebLogin.NoRouteException) {
                message = FormMessage.NoRoute
            } catch (e: BangumiWebLogin.NotStartedException) {
                // 这次已经失效 (会话被收掉了): 收起验证码, 页面给「重新开始」
                captcha = null
                message = FormMessage.Res(Lang.touch_login_expired)
            } catch (e: Exception) {
                logger.info { "Touch web login failed: ${e::class.simpleName}: ${e.message}" }
                message = FormMessage.Res(Lang.touch_login_unreachable)
            } finally {
                busy = false
            }
        }
    }

    private fun BangumiWebLogin.Captcha.decode(): ImageBitmap = bytes.decodeToImageBitmap()

    private companion object {
        val logger = logger<WebLoginFormState>()

        /** 开始时可能要先试一遍连不上的官方 (缩短的超时) 再换线路. */
        val START_TIMEOUT = 40.seconds

        /** 提交登录与生成令牌 (两个请求); 校验令牌另算 [OP_TIMEOUT]. */
        val SUBMIT_TIMEOUT = 25.seconds

        val OP_TIMEOUT = 15.seconds
    }
}

@Composable
private fun BangumiWebLoginForm(modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val state = remember { WebLoginFormState(scope) }
    LaunchedEffect(Unit) { state.start() }

    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val submit = {
        state.submit(email, password, code)
        code = ""
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(Lang.touch_login_web_intro), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            email, { email = it },
            label = { Text(stringResource(Lang.touch_login_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            password, { password = it },
            label = { Text(stringResource(Lang.touch_login_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                code, { code = it },
                label = { Text(stringResource(Lang.touch_login_captcha)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f),
            )
            CaptchaImage(state.captcha, loading = state.busy, onClick = state::refreshCaptcha)
        }
        Text(
            stringResource(Lang.touch_login_captcha_refresh),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.message?.let { Text(renderFormMessage(it), color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = submit, enabled = !state.busy && state.captcha != null) {
                Text(stringResource(Lang.touch_login_submit))
            }
            // 没取到验证码 (连不上、这次已失效) 时从头来
            if (!state.busy && state.captcha == null) {
                FilledTonalButton(onClick = state::start) { Text(stringResource(Lang.touch_login_retry)) }
            }
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                if (state.captcha == null) Text(stringResource(Lang.touch_login_connecting))
            }
        }
    }
}

/** 验证码图: 点一下换一张. */
@Composable
private fun CaptchaImage(captcha: ImageBitmap?, loading: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.width(120.dp).height(48.dp).clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !loading && captcha != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (captcha != null) {
            Image(captcha, contentDescription = stringResource(Lang.touch_login_captcha), contentScale = ContentScale.Fit)
        } else if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

// ---- 个人令牌 ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BangumiTokenLoginForm(modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val browser = rememberAsyncBrowserNavigator()
    val endpoints = remember { GlobalKoin.get<BangumiEndpointProvider>() }
    val settings = remember { GlobalKoin.get<SettingsRepository>() }
    val oauthManager = remember { GlobalKoin.get<BangumiOAuthManager>() }
    val viaMirror by endpoints.viaThirdPartyMirror.collectAsStateWithLifecycle()
    val credentialsAllowed by remember { settings.bangumiEndpointSettings.flow.map { it.allowCredentialsViaMirror } }
        .collectAsStateWithLifecycle(initialValue = true)
    // 经镜像而凭证不许经过镜像: 校验请求会被留在官方 (连不上) 干等到超时, 先要打开那个开关
    val needCredentials = viaMirror && !credentialsAllowed

    var token by remember { mutableStateOf("") }
    var days by rememberSaveable { mutableIntStateOf(TOKEN_DAYS.last()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<FormMessage?>(null) }

    fun submit() {
        if (busy) return
        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            message = FormMessage.Res(Lang.touch_login_token_empty)
            return
        }
        busy = true
        message = null
        scope.launch {
            try {
                val outcome = withTimeoutOrNull(TOKEN_TIMEOUT) { TokenOutcome(oauthManager.loginWithPersonalToken(trimmed, days)) }
                val error = outcome?.error
                message = when {
                    outcome == null -> FormMessage.Res(Lang.touch_login_token_timeout)
                    error == null -> null
                    error == LoadError.RequiresLogin -> FormMessage.Res(Lang.touch_login_token_invalid)
                    error == LoadError.NetworkError -> FormMessage.TokenNetwork
                    else -> FormMessage.Load(error)
                }
            } finally {
                busy = false
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(Lang.touch_login_token_intro), style = MaterialTheme.typography.bodyLarge)
        if (needCredentials) {
            Text(
                stringResource(Lang.touch_login_mirror_credentials, stringResource(Lang.settings_network_bangumi_mirror_credentials)),
                color = MaterialTheme.colorScheme.error,
            )
            // 这个按钮就是同意: 风险写在上面那一句里
            FilledTonalButton(
                onClick = { scope.launch { settings.bangumiEndpointSettings.update { copy(allowCredentialsViaMirror = true) } } },
            ) { Text(stringResource(Lang.touch_login_mirror_credentials_enable)) }
        }
        Text(stringResource(Lang.touch_login_token_steps))
        TextButton(onClick = { scope.launch { browser.openBrowser(context, BangumiOAuthConstants.PERSONAL_TOKEN_PAGE) } }) {
            Text(stringResource(Lang.touch_login_token_open_page))
        }
        OutlinedTextField(
            token, { token = it },
            label = { Text(stringResource(Lang.touch_login_token)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(Lang.touch_login_token_days), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in TOKEN_DAYS) {
                FilterChip(
                    selected = option == days,
                    onClick = { days = option },
                    label = { Text(stringResource(Lang.touch_login_token_days_value, option)) },
                )
            }
        }
        message?.let { Text(renderFormMessage(it), color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = ::submit, enabled = !busy && !needCredentials) { Text(stringResource(Lang.touch_login_submit)) }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

/** 生成令牌时可选的有效期 (天), 同 Web 控制台. */
private val TOKEN_DAYS = listOf(7, 30, 90, 180, 365)

private val TOKEN_TIMEOUT = 15.seconds
