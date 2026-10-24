/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import me.him188.ani.app.data.models.subject.RelatedCharacterInfo
import me.him188.ani.app.data.models.subject.RelatedPersonInfo
import me.him188.ani.app.data.models.subject.RelatedSubjectInfo
import me.him188.ani.app.data.models.subject.Tag
import me.him188.ani.app.data.models.subject.nameCn
import me.him188.ani.app.tools.formatDateTime
import me.him188.ani.app.ui.comment.UIComment
import me.him188.ani.app.ui.comment.UIRichText
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.avatar.AvatarImage
import me.him188.ani.app.ui.foundation.rememberAsyncImageRetryState
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.theme.glassContainerColor
import me.him188.ani.app.ui.foundation.tv.FillTvTmdbTextsFromCache
import me.him188.ani.app.ui.foundation.tv.rememberTvTmdbTitles
import me.him188.ani.app.ui.foundation.tv.tvUseOriginalTitle
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.aniGlassButtonColor
import me.him188.ani.app.ui.foundation.widgets.aniGlassEdge
import me.him188.ani.app.ui.foundation.widgets.aniPanelSubtitleStyle
import me.him188.ani.app.ui.foundation.widgets.aniVerticalScrollFade
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_episode_close
import me.him188.ani.app.ui.lang.subject_details_characters
import me.him188.ani.app.ui.lang.subject_details_episodes
import me.him188.ani.app.ui.lang.subject_details_related_subjects
import me.him188.ani.app.ui.lang.subject_details_staff
import me.him188.ani.app.ui.lang.subject_details_tags
import me.him188.ani.app.ui.lang.subject_details_tab_comments
import me.him188.ani.app.ui.lang.subject_details_view_all
import me.him188.ani.app.ui.lang.pad_subject_details_all_episodes
import me.him188.ani.app.ui.lang.subject_episode_cache
import me.him188.ani.app.ui.rating.FiveRatingStars
import me.him188.ani.app.ui.richtext.UIRichElement
import me.him188.ani.app.ui.subject.details.components.COVER_WIDTH_TO_HEIGHT_RATIO
import me.him188.ani.app.ui.subject.details.components.rememberNavigateToRelatedSubject
import me.him188.ani.app.ui.subject.details.components.renderSubjectRelation
import me.him188.ani.app.ui.subject.details.sections.CharactersViewAllDialog
import me.him188.ani.app.ui.subject.details.sections.EPISODE_CARD_CORNER
import me.him188.ani.app.ui.subject.details.sections.EPISODE_IMAGE_NAME_ALPHA
import me.him188.ani.app.ui.subject.details.sections.EPISODE_IMAGE_TRACK_ALPHA
import me.him188.ani.app.ui.subject.details.sections.EPISODE_PROGRESS_BAR_HEIGHT
import me.him188.ani.app.ui.subject.details.sections.EPISODE_PROGRESS_BAR_SIDE_INSET
import me.him188.ani.app.ui.subject.details.sections.EPISODE_PROGRESS_BOTTOM_INSET
import me.him188.ani.app.ui.subject.details.sections.EPISODE_STILL_SCRIM_ALPHA
import me.him188.ani.app.ui.subject.details.sections.EPISODE_STILL_SCRIM_START
import me.him188.ani.app.ui.subject.details.sections.StaffViewAllDialog
import me.him188.ani.app.ui.subject.details.sections.episodeStillImageUrl
import me.him188.ani.app.ui.subject.details.sections.episodeMetaText
import me.him188.ani.app.ui.subject.details.sections.focusEpisodeCardColors
import me.him188.ani.app.ui.subject.details.sections.tvEpisodeName
import me.him188.ani.app.ui.subject.episode.list.EpisodeListItem
import me.him188.ani.app.ui.subject.person.PeoplePreviewTarget
import me.him188.ani.app.ui.subject.person.rememberPeopleClickHandler
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/*
 * 触屏详情页 (SubjectDetailsPadPage) 头部以下的各区: 选集、角色、制作人员、评价、关联作品. 每区一行标题 + 一条横滑行
 * (行两端留 [TV_PAD_DETAILS_PADDING], 滑动时卡片一直画到页面右缘). 卡片的样子沿用遥控器版 (选集剧照卡、圆头像、海报卡、评论卡),
 * 但没有任何焦点效果: 点一下就是动作, 按下出涟漪.
 */

