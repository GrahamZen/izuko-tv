/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.tvPrefersTmdbText
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniFocusActionButton
import me.him188.ani.app.ui.foundation.widgets.AniScrollableTextDialog
import me.him188.ani.app.ui.foundation.widgets.CENTERED_PANEL_TITLE_GAP
import me.him188.ani.app.ui.foundation.widgets.aniFocusContainerColor
import me.him188.ani.app.ui.foundation.widgets.aniFocusContentColor
import me.him188.ani.app.ui.foundation.widgets.aniGlassButtonColor
import me.him188.ani.app.ui.foundation.widgets.aniGlassEdge
import me.him188.ani.app.ui.foundation.widgets.aniPanelTitleStyle
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.all_episodes_hint_remote
import me.him188.ani.app.ui.lang.all_episodes_hint_touch
import me.him188.ani.app.ui.lang.all_episodes_tab_all
import me.him188.ani.app.ui.lang.all_episodes_tab_specials
import me.him188.ani.app.ui.lang.all_episodes_view_numbers
import me.him188.ani.app.ui.lang.all_episodes_view_stills
import me.him188.ani.app.ui.lang.subject_details_episodes
import me.him188.ani.app.ui.lang.subject_details_main_episodes
import me.him188.ani.app.ui.lang.subject_details_no_summary
import me.him188.ani.app.ui.lang.subject_episode_cache
import me.him188.ani.app.ui.lang.subject_episode_close
import me.him188.ani.app.ui.lang.subject_episode_mark_watched
import me.him188.ani.app.ui.lang.subject_episode_now_playing
import me.him188.ani.app.ui.lang.subject_episode_unwatch
import me.him188.ani.app.ui.subject.details.sections.episodeMetaText
import me.him188.ani.app.ui.subject.details.sections.episodeStillImageUrl
import me.him188.ani.app.ui.subject.details.sections.mergedEpisodeDesc
import me.him188.ani.app.ui.subject.details.sections.tvEpisodeName
import me.him188.ani.app.ui.subject.episode.list.EpisodeListItem
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import org.jetbrains.compose.resources.stringResource

/**
 * 全部选集 (电视、平板、iPad 同一个): 居中玻璃面板. 标题行左边「选集」(不写条目名: 就是从这一部的详情页开的), 右边一排同高的控件:
 * 有特别篇时的分段「全部 / 正片 / 特别篇」(带集数)、「剧照卡 / 只显示集号」的切换 (长番剧照卡要翻很久; 选过的记到进程结束,
 * 换一部再开还是那种)、「下载」, 触屏再加关闭 (遥控器按返回关). 下面是选集网格 ([TvAllEpisodesGrid]: 安卓上是原生的, 长番按住连翻也顺;
 * iOS 上是 Compose 版): 剧照卡 (正在播放那张换主色底、集号前画声浪) 或一格一个集号的密排格; 打开 / 切换时正在播放那一集滚进来,
 * 滚不完的那边渐隐; 最底下一行左边写正在播放第几集, 右边是操作提示.
 * 点 (确定) 播放, 长按 (按住确定) 开本集详情, 详情里左右换集. 切换也是点 (确定) 了才换.
 *
 * 遥控器上分两层: 外层是标题行的切换与「下载」, 里层是选集格.
 * - 打开时直接进里层, 落在正在播放那一集 (没有就第一格).
 * - 里层按返回先回到切换 (有分段落在选中的分段, 没有就落在剧照卡 / 集号的选中那段), 外层再按返回才关弹窗.
 * - 外层按下回到里层刚才停的那一格 (换了分段不在了就落在正在播放那一集), 不按位置找.
 * 示焦照 FocusHighlight.kt 的约定: 剧照卡 (大块内容) 画主题色描边, 集号格、分段、「下载」这些小控件铺实底; 触屏不画聚焦 (同玻璃弹窗).
 */
