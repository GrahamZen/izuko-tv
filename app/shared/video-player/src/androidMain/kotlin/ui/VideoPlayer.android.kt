/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView.ControllerVisibilityListener
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.flow.collectLatest
import me.him188.ani.app.videoplayer.media.LibassExoPlayerMediampPlayer
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.exoplayer.ExoPlayerMediampPlayer
import org.openani.mediamp.exoplayer.compose.ExoPlayerMediampPlayerSurface

@OptIn(UnstableApi::class)
@Composable
actual fun VideoPlayer(
    player: MediampPlayer,
    modifier: Modifier
) {
    val isPreviewing by rememberUpdatedState(me.him188.ani.app.ui.foundation.LocalIsPreviewing.current)

    if (isPreviewing) {
        Box(modifier)
    } else {
        val libassPlayer = player as? LibassExoPlayerMediampPlayer
        val exoPlayer = libassPlayer?.exoMediampPlayer ?: player as ExoPlayerMediampPlayer
        // key(player): 下面这个配置块只在 AndroidView factory 跑一次, 播放器实例变了
        // 不会重新执行 —— 不重建的话新播放器不会进 registerAndroidVideoSurface
        // (dataspace 兜底失效), 旧播放器的清理重试还可能碰到被同值接任的旧 Surface。
        // 换实例就换 SurfaceView, 新旧账各归各的 Surface 代次
        key(player) {
            // 播放器视图里那个字幕视图 (SRT 这类) 与叠在里面的 ASS 视图, 配置块拿到后记下来
            var subtitles by remember { mutableStateOf<SubtitleView?>(null) }
            var assSubtitles by remember { mutableStateOf<LiftableAssSubtitleView?>(null) }
            SubtitleObstructionEffect(exoPlayer.impl, subtitles, assSubtitles)
            ExoPlayerMediampPlayerSurface(exoPlayer, modifier) {
                (videoSurfaceView as? SurfaceView)?.let { registerAndroidVideoSurface(player, it) }
                controllerAutoShow = false
                useController = false
                controllerHideOnTouch = false
                subtitleView?.apply {
                    subtitles = this
                    libassPlayer?.let {
                        assSubtitles = LiftableAssSubtitleView(context, it.assHandler).also { view ->
                            addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        }
                    }
                    this.setStyle(
                        CaptionStyleCompat(
                            Color.WHITE,
                            0x000000FF,
                            0x00000000,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            Color.BLACK,
                            Typeface.DEFAULT,
                        ),
                    )
                }
                setControllerVisibilityListener(
                    ControllerVisibilityListener { visibility ->
                        if (visibility == View.VISIBLE) {
                            hideController()
                        }
                    },
                )
            }
        }
    }
}

/**
 * 控件挡住画面底部时 (见 [LocalSubtitleObstructionTop]) 把底部字幕挪到控件上面, 收起时回到原位, 不触发重组:
 * - SRT 这类没给位置的 ([view]): 改 SubtitleView 的底部留白;
 * - 自带位置的 (PGS 图片字幕、给了行位置的文字字幕, 底部留白管不到): 下半部分的往上挪被挡住的高度, 见 [PositionedCueLift];
 * - ASS ([assView]): 同上, 下半部分往上挪, 上半部分的留在原处.
 */
@OptIn(UnstableApi::class)
@Composable
private fun SubtitleObstructionEffect(player: Player, view: SubtitleView?, assView: LiftableAssSubtitleView?) {
    val obstructionTop = LocalSubtitleObstructionTop.current
    LaunchedEffect(player, view, assView, obstructionTop) {
        if (view == null) return@LaunchedEffect
        val positioned = PositionedCueLift(player, view)
        player.addListener(positioned)
        try {
            val covered = Animatable(0f)
            snapshotFlow { obstructionTop() }.collectLatest { top ->
                covered.animateTo(view.coveredHeightBelow(top), tween(SUBTITLE_LIFT_MILLIS)) {
                    view.setBottomPaddingFraction(bottomPaddingFractionFor(value, view.height))
                    positioned.liftFraction = if (view.height > 0) value / view.height else 0f
                    assView?.bottomLift = value
                }
            }
        } finally {
            player.removeListener(positioned)
            positioned.liftFraction = 0f
        }
    }
}

