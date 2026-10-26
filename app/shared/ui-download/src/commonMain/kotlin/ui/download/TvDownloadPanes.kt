/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.download

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.ui.download.components.SubjectDownloadGroup
import me.him188.ani.app.ui.download.subject.SubjectDownloadListItem
import me.him188.ani.app.ui.download.subject.SubjectDownloadsUiState
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.TV_CONFIRM_KEYS
import me.him188.ani.app.ui.foundation.isAutoRepeat
import me.him188.ani.app.ui.foundation.tv.ProvideRingOnlyFocus

/**
 * 左栏焦点在一部上停这么久, 右栏才换成它. 按住上下时系统约 50ms 连发一下, 扫过的那些部都到不了这个数.
 */
internal val TV_DOWNLOAD_FOLLOW_DELAY = 250.milliseconds

/**
 * 进页时左栏的卡还没组合出来, 最多等这么久. 等不到 (卡在列表可视范围外) 就放行默认进组, 免得焦点哪都落不下.
 */
internal val TV_DOWNLOAD_ENTRY_WAIT = 1.seconds

/**
 * 缓存总页在电视上的两栏焦点 (fork): 遥控器从侧边栏到播放一集是「右 → 右 → 确定」.
 *
 * - **进页**落在左栏正在看的那一部 (被筛选掉了就是列表第一部), 不经过上面的总体统计与筛选栏, 筛选往上按.
 *   侧边栏按右 / 返回、从播放页回来、焦点丢了被全局兜底拉回来, 都是从页面外进来, 一律走这里.
 *   刚切到本页的头几帧左栏的卡还没组合出来: 有条目 (或还在加载) 时先拦下这次进组, 卡出来再送焦, 不先落到按钮上;
 *   没有条目才默认进组.
 * - **右栏跟着左栏焦点**: 焦点在一部上停 [TV_DOWNLOAD_FOLLOW_DELAY] 右栏才换. 右栏每换一部要新建 presenter、查一次库,
 *   条目信息过期还会请求一次 Bangumi, 按住上下扫过的那些部不该一部一部建; 等待期间右栏保持原样.
 * - **左栏按右或确定**落到右栏里这一部上次停的那一集, 没有就是第一条有缓存的, 跳过顶上的「全部暂停 / 继续」(往上按能到).
 *   右栏还没换成这一部 (没停稳就按) 或还在加载时先换过去, 等分集出来再送焦; 期间焦点离开这张卡就作罢.
 * - **右栏按左**回到左栏正在看的那一部. 交给二维搜索会落到同一高度的别的卡上, 停稳后右栏就被换成那一部.
 * - **右栏的行与左栏卡片同一套示焦与按下效果** ([tvDownloadDetailItems]): 持焦整行铺选中卡片的底色并叠焦点态层, 按确定整行出涟漪;
 *   每行只有行尾一颗按钮, 它不再画自己的焦点圈与涟漪. 单独的条目缓存页用的是同一个列表, 行的外观一样.
 *
 * 右栏的列表状态与各行由 [SubjectDownloadsContent][me.him188.ani.app.ui.download.subject.SubjectDownloadsContent]
 * 里的钩子报上来 ([rememberTvDownloadDetailGridState], [tvDownloadDetailItems]); 单独的条目缓存页没有本对象, 钩子只管行的外观; 非电视什么都不做.
 */
