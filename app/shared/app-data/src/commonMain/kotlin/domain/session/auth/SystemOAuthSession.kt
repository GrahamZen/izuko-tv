/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session.auth

/**
 * 系统自带的授权弹窗 (iOS 的 `ASWebAuthenticationSession`): Safari 开在应用里面, 共用 Safari 的登录状态与钥匙串密码.
 *
 * 用它而不跳去浏览器应用, 是因为 bgm 授权完只跳登记好的回环地址 ([BangumiOAuthConstants.CALLBACK_URL]):
 * 浏览器应用在前台时 iOS 会把我们挂起, [OAuthLoopbackServer] 接不到那一跳; 授权弹窗开着时应用一直在前台, 接得住.
 * 回调不走自定义 scheme, 由回环监听接住后 [close] 收起弹窗.
 */
internal expect object SystemOAuthSession {
    /**
     * 在授权弹窗里打开 [url]. 主线程调用.
     *
     * @param onDismissedByUser 用户没授权完就关掉了弹窗时调用 (主线程); [close] 收起的不算
     * @return 平台没有这种弹窗或打不开时返回 false, 调用方照旧交给系统浏览器
     */
    fun open(url: String, onDismissedByUser: () -> Unit): Boolean

    /** 收起开着的弹窗 (拿到回调、取消、换了一条路); 没开着时什么也不做. 任意线程. */
    fun close()
}