/**
 * 自带位置的字幕 (cue 给了比例行位置: PGS 这类图片字幕、带定位的文字字幕) 跟着控件往上挪.
 *
 * SubtitleView 的底部留白只作用于没给行位置的字幕, 这些得改 cue 本身: PlayerView 收到一批字幕先原样交给 SubtitleView,
 * 本监听注册在它后面, 紧接着把中心落在下半部分的那些往上挪 [liftFraction] 再设一遍; 挪动距离变了时照最近一批重设.
 */
@OptIn(UnstableApi::class)
private class PositionedCueLift(
    private val player: Player,
    private val view: SubtitleView,
) : Player.Listener {
    /** 往上挪多少 (占字幕视图高度), 0 = 原位. */
    var liftFraction: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            apply(player.currentCues.cues)
        }

    /** 视图上现在是挪过的字幕: 挪动距离回到 0 时要原样重设一次. */
    private var lifted = false

    override fun onCues(cueGroup: CueGroup) {
        apply(cueGroup.cues)
    }

    private fun apply(cues: List<Cue>) {
        if (liftFraction == 0f) {
            if (lifted) view.setCues(cues)
            lifted = false
            return
        }
        view.setCues(cues.map { it.liftedBy(liftFraction) })
        lifted = true
    }
}

/** 给了比例行位置、且中心在下半部分的字幕往上挪 [fraction] (占视图高度); 其余原样. */
@OptIn(UnstableApi::class)
private fun Cue.liftedBy(fraction: Float): Cue {
    if (line == Cue.DIMEN_UNSET || lineType != Cue.LINE_TYPE_FRACTION || verticalType != Cue.TYPE_UNSET) return this
    // 图片字幕有高度, 文字字幕没有, 只看锚点
    val height = if (bitmap != null && bitmapHeight != Cue.DIMEN_UNSET) bitmapHeight else 0f
    val center = when (lineAnchor) {
        Cue.ANCHOR_TYPE_MIDDLE -> line
        Cue.ANCHOR_TYPE_END -> line - height / 2
        else -> line + height / 2 // 没给锚点时按上缘, 同 SubtitlePainter
    }
    if (center <= 0.5f) return this
    return buildUpon().setLine((line - fraction).coerceAtLeast(0f), Cue.LINE_TYPE_FRACTION).build()
}

/** 本视图底部有多高 (像素) 落在 window 坐标 [obstructionTop] 之下, 最多 [SUBTITLE_LIFT_MAX_FRACTION]; 没挡住时为 0. */
private fun View.coveredHeightBelow(obstructionTop: Float?): Float {
    if (obstructionTop == null || height <= 0) return 0f
    val location = IntArray(2).also { getLocationInWindow(it) }
    return (location[1] + height - obstructionTop).coerceIn(0f, height * SUBTITLE_LIFT_MAX_FRACTION)
}

/** 底部被挡住 [covered] 像素时 SubtitleView 的底部留白 (占视图高度); 没挡住时为默认值. */
@OptIn(UnstableApi::class)
private fun bottomPaddingFractionFor(covered: Float, height: Int): Float {
    val default = SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
    if (covered <= 0f || height <= 0) return default
    return (covered / height + SUBTITLE_LIFT_GAP_FRACTION).coerceIn(default, SUBTITLE_LIFT_MAX_FRACTION)
}

/** 字幕跟着控件挪动的时长, 与控制层出现 / 收起的动画相当. */
private const val SUBTITLE_LIFT_MILLIS = 250

/** 挪上去之后与控件之间再空出的距离 (占视图高度). */
private const val SUBTITLE_LIFT_GAP_FRACTION = 0.02f

/** 最多挪到哪儿: 控件很高 (选集条) 时字幕也不顶到画面上半部. */
private const val SUBTITLE_LIFT_MAX_FRACTION = 0.6f
