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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.notice.NoticeEntry
import me.him188.ani.app.domain.notice.Notices
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds

/**
 * 列出来的一条: 种类认得出、那句话取得到的.
 */
@Immutable
class TvShownNotice(
    val entry: NoticeEntry,
    val text: String,
    val kind: TvNoticeKind,
) {
    val notice: Notice get() = entry.notice
    val key: String get() = entry.notice.key
    val destination: TvNoticeDestination? get() = kind.destination(entry.notice)
}

/**
 * 开着的通知弹窗.
 *
 * @param fresh 打开时还是新的那几条 (前面画点); 打开就算看过了, 这里留着打开那一刻的样子
 * @param focusKey 一打开焦点落在哪条; 那条没了 (处理掉了) 落在原来的位置 [focusIndex] 上
 */
@Immutable
class TvNoticeDialogState(
    val fresh: Set<String>,
    val focusKey: String?,
    val focusIndex: Int,
)

/**
 * 电视上的通知 (见 docs/contributing/code/notices.md): 种类表、列出来的通知、通知弹窗开没开、进应用时要不要自动弹.
 * 弹窗由 TV 根部的 [TvNoticeHost] 画; 头像上的「通知」调 [open].
 */
@OptIn(ExperimentalCoroutinesApi::class)
object TvNotices {
    /** 种类表: 加一种通知在这里登记一行. */
    private val kinds: List<TvNoticeKind> = listOf(
        TvFeedbackNotice,
        TvPendingCollectionsNotice,
        TvReleaseNotice,
    )

    private val logger = logger<TvNotices>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var installed = false

    private val notices: Notices? by lazy { runCatching { KoinPlatform.getKoin().get<Notices>() }.getOrNull() }

    private val _shown = MutableStateFlow<List<TvShownNotice>?>(null)

    /** 列出来的通知, 排好先后 (见 [Notices.all]); 还没算出来为 null. */
    val shown: StateFlow<List<TvShownNotice>?> = _shown.asStateFlow()

    /** 有新的 (没看过的) 通知: 头像与「通知」按钮上的红点. */
    val hasFresh: Flow<Boolean> = shown.map { list -> list.orEmpty().any { !it.entry.seen } }.distinctUntilChanged()

    private val _dialog = MutableStateFlow<TvNoticeDialogState?>(null)

    /** 开着的通知弹窗; null = 没开. */
    val dialog: StateFlow<TvNoticeDialogState?> = _dialog.asStateFlow()

    /** 等到首页、上面没有别的层时自动打开 (进应用时有新的通知). */
    internal val autoOpenPending = MutableStateFlow(false)

    /** 跳去处页面时记下的那条: 从那页回到首页时重新打开、焦点落回它. */
    internal val returnTo = MutableStateFlow<TvNoticeDialogState?>(null)

    fun kindOf(id: String): TvNoticeKind? = kinds.firstOrNull { it.id == id }

    /** 各种类的来源接到数据层, 开始算列出来的通知. 一个进程一次, TV 根部一组合就调. */
    fun install() {
        if (installed) return
        installed = true
        val notices = notices ?: return
        kinds.forEach { kind -> kind.source()?.let(notices::addSource) }
        scope.launch {
            notices.all.mapLatest { entries -> entries.mapNotNull { resolve(it) } }.collect { _shown.value = it }
        }
    }

    private suspend fun resolve(entry: NoticeEntry): TvShownNotice? {
        val kind = kindOf(entry.notice.kind) ?: return null
        val text = try {
            kind.text(entry.notice)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to resolve text of ${entry.notice.kind}" }
            null
        } ?: return null
        return TvShownNotice(entry, text, kind)
    }

    /**
     * 打开通知弹窗. 显示到屏幕上的才算看过 (由弹窗报, 见 [markSeenKeys]): 一页放不下的, 翻到才算.
     * [fresh] 给了时照它画「新的」那个点 (从去处页面回来时接着用第一次打开那一刻的), 否则照此刻没看过的.
     */
    fun open(focusKey: String? = null, focusIndex: Int = 0, fresh: Set<String>? = null) {
        val list = _shown.value.orEmpty()
        _dialog.value = TvNoticeDialogState(
            fresh = fresh ?: list.filter { !it.entry.seen }.map { it.key }.toSet(),
            focusKey = focusKey,
            focusIndex = focusIndex,
        )
    }

    /** 手机控制台上点了「在电视上打开」一条去处是弹窗的: 打开列表并把那一条的弹窗叠上去 (见 [TvNoticeHost]). */
    internal val overlayRequest = MutableStateFlow<String?>(null)

    internal fun openOnTv(key: String) {
        val list = _shown.value.orEmpty()
        open(focusKey = key, focusIndex = list.indexOfFirst { it.key == key }.coerceAtLeast(0))
        overlayRequest.value = key
    }

    /** 弹窗开着时把焦点落到 [focusKey] (叠在上面的去处弹窗关掉, 回到列表时). */
    internal fun refocus(focusKey: String, focusIndex: Int) {
        _dialog.value = _dialog.value?.let { TvNoticeDialogState(it.fresh, focusKey, focusIndex) }
    }

    fun close() {
        _dialog.value = null
    }

    /** 这几条显示到屏幕上了 (通知弹窗里一行露出一半以上, 见 TvNoticeDialog). */
    internal fun markSeenKeys(keys: Collection<String>) {
        if (keys.isEmpty()) return
        markSeen(_shown.value.orEmpty().filter { it.key in keys })
    }

    /** 这几条看过了 (手机控制台的列表里显示到屏幕上了, 或 [markSeenKeys]). */
    internal fun markSeen(list: List<TvShownNotice>) {
        val notices = notices ?: return
        val unseen = list.filter { !it.entry.seen }
        if (unseen.isEmpty()) return
        scope.launch { notices.markSeen(unseen.map { it.notice }) }
    }

    /**
     * 进应用 (冷启动) 时问一次: 有新的通知、设置「打开应用时显示新通知」开着, 就记下要自动打开 (等到首页、上面没别的层时由 [TvNoticeHost] 打开)
     * 并返回 true —— 这一次不再弹控制台二维码 (每次进应用最多盖一层).
     */
    suspend fun claimLaunch(): Boolean {
        val fresh = withTimeoutOrNull(LAUNCH_WAIT) { shown.filterNotNull().first() }.orEmpty().any { !it.entry.seen }
        if (!fresh || !openOnLaunchEnabled()) return false
        autoOpenPending.value = true
        return true
    }

    /** 从后台回到前台: 同 [claimLaunch], 不过这时没有二维码要让. */
    internal fun onReturnToForeground() {
        scope.launch {
            if (_shown.value.orEmpty().any { !it.entry.seen } && openOnLaunchEnabled()) autoOpenPending.value = true
        }
    }

    private suspend fun openOnLaunchEnabled(): Boolean =
        runCatching { KoinPlatform.getKoin().get<SettingsRepository>().themeSettings.flow.first().tvNoticesOnLaunch }.getOrDefault(true)

    /** 进应用时最多等列出来的通知这么久 (各来源都给出第一份); 等不到当没有. */
    private val LAUNCH_WAIT = 5.seconds
}

/** 头像与「通知」按钮上的红点. */
@Composable
fun rememberTvNoticesFresh(): Boolean = remember { TvNotices.hasFresh }.collectAsState(false).value
