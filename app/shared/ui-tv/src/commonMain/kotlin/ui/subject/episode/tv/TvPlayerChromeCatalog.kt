/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import me.him188.ani.app.data.models.preference.TvPlayerChromeItem
import me.him188.ani.app.ui.foundation.icons.AniIcons
import me.him188.ani.app.ui.foundation.icons.Forward80
import me.him188.ani.app.ui.foundation.icons.Forward85
import me.him188.ani.app.ui.foundation.icons.Forward90
import me.him188.ani.app.ui.foundation.icons.SubtitleGear
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.episode_comments
import me.him188.ani.app.ui.lang.episode_danmaku
import me.him188.ani.app.ui.lang.subject_details_characters
import me.him188.ani.app.ui.lang.subject_details_staff
import me.him188.ani.app.ui.lang.subject_episode_cache
import me.him188.ani.app.ui.lang.subject_episode_danmaku_settings_title
import me.him188.ani.app.ui.lang.subject_episode_details
import me.him188.ani.app.ui.lang.subject_episode_external_links
import me.him188.ani.app.ui.lang.subject_episode_fast_forward_seconds
import me.him188.ani.app.ui.lang.subject_episode_related_recommendations
import me.him188.ani.app.ui.lang.subject_episode_select_media_source
import me.him188.ani.app.ui.lang.video_player_tv_chrome_item_aspect_ratio
import me.him188.ani.app.ui.lang.video_player_tv_chrome_item_divider
import me.him188.ani.app.ui.lang.video_player_tv_chrome_item_spacer
import me.him188.ani.app.ui.lang.video_player_aspect_fit
import me.him188.ani.app.ui.lang.video_player_enable_danmaku
import me.him188.ani.app.ui.lang.video_player_next_episode
import me.him188.ani.app.ui.lang.video_player_select_episode
import me.him188.ani.app.ui.lang.video_player_speed
import me.him188.ani.app.ui.lang.video_player_stats_title_show
import me.him188.ani.app.ui.lang.video_player_subtitle
import me.him188.ani.app.ui.lang.video_player_tv_collection
import me.him188.ani.app.ui.lang.video_player_tv_restart
import me.him188.ani.app.ui.lang.watch_together_title
import org.jetbrains.compose.resources.stringResource

/**
 * 一个 [TvPlayerChromeItem] 在控制层上长什么样 —— 圆钮里的图标, 还是一颗自描述的文字按钮,
 * 还是分组竖线 / 弹性留白那样的装饰.
 *
 * 只给**自定义版式那一页** ([TvPlayerChromeLayoutPage]) 用: 播放器本体是照着各条目自己的代码画的
 * (它们各带弹窗、下拉、焦点善后), 这里是同一批东西的一份**纯外观副本**, 不依赖 ViewModel.
 * 加新按钮时两边都要添 —— 漏了这边, 那颗按钮在自定义页上会缺个图标.
 */
@Immutable
internal sealed interface TvChromeGlyph {
    @Immutable
    data class Vector(val icon: ImageVector) : TvChromeGlyph

    /** 自描述的文字按钮 (字幕轨 / 倍速 / 画面比例). */
    @Immutable
    data class Label(val text: String) : TvChromeGlyph

    /** 分组竖线. */
    data object Divider : TvChromeGlyph

    /** 把两半推开的弹性留白. */
    data object FlexibleSpace : TvChromeGlyph
}

@Immutable
internal data class TvChromeItemAppearance(
    val glyph: TvChromeGlyph,
    /** 聚焦时按钮下方浮现的那行字, 与播放器里的 contentDescription 是同一句. */
    val name: String,
)

/**
 * @param opEdSkipSeconds 「快进 N 秒」按钮的 N, 跟着设置走 (图标也按它换).
 */
