/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.cache.subject

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.him188.ani.app.domain.media.cache.EpisodeCacheStatus
import me.him188.ani.app.tools.getOrZero
import me.him188.ani.app.ui.foundation.theme.stronglyWeaken
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_subject_cache
import me.him188.ani.app.ui.lang.cache_subject_cancel
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.api.topic.isDoneOrDropped
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged


@Immutable
data class EpisodeCacheInfo(
    val sort: EpisodeSort,
    val ep: EpisodeSort?,
    val title: String,
    val watchStatus: UnifiedCollectionType,
    /**
     * 是否已经上映了
     */
    val hasPublished: Boolean,
    val _placeholder: Int = 0,
) {
    val sortString = sort.toString()

    companion object {
        @Stable
        val Placeholder = EpisodeCacheInfo(
            EpisodeSort(0),
            null,
            "",
            UnifiedCollectionType.DONE,
            false,
            -1,
        )
    }
}

@Composable
fun contentColorForWatchStatus(
    collectionType: UnifiedCollectionType,
    isKnownBroadcast: Boolean
) =
    if (collectionType.isDoneOrDropped() || !isKnownBroadcast) {
        LocalContentColor.current.stronglyWeaken()
    } else {
        LocalContentColor.current
    }

@Composable
fun EpisodeCacheActionIcon(
    isLoadingIndefinitely: Boolean,
    hasActionRunning: Boolean,
    cacheStatus: EpisodeCacheStatus?,
    canCache: Boolean,
    onClick: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 本集是否有"会抢走焦点的弹窗"正在前台显示(数据源选择 bottom sheet 或删除下拉菜单).
     * 弹窗开着时不夺回焦点(要留给弹窗内导航), 弹窗关闭后才把焦点还给本按钮.
     */
    popupOpen: Boolean = LocalCachePopupOpen.current,
    /**
     * 本按钮代表哪一集. 传了就参与 [LocalCacheRowRefocus] 的接力: 别的行把这一集的焦点交接过来时
     * (删掉缓存 -> 已缓存行变回未缓存行), 由本按钮接住.
     */
    refocusEpisodeId: Int? = null,
) = Box(modifier) {
    val progressIndicatorSize = 20.dp
    val strokeWidth = 2.dp
    val trackColor = MaterialTheme.colorScheme.primaryContainer

    // "加载中"(isLoadingIndefinitely) 与 "操作进行中"(hasActionRunning) 统一视为 running.
    // 按下下载后, 状态会先变成 isLoadingIndefinitely=true (准备缓存请求), 这个状态也必须
    // 走下面同一个可聚焦的 IconButton, 否则节点被替换会导致遥控器焦点丢失 (TV).
    val running = isLoadingIndefinitely || hasActionRunning

    // 既不在运行/加载, 又没有可显示/可点击的内容时, 才不显示按钮.
    if (!running && (cacheStatus == null || (cacheStatus is EpisodeCacheStatus.NotCached && !canCache))) {
        return@Box
    }

    // 所有会出现的状态(下载/加载中/缓存中/已缓存/操作进行中)共用同一个 IconButton.
    // 同一个 call site -> 节点稳定 -> 按下后焦点留在原处.
    var showCancel by remember { mutableStateOf(false) }
    LaunchedEffect(showCancel) {
        if (showCancel) {
            delay(2000)
            showCancel = false
        }
    }
    // 不再运行/加载时复位取消态, 避免残留.
    LaunchedEffect(running) {
        if (!running) showCancel = false
    }

    // TV: 点击后焦点会丢失(节点没被销毁但失焦, 或被弹窗夺走), 需要把焦点夺回本按钮. 要覆盖:
    //   1. 直接缓存(无弹窗): Download->进度圈->Caching 的视觉切换会清掉焦点;
    //   2. 数据源选择 bottom sheet / 删除下拉菜单: 弹窗夺走焦点, 关闭后不会自动还回.
    //
    // 完全事件驱动(不靠 wall-clock 轮询/定时窗口), 关键是区分两种失焦:
    //   (A) 焦点被"清空到无人持有"(切换/弹窗关闭所致) —— 要夺回;
    //   (B) 焦点被"移到了别的缓存按钮"(用户方向键导航) —— 绝不抢回.
    // 用 LocalCacheFocusOwner 这个跨按钮共享的"当前焦点归属"来区分: 归属为 null=被清空(夺回),
    // 归属为别的 key=用户导航走了(放弃). 夺回只由真实事件触发: 本按钮视觉切换、弹窗关闭; 重试也只
    // 等渲染帧(withFrameNanos)而非计时. 因此永不与用户导航/别的按钮抢焦点.
    val focusRequester = remember { FocusRequester() }
    val focusOwner = LocalCacheFocusOwner.current
    val myFocusKey = remember { Any() }
    var isFocused by remember { mutableStateOf(false) }
    var wantFocus by remember { mutableStateOf(false) }
    val currentPopupOpen by rememberUpdatedState(popupOpen)

    fun focusIsElsewhere(): Boolean {
        val owner = focusOwner?.value
        return owner != null && owner != myFocusKey
    }

    // 本按钮视觉状态标识; 变化即一次会清焦点的切换(progress 数值变化不计入, 只取 Caching 类型).
    val visualKey = "$running|" + when (cacheStatus) {
        is EpisodeCacheStatus.Cached -> "cached"
        is EpisodeCacheStatus.Caching -> "caching"
        is EpisodeCacheStatus.NotCached -> "notCached"
        null -> "null"
    } + "|$showCancel"

    // 兜底上限: 点击意图最多保持一段时间(只为防止"下载很久后完成的切换"在用户早已离开时把焦点拉回).
    // 不参与日常夺回时序, 日常夺回完全由下面的事件触发.
    // 注意: 弹窗(数据源选择)打开期间不能计时 —— 用户可能在 sheet 里浏览数据源很久(>8s), 若此时
    // 把 wantFocus 清掉, 选完关闭 sheet 时就不会夺回焦点了. 因此只在"无弹窗"时才计这 8s.
    LaunchedEffect(wantFocus, popupOpen) {
        if (wantFocus && !popupOpen) {
            delay(8000)
            wantFocus = false
        }
    }

    // 事件触发的夺回: 在随后的若干渲染帧内补请求焦点(直到拿回或条件失效). 用 withFrameNanos 等帧,
    // 能吃下"切换的失焦比事件回调晚一两帧"的情况.
    //
    // force 用于区分两类"焦点跑到别的按钮":
    //  - 切换(transition)夺回: force=false, 遵守 focusIsElsewhere —— 若用户已用方向键导航到别的按钮,
    //    则不抢(下载 settling 期间用户可能正在导航).
    //  - 弹窗关闭(popupClose)夺回: force=true, 即便焦点被交给了别的按钮也抢回来 —— 因为这是 ModalBottomSheet
    //    关闭瞬间系统/列表把焦点错误派发到别处(冷启动偶发), 而此刻刚选完数据源、用户来不及在几帧内导航,
    //    所以这几帧内的"别处"一定是系统误派而非用户操作.
    suspend fun reclaimByFrames(force: Boolean) {
        repeat(8) {
            withFrameNanos { }
            if (!wantFocus || currentPopupOpen) return
            if (!force && focusIsElsewhere()) return
            if (!isFocused) runCatching { focusRequester.requestFocus() }
        }
    }

    // 事件1: 本按钮视觉切换(下载状态变化), 可能清掉焦点 -> 夺回. 跳过首次组合(初始值, 不是切换).
    var visualKeySeen by remember { mutableStateOf(false) }
    LaunchedEffect(visualKey) {
        if (!visualKeySeen) {
            visualKeySeen = true
            return@LaunchedEffect
        }
        reclaimByFrames(force = false)
    }
    // 事件0: 别的行把这一集的焦点交接过来 (见 LocalCacheRowRefocus). 与上面两个事件不同, 这一次
    // 焦点本来就不在本按钮上 (交接前持有焦点的是已经被销毁的那一行), 所以不看 wantFocus/焦点归属,
    // 抢到就清掉接力棒.
    val rowRefocus = LocalCacheRowRefocus.current
    // 只接"删除方向"的棒子: toCached=true 那一半是给已缓存行的, 本按钮长在未缓存行上.
    val myBaton = refocusEpisodeId?.let { CacheRowRefocus(it, toCached = false) }
    if (rowRefocus != null && myBaton != null) {
        LaunchedEffect(rowRefocus.value == myBaton, popupOpen) {
            if (rowRefocus.value != myBaton || popupOpen) return@LaunchedEffect
            // 整个窗口内只要没拿到就一直补: 新行刚出现那几帧里, 别的新冒出来的可聚焦控件
            // (如"全部暂停") 会被默认焦点分配抢走, 不能只试一帧就作罢.
            repeat(REFOCUS_FRAMES) {
                withFrameNanos { }
                if (!isFocused) runCatching { focusRequester.requestFocus() }
            }
            if (rowRefocus.value == myBaton) rowRefocus.value = null
        }
    }

    // 事件2: 弹窗(数据源 sheet / 删除下拉菜单)关闭 -> 夺回(force, 抢回被误派到别处的焦点).
    var popupWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(popupOpen) {
        val wasOpen = popupWasOpen
        popupWasOpen = popupOpen
        if (wasOpen && !popupOpen) reclaimByFrames(force = true)
    }

    val onButtonClick: () -> Unit = when {
        running -> if (showCancel) {
            { onCancel(); showCancel = false }
        } else {
            { showCancel = true }
        }

        else -> onClick
    }

    IconButton(
        onClick = {
            wantFocus = true // 点击后表达"希望焦点留在本按钮", 由事件驱动的夺回兑现
            onButtonClick()
        },
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged {
                isFocused = it.isFocused
                // 维护跨按钮的焦点归属: 拿到焦点记为本按钮; 失去焦点且归属仍是自己时置空(=被清空).
                focusOwner?.let { owner ->
                    if (it.isFocused) {
                        owner.value = myFocusKey
                    } else if (owner.value == myFocusKey) {
                        owner.value = null
                    }
                }
            },
    ) {
        Crossfade(
            when {
                running && showCancel -> CacheActionVisual.Cancel
                running -> CacheActionVisual.RunningSpinner
                cacheStatus is EpisodeCacheStatus.Cached -> CacheActionVisual.Cached
                cacheStatus is EpisodeCacheStatus.Caching -> CacheActionVisual.Caching
                else -> CacheActionVisual.Download
            },
        ) { visual ->
            when (visual) {
                CacheActionVisual.Cancel ->
                    Icon(Icons.Rounded.Close, stringResource(Lang.cache_subject_cancel))

                CacheActionVisual.RunningSpinner ->
                    CircularProgressIndicator(
                        Modifier.size(progressIndicatorSize),
                        strokeWidth = strokeWidth,
                        trackColor = trackColor,
                    )

                CacheActionVisual.Cached ->
                    Icon(Icons.Rounded.DownloadDone, null)

                CacheActionVisual.Caching -> {
                    val caching = cacheStatus as? EpisodeCacheStatus.Caching
                    if (caching == null || caching.progress.isUnspecified) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(progressIndicatorSize),
                            strokeWidth = strokeWidth,
                            trackColor = trackColor,
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { caching.progress.getOrZero() },
                            modifier = Modifier.size(progressIndicatorSize),
                            strokeWidth = strokeWidth,
                            trackColor = trackColor,
                        )
                    }
                }

                CacheActionVisual.Download ->
                    CompositionLocalProvider(
                        LocalContentColor providesDefault MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(Icons.Rounded.Download, stringResource(Lang.cache_subject_cache))
                    }
            }
        }
    }
}

