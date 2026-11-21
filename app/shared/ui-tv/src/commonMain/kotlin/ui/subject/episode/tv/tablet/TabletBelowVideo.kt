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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItemsWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.subject.ContinueWatchingStatus
import me.him188.ani.app.data.models.subject.RelatedSubjectInfo
import me.him188.ani.app.data.models.subject.preferredDisplayName
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.together.TogetherManager
import me.him188.ani.app.domain.together.TogetherState
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.ui.comment.UIComment
import me.him188.ani.app.ui.comment.UIRichText
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.avatar.AvatarImage
import me.him188.ani.app.ui.foundation.icons.AniIcons
import me.him188.ani.app.ui.foundation.icons.SubtitleGear
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.theme.glassContainerColor
import me.him188.ani.app.ui.foundation.tv.FillTvTmdbTextsFromCache
import me.him188.ani.app.ui.foundation.tv.rememberTvTmdbTitles
import me.him188.ani.app.ui.foundation.tv.tvGridBleed
import me.him188.ani.app.ui.foundation.tv.tvUseOriginalTitle
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.playback_history_episode_label
import me.him188.ani.app.ui.lang.episode_comments
import me.him188.ani.app.ui.lang.episode_danmaku
import me.him188.ani.app.ui.lang.subject_collection_doing
import me.him188.ani.app.ui.lang.subject_collection_done
import me.him188.ani.app.ui.lang.subject_collection_dropped
import me.him188.ani.app.ui.lang.subject_collection_on_hold
import me.him188.ani.app.ui.lang.subject_collection_rewatching
import me.him188.ani.app.ui.lang.subject_collection_wish
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
import me.him188.ani.app.ui.subject.collection.components.SubjectCollectionActionsForCollect
import me.him188.ani.app.ui.subject.details.SubjectDetailsLoadState
import me.him188.ani.app.ui.subject.details.components.COVER_WIDTH_TO_HEIGHT_RATIO
import me.him188.ani.app.ui.subject.details.components.rememberNavigateToRelatedSubject
import me.him188.ani.app.ui.subject.details.components.renderSubjectRelation
import me.him188.ani.app.ui.subject.details.layout.TvRewatchIcon
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
 * 从上往下: 作品名 + 收藏状态 → 「第 N 集 集名 · ★ 评分 … 详情 ›」(点开右栏的详情)
 * → 带字的图标 (弹幕 · 下载 · 一起看 · 外部链接) → 选集 (标题行写连载状态与总集数、点开右栏的完整选集, 下面一排集卡片, 见 [TabletEpisodeSection])
 * → 竖屏时当前数据源卡片 (横屏在右栏顶上)
 * → 评论卡片 (露一条, 点开右栏的评论) → 系列 (关联作品的封面卡片) → 竖屏时再接相关推荐 (横屏在右栏).
 *
 * 非全屏时会在侧边 (右栏 / 竖屏盖住这一块的面板) 打开的页, 入口都在画面外面 (这里与右栏), 画面上只放管播放本身的按钮
 * (同哔哩哔哩: 竖屏 / 分栏的播放器只管播放, 选集、追番等在视频下面; 全屏看不到这些时才搬进画面).
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

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = TABLET_BELOW_VIDEO_SIDE_PADDING, vertical = 12.dp),
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
                TabletCollectionButton(vm, overlay)
            }
            val episode = page.episodePresentation
            val meta = buildList {
                add(stringResource(Lang.playback_history_episode_label, episode.ep) + " " + episode.title)
                details?.info?.ratingInfo?.score?.takeIf { (it.toFloatOrNull() ?: 0f) > 0f }?.let { add("★ $it") }
            }.joinToString(" · ")
            Row(
                Modifier.fillMaxWidth().tabletTextClickable { tablet.toggle(TabletSidePanel.DETAILS) },
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

        TabletActionsRow(vm, page, overlay, tablet)

        TabletEpisodeSection(vm, tablet)

        // 竖屏没有右栏: 当前数据源接在这一排下面 (横屏在右栏顶上)
        if (geometry.sideMode == TabletSideMode.SHEET) {
            TabletSourceSummaryCard(page, tablet, Modifier.fillMaxWidth())
        }

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
 * 收藏状态: 没收藏是空心心形「收藏」, 收藏了是那一态自己的图标 (同详情页与下拉菜单) + 那一态的名字「在看」;
 * 点开收藏状态的小菜单 (末尾是「重温」), 同电视版那颗. 只写状态不写看到第几集 (同哔哩哔哩「已追番」): 正在看哪一集标题行上写着,
 * 看到哪里在选集列表里.
 * 各状态 (含没收藏) 都是中性浅底 (同评论卡片与视频下面那一排) 与普通字色, 不抢画面; 没收藏与收藏了靠图标与字区分.
 *
 * 两处用它: 视频下面作品名旁边, 与全屏时画面左上标题下面 (同哔哩哔哩全屏的「追番」). 视频下面那一块全屏时也留在组合里,
 * 「看过」确认对话框只由它挂 ([hostsDialogs]), 画面上那颗不再挂一份, 免得弹两次.
 */
@Composable
internal fun TabletCollectionButton(
    vm: EpisodeViewModel,
    overlay: TvPlayerOverlayState,
    hostsDialogs: Boolean = true,
) {
    val state = vm.editableSubjectCollectionTypeState
    val presentation by state.presentationFlow.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    // 小菜单与「看过」确认对话框开着时控件不自动隐藏 (同电视版)
    if (expanded || (hostsDialogs && presentation.showSetAllEpisodesDoneDialog)) {
        DisposableEffect(Unit) {
            overlay.onPopupExpandedChanged(true)
            onDispose { overlay.onPopupExpandedChanged(false) }
        }
    }
    if (hostsDialogs) EditableSubjectCollectionTypeDialogsHost(state)
    // 重温中显示「重温中」, 菜单里不勾在看 (见 TvSubjectRewatch)
    val rewatch = rememberTvSubjectRewatch(vm.subjectId)
    val collected = presentation.selfCollectionType != UnifiedCollectionType.NOT_COLLECTED
    val label = when {
        rewatch.rewatching -> stringResource(Lang.subject_collection_rewatching)
        collected -> presentation.selfCollectionType.tabletLabel()
        else -> stringResource(Lang.video_player_tv_collection)
    }
    val container = glassContainerColor()
    val content = MaterialTheme.colorScheme.onSurface
    val stateIcon = remember(presentation.selfCollectionType) {
        SubjectCollectionActionsForCollect.find { it.type == presentation.selfCollectionType }?.icon
    }
    Box {
        Row(
            Modifier
                .height(32.dp)
                .clip(CircleShape)
                .background(container)
                .clickable { expanded = true }
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (rewatch.rewatching || (collected && stateIcon != null)) {
                // 那一态的图标 (默认 24dp) 收进 16dp 的格子, 颜色跟着字
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    CompositionLocalProvider(LocalContentColor provides content) {
                        if (rewatch.rewatching) TvRewatchIcon() else stateIcon?.invoke()
                    }
                }
            } else {
                Icon(Icons.Rounded.FavoriteBorder, contentDescription = null, Modifier.size(16.dp), tint = content)
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, Modifier.size(18.dp), tint = content)
        }
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
 * 一排带字的图标: 弹幕 (弹幕列表与设置, 在侧边打开, 开着时图标是主题色, 再点收起)
 * · 下载 (这一部的下载管理) · 一起看 (在房间里时图标是主题色) · 外部链接 (复制地址、外部应用播放、打开来源网页).
 * 选集在这一排下面单独一节 ([TabletEpisodeSection]).
 * 全屏时这一排看不到: 弹幕在画面底下那一排, 一起看与外部链接在画面上的 ⋮ 里.
 */
@Composable
private fun TabletActionsRow(vm: EpisodeViewModel, page: EpisodePageState, overlay: TvPlayerOverlayState, tablet: TabletPlayerState) {
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
        TabletActionButton(
            AniIcons.SubtitleGear,
            stringResource(Lang.episode_danmaku),
            tint = if (tablet.shownPanel == TabletSidePanel.DANMAKU) tabletAccent else null,
        ) { tablet.toggle(TabletSidePanel.DANMAKU) }
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
            .background(glassContainerColor())
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

/**
 * 系列: 关联作品 (前传、续集、番外、剧场版……) 的封面卡片, 一排横滑; 没有关联作品时整块不显示.
 * 点卡片: 那一部看过、下一集已播出 ([ContinueWatchingStatus.Continue]) 就接着播下一集 (压一个新的播放页, 返回回到这一部);
 * 别的情况 (没开始看、追平了、看完了、没开播, 或进度没在 [TABLET_SERIES_PROGRESS_BUDGET_MILLIS] 内取到) 进那一部的详情页. 长按一律进详情页.
 */
@Composable
private fun TabletSeriesRow(related: LazyPagingItems<RelatedSubjectInfo>) {
    if (related.itemCount == 0) return
    val navigateToRelated = rememberNavigateToRelatedSubject()
    val navigator = LocalNavigator.current
    val collectionRepo = remember { GlobalKoin.get<SubjectCollectionRepository>() }
    val scope = rememberCoroutineScope()
    // 取进度的那一下还没回来时再点不重复取
    var pending by remember { mutableStateOf<Job?>(null) }
    val openRelated: (RelatedSubjectInfo) -> Unit = { info ->
        if (pending?.isActive != true) {
            pending = scope.launch {
                val progress = try {
                    withTimeoutOrNull(TABLET_SERIES_PROGRESS_BUDGET_MILLIS) {
                        collectionRepo.subjectCollectionFlow(info.subjectId).first()
                    }?.progressInfo
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                val next = progress?.nextEpisodeIdToPlay
                if (progress?.continueWatchingStatus is ContinueWatchingStatus.Continue && next != null) {
                    navigator.navigateEpisodeDetails(info.subjectId, next)
                } else {
                    navigateToRelated(info)
                }
            }
        }
    }
    val useOriginalTitle = tvUseOriginalTitle()
    val tmdbTitles = rememberTvTmdbTitles()
    val snapshot = related.itemSnapshotList
    FillTvTmdbTextsFromCache(remember(snapshot) { snapshot.mapNotNull { it?.subjectId } })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Lang.tablet_player_series), style = MaterialTheme.typography.titleSmall)
        LazyRow(
            Modifier.tabletRowBleed(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = TABLET_BELOW_VIDEO_SIDE_PADDING),
        ) {
            items(related.itemCount) { index ->
                val info = related[index] ?: return@items
                TabletSeriesCard(
                    subjectId = info.subjectId,
                    coverUrl = info.image,
                    title = tmdbTitles[info.subjectId] ?: info.preferredDisplayName(useOriginalTitle),
                    relation = info.relation?.let { renderSubjectRelation(it) },
                    onClick = { openRelated(info) },
                    onLongClick = { navigateToRelated(info) },
                )
            }
        }
    }
}

/** 点系列卡片时等那一部的观看进度最多多久, 等不到就进详情页 (同探索页「立即观看」). */
private const val TABLET_SERIES_PROGRESS_BUDGET_MILLIS = 1_500L

/**
 * 系列的卡片, 同平板详情页的相关条目 (TvPadRelatedSection): Bangumi 的竖版封面 + 标题 + 关系 (前传、续集、剧场版……).
 * 用封面不用 TMDB 的横版背景图: 那张挂在整部剧上, 同一部的几季拿到的是同一张, 前作与这一部分不出来; 封面每一部各有一张.
 */
@Composable
private fun TabletSeriesCard(
    subjectId: Int,
    coverUrl: String?,
    title: String,
    relation: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val nsfw = rememberNsfwPolicy()
    Column(
        Modifier.width(TABLET_SERIES_COVER_WIDTH).clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AsyncImage(
            coverUrl,
            contentDescription = null,
            Modifier.fillMaxWidth().aspectRatio(COVER_WIDTH_TO_HEIGHT_RATIO).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentScale = ContentScale.Crop,
            downsampleLongEdgePx = nsfw.coverDownsample(subjectId),
        )
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        if (relation != null) {
            Text(
                relation,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 2.dp).padding(bottom = 2.dp),
            )
        }
    }
}

/** 系列卡片的宽 (高按封面比例). */
private val TABLET_SERIES_COVER_WIDTH = 108.dp

/** 视频下面那一列的左右留白 */
internal val TABLET_BELOW_VIDEO_SIDE_PADDING = 20.dp

/**
 * 视频下面横滑的一排 (选集、系列) 往左右各出血一份留白 ([TABLET_BELOW_VIDEO_SIDE_PADDING]), 内边距同样一份把首尾对齐回内容边:
 * 停着时与其余内容对齐, 滑动时卡片一直走到这一栏的边上再出去, 不在留白线上被硬切 (同探索页的横滑行).
 */
internal fun Modifier.tabletRowBleed(): Modifier =
    tvGridBleed(start = TABLET_BELOW_VIDEO_SIDE_PADDING, end = TABLET_BELOW_VIDEO_SIDE_PADDING)
