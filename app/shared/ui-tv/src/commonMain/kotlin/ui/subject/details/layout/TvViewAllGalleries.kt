/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.widgets.CENTERED_PANEL_CONTENT_PADDING
import me.him188.ani.app.ui.foundation.widgets.CENTERED_PANEL_TITLE_GAP
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCard
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMonogram
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMonogramGallery
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativePosterGallery
import me.him188.ani.app.ui.foundation.tv.nativeview.TV_OVER_VIDEO_SUBTITLE_ALPHA
import me.him188.ani.app.ui.foundation.tv.nativeview.overVideo
import me.him188.ani.app.ui.subject.details.sections.VIEW_ALL_GALLERY_ROW_SPACING
import me.him188.ani.app.ui.subject.details.sections.VIEW_ALL_GALLERY_TEXT_GAP
import me.him188.ani.app.ui.subject.details.sections.ViewAllGalleries
import me.him188.ani.app.ui.subject.details.sections.monogramInitials
import me.him188.ani.app.ui.subject.details.sections.viewAllGalleryBottomInset
import me.him188.ani.app.ui.subject.details.sections.viewAllGalleryCellWidth
import me.him188.ani.app.ui.subject.details.sections.viewAllGalleryCircleSize
import me.him188.ani.app.ui.subject.details.sections.viewAllGalleryColumnSpacing
import me.him188.ani.app.ui.subject.details.sections.viewAllGallerySideInset
import me.him188.ani.app.ui.subject.details.sections.viewAllGalleryTopInset
import me.him188.ani.app.ui.subject.person.PeopleRowItem
import me.him188.ani.app.ui.subject.person.PosterRowItem

/**
 * 「查看全部」画廊 ([ViewAllGalleries]) 的原生版: 电视包 (电视与安卓平板) 的入口提供. 人物格同详情页的圆头像行 ([TvNativeMonogramGallery]),
 * 作品卡同海报墙 ([TvNativePosterGallery]); 列宽、间距、标签取 ui-subject 里两套实现共用的定义. 触屏上点一下就是确认.
 */
