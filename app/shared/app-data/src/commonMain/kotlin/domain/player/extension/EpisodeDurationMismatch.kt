/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.episode.EpisodeDurations
import me.him188.ani.app.domain.episode.MediaFetchSelectBundle
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.selector.MediaAutoSelector
import me.him188.ani.app.domain.media.selector.MediaSelector
import me.him188.ani.app.domain.media.selector.MediaSelectorSourceTiers
import me.him188.ani.app.domain.media.selector.SelectOrigin
import me.him188.ani.datasources.api.Media
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.openani.mediamp.MediampPlayer
import kotlin.time.Duration.Companion.seconds

/**
 * 自动选中的资源打开后, 播放器给出的时长与这一集 (条目服务记的时长) 差得离谱 ([EpisodeDurations.isMismatch]) 时换一条.
 * 这样的资源页面名与这一集相同, 内容却是同名的剧场版、真人电影或总集篇 (「辉夜大小姐想让我告白：天才们的恋爱头脑战」页面上
 * 唯一一条「1080P」是 2019 年的真人电影); 网页源打开前不知道时长, 只能在这里认. 网盘的时长列文件时就有, 选源时已经去掉.
 *
 * 换的是这一条, 不是这个源: 不当作播放失败 (不记数据源的失败), 只排除这一条与同一个源里同名的其它线路
 * (同一页的另一条线路是同一部片子), 先在同一个源里挑 (这个源多半有正确的那一集), 没有再按平常的顺序换别的源.
 * 用户手选的、回放手动查找记住的线路 ([SelectOrigin.MANUAL] / [SelectOrigin.BROWSE_MEMORY]) 不查. 这一集的时长不知道时不查.
 *
 * @param episodeId 正在播的这一集
 */
internal suspend fun PlayerLoadErrorHandler.observeEpisodeDurationMismatch(
    player: MediampPlayer,
    bundles: Flow<MediaFetchSelectBundle?>,
    episodeId: Int,
    getSourceTiers: suspend () -> MediaSelectorSourceTiers,
) {
    bundles.collectLatest { bundle ->
        if (bundle == null) return@collectLatest
        val selector = bundle.mediaSelector
        coroutineScope {
            selector.events.onSelect.collectLatest select@{ event ->
                val media = event.media ?: return@select
                if (event.origin != SelectOrigin.AUTOMATIC) return@select
                val expectedSeconds = bundle.mediaFetchSession.request.first().episodes
                    .firstOrNull { it.episodeId == episodeId.toString() }?.durationSeconds
                    ?: return@select
                // 播放器换资源时先清空 mediaData 与时长, 打开后再填上: 等到一份新的 mediaData 带着时长, 才是这一条的
                val previousData = player.mediaData.value
                val actualMillis = combine(player.mediaData, player.mediaProperties) { data, properties ->
                    properties?.durationMillis?.takeIf { data != null && data !== previousData && it > 0 }
                }.filterNotNull().first()
                if (selector.selected.value != media) return@select
                if (!EpisodeDurations.isMismatch(actualMillis / 1000, expectedSeconds)) return@select
                logger.info {
                    "Media ${media.mediaId} lasts ${actualMillis / 1000}s, episode $episodeId lasts ${expectedSeconds}s: not this episode, switching"
                }
                // 换源会改 selected、发新的选择事件, 正是本 collectLatest 的取消条件: 放到外层作用域里跑
                launch { switchFromWrongEpisode(bundle.mediaFetchSession, selector, media, getSourceTiers()) }
            }
        }
    }
}

/** 排除 [wrong] 与同一个源里同名的线路, 先在 [wrong] 的源里挑, 没有再按平常的顺序换. */
internal suspend fun PlayerLoadErrorHandler.switchFromWrongEpisode(
    session: MediaFetchSession,
    selector: MediaSelector,
    wrong: Media,
    sourceTiers: MediaSelectorSourceTiers,
) {
    selector.filteredCandidatesMedia.first()
        .filter { it.mediaSourceId == wrong.mediaSourceId && it.originalTitle == wrong.originalTitle }
        .forEach { blacklist(it.mediaId) }
    blacklist(wrong.mediaId)
    val result = MediaAutoSelector(selector).select(
        session,
        MediaAutoSelector.Config(
            preferredSourceId = wrong.mediaSourceId,
            selectCache = false,
            blacklist = blacklistedMedia,
            web = MediaAutoSelector.Web(
                sourceTiers = sourceTiers,
                exactMatchAfter = 1.seconds,
                fuzzyMatchAfter = 1.seconds,
                waitForPendingSources = false,
            ),
        ),
        expectedSelection = wrong,
    )
    logger.info { "Switched away from ${wrong.mediaId} for a wrong duration: $result" }
}

private val logger = logger<PlayerLoadErrorHandler>()