/**
 * 换行接力的补请求窗口 (渲染帧数). 只在换行那一刻起算, 不是轮询 —— 换行事件本身是触发条件.
 * 给到 ~20 帧是因为缓存建出来与行替换、以及"全部暂停"这类新控件出现并抢到默认焦点之间隔了几帧.
 */
internal const val REFOCUS_FRAMES = 20

private enum class CacheActionVisual {
    Download,
    Caching,
    Cached,
    RunningSpinner,
    Cancel,
}

/**
 * 本页此刻有没有"会抢走焦点的弹窗"在前台 (数据源选择 / 存储位置选择).
 *
 * 做成 CompositionLocal 而不是逐层传参: 上游重做后剧集行分散在 subjectCacheEpisodeItems /
 * EpisodeNotCachedRow / SubjectCacheDetailPaneContent 几处, 挨个加形参要动一串签名, 而这个值
 * 全页只有一个. 见 [EpisodeCacheActionIcon] 的 popupOpen.
 */
internal val LocalCachePopupOpen = compositionLocalOf { false }

/**
 * 跨缓存按钮共享的"当前持有焦点的按钮 key". 由 [EpisodeCacheActionIcon] 在 onFocusChanged 中维护,
 * 用于区分"焦点被清空"(归属为 null, 需夺回) 与"用户导航到了别的按钮"(归属为别的 key, 不可抢).
 * 仅在缓存列表处提供; 未提供时为 null(夺回逻辑退化为不跨按钮协调).
 */
