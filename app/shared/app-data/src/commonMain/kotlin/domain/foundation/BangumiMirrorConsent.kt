/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.him188.ani.app.data.models.preference.BangumiEndpointSettings
import me.him188.ani.app.data.models.preference.BangumiRouteKind
import kotlin.concurrent.Volatile

/**
 * 已登录的人改成「只剩第三方镜像」之前要他点头.
 *
 * 登录后应用发往 Bangumi 的请求 (连浏览条目在内) 都带着令牌; 没打开「登录与收藏同步也经过镜像」时, 带令牌的请求不走镜像
 * (见 [BangumiRoutingFeature]). 所以已登录、凭证又不经过镜像时把能带凭证的线路 (官方、自建) 全关掉,
 * 就什么都用不了 —— 改之前让他选: 允许凭证经过镜像 / 退出登录再改 (只用镜像浏览) / 不改.
 */
object BangumiMirrorConsent {
    enum class Question {
        /** 要改成只剩镜像, 而凭证不经过镜像. */
        SwitchToMirror,

        /** 正只用着镜像, 要关掉「登录与收藏同步也经过镜像」. */
        CredentialsOff,
    }

    /** 从 [current] 改成 [target] 之前要问哪一句; `null` = 不用问. */
    fun check(current: BangumiEndpointSettings, target: BangumiEndpointSettings, loggedIn: Boolean): Question? = when {
        !loggedIn || target.allowCredentialsViaMirror || !target.onlyThirdPartyRoutes() -> null
        !current.onlyThirdPartyRoutes() -> Question.SwitchToMirror
        current.allowCredentialsViaMirror -> Question.CredentialsOff
        else -> null
    }

    /** 开着的线路里只剩第三方镜像: 官方、自建 (填得成样子) 都没开. */
    fun BangumiEndpointSettings.onlyThirdPartyRoutes(): Boolean =
        isEnabled(BangumiRouteKind.MIRRORS) &&
                !isEnabled(BangumiRouteKind.ORIGIN) &&
                !(isEnabled(BangumiRouteKind.CUSTOM) && customRoot != null)
}

/**
 * 「官方连不上时用镜像」要自动改成「用镜像」、却需要用户点头时 (见 [BangumiMirrorConsent]), 由这里请界面问他.
 *
 * 选了「暂不」这次运行就不再问: 镜像的粘性过期后请求还会再报一次官方连不上, 不能每半小时弹一次.
 */
class BangumiMirrorConsentRequests {
    private val _pending = MutableStateFlow(false)

    /** 有一个待回答的询问. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    @Volatile
    private var declined = false

    fun request() {
        if (!declined) _pending.value = true
    }

    /** 界面问完了; [declined] = 用户选了「暂不」. */
    fun resolve(declined: Boolean) {
        if (declined) this.declined = true
        _pending.value = false
    }
}
