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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.notice.NoticeEntry
import me.him188.ani.app.domain.notice.Notices
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_notice_action_open
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import kotlin.concurrent.Volatile

/**
 * 列出来的一条: 种类认得出、标题取得到的. 列表里写标题与 [summary]; 右栏写 [detail] (没有时写标题与摘要), 处理它的操作是 [actions].
 */
@Immutable
class TvShownNotice(
    val entry: NoticeEntry,
    val title: String,
    val kind: TvNoticeKind,
    val detail: TvNoticeDetail? = null,
    val actions: List<TvNoticeAction> = emptyList(),
    val summary: String? = null,
) {
    val notice: Notice get() = entry.notice
    val key: String get() = entry.notice.key

    /** 在列表里按确定键直接做的那一个 (只有一个操作时); 几个时进右栏挑, 没有时只是告知 */
    val destination: TvNoticeDestination? get() = actions.singleOrNull()?.destination
}

/**
 * 开着的通知弹窗.
 *
 * @param fresh 打开时还是新的那几条 (前面画点); 打开就算看过了, 这里留着打开那一刻的样子
 * @param focusKey 一打开焦点落在哪条; 那条没了 (处理掉了) 落在原来的位置 [focusIndex] 上
 * @param focusAction 一打开焦点落在 [focusKey] 那一条的第几个操作上 (有几个操作时): 从别处处理一条 ([TvNotices.handle]) 是第一个,
 *   叠在列表上的弹窗关掉时是开它的那一个; 没给落在那一行上
 */
@Immutable
class TvNoticeDialogState(
    val fresh: Set<String>,
    val focusKey: String?,
    val focusIndex: Int,
    val focusAction: Int? = null,
)

/**
 * 电视上的通知 (见 docs/contributing/code/notices.md): 种类表、列出来的通知、通知弹窗开没开、通知气泡弹过哪一批.
 * 弹窗与气泡由 TV 根部的 [TvNoticeHost] 管; 设置账号页的「通知」那一行与气泡上的「查看」调 [open].
 */
@OptIn(ExperimentalCoroutinesApi::class)
object TvNotices {
    /** 种类表: 加一种通知在这里登记一行. */
    private val kinds: List<TvNoticeKind> = listOf(
        TvFeedbackNotice,
        TvPendingCollectionsNotice,
        TvReleaseNotice,
        TvUpdateNotice,
    )

    private val logger = logger<TvNotices>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var installed = false

    private val notices: Notices? by lazy { runCatching { KoinPlatform.getKoin().get<Notices>() }.getOrNull() }

    private val _shown = MutableStateFlow<List<TvShownNotice>?>(null)

    /** 列出来的通知, 排好先后 (见 [Notices.all]); 还没算出来为 null. */
    val shown: StateFlow<List<TvShownNotice>?> = _shown.asStateFlow()

    /** 有新的 (没看过的) 通知: 主页头像上的红点. */
    val hasFresh: Flow<Boolean> = shown.map { list -> list.orEmpty().any { !it.entry.seen } }.distinctUntilChanged()

    private val _dialog = MutableStateFlow<TvNoticeDialogState?>(null)

    /** 开着的通知弹窗; null = 没开. */
    val dialog: StateFlow<TvNoticeDialogState?> = _dialog.asStateFlow()

    /** 通知气泡开始弹了 (进应用时的控制台二维码弹窗关掉之后, 见 [startBubbles]); 之前不弹. */
    internal val bubbleArmed = MutableStateFlow(false)

    /** 这一批弹过气泡了 (关掉了、按了「查看」) 的没看过的通知 ([tvNoticeBubbleKey]): 来了新的一条才再弹 */
    internal val bubbled = MutableStateFlow<Set<String>>(emptySet())

    /** 设置「有新通知时弹出提示」 */
    internal val bubbleEnabled: Flow<Boolean> = flow {
        val settings = runCatching { KoinPlatform.getKoin().get<SettingsRepository>() }.getOrNull()
        if (settings == null) emit(true) else emitAll(settings.themeSettings.flow.map { it.tvNoticesOnLaunch })
    }.distinctUntilChanged()

    /** 跳去处页面时记下的那条: 从那页回到打开通知时的那一页 (首页或设置的账号页) 时重新打开、焦点落回它. */
    internal val returnTo = MutableStateFlow<TvNoticeDialogState?>(null)

    fun kindOf(id: String): TvNoticeKind? = kinds.firstOrNull { it.id == id }

    /** 各种类的来源接到数据层, 开始算列出来的通知. 一个进程一次, TV 根部一组合就调. */
    fun install() {
        if (installed) return
        installed = true
        val notices = notices ?: return
        kinds.forEach { kind -> kind.source()?.let(notices::addSource) }
        // 通知变了, 或哪一类的字看的状态变了 (见 TvNoticeKind.textChanges), 都重算
        val textChanges = merge(flowOf(Unit), *kinds.mapNotNull { it.textChanges() }.toTypedArray())
        scope.launch {
            combine(notices.all, textChanges) { entries, _ -> entries }
                .mapLatest { entries -> entries.mapNotNull { resolve(it) } }
                .collect { _shown.value = it }
        }
    }

