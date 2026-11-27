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
 * 列出来的一条: 种类认得出、标题取得到的. 列表里写标题与 [summary], 左边画 [cover] (没有的画类别图标); 右栏写 [detail] (没有时写标题与摘要), 处理它的操作是 [actions].
 */
@Immutable
class TvShownNotice(
    val entry: NoticeEntry,
    val title: String,
    val kind: TvNoticeKind,
    val detail: TvNoticeDetail? = null,
    val actions: List<TvNoticeAction> = emptyList(),
    val summary: String? = null,
    val cover: TvNoticeCover? = null,
) {
    val notice: Notice get() = entry.notice
    val key: String get() = entry.notice.key

    /** 在列表里按确定键直接做的那一个 (只有一个操作时); 几个时进右栏挑, 没有时只是告知 */
    val destination: TvNoticeDestination? get() = actions.singleOrNull()?.destination

    /** 横幅上短按直接做的那一个: 第一个操作, 它给了 [TvNoticeAction.quick] 时; 没有时短按打开通知弹窗 */
    val quick: TvNoticeAction? get() = tvNoticeQuickAction(actions)
}

/** [actions] 里横幅上短按直接做的那一个 (见 [TvNoticeAction.quick]): 只看第一个, 它不能一按就做时没有 (不往后找). */
internal fun tvNoticeQuickAction(actions: List<TvNoticeAction>): TvNoticeAction? = actions.firstOrNull()?.takeIf { it.quick != null }

/**
 * 开着的通知弹窗.
 *
 * @param fresh 打开时还没读过的那几条 (排在「新」那一段; 前面的点画到读了为止, 见 [TvShownNotice.entry] 的 read); 打开就都算看过了,
 *   这里留着打开那一刻的样子, 读了一条它也不挪位置. 弹窗开着时新来的见 [TvNotices.arrivedWhileOpen]
 * @param focusKey 一打开焦点落在哪条; 那条没了 (处理掉了) 落在原来的位置 [focusIndex] 上
 * @param focusAction 一打开焦点落在 [focusKey] 那一条的第几个操作上 (那一条画了按钮时); 没给落在那一行上
 * @param tab 开在哪个签上 ([TvNoticeTab.NOTICES] 或 [TvNotices.tabs] 里一个的 id); [focusKey] 是这个签里的一行
 * @param focusOnTabs 焦点不挪进列表 (触屏上点签换的签)
 */
@Immutable
class TvNoticeDialogState(
    val fresh: Set<String>,
    val focusKey: String?,
    val focusIndex: Int,
    val focusAction: Int? = null,
    val tab: String = TvNoticeTab.NOTICES,
    val focusOnTabs: Boolean = false,
)

