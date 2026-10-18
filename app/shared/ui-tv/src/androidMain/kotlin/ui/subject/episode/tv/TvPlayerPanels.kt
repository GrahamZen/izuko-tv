/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItemsWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.subject.nameCn
import me.him188.ani.app.data.repository.RepositoryRateLimitedException
import me.him188.ani.app.domain.danmaku.DanmakuLoadingState
import me.him188.ani.app.domain.episode.SubjectRecommendation
import me.him188.ani.app.domain.episode.preferredDisplayName
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.navigation.SubjectDetailPlaceholder
import me.him188.ani.app.tools.LocalTimeFormatter
import me.him188.ani.app.tools.TimeFormatter
import me.him188.ani.app.domain.comment.CommentContext
import me.him188.ani.app.ui.comment.UIComment
import me.him188.ani.app.ui.danmaku.DanmakuEditorState
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.ProvideRingOnlyFocus
import me.him188.ani.app.ui.foundation.tv.nativeview.TV_NATIVE_COMMENT_ENTRY_NEAREST_BOTTOM
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeComment
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCommentEdge
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCommentList
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCommentListController
import me.him188.ani.app.ui.foundation.tv.TV_PILL_ICON_SIZE
import me.him188.ani.app.ui.foundation.tv.TvPillFailedIcon
import me.him188.ani.app.ui.foundation.tv.TvPillShell
import me.him188.ani.app.ui.foundation.tv.tvTmdbSubjectTitleFilled
import me.him188.ani.app.ui.foundation.tv.tvTouchFocusOnTap
import me.him188.ani.app.ui.foundation.tv.tvUseOriginalTitle
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.aniVerticalScrollFade
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.episode_danmaku
import me.him188.ani.app.ui.lang.episode_send_danmaku_failed
import me.him188.ani.app.ui.lang.episode_send_danmaku_quota_exceeded
import me.him188.ani.app.ui.lang.subject_episode_danmaku_list_empty
import me.him188.ani.app.ui.lang.subject_episode_related_recommendations
import me.him188.ani.app.ui.lang.subject_episode_related_recommendations_empty
import me.him188.ani.app.ui.subject.details.SubjectDetailsLoadState
import me.him188.ani.app.ui.subject.details.sections.CharactersViewAllDialog
import me.him188.ani.app.ui.subject.details.sections.PosterGalleryDialog
import me.him188.ani.app.ui.subject.details.sections.StaffViewAllDialog
import me.him188.ani.app.ui.subject.details.layout.rememberTvPlayerCommentStyle
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.ui.subject.person.PosterRowItem
import me.him188.ani.app.ui.episode.danmaku.DanmakuSourceChips
import me.him188.ani.app.ui.episode.danmaku.DanmakuTimeShiftDialog
import me.him188.ani.app.ui.episode.danmaku.renderDanmakuServiceId
import me.him188.ani.app.ui.subject.episode.statistics.DanmakuStatistics
import me.him188.ani.danmaku.api.DanmakuContent
import me.him188.ani.danmaku.api.DanmakuLocation
import me.him188.ani.danmaku.api.DanmakuServiceId
import me.him188.ani.utils.analytics.Analytics
import me.him188.ani.utils.analytics.AnalyticsEvent.Companion.SubjectEnter
import me.him188.ani.utils.analytics.AnalyticsEvent.Companion.SubjectRecommendationClick
import me.him188.ani.utils.analytics.recordEvent
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---- 面板调参 ----

/** 面板宽度 (Prime 实测约屏宽 1/4): 弹幕列表/评论等文字面板. */
internal val TV_PANEL_WIDTH = 420.dp

/**
 * 还没量到空隙 (首帧) 时面板的最大高度 (条目少时仍按内容收缩).
 *
 * 实际上限按屏实算: 上到左上角标题块的上沿 (面板开着时标题块淡出, 见 TvPlayerTopInfo)、下到胶囊行上缘, 由
 * [TvPlayerControlsOverlay] 实测两者位置传入 (见 [TvPlayerPanelHost] 的 `availableHeightPx`).
 * 4K 上报原生 density 时逻辑高度翻倍, 锁死一个固定值就只剩三分之一屏, 所以量到了就按量的.
 */
private val TV_PANEL_FALLBACK_MAX_HEIGHT = 300.dp

/** 量出来的空隙再小 (屏很矮), 面板也给这么高: 露得出一两张卡. */
private val TV_PANEL_MIN_HEIGHT = 120.dp

/** 面板上缘与顶部信息上沿之间保留的间隙. */
private val TV_PANEL_TOP_GAP = 16.dp

private data class TvPanelEntryFocus(val panel: TvPlayerPanel) : TvFocusKey

/**
 * 面板条目的焦点登记处 (焦点找回用).
 *
 * [requester] 由条目获焦时登记 —— 不按 focusedIndex 在组合里挂请求器: 那要求每个条目都读
 * focusedIndex, 一步导航就让在场的所有条目重组.
 *
 * 节点脱离时会撤销登记; 找回逻辑监听“宿主窗口已重新获焦 + 当前条目已登记”事件后单次送焦.
 */
@Stable
internal class TvPanelItemFocusRegistry {
    var requester: FocusRequester? by mutableStateOf(null)

    fun register(value: FocusRequester) {
        requester = value
    }

    fun unregister(value: FocusRequester) {
        if (requester === value) {
            requester = null
        }
    }
}

/** 面板条目未聚焦底色 (半透明玻璃, 视频上可读). */
internal val TV_PANEL_ITEM_COLOR = Color.Black.copy(alpha = 0.55f)

/** 面板条目聚焦底色. */
internal val TV_PANEL_ITEM_FOCUSED_COLOR = Color.Black.copy(alpha = 0.8f)

/** 面板最下一条与胶囊行之间的空. */
internal val TV_PANEL_BOTTOM_GAP = 14.dp

/**
 * L2 浮出面板宿主: 弹幕列表 / 本集评论. 相关推荐 / 角色 / 制作人员三颗胶囊的面板是胶囊上方的画廊 (见 TvPlayerDetailLayers), 这里不画.
 *
 * 弹幕: `LazyColumn(reverseLayout = true)` —— index 0 在底部, 向上导航 = 索引递增, 聚焦项吸附到底缘, 最底项按下键显式回到打开面板的胶囊,
 * 面板每次浮出都复位到底部 (进入焦点恒落最下第一项). 评论: 原生列表, 第一楼在最上面, 进出规矩同胶囊上方的画廊 (见 TvCommentsPanel).
 */