@Stable
internal class TvDownloadPanes(
    /** 每一部右栏上次停的那一行 (列表项 key), 从播放页回来后按右仍回到刚播的那一集. */
    private val lastRowBySubject: SnapshotStateMap<Int, String>,
) {
    internal val listEntry = FocusRequester()
    internal val detailEntry = FocusRequester()

    /** 左栏的落点: 右栏正在看的那一部, 被筛选掉了就是列表第一部. */
    var entryGroupKey: String? by mutableStateOf(null)
        internal set

    /** 右栏正在看的那一部. */
    var currentGroup: SubjectDownloadGroup? by mutableStateOf(null)
        internal set

    /** 缓存列表还没读出来. */
    var loading: Boolean by mutableStateOf(false)
        internal set

    /** 已经组合出来的左栏卡片. */
    private val attachedCards = mutableStateListOf<String>()

    /** 进页时卡还没出来, 拦下了那次进组, 在等卡 (见 [rememberTvDownloadPanes]). */
    var awaitingListEntry: Boolean by mutableStateOf(false)
        private set

    /** 上一次等卡没等到: 下次进页直接默认进组, 直到有卡拿到焦点. */
    private var listEntryWaitExpired = false

    internal var onSelect: (String) -> Unit = {}

    /** 左栏持焦的那张卡. */
    var focusedGroupKey: String? by mutableStateOf(null)
        private set

    /** 按了右或确定、要等右栏分集出来再送焦的那一部. */
    var pendingEnter: String? by mutableStateOf(null)
        private set

    private var detail: Detail? = null
    private val rowRequesters = mutableMapOf<String, FocusRequester>()

    /** 右栏报上来的现状. [groupKey] 是报的时候右栏正在看的那一部. */
    private class Detail(
        val groupKey: String?,
        val items: List<SubjectDownloadListItem>,
        val ready: Boolean,
        /** 列表里分集行之前还有几项 (顶上的汇总, 以及加载失败的提示). */
        val leadingItems: Int,
        val gridState: LazyGridState,
    )

    internal fun onCardFocusChanged(group: SubjectDownloadGroup, focused: Boolean) {
        if (focused) {
            focusedGroupKey = group.key
            listEntryWaitExpired = false
        } else if (focusedGroupKey == group.key) {
            focusedGroupKey = null
        }
        if (pendingEnter != null && pendingEnter != focusedGroupKey) pendingEnter = null
    }

    /**
     * 左栏一张卡上按了右或确定: 送焦到右栏这一部的落点. 返回 false = 不接管, 交给默认的焦点搜索.
     */
    internal fun enterDetail(group: SubjectDownloadGroup): Boolean {
        if (group.key != currentGroup?.key) {
            onSelect(group.key)
            pendingEnter = group.key
            return true
        }
        val detail = detail ?: return false
        if (detail.groupKey != group.key || !detail.ready) {
            pendingEnter = group.key
            return true
        }
        val target = targetRow(detail.items) ?: return false
        if (rowRequesters[target]?.tryFocus() == true) return true
        // 那一行在可视范围外还没组合出来: 由右栏的效应先滚过去再送
        pendingEnter = group.key
        return true
    }

    private fun targetRow(items: List<SubjectDownloadListItem>): String? {
        val last = currentGroup?.subjectId?.let { lastRowBySubject[it] }
        return last?.takeIf { key -> items.any { it.key == key } }
            ?: items.firstOrNull { it is SubjectDownloadListItem.Download }?.key
            ?: items.firstOrNull()?.key
    }

    internal fun reportDetail(
        state: SubjectDownloadsUiState,
        gridState: LazyGridState,
    ) {
        val failed = state.episodesFailed || state.downloadsFailed
        val ready = !state.episodesLoading && !state.downloadsLoading
        detail = Detail(
            groupKey = currentGroup?.key,
            items = state.items,
            ready = ready,
            leadingItems = 1 + (if (failed) 1 else 0) + (if (!ready || (state.items.isEmpty() && !failed)) 1 else 0),
            gridState = gridState,
        )
    }

    /**
     * 右栏换好了、分集也出来了, 把等着的那一次送焦做完.
     */
    internal suspend fun completePendingEnter() {
        val groupKey = pendingEnter ?: return
        val detail = detail ?: return
        if (detail.groupKey != groupKey || !detail.ready) return
        val target = targetRow(detail.items)
        if (target != null && rowRequesters[target] == null) {
            val index = detail.leadingItems + detail.items.indexOfFirst { it.key == target }
            // 上面留一行, 落点不贴着右栏顶边
            detail.gridState.scrollToItem((index - 1).coerceAtLeast(0))
            withFrameNanos { }
        }
        if (pendingEnter != groupKey || focusedGroupKey != groupKey) return
        pendingEnter = null
        if (target == null || rowRequesters[target]?.tryFocus() != true) {
            detailEntry.tryFocus()
        }
    }

    /**
     * 进页: 送焦到左栏的落点. 返回 false = 卡还没组合出来.
     */
    internal fun enterList(): Boolean = entryGroupKey in attachedCards && listEntry.tryFocus()

    /**
     * 进页时卡还没组合出来: 有条目或还在加载时返回 true, 由调用方拦下这次进组, 卡出来后由 [awaitListEntry] 送焦.
     */
    internal fun deferListEntry(): Boolean {
        if (listEntryWaitExpired || (entryGroupKey == null && !loading)) return false
        awaitingListEntry = true
        return true
    }

    internal suspend fun awaitListEntry() {
        val entered = withTimeoutOrNull(TV_DOWNLOAD_ENTRY_WAIT) {
            snapshotFlow { entryGroupKey?.let { it in attachedCards } == true }.first { it }
            listEntry.tryFocus()
        } == true
        if (!entered) listEntryWaitExpired = true
        awaitingListEntry = false
    }

    internal fun attachCard(key: String) {
        attachedCards += key
    }

    internal fun detachCard(key: String) {
        attachedCards -= key
    }

    internal fun registerRow(key: String, requester: FocusRequester) {
        rowRequesters[key] = requester
    }

    internal fun unregisterRow(key: String, requester: FocusRequester) {
        if (rowRequesters[key] === requester) rowRequesters.remove(key)
    }

    internal fun onRowFocused(key: String) {
        currentGroup?.subjectId?.let { lastRowBySubject[it] = key }
        pendingEnter = null
    }
}

