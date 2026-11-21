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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.domain.episode.SubjectRecommendation
import me.him188.ani.app.domain.episode.preferredDisplayName
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.tv.tvTmdbSubjectTitleFilled
import me.him188.ani.app.ui.foundation.tv.tvUseOriginalTitle
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_episode_related_recommendations
import me.him188.ani.app.ui.lang.subject_episode_related_recommendations_empty
import me.him188.ani.app.ui.lang.tablet_player_rec_also_watched
import me.him188.ani.app.ui.lang.tablet_player_rec_similar
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.ui.subject.episode.tv.openRecommendation
import org.jetbrains.compose.resources.stringResource

/**
 * 右栏没开面板时的内容 (同 YouTube / 哔哩哔哩平板的右栏): 栏顶「相关推荐」, 下面一列推荐行, 能滑.
 */
@Composable
internal fun TabletRecommendationsColumn(vm: EpisodeViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Text(
            stringResource(Lang.subject_episode_related_recommendations),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TabletRecommendationRows(vm)
        }
    }
}

/**
 * 推荐行, 摆进调用方的 Column (右栏、竖屏时视频下面那一块的末尾都用它).
 * 一行 = 横图 + 名字 (两行) + 另一个名字 + 推荐理由 (Bangumi「看过这部的人也看过」/ 推荐系统里「和这部相似」);
 * 数据同电视播放器的相关推荐胶囊 (`episodeDetailsState.recommendations`), NSFW 照设置隐藏 / 打码. 点一行进那一部的详情页.
 * 还没拿到时转圈; 一部都没有时写一句.
 */
@Composable
internal fun TabletRecommendationRows(vm: EpisodeViewModel) {
    val loaded by vm.episodeDetailsState.recommendations
    val nsfw = rememberNsfwPolicy()
    val recommendations = nsfw.visible(loaded.orEmpty()) { it.subjectId?.toInt() ?: -1 }
    when {
        loaded == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp))
        }

        recommendations.isEmpty() -> Text(
            stringResource(Lang.subject_episode_related_recommendations_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        else -> {
            val navigator = LocalNavigator.current
            val useOriginalTitle = tvUseOriginalTitle()
            for (recommendation in recommendations) {
                val subjectId = recommendation.subjectId?.toInt() ?: continue
                key(recommendation.uniqueId) {
                    val title = tvTmdbSubjectTitleFilled(subjectId) ?: recommendation.preferredDisplayName(useOriginalTitle)
                    val other = (if (title == recommendation.name) recommendation.nameCn else recommendation.name)
                        ?.takeIf { it.isNotBlank() && it != title }
                    TabletRecommendationRow(
                        subjectId = subjectId,
                        originalName = recommendation.name,
                        imageUrl = recommendation.imageUrl,
                        title = title,
                        otherName = other,
                        reason = when (recommendation.source) {
                            SubjectRecommendation.Source.ALSO_WATCHED -> stringResource(Lang.tablet_player_rec_also_watched)
                            SubjectRecommendation.Source.SIMILAR -> stringResource(Lang.tablet_player_rec_similar)
                        },
                        onClick = { openRecommendation(navigator, recommendation) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TabletRecommendationRow(
    subjectId: Int,
    originalName: String,
    imageUrl: String,
    title: String,
    otherName: String?,
    reason: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TabletRecommendationImage(subjectId, originalName, imageUrl, Modifier.width(TABLET_REC_IMAGE_WIDTH))
        Column(Modifier.weight(1f).padding(top = 1.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (otherName != null) {
                Text(
                    otherName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(reason, style = MaterialTheme.typography.labelSmall, color = tabletAccent, maxLines = 1)
        }
    }
}

/**
 * 推荐行左边的横图: TMDB 的横版背景图 (hero 那张), 没有时用 Bangumi 封面裁成横的. 宽由调用方定, 高按 16:9.
 */
@Composable
private fun TabletRecommendationImage(
    subjectId: Int,
    originalName: String,
    posterUrl: String,
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
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(24.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.25f)))),
        )
    }
}

/** 推荐行左边横图的宽 (高按 16:9). */
private val TABLET_REC_IMAGE_WIDTH = 136.dp