@Composable
internal fun TvAllEpisodesDialog(
    mainEpisodes: List<EpisodeListItem>,
    specialEpisodes: List<EpisodeListItem>,
    currentEpisodeId: Int?,
    stills: Map<Int, String>,
    playProgress: Map<Int, Float>,
    runtimes: Map<Int, Int>,
    overviews: Map<Int, String>,
    names: Map<Int, String>,
    onPlay: (EpisodeListItem) -> Unit,
    onSetCollectionType: (EpisodeListItem, UnifiedCollectionType) -> Unit,
    onClickCache: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val touch = LocalTvTouchInputEnabled.current
    var detailsTarget by remember { mutableStateOf<EpisodeListItem?>(null) }
    var tab by rememberSaveable { mutableStateOf(TvAllEpisodesTab.All) }
    val numberMode = tvAllEpisodesNumberMode
    val all = remember(mainEpisodes, specialEpisodes) { mainEpisodes + specialEpisodes }
    val shown = when (tab) {
        TvAllEpisodesTab.All -> all
        TvAllEpisodesTab.Main -> mainEpisodes
        TvAllEpisodesTab.Specials -> specialEpisodes
    }
    val current = all.firstOrNull { it.episodeId == currentEpisodeId }
    val nowPlaying = stringResource(Lang.subject_episode_now_playing)
    // 标题行也放在内容里 (不用面板的标题槽): 它要用到下面建在弹窗窗口里的送焦作用域与网格状态
    AniCenteredPanelDialog(onDismissRequest = onDismiss) {
        // 建在弹窗内容里: 送焦按这个窗口有没有焦点来等 (见 ViewAllPanelDialog)
        val focus = rememberTvFocusScope()
        // 里层最后停的那一格 (从切换按下时回到这里); 打开时是正在播放那一集
        var lastFocusedId by remember { mutableStateOf(currentEpisodeId) }
        // 焦点在外面时网格选中 (滚进来) 的那一格, 焦点送进去就落在它上面: 里层刚才停的那格, 换了分段不在了就是正在播放那一集
        val gridTargetIndex = shown.indexOfFirst { it.episodeId == lastFocusedId }.takeIf { it >= 0 }
            ?: shown.indexOfFirst { it.episodeId == currentEpisodeId }.coerceAtLeast(0)
        LaunchedEffect(Unit) { if (!touch) focus.request(TvAllEpisodesGridKey) }
        // 里层按返回回到的那段: 有分段是选中的分段, 没有就是剧照卡 / 集号的选中那段
        val backTarget = if (touch) Modifier else Modifier.tvFocusAnchor(focus, TvAllEpisodesSwitchKey)
        Column(verticalArrangement = Arrangement.spacedBy(CENTERED_PANEL_TITLE_GAP)) {
            Row(
                Modifier.fillMaxWidth()
                    // 外层按下: 回到里层刚才停的那一格 (见 KDoc; 网格已经把它选中、滚进来了)
                    .onPreviewKeyEvent { event ->
                        if (touch || event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown) focus.request(TvAllEpisodesGridKey)
                        true
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(Lang.subject_details_episodes),
                    Modifier.weight(1f),
                    style = aniPanelTitleStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (specialEpisodes.isNotEmpty()) {
                    TvAllEpisodesTabs(
                        selected = tab,
                        counts = mapOf(
                            TvAllEpisodesTab.All to all.size,
                            TvAllEpisodesTab.Main to mainEpisodes.size,
                            TvAllEpisodesTab.Specials to specialEpisodes.size,
                        ),
                        onSelect = { tab = it },
                        selectedModifier = backTarget,
                    )
                }
                TvAllEpisodesViewToggle(
                    numbers = numberMode,
                    onChange = { tvAllEpisodesNumberMode = it },
                    selectedModifier = if (specialEpisodes.isEmpty()) backTarget else Modifier,
                )
                if (onClickCache != null) {
                    TvAllEpisodesGlassButton(
                        onClick = {
                            onDismiss()
                            onClickCache()
                        },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, Modifier.size(18.dp))
                            Text(stringResource(Lang.subject_episode_cache), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                if (touch) {
                    TvAllEpisodesGlassButton(onClick = onDismiss, Modifier.width(TV_ALL_EPISODES_CONTROL_HEIGHT)) {
                        Icon(Icons.Rounded.Close, stringResource(Lang.subject_episode_close), Modifier.size(20.dp))
                    }
                }
            }
            TvAllEpisodesGrid(
                episodes = shown,
                numbers = numberMode,
                selectedIndex = gridTargetIndex,
                currentEpisodeId = currentEpisodeId,
                stills = stills,
                names = names,
                playProgress = playProgress,
                touch = touch,
                onFocused = { index -> shown.getOrNull(index)?.let { lastFocusedId = it.episodeId } },
                onClick = { index ->
                    shown.getOrNull(index)?.let {
                        onDismiss()
                        onPlay(it)
                    }
                },
                onLongPress = { index -> shown.getOrNull(index)?.let { detailsTarget = it } },
                modifier = Modifier.weight(1f, fill = false)
                    .then(if (touch) Modifier else Modifier.tvFocusAnchor(focus, TvAllEpisodesGridKey))
                    // 里层按返回: 先回到切换, 那里再按返回才关弹窗 (按下与抬起都吞掉, 弹窗自己收不到)
                    .onPreviewKeyEvent { event ->
                        if (touch || event.key !in TV_ALL_EPISODES_BACK_KEYS) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyUp) focus.request(TvAllEpisodesSwitchKey)
                        true
                    },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f)) {
                    current?.let {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.GraphicEq, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(
                                "$nowPlaying ${it.sort}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Text(
                    stringResource(if (touch) Lang.all_episodes_hint_touch else Lang.all_episodes_hint_remote),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
    detailsTarget?.let { target ->
        TvEpisodeDetailsDialog(
            target,
            // 正片与特别篇连着翻, 同网格里的先后
            episodes = all,
            onNavigate = { detailsTarget = it },
            stillUrl = stills[target.episodeId],
            runtime = runtimes[target.episodeId],
            overview = overviews[target.episodeId],
            names = names,
            onSetCollectionType = { type -> onSetCollectionType(target, type) },
            onDismiss = { detailsTarget = null },
        )
    }
}

/**
 * 本集详情 (长按选集卡开): 剧照做满幅背景, 完整简介可滚, 底部唯一的按钮按当前状态是「标记看过」或「取消看过」(同选集行长按的那个).
 * 两侧箭头 / 左右滑 / 左右键原地换成 [episodes] 里相邻那一集 ([onNavigate]), 到头那一侧不动.
 */
@Composable
internal fun TvEpisodeDetailsDialog(
    target: EpisodeListItem,
    episodes: List<EpisodeListItem>,
    onNavigate: (EpisodeListItem) -> Unit,
    stillUrl: String?,
    runtime: Int?,
    overview: String?,
    names: Map<Int, String>,
    onSetCollectionType: (UnifiedCollectionType) -> Unit,
    onDismiss: () -> Unit,
) {
    val watched = target.isDoneOrDropped
    val index = episodes.indexOfFirst { it.episodeId == target.episodeId }
    val background: (@Composable BoxScope.() -> Unit)? = if (stillUrl == null) {
        null
    } else {
        {
            AsyncImage(
                episodeStillImageUrl(stillUrl),
                contentDescription = null,
                Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                decodeAtOriginalSize = true,
            )
        }
    }
    AniScrollableTextDialog(
        title = "${target.sort}. ${target.tvEpisodeName(names)}",
        text = mergedEpisodeDesc(overview, target.desc, tmdbOnly = tvPrefersTmdbText())
            .ifBlank { stringResource(Lang.subject_details_no_summary) },
        onDismissRequest = onDismiss,
        onHorizontalNav = { delta -> episodes.getOrNull(index + delta)?.takeIf { index >= 0 }?.let(onNavigate) },
        canHorizontalNav = { delta -> index >= 0 && index + delta in episodes.indices },
        background = background,
        meta = episodeMetaText(runtime, target.airDate),
        aspectRatio = TV_EPISODE_STILL_RATIO,
        action = { modifier ->
            AniFocusActionButton(
                onClick = {
                    onSetCollectionType(if (watched) UnifiedCollectionType.NOT_COLLECTED else UnifiedCollectionType.DONE)
                    onDismiss()
                },
                modifier = modifier,
            ) {
                Text(
                    stringResource(if (watched) Lang.subject_episode_unwatch else Lang.subject_episode_mark_watched),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}

/**
 * 触屏 ([touch]) 上弹窗里的东西一律不可聚焦: 只有点, 没有焦点 (接了键盘也不会有个格子悄悄聚焦着、按确定就播了那一集).
 * 挂在点击之前 (管的是后面那个可聚焦节点).
 */
private fun Modifier.tvAllEpisodesTouchNoFocus(touch: Boolean): Modifier =
    if (touch) focusProperties { canFocus = false } else this

/** 全部选集的分段: 全部 / 正片 / 特别篇. */
private enum class TvAllEpisodesTab { All, Main, Specials }

/** 全部选集用「只显示集号」的样子 (见 [TvAllEpisodesViewToggle]); 记到进程结束, 换一部再开还是上次那种. */
private var tvAllEpisodesNumberMode by mutableStateOf(false)

/**
 * 「剧照卡 / 只显示集号」的切换: 同分段标签的样子 (淡底胶囊里两段, 选中的垫亮一档的胶囊), 每段一个图标.
 * [selectedModifier] 挂在选中的那段上 (里层按返回落到这里).
 */
@Composable
private fun TvAllEpisodesViewToggle(
    numbers: Boolean,
    onChange: (Boolean) -> Unit,
    selectedModifier: Modifier,
) {
    TvAllEpisodesSegments {
        listOf(
            Triple(false, Icons.Rounded.GridView, Lang.all_episodes_view_stills),
            Triple(true, Icons.Rounded.Apps, Lang.all_episodes_view_numbers),
        ).forEach { (value, icon, label) ->
            val isSelected = value == numbers
            TvAllEpisodesSegment(
                selected = isSelected,
                onClick = { onChange(value) },
                modifier = if (isSelected) selectedModifier else Modifier,
            ) { contentColor ->
                Icon(
                    icon,
                    contentDescription = stringResource(label),
                    Modifier.padding(horizontal = 14.dp).size(20.dp),
                    tint = contentColor,
                )
            }
        }
    }
}

/**
 * 分段标签: 一条淡底胶囊里排着「全部 12」「正片 11」「特别篇 1」, 选中的那段垫一块亮一档的胶囊.
 * [selectedModifier] 挂在选中的那段上 (里层按返回落到这里).
 */
@Composable
private fun TvAllEpisodesTabs(
    selected: TvAllEpisodesTab,
    counts: Map<TvAllEpisodesTab, Int>,
    onSelect: (TvAllEpisodesTab) -> Unit,
    selectedModifier: Modifier,
) {
    TvAllEpisodesSegments {
        TvAllEpisodesTab.entries.forEach { tab ->
            val label = stringResource(
                when (tab) {
                    TvAllEpisodesTab.All -> Lang.all_episodes_tab_all
                    TvAllEpisodesTab.Main -> Lang.subject_details_main_episodes
                    TvAllEpisodesTab.Specials -> Lang.all_episodes_tab_specials
                },
            )
            val isSelected = tab == selected
            TvAllEpisodesSegment(
                selected = isSelected,
                onClick = { onSelect(tab) },
                modifier = if (isSelected) selectedModifier else Modifier,
            ) { contentColor ->
                Text(
                    "$label ${counts[tab] ?: 0}",
                    Modifier.padding(horizontal = 18.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 分段控件的淡底胶囊: 与标题行的玻璃按钮同高 ([TV_ALL_EPISODES_CONTROL_HEIGHT]), 里面每段铺满高. */
@Composable
private fun TvAllEpisodesSegments(content: @Composable () -> Unit) {
    Row(
        Modifier.height(TV_ALL_EPISODES_CONTROL_HEIGHT)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = TV_ALL_EPISODES_TABS_TRACK_ALPHA))
            .padding(TV_ALL_EPISODES_TABS_INSET),
    ) {
        content()
    }
}

/**
 * 分段里的一段: 选中的垫亮一档的胶囊; 遥控器聚焦铺实底 (小控件的示焦, 见 FocusHighlight.kt). 点 (确定) 了才换.
 * [modifier] 挂在最外层 (如里层按返回的落点).
 */
@Composable
private fun TvAllEpisodesSegment(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (contentColor: Color) -> Unit,
) {
    val touch = LocalTvTouchInputEnabled.current
    var focused by remember { mutableStateOf(false) }
    val showFocus = focused && !touch
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxHeight()
            .clip(CircleShape)
            .background(
                when {
                    showFocus -> aniFocusContainerColor()
                    selected -> colors.surfaceBright
                    else -> Color.Transparent
                },
            )
            .onFocusChanged { focused = it.isFocused }
            .tvAllEpisodesTouchNoFocus(touch)
            .clickable(interactionSource = null, indication = if (touch) LocalIndication.current else null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content(
            when {
                showFocus -> aniFocusContentColor()
                selected -> colors.onSurface
                else -> colors.onSurfaceVariant
            },
        )
    }
}

/**
 * 弹窗标题行右端的玻璃按钮 (「下载」胶囊、关闭圆钮): 高 [TV_ALL_EPISODES_CONTROL_HEIGHT], 淡白底 + 细高光边, 同面板弹窗的按钮;
 * 遥控器聚焦铺实底.
 */
@Composable
private fun TvAllEpisodesGlassButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val touch = LocalTvTouchInputEnabled.current
    var focused by remember { mutableStateOf(false) }
    val showFocus = focused && !touch
    Box(
        modifier.height(TV_ALL_EPISODES_CONTROL_HEIGHT)
            .clip(CircleShape)
            .background(if (showFocus) aniFocusContainerColor() else aniGlassButtonColor())
            .border(aniGlassEdge(), CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .tvAllEpisodesTouchNoFocus(touch)
            .clickable(interactionSource = null, indication = if (touch) LocalIndication.current else null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (showFocus) aniFocusContentColor() else MaterialTheme.colorScheme.onSurface,
            content = content,
        )
    }
}

/** 全部选集的送焦目标: 里层要回到的那一格 / 外层按返回落到的那段切换 (见 [TvAllEpisodesDialog]). */
private val TvAllEpisodesGridKey = TvFocusKey("allEpisodesGrid")
private val TvAllEpisodesSwitchKey = TvFocusKey("allEpisodesSwitch")

/** 里层按返回回到切换的那些键 (同各处的返回键). */
private val TV_ALL_EPISODES_BACK_KEYS = setOf(Key.Back, Key.Escape)

/** 本集详情面板的宽高比 (同 TMDB 剧照 16:9, 剧照铺满不裁). */
private const val TV_EPISODE_STILL_RATIO = 16f / 9f

/** 分段控件的淡底与内缩. */
private const val TV_ALL_EPISODES_TABS_TRACK_ALPHA = 0.08f
private val TV_ALL_EPISODES_TABS_INSET = 4.dp

/** 标题行控件 (分段、切换、「下载」、关闭) 统一的高. */
private val TV_ALL_EPISODES_CONTROL_HEIGHT = 44.dp
