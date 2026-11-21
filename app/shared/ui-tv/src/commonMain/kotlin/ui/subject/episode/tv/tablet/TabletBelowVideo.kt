/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItemsWithLifecycle
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.subject.RelatedSubjectInfo
import me.him188.ani.app.data.models.subject.preferredDisplayName
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.domain.together.TogetherManager
import me.him188.ani.app.domain.together.TogetherState
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.ui.comment.UIComment
import me.him188.ani.app.ui.comment.UIRichText
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.avatar.AvatarImage
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.tv.FillTvTmdbTextsFromCache
import me.him188.ani.app.ui.foundation.tv.rememberTvTmdbTitles
import me.him188.ani.app.ui.foundation.tv.tvUseOriginalTitle
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.playback_history_episode_label
import me.him188.ani.app.ui.lang.episode_comments
import me.him188.ani.app.ui.lang.subject_collection_doing
import me.him188.ani.app.ui.lang.subject_collection_done
import me.him188.ani.app.ui.lang.subject_collection_dropped
import me.him188.ani.app.ui.lang.subject_collection_on_hold
import me.him188.ani.app.ui.lang.subject_collection_wish
import me.him188.ani.app.ui.lang.subject_airing_total_episodes_completed
import me.him188.ani.app.ui.lang.subject_episode_cache
import me.him188.ani.app.ui.lang.subject_episode_external_links
import me.him188.ani.app.ui.lang.subject_episode_related_recommendations
import me.him188.ani.app.ui.lang.tablet_player_details
import me.him188.ani.app.ui.lang.tablet_player_series
import me.him188.ani.app.ui.lang.video_player_tv_collection
import me.him188.ani.app.ui.lang.watch_together_title
import me.him188.ani.app.ui.richtext.UIRichElement
import me.him188.ani.app.ui.subject.collection.components.EditCollectionTypeDropDown
import me.him188.ani.app.ui.subject.collection.components.EditableSubjectCollectionTypeDialogsHost
import me.him188.ani.app.ui.subject.details.SubjectDetailsLoadState
import me.him188.ani.app.ui.subject.details.components.rememberNavigateToRelatedSubject
import me.him188.ani.app.ui.subject.details.components.renderSubjectRelation
import me.him188.ani.app.ui.subject.details.layout.TvRewatchMenuItem
import me.him188.ani.app.ui.subject.details.layout.rememberTvSubjectRewatch
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.ui.subject.episode.details.components.ShareEpisodeDropdown
import me.him188.ani.app.ui.subject.episode.tv.TvPlayerOverlayState
import me.him188.ani.app.ui.together.TvTogetherEntry
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

/**
 * 视频下面那一块 (分栏与竖屏): 正在看的这一部、这一集, 往下滑.
 *
 * 从上往下: 作品名 + 收藏状态 → 「第 N 集 集名 · ★ 评分 · 全 N 话 … 详情 ›」(点开右栏的详情) → 带字的图标 (下载 · 一起看 · 外部链接)
 * → 评论卡片 (露一条, 点开右栏的评论) → 系列 (关联作品的横图卡片) → 竖屏时再接相关推荐 (横屏在右栏).
 * 选集不在这里 (占位置): 视频底下那一排的「选集」打开侧边的选集页.
 */
@Composable
internal fun TabletBelowVideo(
    vm: EpisodeViewModel,
    page: EpisodePageState,
    overlay: TvPlayerOverlayState,
    tablet: TabletPlayerState,
    geometry: TabletPlayerGeometry,
    modifier: Modifier = Modifier,
) {
    val loadState by vm.episodeDetailsState.subjectDetailsStateLoader.state.collectAsStateWithLifecycle()
    val details = (loadState as? SubjectDetailsLoadState.Ok)?.value
    val episodeList by vm.episodeListUiStateFlow.collectAsStateWithLifecycle()
    val mainEpisodes = episodeList?.mainEpisodes.orEmpty()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    page.subjectPresentation.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TabletCollectionButton(
                    vm, overlay,
                    watched = mainEpisodes.count { it.isDoneOrDropped },
                    total = mainEpisodes.size,
                )
            }
            val episode = page.episodePresentation
            val meta = buildList {
                add(stringResource(Lang.playback_history_episode_label, episode.ep) + " " + episode.title)
                details?.info?.ratingInfo?.score?.takeIf { (it.toFloatOrNull() ?: 0f) > 0f }?.let { add("★ $it") }
                if (mainEpisodes.isNotEmpty()) add(stringResource(Lang.subject_airing_total_episodes_completed, mainEpisodes.size.toString()))
            }.joinToString(" · ")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { tablet.toggle(TabletSidePanel.DETAILS) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(Lang.tablet_player_details), style = MaterialTheme.typography.labelLarge)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, Modifier.size(18.dp))
            }
        }

        TabletActionsRow(vm, page, overlay)

        TabletCommentCard(vm, onClick = { tablet.toggle(TabletSidePanel.COMMENTS) })

        if (details != null) {
            val related = details.relatedSubjectsPager.collectAsLazyPagingItemsWithLifecycle()
            TabletSeriesRow(related)
        }

        // 竖屏没有右栏: 推荐接在最后 (同 YouTube / 哔哩哔哩竖屏)
        if (geometry.sideMode == TabletSideMode.SHEET) {
            Text(stringResource(Lang.subject_episode_related_recommendations), style = MaterialTheme.typography.titleSmall)
            TabletRecommendationRows(vm)
        }
    }
}