@Composable
internal fun TvPlayerPanelHost(
    overlay: TvPlayerOverlayState,
    vm: EpisodeViewModel,
    page: EpisodePageState,
    /** 各胶囊按钮的焦点请求器: 面板最底项按下键显式回到打开它的那个胶囊. */
    pillFocusRequesters: Map<TvPlayerPanel, FocusRequester>,
    /**
     * 胶囊此刻的视觉顺序 (左→右), 条目上按左/右键跳相邻面板的依据.
     * 用户能排版式 (顺序 + 显隐) 之后这份顺序是现算的, 不再是常量 —— 藏起来的胶囊不在里面,
     * 它的请求器根本没附着.
     */
    pillOrder: List<TvPlayerPanel>,
    /**
     * 面板可向上生长的空间 (window px): 胶囊行上缘 − 顶部信息上沿 (面板开着时左上角标题块淡出让位), 由调用方实测.
     * NaN (尚未测得) 时兜底 [TV_PANEL_FALLBACK_MAX_HEIGHT]. Provider 形式 + 测量阶段才读:
     * 位置变化只触发面板重测量, 不重组控制层.
     */
    availableHeightPx: () -> Float = { Float.NaN },
    modifier: Modifier = Modifier,
) {
    val danmakuListState = rememberLazyListState()
    val commentsList = remember { TvNativeCommentListController() }

    // 当前聚焦条目下标 (吸底滚动用; 同一时刻只有一个面板在场, 共享一个)
    val focusedIndex = remember { mutableIntStateOf(-1) }
    // 每面板独立 key: AnimatedContent 淡切期间新旧面板可能并存, 不共享同一锚点.
    val focus = rememberTvFocusScope()
    val windowInfo = LocalWindowInfo.current
    // 弹幕面板底部 chips 行当前是否存在 (加载早期/全部源失败时不组合), 由面板上报;
    // 左右键跳相邻胶囊的豁免判断依据 —— chips 缺席时 index 0 是普通弹幕行, 不豁免
    val danmakuChipsPresent = remember { mutableStateOf(false) }
    // 当前聚焦条目的登记 (焦点找回用, 见 overlay.panelItemFocusTick)
    val itemFocus = remember { TvPanelItemFocusRegistry() }

    // 面板每次浮出都复位到底部 (index 0): 进入焦点 (点击胶囊/上键) 永远落在最下面
    // 第一项 —— 保留上次滚动位置会让进入焦点落在"不知道第几项"上, 反直觉
    LaunchedEffect(overlay.activePanel) {
        // 换面板/关面板: 旧面板条目的登记作废 (它的节点即将被移除, 焦点找回不能落在上面)
        itemFocus.requester = null
        when (overlay.activePanel) {
            TvPlayerPanel.DANMAKU_LIST -> {
                focusedIndex.intValue = -1
                runCatching { danmakuListState.scrollToItem(0) }
            }

            TvPlayerPanel.COMMENTS -> {
                focusedIndex.intValue = -1
                commentsList.entryIndex = TV_NATIVE_COMMENT_ENTRY_NEAREST_BOTTOM
                commentsList.scrollTo(0)
            }

            else -> {}
        }
    }

    // 换集 (上一集 / 下一集键、自动连播、控制台): 评论 / 弹幕整张换掉, 持焦的那一条跟着没了 —— Compose 移除持焦节点时整棵树没有焦点,
    // 遥控器像失灵. 焦点在这两个面板里、或在评论详情里 (那一楼是上一集的, 一并关掉) 时回到打开面板的那颗胶囊, 列表回到底.
    // 画廊与人物页按条目算, 换集不变, 焦点留在原处
    val currentEpisodeId by rememberUpdatedState(page.episodePresentation.episodeId)
    LaunchedEffect(Unit) {
        snapshotFlow { currentEpisodeId }.drop(1).collect {
            focusedIndex.intValue = -1
            itemFocus.requester = null
            val panel = overlay.activePanel
            val commentOpen = overlay.replyingComment != null
            val focusInside = overlay.focusRegion == TvPlayerFocusRegion.PANEL ||
                    (commentOpen && overlay.focusRegion == TvPlayerFocusRegion.LAYER)
            if (commentOpen) overlay.dismissReply(restoreFocus = false)
            if (focusInside) {
                val pill = panel?.takeUnless { it.showsGallery }?.let { pillFocusRequesters[it] }
                if (pill != null) runCatching { pill.requestFocus() } else overlay.focusProgress()
            }
            // 不挂起的那个: 不在场的那张列表 (从没排过版) 用 scrollToItem 会一直等它第一次排版
            danmakuListState.requestScrollToItem(0)
            commentsList.entryIndex = TV_NATIVE_COMMENT_ENTRY_NEAREST_BOTTOM
            commentsList.scrollTo(0)
        }
    }

    // 点击胶囊把焦点送进当前面板: 请求悬挂到该面板 index 0 锚点附着. 画廊胶囊交给画廊 (见 TvPlayerDetailLayers)
    LaunchedEffect(Unit) {
        snapshotFlow { overlay.pendingFocus }.collectLatest { (target, _) ->
            if (target != TvPlayerFocusTarget.PANEL) return@collectLatest
            val panel = overlay.activePanel ?: return@collectLatest
            if (panel == TvPlayerPanel.COMMENTS) {
                // 胶囊上按确定进第一楼 (按上进来是回到上次离开的那楼, 见 TvCommentsPanel)
                commentsList.entryIndex = 0
                commentsList.scrollTo(0)
            }
            if (panel.showsGallery) overlay.detailLayers.requestGalleryFocus() else focus.request(TvPanelEntryFocus(panel))
        }
    }

    // 焦点找回: 从面板条目点开的东西 (回复弹窗/人物预览/弹幕延迟对话框) 关掉之后, 把焦点
    // 送回**刚点开的那一条**. Compose 移除聚焦节点时会清掉整棵树的焦点且不交给祖先, 所以
    // 每一次这样的移除都得有人显式还回来, 否则整层没有焦点 (方向键全失效).
    // drop(1): 初值不是一次真实请求
    LaunchedEffect(Unit) {
        snapshotFlow { overlay.panelItemFocusTick }.drop(1).collectLatest {
            // **必须等弹窗真的离开组合树**, 不能只看登记与窗口焦点 —— dismissReply 是在同一次
            // 同步调用里清状态 + 发找回信号的, 那一刻弹窗还在树上并占着焦点, requestFocus 会被
            // 拒绝且不重试 (真机取证见 TvPlayerOverlayState.replyDialogComposed).
            val requester = snapshotFlow {
                Triple(itemFocus.requester, windowInfo.isWindowFocused, overlay.replyDialogComposed)
            }
                .first { (requester, windowFocused, dialogComposed) ->
                    requester != null && windowFocused && !dialogComposed
                }
                .first ?: return@collectLatest
            // 从叠层 (评论详情 / 人物页) 退回来时焦点区域还停在 LAYER (叠层获焦时上报的)
            if (overlay.activePanel != null && overlay.layer == TvPlayerLayer.CONTROLS &&
                (overlay.focusRegion == TvPlayerFocusRegion.PANEL || overlay.focusRegion == TvPlayerFocusRegion.LAYER)
            ) {
                runCatching { requester.requestFocus() }
            }
        }
    }

    /** 焦点去 [panel] 那颗胶囊左 / 右 ([offset] = -1 / 1) 相邻的那颗; 0 = 就是那颗. 那颗胶囊已经不在行上了 (刚被藏掉) 时不猜邻居. */
    fun focusPill(panel: TvPlayerPanel, offset: Int) {
        val target = if (offset == 0) {
            panel
        } else {
            val current = pillOrder.indexOf(panel)
            if (current < 0) return
            pillOrder.getOrNull(current + offset) ?: return
        }
        runCatching { pillFocusRequesters.getValue(target).requestFocus() }
    }

    AnimatedContent(
        targetState = overlay.activePanel,
        modifier = modifier.tvFocusNavSignal(focus),
        transitionSpec = {
            when {
                initialState == null ->
                    (slideInVertically(tween(200)) { it / 4 } + fadeIn(tween(200))) togetherWith
                            fadeOut(tween(120))

                targetState == null ->
                    fadeIn(tween(120)) togetherWith
                            (slideOutVertically(tween(200)) { it / 4 } + fadeOut(tween(200)))

                else -> fadeIn(tween(150)) togetherWith fadeOut(tween(100))
            }
        },
        contentAlignment = Alignment.BottomStart,
        label = "tvPlayerPanel",
    ) { panel ->
        val panelModifier = Modifier
            .width(TV_PANEL_WIDTH)
            // 最大高度按屏实算 (顶到顶部信息上沿为止); 在测量阶段读位置, 不因位置变化重组
            .layout { measurable, constraints ->
                val available = availableHeightPx()
                val maxHeightPx = if (available.isNaN()) {
                    TV_PANEL_FALLBACK_MAX_HEIGHT.roundToPx()
                } else {
                    max(TV_PANEL_MIN_HEIGHT.roundToPx(), (available - TV_PANEL_TOP_GAP.toPx()).roundToInt())
                }
                val placeable = measurable.measure(
                    constraints.copy(maxHeight = min(constraints.maxHeight, maxHeightPx)),
                )
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .padding(bottom = TV_PANEL_BOTTOM_GAP)
            // 从下方胶囊按上键进来 (焦点搜索进组) 一律改道到入口锚点 (index 0), 与点击胶囊同一个落点.
            // 交给空间搜索的话它只认胶囊正上方的候选: 弹幕面板最底行是一排靠左的窄胶囊, 从行末的「弹幕」胶囊
            // 往上会越过它们, 落到上面整行宽的弹幕条上. onEnter 只在焦点组上生效, 组在 TvPanelList 里, 挂在它外面正好
            .focusProperties {
                onEnter = { panel?.let { runCatching { focus.requesterOf(TvPanelEntryFocus(it)).requestFocus() } } }
            }
            // 弹幕面板最底项 (index 0) 按下键: 显式回到打开本面板的胶囊 —— 交给空间搜索会落到
            // 面板正下方的任意按钮, 落错后该按钮的聚焦回调又把面板切成自己的 (面板跳变)
            .onPreviewKeyEvent { event ->
                when {
                    panel == null -> false

                    // 评论面板第一楼在最上面, 什么时候回胶囊由原生列表自己的到边处理定 (最后一楼再按下, 见 TvCommentsPanel)
                    event.key == Key.DirectionDown && panel != TvPlayerPanel.COMMENTS && focusedIndex.intValue == 0 -> {
                        if (event.type == KeyEventType.KeyDown) {
                            runCatching { pillFocusRequesters.getValue(panel).requestFocus() }
                        }
                        true
                    }

                    // 条目上按左/右: 焦点跳到打开本面板的胶囊的左/右相邻胶囊 (面板随之
                    // 切换). 面板条目单列, 左右键没有面板内目标, 交给空间搜索会斜跳到
                    // 下方按钮行的任意按钮; 例外: 弹幕列表最底行 (index 0) 是横排 chips
                    // (且 chips 确实在场, 缺席时 index 0 是普通弹幕行), 左右键留给行内导航
                    (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) &&
                            !(panel == TvPlayerPanel.DANMAKU_LIST && focusedIndex.intValue == 0 &&
                                    danmakuChipsPresent.value) -> {
                        if (event.type == KeyEventType.KeyDown) {
                            focusPill(panel, if (event.key == Key.DirectionLeft) -1 else 1)
                        }
                        // 到头 (最左/最右胶囊的面板) 也消费, 防斜跳
                        true
                    }

                    else -> false
                }
            }
        when (panel) {
            // 画廊画在胶囊上方的叠层里 (见 TvPlayerDetailLayers)
            null, TvPlayerPanel.RECOMMENDATIONS, TvPlayerPanel.STAFF, TvPlayerPanel.CHARACTERS -> Box(Modifier.height(0.dp))

            TvPlayerPanel.COMMENTS -> TvCommentsPanel(
                vm, page, overlay, commentsList, focusedIndex, itemFocus,
                // 原生列表到边 (条目上的方向键原生那边先收到): 同上面的规则, 往下回胶囊、往左右去相邻胶囊
                onExit = { edge ->
                    when (edge) {
                        TvNativeCommentEdge.DOWN -> focusPill(panel, 0)
                        TvNativeCommentEdge.LEFT -> focusPill(panel, -1)
                        TvNativeCommentEdge.RIGHT -> focusPill(panel, 1)
                        TvNativeCommentEdge.UP -> {}
                    }
                },
                Modifier.tvFocusAnchor(focus, TvPanelEntryFocus(panel)), panelModifier,
            )

            TvPlayerPanel.DANMAKU_LIST -> TvDanmakuListPanel(
                vm, page, overlay, danmakuListState, focusedIndex, itemFocus,
                Modifier.tvFocusAnchor(focus, TvPanelEntryFocus(panel)),
                onChipsPresentChanged = { danmakuChipsPresent.value = it },
                modifier = panelModifier,
            )
        }
    }
}

// ============================ 共用脚手架 ============================

/**
 * 底锚可导航列表: reverseLayout (index 0 在底), 聚焦项吸底, 关默认 bring-into-view.
 *
 * 面板入口请求器不挂在本列表上 (对焦点组 requestFocus 的进组落点不确定, 实测会落到
 * 视觉最上面的条目), 由各面板挂到自己的 index 0 (最底, 紧邻胶囊按钮) 条目上.
 */
@Composable
private fun TvPanelList(
    listState: LazyListState,
    overlay: TvPlayerOverlayState,
    focusedIndex: MutableIntState,
    modifier: Modifier = Modifier,
    itemSpacing: Dp = 8.dp,
    content: LazyListScope.() -> Unit,
) {
    val noBringIntoView = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
        }
    }
    // 聚焦项吸底: reverseLayout 下 scrollToItem 把目标项对齐到列表起点 = 底缘
    LaunchedEffect(listState) {
        snapshotFlow { focusedIndex.intValue }.collectLatest { idx ->
            if (idx >= 0) runCatching { listState.animateScrollToItem(idx) }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides noBringIntoView) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = modifier
                // 聚焦项吸在底缘, 挤出去的是更早的条目 (在上面): 只在顶边渐隐, 底边不隐 (那里就是焦点项)
                .aniVerticalScrollFade(canScrollUp = { listState.canScrollForward }, canScrollDown = { false })
                .focusGroup()
                .onFocusChanged { if (it.hasFocus) overlay.focusRegion = TvPlayerFocusRegion.PANEL },
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            content()
        }
    }
}

