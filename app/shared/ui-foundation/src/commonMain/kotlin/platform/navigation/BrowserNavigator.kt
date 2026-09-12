/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import me.him188.ani.app.navigation.BrowserNavigator
import me.him188.ani.app.navigation.OpenBrowserResult
import me.him188.ani.app.platform.Context
import me.him188.ani.app.ui.foundation.rememberAsyncHandler
import me.him188.ani.app.ui.foundation.setClipEntryText
import me.him188.ani.app.ui.foundation.widgets.OpenLinkFallbackDialog
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.logger

/**
 * Please use [rememberAsyncBrowserNavigator] instead of this directly.
 */
val LocalBrowserNavigator: ProvidableCompositionLocal<BrowserNavigator> = staticCompositionLocalOf {
    error("No BrowserNavigator provided")
}

private val logger = logger<BrowserNavigator>()

/**
 * Get [BrowserNavigator] which handles opening URLs asynchronously.
 * That means calling any of its methods always returns [OpenBrowserResult.Success] whether succeeded or failed.
 *
 * 打开失败时 (设备没有浏览器 —— 电视上很常见) 弹 [OpenLinkFallbackDialog]: 链接画成二维码让手机扫,
 * 同时印出链接文字; 顺手也复制进剪贴板 (桌面端好粘). 弹窗挂在调用本函数的那个组合里, 调用方离开
 * 组合弹窗就跟着没了, 不需要全局宿主.
 */
@Composable
fun rememberAsyncBrowserNavigator(): BrowserNavigator {
    val navigator = LocalBrowserNavigator.current
    val clipboard = LocalClipboard.current
    val scope = rememberAsyncHandler()
    var fallbackUrl by remember { mutableStateOf<String?>(null) }

    val failureAction: suspend (OpenBrowserResult.Failure) -> Unit =
        remember(clipboard) {
            { failure ->
                logger.error(failure.throwable) { "Failed to open ${failure.dest}, showing QR fallback" }
                runCatching { clipboard.setClipEntryText(failure.dest) }
                fallbackUrl = failure.dest
            }
        }

    fallbackUrl?.let { url ->
        OpenLinkFallbackDialog(url, onDismissRequest = { fallbackUrl = null })
    }

    return remember(navigator) {
        object : BrowserNavigator {
            override fun openBrowser(context: Context, url: String): OpenBrowserResult {
                scope.launch {
                    val openResult = navigator.openBrowser(context, url)
                    if (openResult is OpenBrowserResult.Failure) {
                        failureAction(openResult)
                    }
                }
                return OpenBrowserResult.Success
            }

            override fun openJoinGroup(context: Context): OpenBrowserResult {
                scope.launch {
                    val openResult = navigator.openJoinGroup(context)
                    if (openResult is OpenBrowserResult.Failure) {
                        failureAction(openResult)
                    }
                }
                return OpenBrowserResult.Success
            }

            override fun intentActionView(context: Context, url: String): OpenBrowserResult {
                scope.launch {
                    val openResult = navigator.intentActionView(context, url)
                    if (openResult is OpenBrowserResult.Failure) {
                        failureAction(openResult)
                    }
                }
                return OpenBrowserResult.Success
            }
        }
    }
}

/**
 * 给整棵界面树换一个打不开链接也不崩的 [LocalUriHandler].
 *
 * 平台自带的那个拉不起浏览器时直接抛异常 (没装浏览器; 7.1 盒子上还见过把网址交给别家不对外开放的
 * Activity, 抛 `SecurityException`), 而各处 `uriHandler.openUri` 都没接, 点个链接应用就闪退.
 * 这里接住, 改弹与 [rememberAsyncBrowserNavigator] 相同的 [OpenLinkFallbackDialog].
 */
@Composable
fun ProvideOpenLinkFallback(content: @Composable () -> Unit) {
    val platformHandler = LocalUriHandler.current
    var fallbackUrl by remember { mutableStateOf<String?>(null) }
    val handler = remember(platformHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                try {
                    platformHandler.openUri(uri)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to open $uri, showing QR fallback" }
                    fallbackUrl = uri
                }
            }
        }
    }

    CompositionLocalProvider(LocalUriHandler provides handler, content = content)

    fallbackUrl?.let { url ->
        OpenLinkFallbackDialog(url, onDismissRequest = { fallbackUrl = null })
    }
}