internal val LocalTvDownloadPanes = staticCompositionLocalOf<TvDownloadPanes?> { null }

/**
 * 只在焦点导航 (电视) 上建, 其余平台返回 null, 各钩子随之不起作用.
 *
 * @param currentGroupKey 右栏正在看的那一部
 * @param onSelect 右栏换成这一部
 */
@Composable
internal fun rememberTvDownloadPanes(
    groups: List<SubjectDownloadGroup>,
    loading: Boolean,
    currentGroupKey: String?,
    currentGroup: SubjectDownloadGroup?,
    onSelect: (String) -> Unit,
): TvDownloadPanes? {
    if (!LocalAniUiBehavior.current.focusDrivenNavigation) return null
    val lastRows = rememberSaveable(saver = LastRowsSaver) { mutableStateMapOf() }
    val panes = remember { TvDownloadPanes(lastRows) }
    panes.entryGroupKey = groups.firstOrNull { it.key == currentGroupKey }?.key ?: groups.firstOrNull()?.key
    panes.loading = loading
    panes.currentGroup = currentGroup
    panes.onSelect = onSelect
    LaunchedEffect(panes) {
        snapshotFlow { panes.awaitingListEntry }.collectLatest { awaiting ->
            if (awaiting) panes.awaitListEntry()
        }
    }
    LaunchedEffect(panes) {
        snapshotFlow { panes.focusedGroupKey }.collectLatest { key ->
            if (key == null || key == panes.currentGroup?.key) return@collectLatest
            delay(TV_DOWNLOAD_FOLLOW_DELAY)
            panes.onSelect(key)
        }
    }
    return panes
}

private val LastRowsSaver = listSaver<SnapshotStateMap<Int, String>, Any>(
    save = { map -> map.flatMap { (subjectId, key) -> listOf(subjectId, key) } },
    restore = { list ->
        mutableStateMapOf<Int, String>().apply {
            list.chunked(2).forEach { (subjectId, key) -> put(subjectId as Int, key as String) }
        }
    },
)

/**
 * 页面根: 从页面外进来的焦点落到左栏正在看的那一部.
 */
internal fun Modifier.tvDownloadPage(panes: TvDownloadPanes?): Modifier =
    if (panes == null) {
        this
    } else {
        focusProperties {
            onEnter = {
                // 卡还没出来时拦下, 不让默认进组先落到右上角或筛选栏的按钮上; 没有条目时放行默认进组
                if (!panes.enterList() && panes.deferListEntry()) cancelFocusChange()
            }
        }.focusGroup()
    }

/**
 * 左栏的分组卡片: 焦点停稳右栏跟着换, 按右进右栏.
 */
internal fun Modifier.tvDownloadGroupCard(panes: TvDownloadPanes?, group: SubjectDownloadGroup): Modifier =
    if (panes == null) {
        this
    } else {
        composed {
            DisposableEffect(panes, group.key) {
                panes.attachCard(group.key)
                onDispose { panes.detachCard(group.key) }
            }
            Modifier
                .then(if (group.key == panes.entryGroupKey) Modifier.focusRequester(panes.listEntry) else Modifier)
                .onFocusChanged { panes.onCardFocusChanged(group, it.isFocused) }
                .onPreviewKeyEvent { event ->
                    event.key == Key.DirectionRight && event.type == KeyEventType.KeyDown && panes.enterDetail(group)
                }
        }
    }

/**
 * 右栏整栏: 按左回左栏正在看的那一部; 右栏没有可落的分集行时由它做默认进组.
 *
 * 左键在整栏上先接住, 不交给二维搜索: 右栏每行只有行尾一颗按钮, 左边没有别的落点, 但下载行与未缓存行的按钮横向差几个像素,
 * 二维搜索会把斜上方那行的按钮当成「左边」, 焦点就出不了右栏.
 */
internal fun Modifier.tvDownloadDetailPane(panes: TvDownloadPanes?): Modifier =
    if (panes == null) {
        this
    } else {
        onPreviewKeyEvent { event ->
            event.key == Key.DirectionLeft && event.type == KeyEventType.KeyDown && panes.listEntry.tryFocus()
        }
            .focusRequester(panes.detailEntry)
            .focusGroup()
    }

