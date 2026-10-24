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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.widgets.CENTERED_PANEL_CONTENT_PADDING
import me.him188.ani.app.ui.foundation.widgets.CENTERED_PANEL_TITLE_GAP
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCard
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMonogram
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMonogramGallery
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativePosterGallery
import me.him188.ani.app.ui.subject.details.sections.VIEW_ALL_GALLERY_COLUMNS
import me.him188.ani.app.ui.subject.details.sections.VIEW_ALL_GALLERY_COLUMN_SPACING
import me.him188.ani.app.ui.subject.details.sections.VIEW_ALL_GALLERY_ROW_SPACING
import me.him188.ani.app.ui.subject.details.sections.VIEW_ALL_GALLERY_BOTTOM_INSET
import me.him188.ani.app.ui.subject.details.sections.VIEW_ALL_GALLERY_SIDE_INSET
import me.him188.ani.app.ui.subject.details.sections.VIEW_ALL_GALLERY_TEXT_GAP
import me.him188.ani.app.ui.subject.details.sections.VIEW_ALL_GALLERY_TOP_INSET
import me.him188.ani.app.ui.subject.details.sections.ViewAllGalleries
import me.him188.ani.app.ui.subject.details.sections.monogramInitials
import me.him188.ani.app.ui.subject.details.sections.viewAllGalleryCellWidth
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
    ) {
        val touch = LocalTvTouchInputEnabled.current
        val monograms = remember(items) {
            items.map { item ->
                item?.let { TvNativeMonogram.Person(it.imageUrl, it.name, it.subtitle, monogramInitials(it.name), it.badge) }
            }
        }
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val style = rememberTvMonogramStyle(
                size = viewAllGalleryCellWidth(maxWidth),
                spacing = VIEW_ALL_GALLERY_COLUMN_SPACING,
                viewAllLabel = "",
                textGap = VIEW_ALL_GALLERY_TEXT_GAP,
                badges = true,
            )
            TvNativeMonogramGallery(
                items = monograms,
                style = style.forTouchGallery(touch),
                columns = VIEW_ALL_GALLERY_COLUMNS,
                rowSpacing = VIEW_ALL_GALLERY_ROW_SPACING,
                sideInset = VIEW_ALL_GALLERY_SIDE_INSET,
                topInset = VIEW_ALL_GALLERY_TOP_INSET,
                bottomInset = VIEW_ALL_GALLERY_BOTTOM_INSET,
                touchTaps = touch,
                edgeBleed = galleryEdgeBleed,
                topBleed = CENTERED_PANEL_TITLE_GAP,
                onClick = onClick,
                onLongPress = onLongPress,
                onBind = onBind,
                modifier = initialFocus,
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
    ) {
        val touch = LocalTvTouchInputEnabled.current
        val cards = remember(items) {
            items.map { item -> item?.let { TvNativeCard(imageUrl = it.imageUrl, title = it.title, subtitle = it.subtitle, subjectId = it.subjectId) } }
        }
        BoxWithConstraints(modifier.fillMaxWidth()) {
            TvNativePosterGallery(
                cards = cards,
                cardWidth = viewAllGalleryCellWidth(maxWidth),
                columns = VIEW_ALL_GALLERY_COLUMNS,
                columnSpacing = VIEW_ALL_GALLERY_COLUMN_SPACING,
                rowSpacing = VIEW_ALL_GALLERY_ROW_SPACING,
                sideInset = VIEW_ALL_GALLERY_SIDE_INSET,
                topInset = VIEW_ALL_GALLERY_TOP_INSET,
                bottomInset = VIEW_ALL_GALLERY_BOTTOM_INSET,
                touchTaps = touch,
                edgeBleed = galleryEdgeBleed,
                topBleed = CENTERED_PANEL_TITLE_GAP,
                onClick = onClick,
                onBind = onBind,
                modifier = initialFocus,
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