/** 面板条目: 半透明玻璃底, 聚焦白色圆角描边 (参考 Prime 实测). [content] 收到实时聚焦态 (跑马灯用). */
@Composable
private fun TvPanelItem(
    index: Int,
    focusedIndex: MutableIntState,
    /** 焦点找回登记处: 本条目获焦时把自己的请求器登记进去 (见 [TvPlayerPanelHost]). */
    itemFocus: TvPanelItemFocusRegistry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 为 true 时: [focusedIndex] 指到本条目就把自己登记进 [itemFocus], 即使没获焦.
     *
     * 平时**不能**这么干 —— 条目一旦读 focusedIndex, 一次上下键就会让在场的所有条目重组
     * (正常情况下登记由获焦回调完成, 不需要读). 只有"焦点在弹窗里而当前项被弹窗内的按键换掉了"
     * 的场合才需要改锚 (评论弹窗左右键翻相邻评论): 关闭弹窗时的通用找回记的是**上一次真正
     * 获焦**的条目, 不改锚焦点就会跳回当初点开的那一条, 而列表已经滚到新的一条了.
     */
    reanchorFocusRegistry: Boolean = false,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val selfFocusRequester = remember { FocusRequester() }
    DisposableEffect(selfFocusRequester) {
        onDispose { itemFocus.unregister(selfFocusRequester) }
    }
    if (reanchorFocusRegistry) {
        val current = focusedIndex.intValue
        LaunchedEffect(current) {
            if (current == index) itemFocus.register(selfFocusRequester)
        }
    }
    ProvideRingOnlyFocus {
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .focusRequester(selfFocusRequester)
                .onFocusChanged {
                    if (it.isFocused) {
                        focusedIndex.intValue = index
                        itemFocus.register(selfFocusRequester)
                    }
                }
                .tvTouchFocusOnTap(),
            shape = RoundedCornerShape(12.dp),
            color = if (focused) TV_PANEL_ITEM_FOCUSED_COLOR else TV_PANEL_ITEM_COLOR,
            contentColor = Color.White,
            border = if (focused) BorderStroke(2.dp, Color.White) else null,
            interactionSource = interactionSource,
        ) {
            content(focused)
        }
    }
}

