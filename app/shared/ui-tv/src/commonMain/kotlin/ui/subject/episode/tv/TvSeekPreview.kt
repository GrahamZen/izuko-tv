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
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.videoplayer.ui.progress.MediaProgressFramePreviewState

/**
 * 拖动进度条时的画面预览, 播放页只经这几样用它. 安卓是主播放器停着解出预览位置的画面 (PlayerSeekPreviewState; 设置成画在全屏上
 * 或开着隧道播放时直接出在全屏上); iOS 上没有, [rememberTvSeekPreview] 给 null, 浮窗只显示时间.
 */
@Stable
internal interface TvSeekPreview {
    /** 浮窗里的小画面. */
    val framePreview: MediaProgressFramePreviewState

    /** 主播放器停在预览的画面上 (视频面盖住, 别闪出别处的画面). */
    val holdsFrame: Boolean

    /** 预览画在全屏上: 全屏上的其它东西 (字幕等) 先藏起来. */
    val hidesFullScreenExtras: Boolean

    /** 进预览 (圆点开始挪). */
    fun begin()

    fun seekTo(positionMillis: Long)

    /** 结束预览并从 [positionMillis] 播. */
    fun commit(positionMillis: Long)

    /** 不跳了, 回到原处. */
    fun cancel()

    /** 画在全屏上的预览的标注层 (叠在视频与弹幕之上、控制层之下). */
    @Composable
    fun FullScreenLayer(modifier: Modifier)
}

/** [enabled] = 设置「显示视频帧预览」开着; 关着或这一端没有时 null. */
@Composable
internal expect fun rememberTvSeekPreview(vm: EpisodeViewModel, enabled: Boolean): TvSeekPreview?
