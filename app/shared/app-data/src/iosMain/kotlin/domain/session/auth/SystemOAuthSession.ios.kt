/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session.auth

import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * `ASWebAuthenticationSession`: 共用 Safari 的登录状态 (不开 `prefersEphemeralWebBrowserSession`), Safari 里登过 bgm 的直接点授权;
 * 要换账号先在 Safari 里退出 bgm. 系统每次打开前会问一句「想要使用 bgm.tv 登录」.
 */
internal actual object SystemOAuthSession {
    private val logger = logger<SystemOAuthSession>()

    /** 开着的那个弹窗. 只在主线程上改; [close] 在别的线程上只读一下. */
    private var current: ASWebAuthenticationSession? = null

    private val anchorProvider = object : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): UIWindow =
            UIApplication.sharedApplication.keyWindow
                ?: UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow
                ?: UIWindow()
    }

    actual fun open(url: String, onDismissedByUser: () -> Unit): Boolean {
        val nsUrl = NSURL.URLWithString(url) ?: return false
        current?.let { old ->
            current = null
            old.cancel()
        }
        var self: ASWebAuthenticationSession? = null
        // 回调地址不是自定义 scheme (bgm 登记的是回环地址), 弹窗不会自己结束: 回环监听接住后由 close() 收起
        val noCallbackScheme: String? = null
        val session = ASWebAuthenticationSession(nsUrl, noCallbackScheme) { _, error ->
            // 先清 current 再 cancel 的那次 (我们自己收起的) 不算用户关掉
            val mine = self
            if (mine != null && current === mine) {
                current = null
                if (error != null) {
                    logger.info { "bgm-direct: oauth 系统授权弹窗被关掉 (code ${error.code})" }
                    onDismissedByUser()
                }
            }
        }
        session.presentationContextProvider = anchorProvider
        session.prefersEphemeralWebBrowserSession = false
        self = session
        current = session
        if (!session.start()) {
            current = null
            logger.warn { "bgm-direct: oauth 系统授权弹窗打不开, 交给系统浏览器" }
            return false
        }
        logger.info { "bgm-direct: oauth 授权页开在系统授权弹窗里" }
        return true
    }

    actual fun close() {
        val session = current ?: return
        dispatch_async(dispatch_get_main_queue()) {
            if (current === session) current = null
            session.cancel()
        }
    }
}