@Composable
internal fun tvChromeItemAppearance(
    item: TvPlayerChromeItem,
    opEdSkipSeconds: Long,
): TvChromeItemAppearance = when (item) {
    TvPlayerChromeItem.PILL_RECOMMENDATIONS -> TvChromeItemAppearance(
        TvChromeGlyph.Vector(Icons.Rounded.VideoLibrary),
        stringResource(Lang.subject_episode_related_recommendations),
    )

    TvPlayerChromeItem.PILL_STAFF -> TvChromeItemAppearance(
        TvChromeGlyph.Vector(Icons.Rounded.Groups),
        stringResource(Lang.subject_details_staff),
    )

    TvPlayerChromeItem.PILL_CHARACTERS -> TvChromeItemAppearance(
        TvChromeGlyph.Vector(Icons.Rounded.Face),
        stringResource(Lang.subject_details_characters),
    )

    TvPlayerChromeItem.PILL_COMMENTS -> TvChromeItemAppearance(
        TvChromeGlyph.Vector(Icons.AutoMirrored.Rounded.Comment),
        stringResource(Lang.episode_comments),
    )

    TvPlayerChromeItem.PILL_DANMAKU -> TvChromeItemAppearance(
        TvChromeGlyph.Vector(Icons.Rounded.Subtitles),
        stringResource(Lang.episode_danmaku),
    )

    TvPlayerChromeItem.RESTART -> TvChromeItemAppearance(
        TvChromeGlyph.Vector(Icons.Rounded.Replay),
        stringResource(Lang.video_player_tv_restart),
    )

    TvPlayerChromeItem.NEXT_EPISODE -> TvChromeItemAppearance(
        TvChromeGlyph.Vector(Icons.Rounded.SkipNext),
        stringResource(Lang.video_player_next_episode),
    )

    TvPlayerChromeItem.SKIP_OP_ED -> TvChromeItemAppearance(
        TvChromeGlyph.Vector(
            when (opEdSkipSeconds) {
                85L -> AniIcons.Forward85
                90L -> AniIcons.Forward90
                else -> AniIcons.Forward80
            },
        ),
        stringResource(Lang.subject_episode_fast_forward_seconds, opEdSkipSeconds),
    )

    TvPlayerChromeItem.TOUCH_EPISODE_STRIP -> TvChromeItemAppearance(
        TvChromeGlyph.Vector(Icons.Rounded.ViewCarousel),
        stringResource(Lang.video_player_select_episode),
    )

    TvPlayerChromeItem.TOUCH_DETAILS -> TvChromeItemAppearance(
        TvChromeGlyph.Vector(Icons.Rounded.Info),
        stringResource(Lang.subject_episode_details),
    )

    TvPlayerChromeItem.MEDIA_SOURCE -> TvChromeItemAppearance(
        TvChromeGlyph.Vector(Icons.Rounded.DisplaySettings),
        stringResource(Lang.subject_episode_select_media_source),
    )

    TvPlayerChromeItem.DANMAKU_TOGGLE -> TvChromeItemAppearance(
        TvChromeGlyph.Vector(Icons.Rounded.Subtitles),
        stringResource(Lang.video_player_enable_danmaku),
    )

    TvPlayerChromeItem.DANMAKU_SETTINGS -> TvChromeItemAppearance(
        TvChromeGlyph.Vector(AniIcons.SubtitleGear),
        stringResource(Lang.subject_episode_danmaku_settings_title),
    )

    TvPlayerChromeItem.WATCH_TOGETHER -> TvChromeItemAppearance(
        TvChromeGlyph.Vector(Icons.Rounded.SyncAlt),
        stringResource(Lang.watch_together_title),
    )

    TvPlayerChromeItem.SUBTITLE_TRACK -> stringResource(Lang.video_player_subtitle).let {
        TvChromeItemAppearance(TvChromeGlyph.Label(it), it)
    }

    // 文字按钮上写的是当前值 (倍速 1x 时写"倍速", 比例默认时写"适应"), 这里照抄那一档的样子
    TvPlayerChromeItem.PLAYBACK_SPEED -> stringResource(Lang.video_player_speed).let {
        TvChromeItemAppearance(TvChromeGlyph.Label(it), it)
    }

    TvPlayerChromeItem.ASPECT_RATIO -> TvChromeItemAppearance(
        TvChromeGlyph.Label(stringResource(Lang.video_player_aspect_fit)),
        stringResource(Lang.video_player_tv_chrome_item_aspect_ratio),
    )

    TvPlayerChromeItem.COLLECTION -> TvChromeItemAppearance(
        TvChromeGlyph.Vector(Icons.Rounded.FavoriteBorder),
        stringResource(Lang.video_player_tv_collection),
    )

    TvPlayerChromeItem.PLAYER_STATS -> TvChromeItemAppearance(
        TvChromeGlyph.Vector(Icons.Outlined.Analytics),
        stringResource(Lang.video_player_stats_title_show),
    )

    TvPlayerChromeItem.SHARE -> TvChromeItemAppearance(
        TvChromeGlyph.Vector(Icons.AutoMirrored.Rounded.OpenInNew),
        stringResource(Lang.subject_episode_external_links),
    )

    TvPlayerChromeItem.CACHE -> TvChromeItemAppearance(
        TvChromeGlyph.Vector(Icons.Rounded.Download),
        stringResource(Lang.subject_episode_cache),
    )

    TvPlayerChromeItem.DIVIDER_1,
    TvPlayerChromeItem.DIVIDER_2,
    TvPlayerChromeItem.DIVIDER_3,
        -> TvChromeItemAppearance(TvChromeGlyph.Divider, stringResource(Lang.video_player_tv_chrome_item_divider))

    TvPlayerChromeItem.SPACER -> TvChromeItemAppearance(
        TvChromeGlyph.FlexibleSpace,
        stringResource(Lang.video_player_tv_chrome_item_spacer),
    )
}
