/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import me.him188.ani.app.domain.session.auth.BangumiOAuthManager
import me.him188.ani.app.domain.session.auth.BangumiOAuthRelay
import me.him188.ani.app.domain.usecase.GlobalKoin
import java.net.URI

/**
 * 电视上直接给一个「用手机扫码登录 Bangumi」的码 (设置的账号页、首次引导的登录那一步): 发起一次经 Worker 中转的授权 (同 Web 控制台
 * 「用手机登录」, 见 [BangumiOAuthManager.startRelay]), 授权页地址画成码. 手机上授权完, bgm 回调 Worker, Worker 跳回电视的局域网地址
 * (Web 控制台那个服务的 `/bgm-oauth`, 见 [RemoteAccount.relayReturn]), 电视换 token 登录 —— 所以手机要和电视连同一个网.
 */
object TvBangumiRelayLogin {
    sealed interface Start {
        /** 授权页地址 (画成码). */
        data class Ready(val url: String) : Start

        /** 这个构建没带中转应用的凭据. */
        data object Unsupported : Start

        /** 电视没有局域网地址 (Web 控制台没起来): 跳不回来. */
        data object NoLan : Start
    }

    /** 发起一次 (放弃上一次没完成的授权). */
    fun start(): Start {
        val manager = GlobalKoin.get<BangumiOAuthManager>()
        if (!manager.relaySupported) return Start.Unsupported
        val host = TvRemoteControl.url.value?.let { runCatching { URI(it) }.getOrNull() }
            ?.let { "${it.host}:${it.port}" }
            ?.takeIf { BangumiOAuthRelay.isPrivateLanHost(it) }
            ?: return Start.NoLan
        manager.resetIfFinished()
        return manager.startRelay(host)?.let { Start.Ready(it) } ?: Start.Unsupported
    }
}