/** 区块标题行: 标题 (+ 一句计数) 在左, 右边一个「查看全部 ›」. */
@Composable
internal fun TvPadSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    /** 标题行右端的几个文字按钮 (如选集区的缓存与全部选集, 见 [TvPadSectionTextAction]). */
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().height(TV_PAD_SECTION_HEADER_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        actions()
        if (actionLabel != null) {
            TextButton(
                onClick = onAction,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                Text(actionLabel)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, Modifier.size(18.dp))
            }
        }
    }
}

/** 区块标题行右端的文字按钮: 图标 + 字, 淡一档的正文色 (如选集区的「缓存」「全部选集」). */
@Composable
internal fun TvPadSectionTextAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(18.dp))
        Text(label, Modifier.padding(start = 6.dp))
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// 选集
// ---------------------------------------------------------------------------------------------------------------------

/** 选集行的卡宽: 一屏放几张整卡 (每张至少 [TV_PAD_EPISODE_MIN_WIDTH], 最多 [TV_PAD_EPISODES_PER_ROW] 张), 由行宽 [rowWidth] 倒推. */
private fun tvPadEpisodeCardWidth(rowWidth: Dp): Dp {
    val count = ((rowWidth + TV_PAD_EPISODE_GAP) / (TV_PAD_EPISODE_MIN_WIDTH + TV_PAD_EPISODE_GAP)).toInt()
        .coerceIn(1, TV_PAD_EPISODES_PER_ROW)
    return (rowWidth - TV_PAD_EPISODE_GAP * (count - 1)) / count
}

/**
 * 选集区: 标题行 (标题 + 集数, 右端缓存与全部选集) + 剧照卡横滑行 (一屏正好 [TV_PAD_EPISODES_PER_ROW] 张). 进页时行滚到当前集
 * (它前面露一张), 之后不再自己动. 点卡片播放, 长按开本集详情 (剧照 + 完整简介 + 标记看过 / 取消看过).
 */
