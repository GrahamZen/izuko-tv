/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.network.RemoteAppConfigRepository
import me.him188.ani.app.data.network.SealedNoticesRepository
import me.him188.ani.app.domain.feedback.FeedbackTickets
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_notice_refresh
import me.him188.ani.app.ui.lang.tv_notice_refresh_busy
import me.him188.ani.app.ui.lang.tv_notice_refresh_failed
import me.him188.ani.app.ui.lang.tv_notice_refresh_last
import me.him188.ani.app.ui.lang.tv_notice_refresh_new
import me.him188.ani.app.ui.lang.tv_notice_refresh_none
import me.him188.ani.app.ui.lang.tv_notice_refresh_part_config
import me.him188.ani.app.ui.lang.tv_notice_refresh_part_feedback
import me.him188.ani.app.ui.lang.tv_notice_refresh_part_release
import me.him188.ani.app.ui.lang.tv_notice_refresh_part_update
import me.him188.ani.app.ui.lang.tv_notice_refresh_wait
import me.him188.ani.app.ui.lang.tv_notice_refreshing
import me.him188.ani.app.ui.update.AppUpdateViewModel
import me.him188.ani.app.ui.update.LocalAppUpdateViewModel
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.currentTimeMillis
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 通知中心右上角的「刷新」(见 docs/contributing/code/notices.md): 一次把通知的几个来源都取一遍 —— 发版后的通知 (加密通知)、
 * 新版本、反馈的回复与处理结果、应用配置 (交流群等). 都只读, 重复取没有副作用. 几样同时取、各自限时 [PART_TIMEOUT],
 * 某一样连不上不拖累别的; 刷完冷却 [COOLDOWN] (反馈回复本来就一分钟内只查一次; 检查更新走 GitHub 接口, 没登录时每个 IP 每小时 60 次).
 * 几 MB 的大表、地址清单 (网络体检管)、订阅不在这里.
 *
 * 状态整个进程一份: 关掉通知中心再打开, 冷却照算. 按钮与焦点在通知弹窗 (见 [TvNoticeRefreshUi]).
 */
internal object TvNoticeRefresh {
    /** 刷完多久之内不再刷 */
    val COOLDOWN = 1.minutes

    /** 每一样最多等多久 */
    private val PART_TIMEOUT = 20.seconds

    /** 刷完等这么久再数新通知: 取到的东西经各自的来源流到通知列表要一点时间 */
    private val SETTLE = 800.milliseconds

    /** 刷的几样 */
    enum class Part(val label: StringResource) {
        Release(Lang.tv_notice_refresh_part_release),
        Update(Lang.tv_notice_refresh_part_update),
        Feedback(Lang.tv_notice_refresh_part_feedback),
        Config(Lang.tv_notice_refresh_part_config),
    }

    /**
     * 电视最外层那份更新 (由 [TvAppUpdateHost] 挂上时登记): 通知弹窗挂在它的 [LocalAppUpdateViewModel] 外面, 取不到时用这一份.
     */
    @Volatile
    var appUpdate: AppUpdateViewModel? = null

    private val logger = logger<TvNoticeRefresh>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(TvNoticeRefreshState())
    val state: StateFlow<TvNoticeRefreshState> = _state.asStateFlow()

    /**
     * 开始刷新; 正在刷、还在冷却时什么都不做, 返回 false. [checkUpdate]: 开始检查新版本并等它查完, 查成了为 true
     * (更新挂在电视最外层的那份 [AppUpdateViewModel] 上, 由界面给); null = 这里没有更新那一层, 不查.
     */
    fun start(checkUpdate: (suspend () -> Boolean)?): Boolean {
        val now = currentTimeMillis()
        val current = _state.value
        if (current.busy || now < current.coolUntil) return false
        _state.value = current.copy(busy = true, newCount = null, failed = emptyList())
        val before = freshKeys()
        scope.launch {
            val parts = buildList<Pair<Part, suspend () -> Boolean>> {
                koin<SealedNoticesRepository>()?.let { repo -> add(Part.Release to { repo.refreshNow() }) }
                checkUpdate?.let { add(Part.Update to it) }
                koin<FeedbackTickets>()?.takeIf { it.available }?.let { tickets -> add(Part.Feedback to { tickets.syncNow() }) }
                koin<RemoteAppConfigRepository>()?.let { repo -> add(Part.Config to { repo.refreshNow() }) }
            }
            val failed = parts.map { (part, run) -> async { part.takeUnless { runPart(part, run) } } }.awaitAll().filterNotNull()
            delay(SETTLE)
            val newCount = (freshKeys() - before).size
            val done = currentTimeMillis()
            logger.info { "Notice refresh done: ${parts.map { it.first }}, failed $failed, $newCount new" }
            _state.value = TvNoticeRefreshState(
                busy = false,
                lastAt = done,
                coolUntil = done + COOLDOWN.inWholeMilliseconds,
                newCount = newCount,
                failed = failed,
            )
        }
        return true
    }

