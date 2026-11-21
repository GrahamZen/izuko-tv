/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import me.him188.ani.app.data.models.preference.RepoHostedListCache
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.foundation.RepoHostedList
import me.him188.ani.utils.ktor.ScopedHttpClient

/**
 * 工单服务 ([FeedbackTicketService]) 的地址清单: 本仓库清单分支的 `ticket-endpoints.json` (字段 `endpoints`, 按顺序试, 见 [RepoHostedList]).
 * 换地址、加自定义域名改清单, 不用发版; 从没拉到过用包里内置的 [FeedbackTicketService.OFFICIAL_ENDPOINTS].
 */
class FeedbackTicketEndpoints(
    cache: Settings<RepoHostedListCache>,
    client: () -> ScopedHttpClient,
    scope: CoroutineScope,
) : RepoHostedList(SPEC, cache, client, scope) {
    /** 现在用的地址, 同步读 (控制台判断工单开没开放、每次请求按它试). */
    val current: StateFlow<List<String>> = entries.stateIn(scope, SharingStarted.Eagerly, SPEC.bundled)

    companion object {
        /** 只认 `https://` 的根地址, 去掉末尾的 `/` (清单是远程内容, 写进去的东西不由我们决定); 认不出返回 `null`. */
        fun normalizeEndpoint(input: String): String? =
            input.trim().trimEnd('/').takeIf { it.startsWith("https://") && it.length > "https://".length && ' ' !in it }

        private val SPEC = Spec(
            fileName = "ticket-endpoints.json",
            field = "endpoints",
            bundled = FeedbackTicketService.OFFICIAL_ENDPOINTS,
            normalize = ::normalizeEndpoint,
        )
    }
}