/**
 * 收藏状态: 心形 + 「在看 · 07 / 13」(没收藏时写「收藏」), 点开收藏状态的小菜单 (末尾是「重温」), 同电视版那颗.
 * 中性浅底 (同评论卡片), 心形与字用强调色; 不上强调色的底 (整块上色的只留数据源面板的「正在播放」卡片).
 */
@Composable
private fun TabletCollectionButton(
    vm: EpisodeViewModel,
    overlay: TvPlayerOverlayState,
    watched: Int,
    total: Int,
) {
    val state = vm.editableSubjectCollectionTypeState
    val presentation by state.presentationFlow.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    // 小菜单与「看过」确认对话框开着时控件不自动隐藏 (同电视版)
    if (expanded || presentation.showSetAllEpisodesDoneDialog) {
        DisposableEffect(Unit) {
            overlay.onPopupExpandedChanged(true)
            onDispose { overlay.onPopupExpandedChanged(false) }
        }
    }
    EditableSubjectCollectionTypeDialogsHost(state)
    val collected = presentation.selfCollectionType != UnifiedCollectionType.NOT_COLLECTED
    val label = if (collected) {
        presentation.selfCollectionType.tabletLabel() +
            (if (total > 0) " · ${watched.toString().padStart(2, '0')} / ${total.toString().padStart(2, '0')}" else "")
    } else {
        stringResource(Lang.video_player_tv_collection)
    }
    Box {
        Row(
            Modifier
                .height(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { expanded = true }
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (collected) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = null,
                Modifier.size(16.dp),
                tint = tabletAccent,
            )
            Text(label, style = MaterialTheme.typography.labelLarge, color = tabletAccent, maxLines = 1)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, Modifier.size(18.dp), tint = tabletAccent)
        }
        val rewatch = rememberTvSubjectRewatch(vm.subjectId)
        val scope = rememberCoroutineScope()
        val toaster = LocalToaster.current
        EditCollectionTypeDropDown(
            currentType = presentation.selfCollectionType.takeUnless { rewatch.rewatching },
            expanded = expanded,
            onDismissRequest = { expanded = false },
            onClick = { scope.launch { state.setSelfCollectionType(it.type)?.let(toaster::showLoadError) } },
            trailingItems = {
                TvRewatchMenuItem(rewatch, presentation.selfCollectionType, onDismissRequest = { expanded = false })
            },
        )
    }
}

/** 收藏状态的短名 (在看 / 想看 / 看过 / 搁置 / 抛弃). */
@Composable
private fun UnifiedCollectionType.tabletLabel(): String = when (this) {
    UnifiedCollectionType.WISH -> stringResource(Lang.subject_collection_wish)
    UnifiedCollectionType.DOING -> stringResource(Lang.subject_collection_doing)
    UnifiedCollectionType.DONE -> stringResource(Lang.subject_collection_done)
    UnifiedCollectionType.ON_HOLD -> stringResource(Lang.subject_collection_on_hold)
    UnifiedCollectionType.DROPPED -> stringResource(Lang.subject_collection_dropped)
    UnifiedCollectionType.NOT_COLLECTED -> ""
}

/**
 * 一排带字的图标: 下载 (这一部的下载管理) · 一起看 (在房间里时图标淡紫) · 外部链接 (复制地址、外部应用播放、打开来源网页).
 * 全屏时这一排看不到, 一起看与外部链接在画面上的 ⋮ 里; 播放信息在 ⋮ 里.
 */
@Composable
private fun TabletActionsRow(vm: EpisodeViewModel, page: EpisodePageState, overlay: TvPlayerOverlayState) {
    val navigator = LocalNavigator.current
    var sharing by remember { mutableStateOf(false) }
    if (sharing) {
        DisposableEffect(Unit) {
            overlay.onPopupExpandedChanged(true)
            onDispose { overlay.onPopupExpandedChanged(false) }
        }
    }
    val together = remember { KoinPlatform.getKoin().getOrNull<TogetherManager>() }
    val inRoom = together?.state?.collectAsStateWithLifecycle()?.value is TogetherState.InRoom
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TabletActionButton(Icons.Rounded.Download, stringResource(Lang.subject_episode_cache)) {
            navigator.navigateSubjectCaches(vm.subjectId)
        }
        TabletActionButton(Icons.Rounded.SyncAlt, stringResource(Lang.watch_together_title), tint = if (inRoom) tabletAccent else null) {
            TvTogetherEntry.open()
        }
        Box {
            TabletActionButton(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(Lang.subject_episode_external_links)) {
                sharing = true
            }
            ShareEpisodeDropdown(page.shareData, sharing, onDismissRequest = { sharing = false })
        }
    }
}

