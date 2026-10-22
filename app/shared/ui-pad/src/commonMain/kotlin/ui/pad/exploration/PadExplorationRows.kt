/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.pad.exploration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import me.him188.ani.app.data.models.player.EpisodeHistory
import me.him188.ani.app.data.models.subject.ContinueWatchingStatus
import me.him188.ani.app.data.models.subject.FollowedSubjectInfo
import me.him188.ani.app.data.recommendation.RecommendationGroup
import me.him188.ani.app.data.recommendation.RecommendationGroupKind
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.exploration_rec_also_watched
import me.him188.ani.app.ui.lang.exploration_rec_because_you_liked
import me.him188.ani.app.ui.lang.exploration_rec_change_taste
import me.him188.ani.app.ui.lang.exploration_rec_for_you_high_rated
import me.him188.ani.app.ui.lang.exploration_rec_similar_to
import me.him188.ani.app.ui.lang.exploration_rec_this_season
import me.him188.ani.app.ui.lang.exploration_rec_this_season_new
import me.him188.ani.app.ui.lang.exploration_rec_top_rated
import me.him188.ani.app.ui.lang.exploration_rec_trending
import me.him188.ani.app.ui.lang.exploration_recommendations
import org.jetbrains.compose.resources.stringResource

/*
 * 探索页的行结构, 与电视版 (ui-tv 的 TvExplorationPage.kt: tvRecRowsOf / tvRecGroupTitle / followedCardProgress) 同一套规则;
 * ui-tv 只编 Android, 这里是照抄的一份, 两边改一处要同步另一处.
 */

/**
 * 推荐区的一行: 平铺列表 (各组依次拼起来) 里第 [start] 条起的 [size] 条, 全部属于 [group]; [header] = 这一行上方放不放组标题.
 *
 * 一组一行、各带标题, 行内横滑; 只有 [RecommendationGroupKind.FEED] (未登录时的整份推荐) 例外 —— 按一屏完整放得下的张数
 * 切成多行、不横滑, 标题只在首行上方放一次.
 */
@Immutable
class PadRecRow(
    val group: RecommendationGroup,
    val start: Int,
    val size: Int,
    val header: Boolean,
    /** 这一组的最后一行: 组可以接着推荐时行尾放「更多」. */
    val last: Boolean,
)

fun padRecRowsOf(groups: List<RecommendationGroup>, feedRowSize: Int): List<PadRecRow> {
    val rows = mutableListOf<PadRecRow>()
    var groupStart = 0
    for (group in groups) {
        val feed = group.kind == RecommendationGroupKind.FEED
        val rowSize = if (feed) feedRowSize.coerceAtLeast(1) else group.items.size.coerceAtLeast(1)
        // FEED 只排满行: 条数不一定是列数的整数倍, 末尾凑不满一行的几条不放; 整组不到一行时照放
        val shown = if (feed && group.items.size >= rowSize) group.items.size / rowSize * rowSize else group.items.size
        for (offset in 0 until shown step rowSize) {
            rows += PadRecRow(
                group,
                start = groupStart + offset,
                size = minOf(rowSize, shown - offset),
                header = offset == 0,
                last = offset + rowSize >= shown,
            )
        }
        groupStart += group.items.size
    }
    return rows
}

/** 分组标题. 只有「因为你喜欢《X》」「看过《X》的人还看了」「和《X》相似」要填参数. */
@Composable
fun padRecGroupTitle(group: RecommendationGroup): String = when (group.kind) {
    RecommendationGroupKind.BECAUSE_YOU_LIKED -> stringResource(
        Lang.exploration_rec_because_you_liked,
        group.titleArg.orEmpty(),
    )

    RecommendationGroupKind.ALSO_WATCHED -> stringResource(
        Lang.exploration_rec_also_watched,
        group.titleArg.orEmpty(),
    )

    RecommendationGroupKind.SIMILAR_TO -> stringResource(
        Lang.exploration_rec_similar_to,
        group.titleArg.orEmpty(),
    )

    RecommendationGroupKind.FOR_YOU_HIGH_RATED -> stringResource(Lang.exploration_rec_for_you_high_rated)
    RecommendationGroupKind.TOP_RATED -> stringResource(Lang.exploration_rec_top_rated)
    RecommendationGroupKind.THIS_SEASON -> stringResource(Lang.exploration_rec_this_season)
    RecommendationGroupKind.THIS_SEASON_NEW -> stringResource(Lang.exploration_rec_this_season_new)
    RecommendationGroupKind.CHANGE_TASTE -> stringResource(Lang.exploration_rec_change_taste)
    RecommendationGroupKind.TRENDING -> stringResource(Lang.exploration_rec_trending)
    RecommendationGroupKind.FEED -> stringResource(Lang.exploration_recommendations)
}

/**
 * 继续观看卡的进度条 (语义同详情页选集卡): 看到一半按播放位置; 已看完最新一集在等更新 (Watched) / 看完全部 (Done)
 * 显示满条; 看完上一集且有新集 / 还没开始看 (无播放记录) 不显示.
 */
fun padFollowedCardProgress(item: FollowedSubjectInfo?, playHistories: List<EpisodeHistory>): Float? =
    item?.subjectCollectionInfo?.progressInfo?.let { progressInfo ->
        val caughtUp = progressInfo.continueWatchingStatus.let {
            it is ContinueWatchingStatus.Watched || it is ContinueWatchingStatus.Done
        }
        if (caughtUp) {
            1f
        } else {
            progressInfo.nextEpisodeIdToPlay
                ?.let { nextId -> playHistories.firstOrNull { it.episodeId == nextId } }
                ?.let { history ->
                    val duration = history.durationMillis
                    if (duration != null && duration > 0) {
                        (history.positionMillis.toFloat() / duration).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                }
        }
    }
