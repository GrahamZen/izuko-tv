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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
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
import me.him188.ani.app.ui.onboarding.TvOnboardingLogin
import me.him188.ani.app.ui.whatsnew.TvWhatsNew
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds

/**
 * 本地用户改成 Bangumi 用户之后, 登录了就问留下的本地收藏怎么办 (见 [BangumiProfileConversion]): 合并到账号, 或者删掉.
 *
 * 不管在哪登录的 (电视上、设置里、Web 控制台用手机) 都在这里问, 挂在 TV 根组合上. 只在首页上弹 ([onMainPage]),
 * 不打断播放, 也不盖引导、选人页与「本版新功能」页. 按返回 = 以后再说: 这次运行不再问, 下次打开应用再问.
 * Web 控制台上也会问 (见 RemoteProfiles), 哪边先选算哪边: 那边删了这边的弹窗跟着关, 那边开始合并了这边显示进度.
 */
@Composable
fun TvPendingCollectionsHost(onMainPage: () -> Boolean) {
    if (UserProfiles.current.isLocal) return
    val koin = remember { KoinPlatform.getKoin() }
    val conversion = remember { koin.getOrNull<BangumiProfileConversion>() } ?: return
    val pendingCount by remember { conversion.pendingCollections.map { it.size } }.collectAsStateWithLifecycle(0)
    val loggedIn by remember { koin.get<SessionStateProvider>().stateFlow.map { it is SessionState.Valid } }
        .collectAsStateWithLifecycle(false)
    val session by ProfileImportSession.state.collectAsStateWithLifecycle()
    val currentOnMainPage by rememberUpdatedState(onMainPage)
    var open by remember { mutableStateOf(false) }
    // 删除在弹窗关掉之后才跑完: 用一直在场的这一层的作用域, 不用弹窗里的 (随弹窗离开组合就取消)
    val scope = rememberCoroutineScope()

    LaunchedEffect(pendingCount > 0 && loggedIn) {
        if (pendingCount == 0 || !loggedIn || PendingCollectionsPrompt.answered) return@LaunchedEffect
        // 等到能弹: 首页, 上面没有别的层; 只在这一刻判断, 弹出来之后不管
        while (!(currentOnMainPage() && nothingCovering())) {
            // 有导入在跑或刚跑完 (Web 控制台上已经在处理): 这次运行不再问
            if (ProfileImportSession.state.value != ProfileImportSession.State.Idle) {
                PendingCollectionsPrompt.answered = true
                return@LaunchedEffect
            }
            delay(POLL_INTERVAL)
        }
        open = true
    }

    if (open && (pendingCount > 0 || session != ProfileImportSession.State.Idle)) {
        val profile = UserProfiles.current
        val name = profile.name.ifBlank { stringResource(Lang.tv_profile_default_name, profile.id) }
        CollectionsImportDialog(
            title = stringResource(Lang.profile_merge_title),
            sessionTitle = { stringResource(Lang.profile_merge_title) },
            confirmLabel = { stringResource(Lang.profile_merge_confirm, it) },
            cancelLabel = stringResource(Lang.profile_merge_later),
            loadPreview = { conversion.previewMerge() ?: LocalProfileImporter.Preview(profile, emptyList(), emptyList()) },
            onStart = { preview -> ProfileImportSession.start(preview, name) { onProgress -> conversion.merge(preview, onProgress) } },
            onDismissRequest = {
                open = false
                PendingCollectionsPrompt.answered = true
            },
            intro = stringResource(Lang.profile_merge_intro),
            onDiscard = { scope.launch { conversion.discard() } },
            discardCount = pendingCount,
            confirmGuard = CONFIRM_GUARD,
        )
    }
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

private fun nothingCovering(): Boolean =
    TvOnboardingLogin.request.value == null && !TvUserProfilePicker.visible.value && TvWhatsNew.launchContent.value == null

private object PendingCollectionsPrompt {
    /** 这次运行问过了 (选了、以后再说, 或者 Web 控制台上在处理). */
    @Volatile
    var answered = false
}

private val POLL_INTERVAL = 500.milliseconds

/**
 * 弹窗出现后多久不认确认键: 它常在人连着按键时冒出来 (登录完按「下一步」回首页, 紧接着按的确认就落进弹窗),
 * 连按的下一下常在弹窗出现一秒多之后才到, 半秒挡不住; 弹窗里有说明要读, 1.5 秒内本来也不会去按.
 */
private val CONFIRM_GUARD = 1500.milliseconds