@Composable
internal fun ProvideTvDownloadPanes(panes: TvDownloadPanes?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTvDownloadPanes provides panes, content = content)
}

/**
 * 右栏的列表状态; 在缓存总页的电视版里同时把右栏的现状报给 [TvDownloadPanes], 并完成「按右时右栏还没好」那一次送焦.
 */
@Composable
internal fun rememberTvDownloadDetailGridState(state: SubjectDownloadsUiState): LazyGridState {
    val gridState = rememberLazyGridState()
    val panes = LocalTvDownloadPanes.current ?: return gridState
    // 组合时就报: 按键处理读到的总是最近一次组合的样子
    panes.reportDetail(state, gridState)
    val ready = !state.episodesLoading && !state.downloadsLoading
    LaunchedEffect(panes, panes.pendingEnter, panes.currentGroup?.key, ready, state.items) {
        panes.completePendingEnter()
    }
    return gridState
}

/**
 * 分集列表的行 (缓存总页的右栏与单独的条目缓存页共用). 电视上每一行包一层 [TvDownloadDetailRow]; 其余平台原样.
 */
internal fun <T> LazyGridScope.tvDownloadDetailItems(
    items: List<T>,
    key: (T) -> String,
    contentType: (T) -> Any?,
    itemContent: @Composable LazyGridItemScope.(T) -> Unit,
) = items(items, key = key, contentType = contentType) { item ->
    if (LocalAniUiBehavior.current.focusDrivenNavigation) {
        TvDownloadDetailRow(LocalTvDownloadPanes.current, key(item)) { itemContent(item) }
    } else {
        itemContent(item)
    }
}

/**
 * 分集列表一行 (分集行或下载行) 的外层. 缓存总页里 ([panes] 不为 null) 让左栏按右能直接送焦到这一行, 记下每一部停在哪一行;
 * 两种页面的示焦与按下效果都同缓存总页左栏的卡片 ([SubjectDownloadGroupCard][me.him188.ani.app.ui.download.components.SubjectDownloadGroupCard]):
 * 持焦时整行铺选中卡片的底色, 再由同一个涟漪 indication 叠焦点态层; 按确定从整行中心出涟漪.
 *
 * 行里那颗按钮不画自己的焦点圈与涟漪 ([ProvideRingOnlyFocus]), 它的按下由这一层从确认键的预览事件里接过来:
 * 新按下发 Press, 抬起发 Release; 按住到连发 (要弹菜单了) 就 Cancel, 抬起落在菜单窗口上也不会让这一行一直按着.
 */
@Composable
private fun TvDownloadDetailRow(panes: TvDownloadPanes?, key: String, content: @Composable () -> Unit) {
    val requester = remember { FocusRequester() }
    if (panes != null) {
        DisposableEffect(panes, key, requester) {
            panes.registerRow(key, requester)
            onDispose { panes.unregisterRow(key, requester) }
        }
    }
    val interactions = remember { MutableInteractionSource() }
    var focus by remember { mutableStateOf<FocusInteraction.Focus?>(null) }
    var press by remember { mutableStateOf<PressInteraction.Press?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun endPress(cancel: Boolean) {
        val current = press ?: return
        interactions.tryEmit(if (cancel) PressInteraction.Cancel(current) else PressInteraction.Release(current))
        press = null
    }
    val selectedColor = MaterialTheme.colorScheme.secondaryContainer
    val background by animateColorAsState(if (focus != null) selectedColor else selectedColor.copy(alpha = 0f))
    Box(
        Modifier
            .focusRequester(requester)
            .onFocusChanged {
                if (it.hasFocus && focus == null) {
                    focus = FocusInteraction.Focus().also { f -> interactions.tryEmit(f) }
                    panes?.onRowFocused(key)
                } else if (!it.hasFocus) {
                    focus?.let { f -> interactions.tryEmit(FocusInteraction.Unfocus(f)) }
                    focus = null
                    endPress(cancel = true)
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.key in TV_CONFIRM_KEYS) {
                    when (event.type) {
                        KeyEventType.KeyDown -> when {
                            press == null && event.isAutoRepeat != true -> {
                                press = PressInteraction.Press(Offset(size.width / 2f, size.height / 2f))
                                    .also { interactions.tryEmit(it) }
                            }

                            press != null -> endPress(cancel = true)
                        }

                        KeyEventType.KeyUp -> endPress(cancel = false)
                    }
                }
                false
            }
            .onSizeChanged { size = it }
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .indication(interactions, LocalIndication.current),
    ) {
        ProvideRingOnlyFocus(content)
    }
}

private fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus() }.getOrDefault(false)