    private suspend fun runPart(part: Part, run: suspend () -> Boolean): Boolean = try {
        withTimeoutOrNull(PART_TIMEOUT) { run() } == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.info { "Notice refresh: $part failed: $e" }
        false
    }

    /** 没看过的通知 (键与时间: 同一条有了新的一次也算新的, 同通知气泡) */
    private fun freshKeys(): Set<String> =
        TvNotices.shown.value.orEmpty().filter { !it.entry.seen }.map(::tvNoticeBubbleKey).toSet()

    private inline fun <reified T : Any> koin(): T? = runCatching { KoinPlatform.getKoin().get<T>() }.getOrNull()
}

/**
 * 一次刷新的状态.
 *
 * @param lastAt 上次刷完的时刻; 这个进程里还没刷过为 null
 * @param coolUntil 到这个时刻之前不能再刷
 * @param newCount 上次刷完多了几条没看过的通知
 * @param failed 上次没刷成的几样
 */
@Immutable
internal data class TvNoticeRefreshState(
    val busy: Boolean = false,
    val lastAt: Long? = null,
    val coolUntil: Long = 0,
    val newCount: Int? = null,
    val failed: List<TvNoticeRefresh.Part> = emptyList(),
)

/** 按「刷新」时调的: 冷却中只提示还要等几秒. */
@Composable
internal fun rememberTvNoticeRefresh(): () -> Unit {
    val viewModel = LocalAppUpdateViewModel.current ?: TvNoticeRefresh.appUpdate
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    return remember(viewModel, toaster, scope) {
        {
            val started = TvNoticeRefresh.start(viewModel?.let { vm -> suspend { vm.checkNow() } })
            val state = TvNoticeRefresh.state.value
            if (!started && !state.busy) {
                scope.launch { toaster.toast(getStringOffMain(Lang.tv_notice_refresh_wait, secondsLeft(state, currentTimeMillis()))) }
            }
        }
    }
}

/** 按钮上的字、左边那行小字、右栏最后一行的提示; 每秒更新 (冷却的秒数、「N 分钟前」). */
@Composable
internal fun rememberTvNoticeRefreshUi(): TvNoticeRefreshUi {
    val state by TvNoticeRefresh.state.collectAsState()
    var now by remember { mutableLongStateOf(currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = currentTimeMillis()
            delay(1.seconds)
        }
    }
    val cooling = !state.busy && now < state.coolUntil
    val lastAt = state.lastAt
    val failedLabels = state.failed.map { stringResource(it.label) }
    val status: String? = when {
        state.busy -> stringResource(Lang.tv_notice_refresh_busy)
        lastAt == null -> null
        cooling && failedLabels.isNotEmpty() -> stringResource(Lang.tv_notice_refresh_failed, failedLabels.joinToString("、"))
        cooling && (state.newCount ?: 0) > 0 -> stringResource(Lang.tv_notice_refresh_new, state.newCount ?: 0)
        cooling -> stringResource(Lang.tv_notice_refresh_none)
        else -> stringResource(Lang.tv_notice_refresh_last, noticeTimeText(lastAt, now))
    }
    return TvNoticeRefreshUi(
        label = stringResource(if (state.busy) Lang.tv_notice_refreshing else Lang.tv_notice_refresh),
        status = status,
        statusHighlight = cooling && failedLabels.isEmpty() && (state.newCount ?: 0) > 0,
        busy = state.busy,
        cooling = cooling,
    )
}

/** 还要等几秒 (向上取整; 界面上的「现在」每秒才更新一次, 可能比刷完的时刻还早, 所以封顶在冷却时长) */
private fun secondsLeft(state: TvNoticeRefreshState, now: Long): Int =
    ((state.coolUntil - now + 999) / 1000).toInt().coerceIn(1, TvNoticeRefresh.COOLDOWN.inWholeSeconds.toInt())

/** 检查一次新版本 (关了自动检查也查) 并等它查完; 查出错为 false. 查到新版本后照常由更新那一层提示, 这里不自动下载. */
private suspend fun AppUpdateViewModel.checkNow(): Boolean {
    startCheckLatestVersion(null, manual = true)
    // 开始检查是另起协程: 先等它真的开始, 再等它结束
    withTimeoutOrNull(1.seconds) { checking.first { it } }
    checking.first { !it }
    return presentationFlow.first { !it.isCheckingUpdate }.checkUpdateError == null
}