    private suspend fun resolve(entry: NoticeEntry): TvShownNotice? {
        val kind = kindOf(entry.notice.kind) ?: return null
        val title = resolving(entry, "title") { kind.title(entry.notice) } ?: return null
        val summary = resolving(entry, "summary") { kind.summary(entry.notice) }
        // 说明取不到的照没有说明列: 右栏写标题与摘要, 去处是唯一的操作
        val detail = resolving(entry, "detail") { kind.detail(entry.notice) }
        val actions = detail?.actions
            ?: kind.destination(entry.notice)?.let { listOf(TvNoticeAction(getStringOffMain(Lang.tv_notice_action_open), it)) }.orEmpty()
        return TvShownNotice(entry, title, kind, detail, actions, summary)
    }

    private inline fun <T> resolving(entry: NoticeEntry, what: String, block: () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        // 通知又变了, 这一轮被下一轮顶掉 (mapLatest)
        throw e
    } catch (e: Exception) {
        logger.warn(e) { "Failed to resolve $what of ${entry.notice.kind}" }
        null
    }

    /**
     * 打开通知弹窗. 显示到屏幕上的才算看过 (由弹窗报, 见 [markSeenKeys]): 一页放不下的, 翻到才算.
     * [fresh] 给了时照它画「新的」那个点 (从去处页面回来时接着用第一次打开那一刻的), 否则照此刻没看过的.
     */
    fun open(focusKey: String? = null, focusIndex: Int = 0, fresh: Set<String>? = null, focusAction: Int? = null) {
        val list = _shown.value.orEmpty()
        _dialog.value = TvNoticeDialogState(
            fresh = fresh ?: list.filter { !it.entry.seen }.map { it.key }.toSet(),
            focusKey = focusKey,
            focusIndex = focusIndex,
            focusAction = focusAction,
        )
    }

    /** 等 [TvNoticeHost] 处理的那一条 (见 [handle]). */
    internal val handleRequest = MutableStateFlow<String?>(null)

    /**
     * 从别处处理一条 (订阅详情最上面那一行、手机控制台的「在电视上打开」): 只有一个操作的直接做, 不打开通知列表 (弹窗叠在当时的页面上,
     * 关掉回到原处); 几个操作的打开通知列表、焦点在它的第一个操作上. 由 TV 根部的 [TvNoticeHost] 做 (要导航与提示).
     */
    fun handle(key: String) {
        handleRequest.value = key
    }

    /** 弹窗开着时把焦点落到 [focusKey] 那一条 (给了 [focusAction] 落在它的那个操作上; 叠在上面的去处弹窗关掉, 回到列表时). */
    internal fun refocus(focusKey: String, focusIndex: Int, focusAction: Int? = null) {
        _dialog.value = _dialog.value?.let { TvNoticeDialogState(it.fresh, focusKey, focusIndex, focusAction) }
    }

    fun close() {
        _dialog.value = null
    }

    /** 收起通知气泡, 这一批算弹过了: 在通知里做了一件要看右下角的事 (开始下载新版本), 关掉通知弹窗后气泡不再回来挡着. */
    internal fun dismissBubble() {
        bubbled.value += _shown.value.orEmpty().map(::tvNoticeBubbleKey)
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
     * 进应用时的弹窗都走完了 (冷启动时本版新功能页、控制台二维码弹窗都关掉之后): 从这时起有没看过的通知就在首页弹通知气泡 (由 [TvNoticeHost] 弹).
     */
    fun startBubbles() {
        bubbleArmed.value = true
    }

    /** 从后台回到前台 (同进应用): 还有没看过的, 这一批再弹一次气泡. */
    internal fun onReturnToForeground() {
        bubbled.value = emptySet()
    }
}

/** 一条没看过的通知在「弹过气泡」里记的样子: 键与时间 (同一条时间变晚了算新的一条). */
internal fun tvNoticeBubbleKey(notice: TvShownNotice): String = "${notice.key}@${notice.notice.atMillis}"

/**
 * 通知气泡要说的那几条 (docs/contributing/code/notices.md 5.1): 没看过的 (气泡上写有几条与它们的标题);
 * 都已经在 [bubbled] 里 (这一批弹过了) 或一条没看过的都没有时为空, 不弹.
 */
internal fun tvNoticesToBubble(shown: List<TvShownNotice>, bubbled: Set<String>): List<TvShownNotice> {
    val fresh = shown.filter { !it.entry.seen }
    return if (fresh.any { tvNoticeBubbleKey(it) !in bubbled }) fresh else emptyList()
}

/** 主页头像上的红点 (有没看过的通知时). */
@Composable
fun rememberTvNoticesFresh(): Boolean = remember { TvNotices.hasFresh }.collectAsState(false).value