// ============================ 弹幕列表面板 ============================

/**
 * 弹幕列表: 底部 (index 0) 是弹幕源开关/重新匹配/调整延迟 chips (即导航入口第一站),
 * 上方为全集弹幕 (时间升序, 越往上越晚).
 */
@Composable
private fun TvDanmakuListPanel(
    vm: EpisodeViewModel,
    page: EpisodePageState,
    overlay: TvPlayerOverlayState,
    listState: LazyListState,
    focusedIndex: MutableIntState,
    itemFocus: TvPanelItemFocusRegistry,
    entryFocusModifier: Modifier,
    /** 底部 chips 行是否在场 (宿主的左右键豁免判断依据), 变化时上报. */
    onChipsPresentChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.danmakuListState.collectAsStateWithLifecycle()
    var editingShiftServiceId by remember { mutableStateOf<DanmakuServiceId?>(null) }
    val chipsFocusRequester = remember { FocusRequester() }
    DisposableEffect(chipsFocusRequester) {
        onDispose { itemFocus.unregister(chipsFocusRequester) }
    }
    SideEffect { onChipsPresentChanged(state.sourceItems.isNotEmpty()) }

    // 弹幕条目多且单行, 间距收到最紧. 条目是可点击 Surface, M3 会给它套 48dp
    // 最小交互尺寸 —— 单行文字实际不到 30dp, 多出的全变成上下空隙, 这里关掉
    // 弹幕条目的导航 index 基底: chips 项存在时它占 0, 条目从 1 起; chips 未组合
    // (加载早期/全部源失败) 时条目自己从 0 起 —— 否则没有任何项是 index 0,
    // 面板"最底项按下键回胶囊"的守卫 (focusedIndex == 0) 永不命中
    val itemIndexBase = if (state.sourceItems.isNotEmpty()) 1 else 0
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
    TvPanelList(listState, overlay, focusedIndex, modifier, itemSpacing = 2.dp) {
        // 底部: 弹幕源 chips (原侧边栏弹幕列表里的源开关/延迟按钮, 功能不变)
        if (state.sourceItems.isNotEmpty()) {
            item("danmaku_sources") {
                Surface(
                    Modifier
                        .fillMaxWidth()
                        .onFocusChanged {
                            if (it.hasFocus) {
                                focusedIndex.intValue = 0
                                // 焦点找回落到本行第一个 chip (行内具体哪个 chip 不记)
                                itemFocus.register(chipsFocusRequester)
                            }
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = TV_PANEL_ITEM_COLOR,
                    contentColor = Color.White,
                ) {
                    DanmakuSourceChips(
                        sourceItems = state.sourceItems,
                        onToggleSource = { serviceId, enabled -> vm.setDanmakuSourceEnabled(serviceId, enabled) },
                        onManualMatch = { serviceId ->
                            page.danmakuStatistics.fetchResults.find { it.serviceId == serviceId }?.let {
                                vm.startMatchingDanmaku(it.providerId)
                            }
                        },
                        onAdjustShift = { serviceId -> editingShiftServiceId = serviceId },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        // 面板入口落点 (index 0): 锚点必须挂在真实可聚焦的第一颗 chip 上.
                        firstItemModifier = Modifier
                            .focusRequester(chipsFocusRequester)
                            .then(entryFocusModifier),
                    )
                }
            }
        }
        if (state.isLoading) {
            item("danmaku_loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        } else if (state.danmakuItems.isEmpty()) {
            item("danmaku_empty") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(Lang.subject_episode_danmaku_list_empty),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        items(
            count = state.danmakuItems.size,
            key = { i -> "${state.danmakuItems[i].id}-$i" },
        ) { i ->
            val danmaku = state.danmakuItems[i]
            TvPanelItem(
                index = i + itemIndexBase,
                focusedIndex = focusedIndex,
                itemFocus = itemFocus,
                onClick = {},
                // chips 未组合时首条弹幕即 index 0, 兼任面板入口落点
                modifier = if (i + itemIndexBase == 0) entryFocusModifier else Modifier,
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        danmaku.content,
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (danmaku.isSelf) MaterialTheme.colorScheme.primary else Color.White,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        renderTvPlayerTimeShort(danmaku.timeMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
    }

    // 弹幕源延迟调整对话框 (复用手机版, 已带 TV slider 按键适配)
    val editingShiftSource = editingShiftServiceId?.let { serviceId ->
        page.danmakuStatistics.fetchResults.firstOrNull { it.serviceId == serviceId }
    }
    if (editingShiftSource != null) {
        DanmakuTimeShiftDialog(
            serviceName = renderDanmakuServiceId(editingShiftSource.serviceId),
            currentShiftMillis = editingShiftSource.config.shiftMillis,
            // 对话框收起后焦点还给 chips 行: 不还的话焦点停在被移除的对话框节点上 (= 没有焦点)
            onDismissRequest = {
                editingShiftServiceId = null
                overlay.requestPanelItemFocus()
            },
            onConfirm = { newShift ->
                vm.setDanmakuSourceShiftMillis(editingShiftSource.serviceId, newShift)
                editingShiftServiceId = null
                overlay.requestPanelItemFocus()
            },
        )
    }
}

private fun renderTvPlayerTimeShort(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}

// ============================ 相关推荐画廊 ============================

/** 点一条相关推荐: 去那个条目的详情页 (面板与画廊同一个动作). 没有条目 id 的不动. */
private fun openRecommendation(navigator: AniNavigator, recommendation: SubjectRecommendation) {
    val targetSubjectId = recommendation.subjectId?.toInt() ?: return
    Analytics.recordEvent(SubjectRecommendationClick) {
        put("subject_id", targetSubjectId)
    }
    Analytics.recordEvent(SubjectEnter) {
        put("source", "episode_recommendation")
        put("subject_id", targetSubjectId)
    }
    navigator.navigateSubjectDetails(
        targetSubjectId,
        SubjectDetailPlaceholder(
            id = targetSubjectId,
            name = recommendation.name,
            nameCN = recommendation.nameCn ?: "",
            coverUrl = recommendation.imageUrl,
        ),
    )
}

/**
 * 相关推荐胶囊的面板: 推荐的条目排成作品海报画廊 (同人物页「出演作品」的), 经 LocalViewAllPanelHost 摆进胶囊上方的叠层 (见 TvPlayerDetailLayers).
 * 番名照 TMDB 标题 / 原名设置, 下面一行写另一个名字; 点海报离开播放器进那个条目的详情页.
 * 还没拿到时不画; 拿到了一条都没有时贴着胶囊写一句 (Bangumi 的推荐对 2023 年下半年起开播的条目普遍为空, 近几年的番多半是这样).
 */
@Composable
internal fun TvPlayerRecommendationsGallery(vm: EpisodeViewModel) {
    val navigator = LocalNavigator.current
    // null = 还没拿到 (加载中或失败)
    val loaded by vm.episodeDetailsState.recommendations
    // NSFW 设为隐藏时去掉、模糊时封面打码 (见 NsfwPolicy)
    val nsfw = rememberNsfwPolicy()
    val recommendations = nsfw.visible(loaded.orEmpty()) { it.subjectId?.toInt() ?: -1 }
    if (recommendations.isEmpty()) {
        if (loaded != null) TvPanelEmptyCard(stringResource(Lang.subject_episode_related_recommendations_empty))
        return
    }
    val useOriginalTitle = tvUseOriginalTitle()
    val items = recommendations.map { recommendation ->
        key(recommendation.uniqueId) {
            val tmdbTitle = recommendation.subjectId?.toInt()?.let { tvTmdbSubjectTitleFilled(it) }
            val title = tmdbTitle ?: recommendation.preferredDisplayName(useOriginalTitle)
            val other = (if (title == recommendation.name) recommendation.nameCn else recommendation.name)
                ?.takeIf { it.isNotBlank() && it != title }
            PosterRowItem(recommendation.imageUrl, title, other, subjectId = recommendation.subjectId?.toInt())
        }
    }
    PosterGalleryDialog(
        title = stringResource(Lang.subject_episode_related_recommendations),
        items = items,
        // 画廊是胶囊的面板, 不由它自己关 (返回回胶囊, 见 TvPlayerDetailLayersState.back)
        onDismissRequest = {},
        onClick = { index -> recommendations.getOrNull(index)?.let { openRecommendation(navigator, it) } },
        onBind = {},
    )
}

/** 胶囊上方的画廊没有内容时贴着胶囊的那一句 (同面板条目的底): 从画廊的格起头的地方起 (叠层贴着屏幕左缘), 字靠左. */
@Composable
private fun TvPanelEmptyCard(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = TV_PLAYER_GALLERY_CONTENT_PADDING_H, bottom = TV_PANEL_BOTTOM_GAP),
        contentAlignment = Alignment.BottomStart,
    ) {
        Surface(
            Modifier.widthIn(max = TV_PANEL_WIDTH),
            shape = RoundedCornerShape(12.dp),
            color = TV_PANEL_ITEM_COLOR,
            contentColor = Color.White,
        ) {
            // 放不下就换行, 不截断
            Text(
                text,
                Modifier.padding(16.dp),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ============================ 本集评论面板 ============================

/** 回复的左缩进 (给 thread 竖线留出的槽): 列表卡里楼下的回复与整楼视图里的回复卡都用这一套. */
internal val TV_COMMENT_REPLY_INDENT = 28.dp

/** thread 竖线: 在缩进槽里的 x 位置 / 线宽 / 颜色. */
internal val TV_COMMENT_THREAD_LINE_INSET = 12.dp
internal val TV_COMMENT_THREAD_LINE_WIDTH = 2.dp
internal val TV_COMMENT_THREAD_LINE_COLOR = Color.White.copy(alpha = 0.22f)

/** 整楼视图里回复卡之间的空, 竖线跨缝延伸也用它. */
internal val TV_COMMENT_ROW_SPACING = 8.dp

/**
 * 发表本集评论 (主楼): 评论胶囊按下确定的去处, 见 `TvPlayerPillsRow`.
 *
 * 胶囊原来的点击是"把焦点送进面板", 与直接按上键完全重复; 而 TV 上此前根本没有发新评论的
 * 入口 —— 只能回复已有的评论 (手机端那颗「发送评论」FAB 在遥控器形态下没有对应物).
 *
 * 走的是与手机端同一条链路: [CommentEditorState.startEdit] 开编辑态 (草稿归属由它管),
 * 发送时 `PostCommentUseCase` 按上下文分派到 `createEpisodeComment`.
 */
internal fun openNewEpisodeComment(
    vm: EpisodeViewModel,
    page: EpisodePageState,
    overlay: TvPlayerOverlayState,
) {
    val context = CommentContext.Episode(vm.subjectId, page.episodePresentation.episodeId.toLong())
    vm.commentEditorState.startEdit(context)
    // startEdit 不管表情面板 (它是手机端那个贴在输入框下面的面板): 上次留着开就带进新弹窗了
    vm.commentEditorState.toggleStickerPanelState(false)
    overlay.startReply(TvCommentReplyTarget(context = context))
}

/**
 * 进播放页就拉本集评论, 并把加载状态报给状态机 ([TvPlayerOverlayState.commentsLoad]); 挂在播放页上, 不随控制层收起.
 *
 * 评论胶囊据此呼吸 (加载中) 或显示失败, 不用先打开面板 —— 与弹幕胶囊一样, 一进这一集就能看出评论有没有加载成功.
 * 面板打开时用的是这里拉好的同一份分页数据 (`cachedIn` 缓存着), 不再发请求. 换集时分页流跟着换, 状态按分集 id 记.
 *
 * loadState 在一次加载里会变好几回, 用 snapshotFlow 读, 不读在本组合的 body 上.
 */
@Composable
internal fun TvCommentsLoadTracker(
    vm: EpisodeViewModel,
    overlay: TvPlayerOverlayState,
    episodeId: Int,
) {
    val comments = vm.episodeCommentState.list.collectAsLazyPagingItemsWithLifecycle()
    LaunchedEffect(comments, episodeId) {
        snapshotFlow { comments.loadState.refresh }.collect { refresh ->
            overlay.reportCommentsLoad(
                episodeId,
                when (refresh) {
                    is LoadState.Loading -> TvPanelLoadState.LOADING
                    is LoadState.Error -> TvPanelLoadState.FAILED
                    is LoadState.NotLoading -> null
                },
            )
        }
    }
}

/**
 * 本集评论: 同「查看全部」的原生评论列表 (见 TvViewAllCommentsList), 一列, 第一楼在最上面, 一楼一张卡: 主楼 + 楼下前几条回复 (竖线串着)
 * + 「共 N 条回复」, 画面上的半透明黑底白字 (同面板别的条目); 滚动同「查看全部」的列表: 焦点在看得见的楼里走, 快到边才滚.
 *
 * 焦点 (同胶囊上方的画廊): 胶囊上按确定进第一楼 (再按一下就打开它); 按上进来落回上次离开时那楼, 这一次面板浮出后还没进来过就是看得见的
 * 里面最靠下的那楼 (离胶囊最近); 最后一楼再按下回打开面板的胶囊、按左 / 右去相邻胶囊 ([onExit]), 第一楼按上停住; 点开的详情关掉后
 * 回到点开 (或翻到) 的那楼 (由面板宿主的焦点找回送进来, 列表交给 entryIndex 那楼).
 *
 * 点一楼: 只读 (Bangumi 评论在 Ani 内只读, 楼中回复也没有对应的写接口) 是一整楼的视图 (见 TvCommentThreadView); 能回复的
 * (Ani 源的主楼) 照旧是带输入框的弹窗, 回复发到主楼 (服务端写接口只接受主楼 id, 见 [me.him188.ani.app.domain.comment.PostCommentUseCase]).
 */
@Composable
private fun TvCommentsPanel(
    vm: EpisodeViewModel,
    page: EpisodePageState,
    overlay: TvPlayerOverlayState,
    list: TvNativeCommentListController,
    focusedIndex: MutableIntState,
    itemFocus: TvPanelItemFocusRegistry,
    onExit: (TvNativeCommentEdge) -> Unit,
    entryFocusModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val comments = vm.episodeCommentState.list.collectAsLazyPagingItemsWithLifecycle()
    val snapshot = comments.itemSnapshotList
    val sort = vm.videoScaffoldConfig.episodeCommentSort
    // 楼的先后只在评论集合或排序变了时重排, 贴贴 / 回复让计数变了不挪位置 —— 按贴贴数排时点一个表情,
    // 正开着的弹窗与焦点不能就此换到别的楼上
    val threadIds = remember(snapshot) { snapshot.mapNotNull { it?.stableId } }
    val threadOrder = remember(threadIds, sort) {
        tvSortCommentThreads(snapshot.filterNotNull(), sort).map { it.stableId }
    }
    val threads = remember(snapshot, threadOrder) { orderTvPanelCommentThreads(snapshot, threadOrder) }
    // 非组合环境 (翻楼的 effect、点击回调) 里也要算时间戳, 所以在这里取一次格式器
    val timeFormatter = LocalTimeFormatter.current
    // 每楼只转一次 (时间格式化、富文本压平); 按对象认, 分页刷新换了新的才重转
    val converted = remember(timeFormatter) { HashMap<UIComment, TvNativeComment>() }
    val items = remember(threads, converted) {
        threads.map { thread -> converted.getOrPut(thread.comment) { thread.comment.toTvNativeComment(timeFormatter, showRating = false) } }
    }
    // 换了排序: 回到最底那一条 (新排序的第一条), 同面板每次浮出时. 新顺序交给列表之后才跳 (按 threadOrder 起效)
    var listedSort by remember { mutableStateOf(sort) }
    LaunchedEffect(threadOrder) {
        if (listedSort == sort) return@LaunchedEffect
        listedSort = sort
        focusedIndex.intValue = -1
        list.entryIndex = TV_NATIVE_COMMENT_ENTRY_NEAREST_BOTTOM
        list.scrollTo(0)
    }
    // 详情里正在显示的是哪一楼 (左右键翻相邻的楼的锚). -1 = 没开
    var shownIndex by remember { mutableIntStateOf(-1) }

    fun openCommentAt(index: Int) {
        val thread = threads.getOrNull(index)?.comment ?: return
        val canReply = thread.canReply
        val context = CommentContext.EpisodeReply(
            vm.subjectId,
            page.episodePresentation.episodeId.toLong(),
            thread.sourceCommentId,
        )
        // 只读态不进编辑态: startEdit 会清掉草稿, 而这里根本不会发送
        if (canReply) {
            vm.commentEditorState.startEdit(context)
        }
        overlay.startReply(
            TvCommentReplyTarget(
                context = context,
                quoted = thread.toTvQuotedComment(timeFormatter),
                canReply = canReply,
                replies = if (canReply) emptyList() else thread.toTvThreadReplies(timeFormatter),
            ),
        )
        shownIndex = index
        // 详情关掉后焦点回到这一楼
        list.entryIndex = index
        // 不动播放状态: 详情多数时候只是"看完整评论", 与弹幕列表、推荐这些面板一样是边看边翻, 停一下再自己按播放很烦
    }

    // effect 里不能直接调上面那个 openCommentAt / 读上面那个 threads: 它们是**当次组合**的值,
    // 分页 append 换掉 threads 之后, 长期存活的 effect 捕获到的就是旧列表了
    val openLatest by rememberUpdatedState<(Int) -> Unit> { openCommentAt(it) }
    val threadsLatest by rememberUpdatedState(threads)

    // 详情里左右键: 原地翻到相邻的一楼, 背后的面板跟着滚到那一楼 (同焦点走过去时的滚动) —— 关掉详情时焦点落在最后看的那楼上, 不再挪.
    // 在这里消费而不是把回调塞进详情: 只有本面板手上有排好序的楼
    LaunchedEffect(Unit) {
        snapshotFlow { overlay.replyNavRequest }.drop(1).collect { (delta, _) ->
            if (overlay.replyingComment == null) return@collect
            val next = shownIndex + delta
            // 到端点无动作 (与选集详情弹窗左右键一致)
            if (shownIndex !in threadsLatest.indices || next !in threadsLatest.indices) return@collect
            openLatest(next)
            focusedIndex.intValue = next
            list.reveal(next)
        }
    }

    // 焦点找回 (详情关掉后) 送到列表上, 列表交给 entryIndex 那楼
    val listFocus = remember { FocusRequester() }
    DisposableEffect(listFocus) {
        onDispose { itemFocus.unregister(listFocus) }
    }
    TvNativeCommentList(
        items = items,
        style = rememberTvPlayerCommentStyle(),
        initialFocusIndex = 0,
        touchTaps = LocalTvTouchInputEnabled.current,
        onClick = { openCommentAt(it) },
        // 读一次以保住 paging 的预取 / append 触发 (面板里的顺序与 pager 不同)
        onBind = { index -> threads.getOrNull(index)?.let { comments[it.pagerIndex] } },
        modifier = modifier
            .focusRequester(listFocus)
            .then(entryFocusModifier)
            .onFocusChanged {
                if (it.hasFocus) {
                    overlay.focusRegion = TvPlayerFocusRegion.PANEL
                    itemFocus.register(listFocus)
                }
            },
        controller = list,
        onEdge = { edge, newPress ->
            if (newPress) onExit(edge)
            true
        },
        // 下次按上进来落回这一楼
        onFocusedPosition = {
            focusedIndex.intValue = it
            list.entryIndex = it
        },
    )
}

/** 面板里的一楼: 主楼与它在 pager 里的下标. */
private class TvPanelCommentThread(val pagerIndex: Int, val comment: UIComment)

/** 按 [threadOrder] (主楼 stableId, 见 [tvSortCommentThreads]) 排好的楼, 排在前面的贴着胶囊. */
private fun orderTvPanelCommentThreads(comments: List<UIComment?>, threadOrder: List<String>): List<TvPanelCommentThread> {
    val pagerIndexOf = HashMap<String, Int>(comments.size)
    comments.forEachIndexed { pagerIndex, comment -> if (comment != null) pagerIndexOf[comment.stableId] = pagerIndex }
    return threadOrder.mapNotNull { id ->
        val pagerIndex = pagerIndexOf[id] ?: return@mapNotNull null
        comments[pagerIndex]?.let { TvPanelCommentThread(pagerIndex, it) }
    }
}

// ============================ 角色 / 制作人员画廊 ============================

/**
 * 角色 / 制作人员胶囊的面板: 完整名单的「查看全部」画廊 —— **与详情页独立页里那两个区块的弹窗是同一个**
 * ([CharactersViewAllDialog] / [StaffViewAllDialog]), 经 LocalViewAllPanelHost 摆进胶囊上方的叠层 (见 TvPlayerDetailLayers).
 * 这两类内容在播放器里没有别的入口 (内嵌详情页是精简版, 没有这两个区块).
 * 画廊是胶囊的面板, 不由它自己关 (返回回胶囊); 点人物不关画廊, 人叠在上面一层, 退回来还是这张画廊那一格.
 *
 * 数据与详情层 / 选集条共用 subjectDetailsStateLoader (进屏已预载); 还没到、或名单是空的时不画.
 */
@Composable
internal fun TvPlayerPeopleGallery(
    vm: EpisodeViewModel,
    panel: TvPlayerPanel,
) {
    val detailsState = vm.episodeDetailsState
    LaunchedEffect(Unit) { vm.ensureTvSubjectDetails() }
    val uiState by detailsState.subjectDetailsStateLoader.state
        .collectAsStateWithLifecycle(SubjectDetailsLoadState.Placeholder(detailsState.subjectId))
    val details = (uiState as? SubjectDetailsLoadState.Ok)?.value ?: return
    val detailsUiState by details.uiState.collectAsStateWithLifecycle()
    when (panel) {
        TvPlayerPanel.CHARACTERS -> {
            val characters = details.charactersPager.collectAsLazyPagingItemsWithLifecycle()
            if (characters.itemCount > 0) {
                CharactersViewAllDialog(
                    allCharacters = characters,
                    totalCharactersCount = detailsUiState.totalCharactersCount,
                    onDismissRequest = {},
                    onBeforeOpenPreview = {},
                )
            }
        }

        TvPlayerPanel.STAFF -> {
            val staff = details.staffPager.collectAsLazyPagingItemsWithLifecycle()
            if (staff.itemCount > 0) {
                StaffViewAllDialog(
                    allStaff = staff,
                    totalStaffCount = detailsUiState.totalStaffCount,
                    onDismissRequest = {},
                    onBeforeOpenPreview = {},
                )
            }
        }

        else -> Unit
    }
}

// ============================ 弹幕发送入口 ============================

/**
 * 这一集的弹幕没加载出来: 请求本身出错了 (网络错误、超时、服务出错; 不是"没匹配到"), 手上也一条弹幕都没有.
 *
 * 远程失败但本地缓存的弹幕照常在屏上时不算 —— 那时远程那条结果根本不会发出来 (见 DanmakuLoaderImpl),
 * 看起来也没有任何问题. [DanmakuLoadingState.Failed] 是本地库出错那一路, 同样算没加载出来.
 */
internal fun DanmakuStatistics.isLoadFailed(): Boolean =
    danmakuLoadingState is DanmakuLoadingState.Failed ||
            (fetchResults.any { it.matchInfo.fetchFailed } && fetchResults.none { it.matchInfo.count > 0 })

/**
 * 这一集的弹幕还没好: 正在请求, 或者请求还没发出去 (要等视频加载出来、拿到时长; 换集时也会先回到这一步) 而手上
 * 一条弹幕都没有 —— 对看的人来说两段都是"弹幕在加载", 胶囊从进播放器起就呼吸, 不是等视频加载完才开始动.
 * 有本地缓存时读到缓存就算加载完 (远程还在后台拉, 屏上已经有弹幕).
 */
internal fun DanmakuStatistics.isLoading(): Boolean = when (danmakuLoadingState) {
    DanmakuLoadingState.Loading -> true
    DanmakuLoadingState.Idle -> fetchResults.none { it.matchInfo.count > 0 }
    else -> false
}

/**
 * 「弹幕」胶囊: 胶囊行末尾那颗, **聚焦浮出弹幕列表面板, 点击向右展开成输入框**发弹幕
 * (点击即进编辑: 自动聚焦弹系统键盘. IME 确认发送后收起, 返回键或键盘被收起时收回胶囊 —— 胶囊相当于搜索页输入框的框,
 * 见 TvTextFieldFrame).
 *
 * 与「评论」那颗同一个模式 (聚焦看, 点击发). 原先是两颗 —— 「弹幕列表」只管浮面板、
 * 「发送弹幕」只管发 —— 那把同一件事的两半摆成了并列入口, 还多占一格横向导航.
 */
@Composable
internal fun TvDanmakuSendEntry(
    overlay: TvPlayerOverlayState,
    danmakuEditorState: DanmakuEditorState,
    vm: EpisodeViewModel,
    /** 弹幕列表面板最底项按下键经此回到本胶囊 (空间搜索会落错按钮导致面板跳变). */
    panelFocusRequester: FocusRequester,
    /** 这一集的弹幕没加载出来 (见 [isLoadFailed]): 图标换成警示图标、文字压暗. */
    loadFailed: Boolean,
    /** 这一集的弹幕正在加载, 且胶囊在屏上 (见 [isLoading]): 胶囊呼吸. */
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val sendFailedText = stringResource(Lang.episode_send_danmaku_failed)
    val quotaExceededText = stringResource(Lang.episode_send_danmaku_quota_exceeded)
    val expanded = overlay.danmakuInputExpanded
    val focus = rememberTvFocusScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var everExpanded by remember { mutableStateOf(false) }
    // 本次展开期间输入框是否真的拿到过焦点. 展开那一刻焦点还在圆钮上、要等一两帧才交到输入框,
    // 这期间不能把"焦点不在输入框"当成用户离开 (否则刚展开就自己收回去了)
    var fieldEverFocused by remember(expanded) { mutableStateOf(false) }
    // 这次收起是因为焦点被挪走 (而不是发送/返回键): 别再把焦点抢回来, 用户正往别处走
    var collapsedByFocusLoss by remember { mutableStateOf(false) }
    val isSending by danmakuEditorState.isSending.collectAsStateWithLifecycle()
    // 「编辑弹幕时暂停视频」(VideoScaffoldConfig.pauseVideoOnEditDanmaku): 输入框展开时正在播就暂停, 收起时只恢复自己停的那一次
    // (展开前本来就暂停着的不动). 离开播放页不恢复: 保留的播放会话不该在后台自己播起来
    var pausedForInput by remember { mutableStateOf(false) }

    // 展开/收起都只发一次语义请求; 目标未组合时由锚点附着事件继续送达.
    LaunchedEffect(expanded) {
        if (expanded) {
            everExpanded = true
            collapsedByFocusLoss = false
            if (vm.videoScaffoldConfig.pauseVideoOnEditDanmaku && vm.player.state.value.playWhenReady) {
                pausedForInput = true
                vm.player.pause()
            }
            focus.request(TvDanmakuSendFocus.FIELD)
        } else if (everExpanded) {
            if (pausedForInput) {
                pausedForInput = false
                vm.player.play()
            }
            keyboard?.hide()
            if (collapsedByFocusLoss) {
                collapsedByFocusLoss = false
                return@LaunchedEffect
            }
            // **无条件把焦点还给胶囊**: 原先只在层级是 CONTROLS 时才还, 而输入框这一收
            // 那个持焦的节点就没了 —— 条件不成立的那条路上焦点直接悬空, 遥控器整个失灵
            // (2026-09-06 用户实测: 发完弹幕焦点不见了, 方向键全不响应)
            focus.request(TvDanmakuSendFocus.BUTTON)
        }
    }

    // 键盘被收起 (输入法多半自己吃掉返回键) 就收回胶囊, 不然光标还留在输入框里, 要再按一次返回.
    // 只认「先看见它弹出、再看见它消失」, 拿不到 IME insets 的形态下静默不生效
    @OptIn(ExperimentalLayoutApi::class)
    val imeVisible = rememberUpdatedState(WindowInsets.isImeVisible)
    LaunchedEffect(expanded) {
        if (!expanded) return@LaunchedEffect
        snapshotFlow { imeVisible.value }.first { it }
        snapshotFlow { imeVisible.value }.first { !it }
        overlay.danmakuInputExpanded = false
    }

    val send: () -> Unit = send@{
        val text = danmakuEditorState.text.trim()
        if (text.isEmpty() || isSending) return@send
        scope.launch {
            val failure = danmakuEditorState.post(
                DanmakuContent(
                    vm.player.currentPositionMillis.value,
                    text = text,
                    color = Color.White.toArgb(),
                    location = DanmakuLocation.NORMAL,
                ),
            )
            // 失败时 post 会把原文放回 text (见 DanmakuEditorState.post), 输入框留着:
            // 失败还把输入框收掉的话, 用户既看不到失败也不知道文字还在
            when (failure) {
                null -> overlay.danmakuInputExpanded = false
                // 应用的发弹幕额度 (所有用户共用) 用完了, 要等弹弹play 重置
                is RepositoryRateLimitedException -> toaster.toast(quotaExceededText)
                else -> toaster.toast(sendFailedText)
            }
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    // 焦点进了弹幕列表面板: 描一圈边 (同其余胶囊). 只在自己翻转时重组
    val selected by remember(overlay) {
        derivedStateOf { overlay.activePanel == TvPlayerPanel.DANMAKU_LIST && overlay.focusRegion.isInsidePillContent }
    }
    TvPillShell(
        // 展开成输入框后即使聚焦也保持深色: 白底上放不下输入光标
        highlighted = focused && !expanded,
        onClick = { if (!expanded) overlay.danmakuInputExpanded = true },
        interactionSource = interactionSource,
        border = if (selected && !focused && !expanded) BorderStroke(TV_PILL_SELECTED_BORDER, Color.White.copy(alpha = 0.85f)) else null,
        // 输入框那一段不压暗也不呼吸: 那时人在打字, 灰掉的只会是输入框前面那个图标, 呼吸则是在打字的地方一闪一闪
        dimmed = loadFailed && !expanded,
        loading = loading && !expanded,
        modifier = modifier
            .focusRequester(panelFocusRequester)
            .tvFocusNavSignal(focus)
            .tvFocusAnchor(
                focus,
                TvDanmakuSendFocus.BUTTON,
                includeDescendants = false,
            )
            // 本胶囊也是面板触发器: 聚焦即浮出弹幕列表面板 (与其余胶囊一致).
            // **展开成输入框那一段不浮面板**: 那时人在打字, 上方再冒出一整块列表既挡视线,
            // 又让上键的去向变得不可预期
            .onFocusChanged { state ->
                if (state.hasFocus) {
                    overlay.activePanel = if (expanded) null else TvPlayerPanel.DANMAKU_LIST
                    return@onFocusChanged
                }
                // 焦点离开这颗胶囊 (hasFocus 含里面的输入框) 就收起输入框.
                //
                // 展开态下根路由会把除返回键以外的所有按键让给 IME (见 TvEpisodeScreen), 而焦点
                // 已经不在输入框上了 —— 表现是遥控器像失灵: 下键拉不出选集条, 只有返回键能救.
                // 方向键能把焦点带出输入框, 所以这条路是真会走到的.
                if (overlay.danmakuInputExpanded && fieldEverFocused) {
                    collapsedByFocusLoss = true
                    overlay.danmakuInputExpanded = false
                }
            },
    ) {
        if (loadFailed && !expanded) {
            TvPillFailedIcon(highlighted = focused)
        } else {
            Icon(Icons.Rounded.Subtitles, null, Modifier.size(TV_PILL_ICON_SIZE))
        }
        if (!expanded) {
            Text(
                // 就叫「弹幕」: 这颗现在既是列表入口也是发送入口, 挂哪一边的名字都偏.
                // 与「评论」并排也整齐 (那颗同样是"聚焦看、点击发")
                stringResource(Lang.episode_danmaku),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        } else {
            BasicTextField(
                value = danmakuEditorState.text,
                onValueChange = { danmakuEditorState.text = it },
                modifier = Modifier
                    .width(240.dp)
                    .tvFocusAnchor(focus, TvDanmakuSendFocus.FIELD)
                    .onFocusChanged {
                        if (it.isFocused) {
                            fieldEverFocused = true
                            overlay.focusRegion = TvPlayerFocusRegion.PILLS
                            keyboard?.show()
                        }
                    },
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                ),
                cursorBrush = SolidColor(Color.White),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
        }
    }
}

private enum class TvDanmakuSendFocus : TvFocusKey {
    BUTTON,
    FIELD,
}