object TvViewAllGalleries : ViewAllGalleries {
    @Composable
    override fun PeopleGallery(
        items: List<PeopleRowItem?>,
        onClick: (index: Int) -> Unit,
        onLongPress: ((index: Int) -> Unit)?,
        onBind: (index: Int) -> Unit,
        initialFocus: Modifier,
        modifier: Modifier,
        columns: Int,
        initialFocusIndex: Int,
        onExitDown: (() -> Unit)?,
        compact: Boolean,
    ) {
        val touch = LocalTvTouchInputEnabled.current
        val monograms = remember(items) {
            items.map { item ->
                item?.let { TvNativeMonogram.Person(it.imageUrl, it.name, it.subtitle, monogramInitials(it.name), it.badge) }
            }
        }
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val circle = viewAllGalleryCircleSize(viewAllGalleryCellWidth(maxWidth, columns, compact), compact)
            val baseStyle = rememberTvMonogramStyle(
                size = circle,
                spacing = if (compact) compactPeopleColumnSpacing(maxWidth, columns, circle) else viewAllGalleryColumnSpacing(compact),
                viewAllLabel = "",
                textGap = VIEW_ALL_GALLERY_TEXT_GAP,
                badges = true,
                smallText = compact,
            )
            val density = LocalDensity.current
            // 紧凑档在播放器胶囊上方, 字直接压在画面上 (见 overVideo)
            val style = remember(baseStyle, compact, density) {
                if (!compact) {
                    baseStyle
                } else {
                    baseStyle.copy(
                        name = baseStyle.name.overVideo(Color.White, density),
                        subtitle = baseStyle.subtitle.overVideo(Color.White, density),
                        subtitleIdleAlpha = TV_OVER_VIDEO_SUBTITLE_ALPHA,
                    )
                }
            }
            TvNativeMonogramGallery(
                items = monograms,
                style = style.forTouchGallery(touch),
                columns = columns,
                rowSpacing = VIEW_ALL_GALLERY_ROW_SPACING,
                sideInset = viewAllGallerySideInset(compact),
                topInset = viewAllGalleryTopInset(compact),
                bottomInset = viewAllGalleryBottomInset(compact),
                touchTaps = touch,
                // 紧凑档 (播放器胶囊上方的叠层) 外面没有弹窗的内边距与标题间距可伸, 首末列的投影画在网格自己的让出里
                edgeBleed = if (compact) 0.dp else galleryEdgeBleed,
                topBleed = if (compact) 0.dp else CENTERED_PANEL_TITLE_GAP,
                onClick = onClick,
                onLongPress = onLongPress,
                onBind = onBind,
                modifier = initialFocus,
                initialIndex = initialFocusIndex,
                onExitDown = onExitDown,
            )
        }
    }

    @Composable
    override fun PosterGallery(
        items: List<PosterRowItem?>,
        onClick: (index: Int) -> Unit,
        onBind: (index: Int) -> Unit,
        initialFocus: Modifier,
        modifier: Modifier,
        columns: Int,
        initialFocusIndex: Int,
        onExitDown: (() -> Unit)?,
        compact: Boolean,
    ) {
        val touch = LocalTvTouchInputEnabled.current
        val cards = remember(items) {
            items.map { item -> item?.let { TvNativeCard(imageUrl = it.imageUrl, title = it.title, subtitle = it.subtitle, subjectId = it.subjectId) } }
        }
        BoxWithConstraints(modifier.fillMaxWidth()) {
            TvNativePosterGallery(
                cards = cards,
                cardWidth = viewAllGalleryCellWidth(maxWidth, columns, compact),
                columns = columns,
                columnSpacing = viewAllGalleryColumnSpacing(compact),
                rowSpacing = VIEW_ALL_GALLERY_ROW_SPACING,
                sideInset = viewAllGallerySideInset(compact),
                topInset = viewAllGalleryTopInset(compact),
                bottomInset = viewAllGalleryBottomInset(compact),
                touchTaps = touch,
                // 紧凑档 (播放器胶囊上方的叠层) 外面没有弹窗的内边距与标题间距可伸, 首末列的投影画在网格自己的让出里
                edgeBleed = if (compact) 0.dp else galleryEdgeBleed,
                topBleed = if (compact) 0.dp else CENTERED_PANEL_TITLE_GAP,
                onClick = onClick,
                onBind = onBind,
                modifier = initialFocus,
                initialIndex = initialFocusIndex,
                onExitDown = onExitDown,
                overVideo = compact,
            )
        }
    }
}

/**
 * 画廊放在「查看全部」弹窗的内容区里 (ViewAllPanelDialog), 左右各有 [CENTERED_PANEL_CONTENT_PADDING] 的空地: 原生网格伸进这一截,
 * 首末列聚焦时的放大与投影画得开, 不在内容区的边上被切掉. 上面同理伸进标题行下面那段间距 ([CENTERED_PANEL_TITLE_GAP]).
 */
private val galleryEdgeBleed: Dp
    @Composable get() = CENTERED_PANEL_CONTENT_PADDING.calculateStartPadding(LocalLayoutDirection.current)

/**
 * 紧凑档人物画廊的列间距: 圆比格窄一圈 (见 viewAllGalleryCircleSize), 最左、最右两列的圆贴到网格两边 (与标题、海报画廊的边对齐),
 * 多出来的空摊进列间距; 格宽 = 圆, 名字按圆宽排.
 */
private fun compactPeopleColumnSpacing(width: Dp, columns: Int, circle: Dp): Dp =
    if (columns <= 1) 0.dp else ((width - viewAllGallerySideInset(compact = true) * 2 - circle * columns) / (columns - 1)).coerceAtLeast(0.dp)