internal val LocalCacheFocusOwner = compositionLocalOf<MutableState<Any?>?> { null }

/**
 * 换行接力棒的内容: 哪一集, 以及**该由哪一种行接住**.
 *
 * 方向是必须的: 交出接力棒的那一刻, 旧行还在、而且正持有焦点. 若只按 episodeId 认领, 旧行会
 * 在下一帧就把自己刚交出去的棒子接回来 (它 isFocused 当场成立), 等新行出现时棒子早没了 ——
 * 真机症状是"选完数据源关掉弹窗, 焦点跑到刚冒出来的『全部暂停』上" (2026-08-23).
 *
 * @param toCached `true` = 交给已缓存行 (按下载), `false` = 交给未缓存行 (删掉缓存).
 */
@Immutable
internal data class CacheRowRefocus(val episodeId: Int, val toCached: Boolean)

/**
 * 「这一集的行马上要被换掉, 新行请把焦点接住」—— `null` 表示没人在等.
 *
 * 上游 d6dd9f245 之后一集有没有缓存决定它是哪一种行 (未缓存 -> [EpisodeCacheActionIcon] 那颗下载
 * 按钮; 已缓存 -> CacheEpisodeRow 的"更多"按钮), 而且是 LazyGrid 里 key 都不同的两个 item.
 * 按下载 / 删除会让这一集从一种行变成另一种, **原来持有焦点的节点连同它自己那套夺回逻辑一起被销毁**,
 * 新行不接的话遥控器就此失灵. 所以接力棒要放在比行更长命的地方 —— 页面级的这个 state 上,
 * 按 episodeId 认领 (cacheId 会变, episodeId 不会).
 *
 * 只在真的会换行的那一刻由点击方写入; 消费方抢到焦点后立即清空, 抢不到也有页面级的兜底过期
 * (见 SubjectCachePage), 免得很久以后那一集碰巧换行时把焦点从别处拽回来.
 */
internal val LocalCacheRowRefocus = compositionLocalOf<MutableState<CacheRowRefocus?>?> { null }