/**
 * 电视上的通知 (见 docs/contributing/code/notices.md): 种类表、列出来的通知、通知弹窗开没开、通知气泡弹过哪一批.
 * 弹窗与气泡由 TV 根部的 [TvNoticeHost] 管; 点侧边栏的「通知」与气泡上的「查看」调 [open].
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

    /** 通知弹窗顶上「通知」之后的签 (见 [TvNoticeTab]): 加一个签在这里登记一行. 一个都没有时不画签, 照旧写标题. */
    internal val tabs: List<TvNoticeTab> = listOf(TvFeedbackTab)

    private val logger = logger<TvNotices>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var installed = false

    private val notices: Notices? by lazy { runCatching { KoinPlatform.getKoin().get<Notices>() }.getOrNull() }

    private val _shown = MutableStateFlow<List<TvShownNotice>?>(null)

    /** 列出来的通知, 排好先后 (见 [Notices.all]); 还没算出来为 null. */
    val shown: StateFlow<List<TvShownNotice>?> = _shown.asStateFlow()

    /** 没看过的通知有几条 (新来的、打开通知弹窗后还没列出来过的): 侧边栏「通知」上红底的数字 (0 = 不画). 打开通知弹窗就清掉. */
    val freshCount: Flow<Int> = shown.map { list -> list.orEmpty().count { !it.entry.seen } }.distinctUntilChanged()

    private val _dialog = MutableStateFlow<TvNoticeDialogState?>(null)

    /** 开着的通知弹窗; null = 没开. */
    val dialog: StateFlow<TvNoticeDialogState?> = _dialog.asStateFlow()

    private val _arrivedWhileOpen = MutableStateFlow<Set<String>>(emptySet())

    /**
     * 通知弹窗开着时新来的几条 (键): 同打开时没读过的排在「新」那一段 (列出来了, 当场算看过, 铃铛上的数字不为它亮). 重新打开弹窗时清空.
     */
    val arrivedWhileOpen: StateFlow<Set<String>> = _arrivedWhileOpen.asStateFlow()

    /** 通知气泡开始弹了 (进应用时的控制台二维码弹窗关掉之后, 见 [startBubbles]); 之前不弹. */
    internal val bubbleArmed = MutableStateFlow(false)

    /** 这一批弹过气泡了 (关掉了、按了「查看」) 的没看过的通知 ([tvNoticeBubbleKey]): 来了新的一条才再弹 */
    internal val bubbled = MutableStateFlow<Set<String>>(emptySet())

    /** 设置「有新通知时弹出提示」 */
    internal val bubbleEnabled: Flow<Boolean> = flow {
        val settings = runCatching { KoinPlatform.getKoin().get<SettingsRepository>() }.getOrNull()
        if (settings == null) emit(true) else emitAll(settings.themeSettings.flow.map { it.tvNoticesOnLaunch })
    }.distinctUntilChanged()

    /** 跳去处页面时记下的那条: 从那页回到打开通知时的那一页 (一般是首页) 时重新打开、焦点落回它. */
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
                .collect { list ->
                    seeWhileOpen(list)
                    _shown.value = list
                }
        }
    }

    private suspend fun resolve(entry: NoticeEntry): TvShownNotice? {
        val kind = kindOf(entry.notice.kind) ?: return null
        val title = resolving(entry, "title") { kind.title(entry.notice) } ?: return null
        val summary = resolving(entry, "summary") { kind.summary(entry.notice) }
        // 说明取不到的照没有说明列: 右栏写标题与摘要, 去处是唯一的操作
        val detail = resolving(entry, "detail") { kind.detail(entry.notice) }
        // 没给说明的, 去处就是「查看」, 横幅上短按直接去; 在别的签里看的 (反馈回复) 去处就是打开那个签, 同打开通知弹窗, 不算
        val actions = detail?.actions
            ?: kind.destination(entry.notice)?.let { destination ->
                val open = getStringOffMain(Lang.tv_notice_action_open)
                listOf(TvNoticeAction(open, destination, quick = open.takeIf { kind.listed }))
            }.orEmpty()
        val cover = resolving(entry, "cover") { kind.cover(entry.notice) }
        return TvShownNotice(entry, title, kind, detail, actions, summary, cover)
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
     * 打开通知弹窗, 开在签 [tab] 上 (没登记的照「通知」签开), 焦点在 [focusKey] 那一行 (不在列表里的落在第 [focusIndex] 行).
     * 弹窗已经开着时就是换到那个签、那一行. 打开就把列出来的都算看过 (铃铛上的数字清掉); 读过由弹窗报 (右栏说一条说满一会儿, 见 [markReadKeys]).
     * [fresh] 给了时照它分「新」那一段 (从去处页面回来时接着用第一次打开那一刻的); 没给时弹窗开着照开着时的, 否则照此刻没读过的.
     */
    fun open(
        focusKey: String? = null,
        focusIndex: Int = 0,
        fresh: Set<String>? = null,
        focusAction: Int? = null,
        tab: String = TvNoticeTab.NOTICES,
    ) {
        val list = _shown.value.orEmpty()
        val opened = _dialog.value
        if (fresh == null && opened == null) _arrivedWhileOpen.value = emptySet()
        markSeen(list)
        _dialog.value = TvNoticeDialogState(
            fresh = fresh ?: opened?.fresh ?: list.filter { it.kind.listed && !it.entry.read }.map { it.key }.toSet(),
            focusKey = focusKey,
            focusIndex = focusIndex,
            focusAction = focusAction,
            tab = tab.takeIf { id -> tabs.any { it.id == id } } ?: TvNoticeTab.NOTICES,
        )
    }

    /**
     * 在通知弹窗里换到签 [tab], 两栏换成那个签的. [intoList] = 从列表里按左右键换的: 焦点进那个签的列表, 落在 [focusKey] 那一行
     * (没有就第一行); 否则 (在顶上的签上换的) 焦点留在签上.
     */
    internal fun selectTab(tab: String, intoList: Boolean = false, focusKey: String? = null) {
        _dialog.value = _dialog.value?.let {
            TvNoticeDialogState(it.fresh, focusKey = focusKey, focusIndex = 0, tab = tab, focusOnTabs = !intoList)
        }
    }

    fun close() {
        _dialog.value = null
    }

    /** 收起通知气泡, 这一批算弹过了: 在通知里做了一件要看右下角的事 (开始下载新版本), 关掉通知弹窗后气泡不再回来挡着. */
    internal fun dismissBubble() {
        bubbled.value += _shown.value.orEmpty().map(::tvNoticeBubbleKey)
    }

    /** 这几条看过了 (列出来了: 打开了通知弹窗, 换到了「我的反馈」签). */
    internal fun markSeen(list: List<TvShownNotice>) {
        val notices = notices ?: return
        val unseen = list.filter { !it.entry.seen }
        if (unseen.isEmpty()) return
        scope.launch { notices.markSeen(unseen.map { it.notice }) }
    }

    /**
     * 这几条读过了: 通知弹窗右栏说它说满一会儿 (见 TvNoticeView), 在它上面做了操作 (弹窗里、横幅上短按), 「我的反馈」右栏写过这张单.
     */
    internal fun markReadKeys(keys: Collection<String>) {
        if (keys.isEmpty()) return
        markRead(_shown.value.orEmpty().filter { it.key in keys })
    }

    /** 这几条读过了 (手机控制台的列表里显示到屏幕上了: 那里整条字都写着, 或 [markReadKeys]). */
    internal fun markRead(list: List<TvShownNotice>) {
        val notices = notices ?: return
        val unread = list.filter { !it.entry.read }
        if (unread.isEmpty()) return
        scope.launch { notices.markRead(unread.map { it.notice }) }
    }

    /** 弹窗开着时新来的 (没看过的): 记进 [arrivedWhileOpen] (列表里的「新」那一段), 当场算看过 */
    private fun seeWhileOpen(list: List<TvShownNotice>) {
        val dialog = _dialog.value ?: return
        val arrived = list.filter { !it.entry.seen }
        if (arrived.isEmpty()) return
        val added = arrived.filter { it.kind.listed && !it.entry.read && it.key !in dialog.fresh }.map { it.key }
        if (added.isNotEmpty()) _arrivedWhileOpen.value += added
        markSeen(arrived)
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

/** 侧边栏「通知」上的数字: 没看过的通知有几条 (见 buildTvRailItems). */
@Composable
fun rememberTvNoticesFreshCount(): Int = remember { TvNotices.freshCount }.collectAsState(0).value
