/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.main.rememberTvPlayingEpisodeStill
import me.him188.ani.app.ui.subject.details.sections.episodeStillImageUrl
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.videoplayer.player.VideoSurfaceFrameSignal

/**
 * 平板播放页: 视频框出第一帧之前先放这一集的剧照, 不是一块黑。剧照同详情页选集卡片那一张 (同一个地址, 从详情页点进来时多半刚下载过),
 * 没有剧照用这一部的 TMDB 横版背景图, 都没有就照旧黑底。压一层暗, 上面的加载提示看得清; 出了第一帧就淡出。
 *
 * 出没出第一帧看播放器的 [VideoSurfaceFrameSignal] (视频面换一代就重新等: 进播放页、从后台回来都算), 拿不到这个信号的播放器不放。
 * 画在视频面之上、手势层与加载提示之下 (见 TvEpisodeScreen 的 playerRoot); 不接触摸, 点它就是点画面。
 */
@Composable
internal fun TabletEpisodeStill(vm: EpisodeViewModel, episodeId: Int, modifier: Modifier = Modifier) {
    val signal = vm.player as? VideoSurfaceFrameSignal ?: return
    val hasFrame by signal.hasFrameOnCurrentSurface.collectAsState()
    val alpha by animateFloatAsState(if (hasFrame) 0f else 1f, tween(TABLET_EPISODE_STILL_FADE_MILLIS))
    if (hasFrame && alpha == 0f) return
    val tmdb = remember { GlobalKoin.get<TmdbImageService>() }
    // 从平板详情页点进来: 那一页的剧照表里就有, 第一帧就拿得到; 别处进来再去查
    val remembered = remember(vm.subjectId, episodeId) { TabletEpisodeStills.get(vm.subjectId, episodeId) }
    val url = remembered
        ?: rememberTvPlayingEpisodeStill(vm.subjectId, episodeId)
        ?: remember(vm.subjectId) { tmdb.peekBackdropUrl(vm.subjectId) }
    if (url == null) return
    val downsample = rememberNsfwPolicy().coverDownsample(vm.subjectId)
    Box(modifier.graphicsLayer { this.alpha = alpha }) {
        AsyncImage(
            url,
            contentDescription = null,
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            downsampleLongEdgePx = downsample,
            // 同选集卡片的解法 (按原图尺寸解, 见 episodeStillImageUrl): 详情页刚解过的那一份直接命中, 不等量尺寸、不重解
            decodeAtOriginalSize = downsample == null,
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = TABLET_EPISODE_STILL_DIM_ALPHA)))
    }
}

/**
 * 平板详情页上各集的剧照 (集 id → 剧照地址, 已换成选集卡片那一档), 按条目记最近几部: 从详情页点进播放页时, 视频框第一帧就同步拿得到
 * (见 [TabletEpisodeStill]), 不等后台再读一遍剧照缓存、再认一遍集. 由 SubjectDetailsPadPage 写. 只在主线程读写.
 */
internal object TabletEpisodeStills {
    private const val CAPACITY = 4
    private val bySubject = LinkedHashMap<Int, Map<Int, String>>()

    fun put(subjectId: Int, stills: Map<Int, String>) {
        if (stills.isEmpty()) return
        bySubject.remove(subjectId)
        bySubject[subjectId] = stills
        while (bySubject.size > CAPACITY) bySubject.remove(bySubject.keys.first())
    }

    fun get(subjectId: Int, episodeId: Int): String? = bySubject[subjectId]?.get(episodeId)?.let(::episodeStillImageUrl)
}

/** 出第一帧后剧照淡出用多久. */
private const val TABLET_EPISODE_STILL_FADE_MILLIS = 250

/** 剧照上压暗的那一层: 白字的加载提示在亮的剧照上也看得清. */
private const val TABLET_EPISODE_STILL_DIM_ALPHA = 0.45f