@Composable
private fun TabletActionButton(icon: ImageVector, label: String, tint: Color? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .widthIn(min = 64.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = tint ?: LocalContentColor.current)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 评论卡片 (同 YouTube): 「评论 N」+ 第一条的头像与一行正文, 点开右栏的本集评论. 还没有评论时只写标题. */
@Composable
private fun TabletCommentCard(vm: EpisodeViewModel, onClick: () -> Unit) {
    val state = vm.episodeCommentState
    val comments = state.list.collectAsLazyPagingItemsWithLifecycle()
    val first: UIComment? = if (comments.itemCount > 0) comments.peek(0) else null
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Lang.episode_comments), style = MaterialTheme.typography.titleSmall)
            state.count?.let {
                Spacer(Modifier.width(6.dp))
                Text(it.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (first != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AvatarImage(first.author?.avatarUrl, Modifier.size(22.dp).clip(CircleShape))
                Text(
                    remember(first) { first.content.tabletPlainText() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 富文本里的纯文字 (图片 / 贴纸 / 引用跳过), 给评论卡片的一行摘要用. */
internal fun UIRichText.tabletPlainText(): String =
    elements.asSequence()
        .filterIsInstance<UIRichElement.AnnotatedText>()
        .flatMap { it.slice }
        .filterIsInstance<UIRichElement.Annotated.Text>()
        .joinToString("") { it.content }
        .replace('\n', ' ')
        .trim()

/** 系列: 关联作品 (前传、续集、番外、剧场版……) 的横图卡片, 一排横滑; 点卡片打开那一部的详情页. 没有关联作品时整块不显示. */
@Composable
private fun TabletSeriesRow(related: LazyPagingItems<RelatedSubjectInfo>) {
    if (related.itemCount == 0) return
    val navigateToRelated = rememberNavigateToRelatedSubject()
    val useOriginalTitle = tvUseOriginalTitle()
    val tmdbTitles = rememberTvTmdbTitles()
    val snapshot = related.itemSnapshotList
    FillTvTmdbTextsFromCache(remember(snapshot) { snapshot.mapNotNull { it?.subjectId } })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Lang.tablet_player_series), style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 20.dp)) {
            items(related.itemCount) { index ->
                val info = related[index] ?: return@items
                TabletHeroCard(
                    subjectId = info.subjectId,
                    originalName = info.name.orEmpty(),
                    posterUrl = info.image,
                    title = tmdbTitles[info.subjectId] ?: info.preferredDisplayName(useOriginalTitle),
                    badge = info.relation?.let { renderSubjectRelation(it) },
                    onClick = { navigateToRelated(info) },
                )
            }
        }
    }
}

/**
 * 系列的横图卡片: [TabletHeroImage] + 下面一行标题.
 */
@Composable
internal fun TabletHeroCard(
    subjectId: Int,
    originalName: String,
    posterUrl: String?,
    title: String,
    badge: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.width(176.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TabletHeroImage(subjectId, originalName, posterUrl, badge, Modifier.fillMaxWidth())
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/**
 * 横图 (系列卡片、推荐行共用): TMDB 的横版背景图 (hero 那张), 没有时用海报裁成横的; 左上角可带一个小标签 (关系).
 * 宽由调用方定, 高按 16:9.
 */
@Composable
internal fun TabletHeroImage(
    subjectId: Int,
    originalName: String,
    posterUrl: String?,
    badge: String?,
    modifier: Modifier = Modifier,
) {
    val tmdb = remember { GlobalKoin.get<TmdbImageService>() }
    val backdrop by produceState(tmdb.peekBackdropUrl(subjectId), subjectId, originalName) {
        if (value == null && originalName.isNotBlank()) {
            value = runCatching { tmdb.getBackdropUrl(subjectId, originalName) }.getOrNull()
        }
    }
    val nsfw = rememberNsfwPolicy()
    Box(
        modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        AsyncImage(
            backdrop ?: posterUrl,
            contentDescription = null,
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            downsampleLongEdgePx = nsfw.coverDownsample(subjectId),
        )
        if (badge != null) {
            Box(
                Modifier.padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(badge, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
            }
        }
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(24.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.25f)))),
        )
    }
}