@Composable
internal fun TvPadEpisodesSection(
    episodes: List<EpisodeListItem>,
    currentEpisodeId: Int?,
    stills: Map<Int, String>,
    playProgress: Map<Int, Float>,
    runtimes: Map<Int, Int>,
    overviews: Map<Int, String>,
    names: Map<Int, String>,
    onPlay: (EpisodeListItem) -> Unit,
    onSetCollectionType: (EpisodeListItem, UnifiedCollectionType) -> Unit,
    onClickCache: (() -> Unit)?,
    onShowAllEpisodes: () -> Unit,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    var detailsTarget by remember { mutableStateOf<EpisodeListItem?>(null) }
    val rowState = rememberLazyListState()
    // 进页对齐到当前集只做一次 (返回本页时行的位置由 rememberLazyListState 恢复, 不再跳)
    var aligned by rememberSaveable { mutableStateOf(false) }
    val currentIndex = episodes.indexOfFirst { it.episodeId == currentEpisodeId }
    LaunchedEffect(currentIndex) {
        if (aligned || currentIndex < 0) return@LaunchedEffect
        rowState.scrollToItem((currentIndex - 1).coerceAtLeast(0))
        aligned = true
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // 一屏正好放下几张整卡: 卡宽由行宽倒推, 高按剧照比例
        val cardWidth = tvPadEpisodeCardWidth(maxWidth - horizontalPadding * 2)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TV_PAD_SECTION_CONTENT_GAP)) {
            TvPadSectionHeader(
                stringResource(Lang.subject_details_episodes),
                Modifier.padding(horizontal = horizontalPadding),
                note = episodes.size.toString(),
                actions = {
                    onClickCache?.let { TvPadSectionTextAction(Icons.Rounded.Download, stringResource(Lang.subject_episode_cache), it) }
                    TvPadSectionTextAction(Icons.Rounded.GridView, stringResource(Lang.pad_subject_details_all_episodes), onShowAllEpisodes)
                },
            )
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(TV_PAD_EPISODE_GAP),
            ) {
                items(episodes, key = { it.episodeId }) { item ->
                    Column(Modifier.width(cardWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvPadEpisodeCard(
                            item,
                            stillUrl = stills[item.episodeId],
                            isCurrent = item.episodeId == currentEpisodeId,
                            progress = playProgress[item.episodeId],
                            names = names,
                            onClick = { onPlay(item) },
                            onLongClick = { detailsTarget = item },
                            modifier = Modifier.fillMaxWidth().aspectRatio(TV_PAD_EPISODE_WIDTH / TV_PAD_EPISODE_HEIGHT),
                        )
                        episodeMetaText(runtimes[item.episodeId], item.airDate)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
    val scope = rememberCoroutineScope()
    detailsTarget?.let { target ->
        TvEpisodeDetailsDialog(
            target,
            episodes = episodes,
            onNavigate = { next ->
                detailsTarget = next
                // 背后的选集行跟着滚到这一集 (它前面露一张, 同进页时的对齐)
                val index = episodes.indexOf(next)
                if (index >= 0) scope.launch { rowState.animateScrollToItem((index - 1).coerceAtLeast(0)) }
            },
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
 * 选集剧照卡 (同遥控器版 FocusEpisodeCard 的样子: 剧照铺满、底部压暗写集号与集名、进度条; 没剧照时是玻璃底的文字卡).
 * 当前集在集号前画声浪图标, 看过的满条. 点击 / 长按都出涟漪.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvPadEpisodeCard(
    item: EpisodeListItem,
    stillUrl: String?,
    isCurrent: Boolean,
    progress: Float?,
    names: Map<Int, String>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val watched = item.isDoneOrDropped
    val colors = focusEpisodeCardColors(isPlaying = isCurrent, isWatched = watched, monochrome = false, glass = true)
    val shownProgress = if (watched) 1f else progress
    Box(
        modifier
            .clip(RoundedCornerShape(EPISODE_CARD_CORNER))
            .background(colors.container)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        val onImage = stillUrl != null
        if (stillUrl != null) {
            val retry = rememberAsyncImageRetryState(stillUrl)
            AsyncImage(
                if (retry.suppressed) null else remember(stillUrl) { episodeStillImageUrl(stillUrl) },
                contentDescription = null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { retry.onError() },
                // 与遥控器版选集卡 / 本集详情弹窗共用同一份解码
                decodeAtOriginalSize = true,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        EPISODE_STILL_SCRIM_START to Color.Transparent,
                        1f to Color.Black.copy(alpha = EPISODE_STILL_SCRIM_ALPHA),
                    ),
                ),
            )
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = if ((shownProgress ?: 0f) > 0f) 14.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isCurrent) {
                    Icon(
                        Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        Modifier.size(16.dp),
                        tint = if (onImage) Color.White else colors.sort,
                    )
                }
                Text(
                    item.sort.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (onImage) Color.White else colors.sort,
                    maxLines = 1,
                )
            }
            Text(
                item.tvEpisodeName(names),
                style = MaterialTheme.typography.bodyMedium,
                color = if (onImage) Color.White.copy(alpha = EPISODE_IMAGE_NAME_ALPHA) else colors.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val p = shownProgress
        if (p != null && p > 0f) {
            Box(
                Modifier.align(Alignment.BottomStart)
                    .padding(horizontal = EPISODE_PROGRESS_BAR_SIDE_INSET)
                    .padding(bottom = EPISODE_PROGRESS_BOTTOM_INSET)
                    .fillMaxWidth()
                    .height(EPISODE_PROGRESS_BAR_HEIGHT)
                    .clip(CircleShape)
                    .background(if (onImage) Color.White.copy(alpha = EPISODE_IMAGE_TRACK_ALPHA) else colors.track),
            ) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().background(colors.progress))
            }
        }
    }
}

/** 弹窗标题行右端的玻璃按钮 (「下载」胶囊、关闭圆钮): 高 [TV_PAD_GLASS_BUTTON_HEIGHT], 淡白底 + 细高光边, 同面板弹窗的按钮. */
@Composable
private fun TvPadGlassButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(TV_PAD_GLASS_BUTTON_HEIGHT),
        shape = CircleShape,
        color = aniGlassButtonColor(),
        border = aniGlassEdge(),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * 简介弹窗 (点头部的简介或标签行的「更多标签」开): 居中玻璃面板, 标题是条目名、下面一行放送日期与话数 ([meta]), 右上角关闭;
 * 中间全文 (末尾跟别名), 滚不完的那边渐隐; 最下面全部标签, 不随全文滚, 点标签关弹窗进搜索. 触屏用.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TvPadSummaryDialog(
    title: String,
    summary: String,
    aliases: String?,
    meta: String?,
    tags: List<Tag>,
    onClickTag: (Tag) -> Unit,
    onDismiss: () -> Unit,
) {
    // 简介里段落之间常连着好几个空行 (尤其「简介原文」前面), 照原样会空出一大块: 连着的空行并成一个
    val text = remember(summary, aliases) {
        listOf(summary, aliases.orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n").replace(TV_PAD_SUMMARY_EXTRA_BLANK_LINES, "\n\n")
    }
    AniCenteredPanelDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    meta?.let {
                        Text(it, style = aniPanelSubtitleStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                TvPadGlassButton(onClick = onDismiss, Modifier.width(TV_PAD_GLASS_BUTTON_HEIGHT)) {
                    Icon(Icons.Rounded.Close, stringResource(Lang.subject_episode_close), Modifier.size(20.dp))
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(TV_PAD_SUMMARY_DIALOG_SECTION_GAP)) {
            if (text.isNotEmpty()) {
                val scroll = rememberScrollState()
                Text(
                    text,
                    Modifier.weight(1f, fill = false).aniVerticalScrollFade(scroll).verticalScroll(scroll),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (tags.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(TV_PAD_SUMMARY_DIALOG_TAG_GAP)) {
                    Text(
                        stringResource(Lang.subject_details_tags),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(TV_PAD_SUMMARY_DIALOG_TAG_GAP),
                        verticalArrangement = Arrangement.spacedBy(TV_PAD_SUMMARY_DIALOG_TAG_GAP),
                    ) {
                        tags.forEach { tag ->
                            TvTagChip(
                                tag.name,
                                Modifier.clickable {
                                    onDismiss()
                                    onClickTag(tag)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// 角色 / 制作人员
// ---------------------------------------------------------------------------------------------------------------------

/**
 * 角色区: 一行人物卡 ([TvPadPersonCard], 上游 PersonCard 的样子放大: 方头像 + 名字 + 「角色 · 声优」), 放得下几张就几张 (按页面宽度定列数), 不能滑;
 * 点卡开人物预览. 还有没露出来的人时标题行有「查看全部」(开全量弹窗).
 */
@Composable
internal fun TvPadCharactersSection(
    exposed: LazyPagingItems<RelatedCharacterInfo>,
    all: LazyPagingItems<RelatedCharacterInfo>,
    totalCount: Int?,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val onClickCharacter = rememberPeopleClickHandler()
    TvPadPeopleSection(
        title = stringResource(Lang.subject_details_characters),
        count = exposed.itemCount,
        totalCount = totalCount,
        card = { index -> exposed[index]?.let { TvPadPersonCard(it) } },
        onClick = { index -> exposed.peek(index)?.let { onClickCharacter(PeoplePreviewTarget.Character(it.character.id)) } },
        onShowAll = { showAll = true },
        horizontalPadding = horizontalPadding,
        modifier = modifier,
    )
    if (showAll) {
        CharactersViewAllDialog(all, totalCount, onDismissRequest = { showAll = false })
    }
}

/** 制作人员区: 同 [TvPadCharactersSection], 卡上第二行是职位. */
@Composable
internal fun TvPadStaffSection(
    exposed: LazyPagingItems<RelatedPersonInfo>,
    all: LazyPagingItems<RelatedPersonInfo>,
    totalCount: Int?,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val onClickPerson = rememberPeopleClickHandler()
    TvPadPeopleSection(
        title = stringResource(Lang.subject_details_staff),
        count = exposed.itemCount,
        totalCount = totalCount,
        card = { index -> exposed[index]?.let { TvPadPersonCard(it) } },
        onClick = { index -> exposed.peek(index)?.let { onClickPerson(PeoplePreviewTarget.Person(it.personInfo.id)) } },
        onShowAll = { showAll = true },
        horizontalPadding = horizontalPadding,
        modifier = modifier,
    )
    if (showAll) {
        StaffViewAllDialog(all, totalCount, onDismissRequest = { showAll = false })
    }
}

/**
 * 一行人物卡: 最多 [TV_PAD_PERSONS_PER_ROW] 张, 窄到每格不足 [TV_PAD_PERSON_MIN_WIDTH] 时减 ([tvPadColumns]), 只放前那么多张; 格子等宽, 人少时后面空着.
 * 整格点击出涟漪 (涟漪比卡宽出 [TV_PAD_PERSON_PRESS_INSET], 行往左挪同样多, 卡的左缘仍与标题对齐).
 */
@Composable
private fun TvPadPeopleSection(
    title: String,
    count: Int,
    totalCount: Int?,
    card: @Composable (index: Int) -> Unit,
    onClick: (index: Int) -> Unit,
    onShowAll: () -> Unit,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val rowWidth = maxWidth - (horizontalPadding - TV_PAD_PERSON_PRESS_INSET) * 2
        val columns = tvPadColumns(rowWidth, TV_PAD_PERSON_MIN_WIDTH, TV_PAD_PERSON_GAP, TV_PAD_PERSONS_PER_ROW)
        val shown = minOf(count, columns)
        val remaining = (totalCount ?: count) - shown
        Column(verticalArrangement = Arrangement.spacedBy(TV_PAD_SECTION_CONTENT_GAP)) {
            TvPadSectionHeader(
                title,
                Modifier.padding(horizontal = horizontalPadding),
                note = totalCount?.toString(),
                actionLabel = if (remaining > 0) stringResource(Lang.subject_details_view_all) else null,
                onAction = onShowAll,
            )
            Row(
                Modifier.padding(horizontal = horizontalPadding - TV_PAD_PERSON_PRESS_INSET),
                horizontalArrangement = Arrangement.spacedBy(TV_PAD_PERSON_GAP),
            ) {
                repeat(columns) { index ->
                    if (index < shown) {
                        Box(
                            Modifier.weight(1f)
                                .clip(RoundedCornerShape(TV_PAD_CARD_CORNER))
                                .clickable { onClick(index) }
                                .padding(TV_PAD_PERSON_PRESS_INSET),
                        ) {
                            card(index)
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** [width] 里每格至少 [minCell]、格间 [gap] 时放得下几格 (至少一格, 至多 [maxColumns] 格). */
private fun tvPadColumns(width: Dp, minCell: Dp, gap: Dp, maxColumns: Int = Int.MAX_VALUE): Int =
    ((width + gap) / (minCell + gap)).toInt().coerceIn(1, maxColumns)

/** 角色卡: 「角色 · 声优」. */
@Composable
private fun TvPadPersonCard(info: RelatedCharacterInfo) {
    val actor = remember(info) { info.character.actors.firstOrNull()?.displayName }
    TvPadPersonCard(
        avatarUrl = info.character.imageMedium,
        name = info.character.displayName,
        relation = if (actor == null) info.role.nameCn else "${info.role.nameCn} · $actor",
    )
}

/** 制作人员卡: 第二行是职位. */
@Composable
private fun TvPadPersonCard(info: RelatedPersonInfo) {
    TvPadPersonCard(info.personInfo.imageMedium, info.personInfo.displayName, info.position.nameCn.orEmpty())
}

/**
 * 人物卡: 上游人物卡 (PersonCard) 的样子放大到平板上 —— 左边圆角方头像 ([TV_PAD_PERSON_AVATAR], 立绘顶部对齐露脸), 右边名字与一行关系,
 * 放不下省略.
 */
@Composable
private fun TvPadPersonCard(avatarUrl: String?, name: String, relation: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AvatarImage(
            avatarUrl,
            Modifier.size(TV_PAD_PERSON_AVATAR).clip(RoundedCornerShape(TV_PAD_PERSON_AVATAR_CORNER)),
            alignment = Alignment.TopCenter,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                relation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// 评价
// ---------------------------------------------------------------------------------------------------------------------

/**
 * 评价区: 一行等宽的评论卡, 放得下几张就几张 (按页面宽度定张数, 每张至少 [TV_PAD_REVIEW_MIN_WIDTH]), 不能滑;
 * 点卡进全量评论里它自己那一条, 标题行「查看全部」从第一条起.
 */
@Composable
internal fun TvPadReviewsSection(
    comments: LazyPagingItems<UIComment>,
    totalCount: Int?,
    onShowAll: (initialIndex: Int) -> Unit,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TV_PAD_SECTION_CONTENT_GAP)) {
        TvPadSectionHeader(
            stringResource(Lang.subject_details_tab_comments),
            Modifier.padding(horizontal = horizontalPadding),
            note = totalCount?.toString(),
            actionLabel = stringResource(Lang.subject_details_view_all),
            onAction = { onShowAll(0) },
        )
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
            val columns = tvPadColumns(maxWidth, TV_PAD_REVIEW_MIN_WIDTH, TV_PAD_REVIEW_GAP)
            val shown = minOf(comments.itemCount, columns)
            Row(horizontalArrangement = Arrangement.spacedBy(TV_PAD_REVIEW_GAP)) {
                repeat(columns) { index ->
                    val comment = if (index < shown) comments[index] else null
                    if (comment != null) {
                        TvPadReviewCard(comment, onClick = { onShowAll(index) }, Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 一张评论卡: 头像 / 昵称 / 星级 / 日期一行, 正文四行截断; 半透明底透出模糊背景. */
@Composable
private fun TvPadReviewCard(comment: UIComment, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(TV_PAD_REVIEW_HEIGHT),
        shape = RoundedCornerShape(TV_PAD_REVIEW_CORNER),
        color = glassContainerColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AvatarImage(comment.author?.avatarUrl, Modifier.size(28.dp).clip(CircleShape))
                Text(
                    comment.author?.nickname ?: comment.author?.id.orEmpty(),
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                comment.rating?.takeIf { it > 0 }?.let { FiveRatingStars(it, starSize = 12.dp) }
                Spacer(Modifier.weight(1f))
                Text(
                    formatDateTime(comment.createdAt, showTime = false),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            val body = remember(comment) { comment.content.plainText() }
            if (body.isNotBlank()) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 富文本里的纯文字 (图片 / 贴纸 / 引用跳过), 给评论卡的摘要用. */
private fun UIRichText.plainText(): String =
    elements.asSequence()
        .filterIsInstance<UIRichElement.AnnotatedText>()
        .flatMap { it.slice }
        .filterIsInstance<UIRichElement.Annotated.Text>()
        .joinToString("") { it.content }
        .trim()

// ---------------------------------------------------------------------------------------------------------------------
// 关联作品
// ---------------------------------------------------------------------------------------------------------------------

/** 关联作品区: 海报卡横滑行 (海报 + 标题 + 关系), 点卡进那一部的详情页. NSFW 打码同各页 (见 NsfwPolicy). */
@Composable
internal fun TvPadRelatedSection(
    related: LazyPagingItems<RelatedSubjectInfo>,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val navigateToRelated = rememberNavigateToRelatedSubject()
    val useOriginalTitle = tvUseOriginalTitle()
    // 非中文界面: 查到过 TMDB 标题的番用它 (见 SubjectInfo.tvTitle); 看过的番从持久缓存补
    val tmdbTitles = rememberTvTmdbTitles()
    val snapshot = related.itemSnapshotList
    FillTvTmdbTextsFromCache(remember(snapshot) { snapshot.mapNotNull { it?.subjectId } })
    val nsfw = rememberNsfwPolicy()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TV_PAD_SECTION_CONTENT_GAP)) {
        TvPadSectionHeader(stringResource(Lang.subject_details_related_subjects), Modifier.padding(horizontal = horizontalPadding))
        LazyRow(
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(TV_PAD_POSTER_GAP),
        ) {
            items(related.itemCount) { index ->
                val info = related[index] ?: return@items
                Column(
                    Modifier.width(TV_PAD_POSTER_WIDTH)
                        .clip(RoundedCornerShape(TV_PAD_CARD_CORNER))
                        .clickable { navigateToRelated(info) },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AsyncImage(
                        info.image,
                        contentDescription = null,
                        Modifier.fillMaxWidth().aspectRatio(COVER_WIDTH_TO_HEIGHT_RATIO)
                            .clip(RoundedCornerShape(TV_PAD_CARD_CORNER))
                            .background(glassContainerColor()),
                        contentScale = ContentScale.Crop,
                        downsampleLongEdgePx = nsfw.coverDownsample(info.subjectId),
                    )
                    Text(
                        tmdbTitles[info.subjectId] ?: info.preferredDisplayName(useOriginalTitle),
                        Modifier.padding(horizontal = 2.dp),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    info.relation?.let {
                        Text(
                            renderSubjectRelation(it),
                            Modifier.padding(horizontal = 2.dp).padding(bottom = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// 占位: 数据在路上时各区块先摆同样几何的淡色块 (标题照常), 到了原地换成真内容, 高度不变、下面的区块不动 (见 SubjectDetailsPadPage)
// ---------------------------------------------------------------------------------------------------------------------

/** 选集区的占位: 标题 + 一屏那几张剧照卡大小的块, 下面留出时长 / 日期那一行. */
@Composable
internal fun TvPadEpisodesSkeleton(horizontalPadding: Dp, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val rowWidth = maxWidth - horizontalPadding * 2
        val cardWidth = tvPadEpisodeCardWidth(rowWidth)
        val count = ((rowWidth + TV_PAD_EPISODE_GAP) / (cardWidth + TV_PAD_EPISODE_GAP)).toInt().coerceAtLeast(1)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TV_PAD_SECTION_CONTENT_GAP)) {
            TvPadSectionHeader(stringResource(Lang.subject_details_episodes), Modifier.padding(horizontal = horizontalPadding))
            Row(Modifier.padding(horizontal = horizontalPadding), horizontalArrangement = Arrangement.spacedBy(TV_PAD_EPISODE_GAP)) {
                repeat(count) {
                    Column(Modifier.width(cardWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvPadSkeletonBlock(
                            Modifier.fillMaxWidth().aspectRatio(TV_PAD_EPISODE_WIDTH / TV_PAD_EPISODE_HEIGHT),
                            RoundedCornerShape(EPISODE_CARD_CORNER),
                        )
                        TvPadSkeletonLine(MaterialTheme.typography.labelMedium, Modifier.fillMaxWidth(TV_PAD_SKELETON_SHORT_LINE))
                    }
                }
            }
        }
    }
}

/** 角色 / 制作人员区的占位: 标题 + 一行人物卡大小的块 (方头像 + 两行字), 列数同 [TvPadPeopleSection]. */
@Composable
internal fun TvPadPeopleSkeleton(title: String, horizontalPadding: Dp, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val rowWidth = maxWidth - (horizontalPadding - TV_PAD_PERSON_PRESS_INSET) * 2
        val columns = tvPadColumns(rowWidth, TV_PAD_PERSON_MIN_WIDTH, TV_PAD_PERSON_GAP, TV_PAD_PERSONS_PER_ROW)
        Column(verticalArrangement = Arrangement.spacedBy(TV_PAD_SECTION_CONTENT_GAP)) {
            TvPadSectionHeader(title, Modifier.padding(horizontal = horizontalPadding))
            Row(
                Modifier.padding(horizontal = horizontalPadding - TV_PAD_PERSON_PRESS_INSET),
                horizontalArrangement = Arrangement.spacedBy(TV_PAD_PERSON_GAP),
            ) {
                repeat(columns) {
                    Row(
                        Modifier.weight(1f).padding(TV_PAD_PERSON_PRESS_INSET),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        TvPadSkeletonBlock(Modifier.size(TV_PAD_PERSON_AVATAR), RoundedCornerShape(TV_PAD_PERSON_AVATAR_CORNER))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TvPadSkeletonLine(MaterialTheme.typography.titleMedium, Modifier.fillMaxWidth(TV_PAD_SKELETON_LONG_LINE))
                            TvPadSkeletonLine(MaterialTheme.typography.bodyMedium, Modifier.fillMaxWidth(TV_PAD_SKELETON_SHORT_LINE))
                        }
                    }
                }
            }
        }
    }
}

/** 评价区的占位: 标题 + 一行评论卡大小的块, 张数同 [TvPadReviewsSection]. */
@Composable
internal fun TvPadReviewsSkeleton(horizontalPadding: Dp, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TV_PAD_SECTION_CONTENT_GAP)) {
        TvPadSectionHeader(stringResource(Lang.subject_details_tab_comments), Modifier.padding(horizontal = horizontalPadding))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
            val columns = tvPadColumns(maxWidth, TV_PAD_REVIEW_MIN_WIDTH, TV_PAD_REVIEW_GAP)
            Row(horizontalArrangement = Arrangement.spacedBy(TV_PAD_REVIEW_GAP)) {
                repeat(columns) {
                    TvPadSkeletonBlock(Modifier.weight(1f).height(TV_PAD_REVIEW_HEIGHT), RoundedCornerShape(TV_PAD_REVIEW_CORNER))
                }
            }
        }
    }
}

/** 关联作品区的占位: 标题 + 一行海报卡大小的块 (海报 + 标题 + 关系两行字), 放得下几张放几张. */
@Composable
internal fun TvPadRelatedSkeleton(horizontalPadding: Dp, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val count = ((maxWidth - horizontalPadding * 2 + TV_PAD_POSTER_GAP) / (TV_PAD_POSTER_WIDTH + TV_PAD_POSTER_GAP)).toInt()
            .coerceAtLeast(1)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TV_PAD_SECTION_CONTENT_GAP)) {
            TvPadSectionHeader(stringResource(Lang.subject_details_related_subjects), Modifier.padding(horizontal = horizontalPadding))
            Row(Modifier.padding(horizontal = horizontalPadding), horizontalArrangement = Arrangement.spacedBy(TV_PAD_POSTER_GAP)) {
                repeat(count) {
                    Column(Modifier.width(TV_PAD_POSTER_WIDTH), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TvPadSkeletonBlock(
                            Modifier.fillMaxWidth().aspectRatio(COVER_WIDTH_TO_HEIGHT_RATIO),
                            RoundedCornerShape(TV_PAD_CARD_CORNER),
                        )
                        TvPadSkeletonLine(
                            MaterialTheme.typography.titleSmall,
                            Modifier.padding(horizontal = 2.dp).fillMaxWidth(TV_PAD_SKELETON_LONG_LINE),
                        )
                        TvPadSkeletonLine(
                            MaterialTheme.typography.bodySmall,
                            Modifier.padding(horizontal = 2.dp).padding(bottom = 4.dp).fillMaxWidth(TV_PAD_SKELETON_SHORT_LINE),
                        )
                    }
                }
            }
        }
    }
}

/** 一块占位: 卡片空着时的那层玻璃底色. */
@Composable
private fun TvPadSkeletonBlock(modifier: Modifier, shape: Shape) {
    Box(modifier.clip(shape).background(glassContainerColor()))
}

/** 一行字的占位: 高同 [style] 的一行, 字身那么粗的圆角条竖着居中. */
@Composable
private fun TvPadSkeletonLine(style: TextStyle, modifier: Modifier) {
    val density = LocalDensity.current
    val lineHeight = with(density) { style.lineHeight.toDp() }
    val barHeight = with(density) { (style.fontSize * TV_PAD_SKELETON_TEXT_BAR_FRACTION).toDp() }
    Box(modifier.height(lineHeight), contentAlignment = Alignment.CenterStart) {
        TvPadSkeletonBlock(Modifier.fillMaxWidth().height(barHeight), CircleShape)
    }
}

/** 占位里字条的长短 (占格子宽的比例) 与粗细 (占字号的比例). */
private const val TV_PAD_SKELETON_LONG_LINE = 0.7f
private const val TV_PAD_SKELETON_SHORT_LINE = 0.45f
private const val TV_PAD_SKELETON_TEXT_BAR_FRACTION = 0.7f

/** 标题行高 (容得下右边的文字按钮, 有没有按钮都一样高). */
private val TV_PAD_SECTION_HEADER_HEIGHT = 40.dp

/** 标题行到横滑行的间距. */
internal val TV_PAD_SECTION_CONTENT_GAP = 10.dp

/** 选集卡尺寸与间距, 同遥控器版选集轮播. */
private val TV_PAD_EPISODE_WIDTH = 256.dp
private val TV_PAD_EPISODE_HEIGHT = 144.dp
private val TV_PAD_EPISODE_GAP = 16.dp

/** 选集行一屏最多放几张整卡、每张至少多宽 (窄屏时少放几张, 见 tvPadEpisodeCardWidth). */
private const val TV_PAD_EPISODES_PER_ROW = 4
private val TV_PAD_EPISODE_MIN_WIDTH = 220.dp

/** 弹窗标题行玻璃按钮的高 (关闭钮是同大的圆). */
private val TV_PAD_GLASS_BUTTON_HEIGHT = 44.dp

/** 简介弹窗里全文与标签两段之间的间距; 标签之间 (横竖) 与「标签」二字下面的间距. */
private val TV_PAD_SUMMARY_DIALOG_SECTION_GAP = 24.dp
private val TV_PAD_SUMMARY_DIALOG_TAG_GAP = 8.dp

/** 两个以上连着的空行 (中间可夹空白字符). */
private val TV_PAD_SUMMARY_EXTRA_BLANK_LINES = Regex("\n\\s*\n(\\s*\n)+")

/** 人物卡: 一行最多几张、每格最窄多宽 (窄到放不下这么多张才减)、头像边长与圆角、格间距、涟漪比卡宽出多少. */
private const val TV_PAD_PERSONS_PER_ROW = 5
private val TV_PAD_PERSON_MIN_WIDTH = 160.dp
private val TV_PAD_PERSON_AVATAR = 72.dp
private val TV_PAD_PERSON_AVATAR_CORNER = 10.dp
private val TV_PAD_PERSON_GAP = 12.dp
private val TV_PAD_PERSON_PRESS_INSET = 8.dp

/** 评论卡: 每张最窄多宽、高、圆角、间距. */
private val TV_PAD_REVIEW_MIN_WIDTH = 320.dp
private val TV_PAD_REVIEW_HEIGHT = 156.dp
private val TV_PAD_REVIEW_CORNER = 12.dp
private val TV_PAD_REVIEW_GAP = 16.dp

/** 关联作品海报卡宽度与间距 (海报按封面比例). */
private val TV_PAD_POSTER_WIDTH = 150.dp
private val TV_PAD_POSTER_GAP = 20.dp

/** 人物格与海报卡的圆角 (涟漪也按它裁). */
private val TV_PAD_CARD_CORNER = 10.dp
