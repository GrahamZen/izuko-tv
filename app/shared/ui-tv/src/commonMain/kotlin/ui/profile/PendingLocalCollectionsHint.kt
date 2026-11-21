/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import me.him188.ani.app.domain.profile.BangumiProfileConversion
import me.him188.ani.app.domain.profile.UserProfiles
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.profile_pending_hint
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

/**
 * 当前用户 (改成 Bangumi 用户后还没登录) 有等着处理的本地收藏时的一句提示, 没有为 null.
 * 收藏页空着、设置的账号页上给人看: 收藏没丢, 登录后再选.
 */
@Composable
fun rememberPendingLocalCollectionsHint(): String? {
    val count by remember { pendingCountWhileLoggedOut() }.collectAsStateWithLifecycle(0)
    return if (count > 0) stringResource(Lang.profile_pending_hint, count) else null
}

/** 改成 Bangumi 用户后还没登录时, 等着处理的本地收藏条数; 其余情况为 0. */
fun pendingCountWhileLoggedOut(): Flow<Int> {
    if (UserProfiles.current.isLocal) return flowOf(0)
    val koin = KoinPlatform.getKoin()
    val conversion = koin.getOrNull<BangumiProfileConversion>() ?: return flowOf(0)
    return combine(conversion.pendingCollections, koin.get<SessionStateProvider>().stateFlow) { pending, state ->
        if (state is SessionState.Valid) 0 else pending.size
    }
}
