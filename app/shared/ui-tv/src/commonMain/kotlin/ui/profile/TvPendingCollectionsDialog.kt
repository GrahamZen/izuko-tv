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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.profile.BangumiProfileConversion
import me.him188.ani.app.domain.profile.LocalProfileImporter
import me.him188.ani.app.domain.profile.UserProfiles
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.profile_merge_confirm
import me.him188.ani.app.ui.lang.profile_merge_intro
import me.him188.ani.app.ui.lang.profile_merge_later
import me.him188.ani.app.ui.lang.profile_merge_title
import me.him188.ani.app.ui.lang.profile_pending_hint
import me.him188.ani.app.ui.lang.tv_profile_default_name
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import kotlin.time.Duration.Companion.milliseconds

/**
 * 本地用户改成 Bangumi 用户、登录以后: 留下的本地收藏怎么办 (见 [BangumiProfileConversion]) —— 合并到账号, 或者删掉.
 * 从通知里打开 (见 TvPendingCollectionsNotice: 有待处理的本地收藏时通知里一条). 按返回 = 以后再说, 通知留着.
 * Web 控制台上也能处理 (见 RemoteProfiles), 哪边先选算哪边: 那边删了这边的弹窗跟着关, 那边开始合并了这边显示进度.
 */
@Composable
fun TvPendingCollectionsDialog(onDismiss: () -> Unit) {
    val koin = remember { KoinPlatform.getKoin() }
    val conversion = remember { koin.getOrNull<BangumiProfileConversion>() }
    if (conversion == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val pendingCount by remember { conversion.pendingCollections.map<List<*>, Int?> { it.size } }.collectAsStateWithLifecycle(null)
    val session by ProfileImportSession.state.collectAsStateWithLifecycle()
    // 删除在弹窗关掉之后才跑完: 用宿主的作用域 (弹窗离开组合时不取消)
    val scope = rememberCoroutineScope()
    val count = pendingCount ?: return
    if (count == 0 && session == ProfileImportSession.State.Idle) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val profile = UserProfiles.current
    val name = profile.name.ifBlank { stringResource(Lang.tv_profile_default_name, profile.id) }
    CollectionsImportDialog(
        title = stringResource(Lang.profile_merge_title),
        sessionTitle = { stringResource(Lang.profile_merge_title) },
        confirmLabel = { stringResource(Lang.profile_merge_confirm, it) },
        cancelLabel = stringResource(Lang.profile_merge_later),
        loadPreview = { conversion.previewMerge() ?: LocalProfileImporter.Preview(profile, emptyList(), emptyList()) },
        onStart = { preview -> ProfileImportSession.start(preview, name) { onProgress -> conversion.merge(preview, onProgress) } },
        onDismissRequest = onDismiss,
        intro = stringResource(Lang.profile_merge_intro),
        onDiscard = { scope.launch { conversion.discard() } },
        discardCount = count,
        confirmGuard = CONFIRM_GUARD,
    )
}

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

/**
 * 弹窗出现后多久不认确认键: 它是在通知列表里按确定打开的, 按住没松的那一下与连着按的下一下常在弹窗出现一秒多之后才到,
 * 半秒挡不住; 弹窗里有说明要读, 1.5 秒内本来也不会去按.
 */
private val CONFIRM_GUARD = 1500.milliseconds
