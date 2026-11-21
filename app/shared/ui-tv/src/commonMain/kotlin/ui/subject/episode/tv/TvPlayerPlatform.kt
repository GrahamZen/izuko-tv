/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeComment
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCommentEdge
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCommentListController
import me.him188.ani.app.ui.subject.details.sections.FocusEpisodeRowSpec
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.danmaku.ui.DanmakuLayerSnapshots
import me.him188.ani.datasources.api.Media
import org.openani.mediamp.MediampPlayer

// 播放页用到、各端不一样的几样: 加入下载、Web 控制台. 安卓接 Web 控制台那一套, iOS 没有控制台也不下载.

/** 这一端能不能把选源里的一条资源加进下载 (选源面板长按「下载」只在能的一端出现). */
internal expect val tvCanCacheMedia: Boolean

/**
 * 把 [media] 加进这一集的下载 (安卓同 Web 控制台播放页的「缓存」: 先查这一集的缓存状态再建), 返回给用户看的一句话; 这一端不支持时 null.
 * 会读写磁盘, 在 IO 上调.
 */
internal expect suspend fun tvCacheMedia(subjectId: Int, episodeId: Int, media: Media): String?

/** 播放页在场期间登记成 Web 控制台的当前播放器 (安卓); 没有控制台的一端什么都不做. */
@Composable
internal expect fun RegisterPlayerOnRemoteConsole(vm: EpisodeViewModel, page: EpisodePageState)

/** 播放页在场期间把截图交给 Web 控制台 (安卓); 没有控制台的一端什么都不做. */
@Composable
internal expect fun RegisterPlayerScreenshotOnRemoteConsole(player: MediampPlayer)

/**
 * 缓冲限速时的提示 (安卓 ExoPlayer 的边下边播在等缓冲攒够: 「再等 N 秒」, 卡第二次起写卡了几次); 不是那种播放器时什么都不画.
 * [buffering] = 播放器此刻在缓冲.
 */
@Composable
internal expect fun TvThrottledBufferHint(player: MediampPlayer, buffering: Boolean, modifier: Modifier = Modifier)

/** 「边下边播」关着、往回跳要联网重新下载时提示一次 (安卓 ExoPlayer); 不是那种播放器时什么都不做. */
@Composable
internal expect fun TvDiskCacheHint(player: MediampPlayer)

/** 遥控器选集条的一行 (安卓是原生的 TvNativeEpisodeRow); iPad 上用平板的选集面板, 这一行不出现. */
@Composable
internal expect fun TvPlayerEpisodeStripRow(spec: FocusEpisodeRowSpec)

/** 挂在弹幕层上, 让 Web 控制台的截图带上弹幕 (安卓); 没有控制台的一端原样返回 [Modifier]. */
@Composable
internal expect fun rememberTvScreenshotDanmakuLayer(snapshots: DanmakuLayerSnapshots): Modifier

/**
 * 遥控器评论面板里的楼列表 (安卓是原生评论列表, 用播放器评论样式); iPad 上评论在平板右栏, 这个面板不出现.
 * 参数同 TvNativeCommentList.
 */
@Composable
internal expect fun TvPlayerCommentsList(
    items: List<TvNativeComment?>,
    touchTaps: Boolean,
    onClick: (index: Int) -> Unit,
    onBind: (index: Int) -> Unit,
    modifier: Modifier,
    controller: TvNativeCommentListController,
    onEdge: (edge: TvNativeCommentEdge, newPress: Boolean) -> Boolean,
    onFocusedPosition: (Int) -> Unit,
)
