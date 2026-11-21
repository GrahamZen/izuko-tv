/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.platform.features.BrightnessManager
import me.him188.ani.app.platform.features.StreamType
import me.him188.ani.app.platform.features.getComponentAccessors
import me.him188.ani.app.videoplayer.ui.gesture.GestureIndicatorState
import me.him188.ani.app.videoplayer.ui.gesture.LevelController
import me.him188.ani.app.videoplayer.ui.gesture.asLevelController
import kotlin.math.roundToInt

/**
 * 平板模式画面上的纵向拖动 (同哔哩哔哩): 左三分之一调亮度, 右三分之一调系统媒体音量 (中间那列是进出全屏, 见 TvPlayerTouchLayer);
 * 拖过一整个画面高 = 从最小到最大.
 * 调的时候画面上方居中出现指示 ([indicator], 上游播放器的 GestureIndicator), 松手半秒后消失.
 *
 * 亮度只改本窗口 (不改系统设置), 离开播放页还原成跟随系统 ([rememberTabletLevelGestures]).
 * 起手判方向、分左右由手势层 (TvEpisodeScreen 的 TvPlayerTouchLayer) 做, 这里只管从起手那一刻的值往上加减.
 */
@Stable
internal class TabletLevelGestures(
    private val brightnessManager: BrightnessManager?,
    private val volume: LevelController?,
    private val scope: CoroutineScope,
) {
    val indicator = GestureIndicatorState()

    private val brightness = brightnessManager?.asLevelController()
    private var target: LevelController? = null
    private var targetIsBrightness = false
    private var startLevel = 0f
    private var lastSet = Float.NaN
    private var brightnessChanged = false

    /** 起手: [left] 为真调亮度, 否则调音量. 这台设备调不了那一样时返回 false (手势层放弃这次拖动). */
    fun start(left: Boolean): Boolean {
        val controller = (if (left) brightness else volume) ?: return false
        target = controller
        targetIsBrightness = left
        startLevel = controller.level.coerceIn(controller.range)
        lastSet = Float.NaN
        return true
    }

    /** 拖动中: [heightFraction] 是相对起手点的纵向位移, 以画面高为单位, 向上为正. */
    fun drag(heightFraction: Float) {
        val controller = target ?: return
        val range = controller.range
        val span = range.endInclusive - range.start
        if (span <= 0f) return
        val raw = (startLevel + heightFraction * span).coerceIn(range)
        // 音量只有十几档: 落到同一档就不再调, 免得每个触摸事件都调一次系统接口
        val step = controller.levelStep.takeIf { it > 0f } ?: TABLET_LEVEL_MIN_STEP
        val level = ((raw - range.start) / step).roundToInt() * step + range.start
        if (level == lastSet) return
        lastSet = level
        controller.setLevel(level.coerceIn(range))
        val ratio = ((level - range.start) / span).coerceIn(0f, 1f)
        val isBrightness = targetIsBrightness
        if (isBrightness) brightnessChanged = true
        scope.launch {
            if (isBrightness) indicator.showBrightnessRange(ratio) else indicator.showVolumeRange(ratio)
        }
    }

    fun end() {
        target = null
    }

    /** 调过亮度就还原成跟随系统. */
    fun restoreBrightness() {
        if (!brightnessChanged) return
        brightnessChanged = false
        brightnessManager?.setBrightness(TABLET_BRIGHTNESS_FOLLOW_SYSTEM)
    }
}

/** 平板模式播放页的亮度 / 音量拖动; 离开播放页 (这一段组合销毁) 时把亮度还原成跟随系统. */
@Composable
internal fun rememberTabletLevelGestures(): TabletLevelGestures {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gestures = remember(context, scope) {
        val components = context.getComponentAccessors()
        TabletLevelGestures(
            brightnessManager = components.brightnessManager,
            volume = components.audioManager?.asLevelController(StreamType.MUSIC),
            scope = scope,
        )
    }
    DisposableEffect(gestures) {
        onDispose { gestures.restoreBrightness() }
    }
    return gestures
}

/** 窗口亮度「不覆盖, 跟随系统」(安卓 `WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE`; ui-tv 只编安卓). */
private const val TABLET_BRIGHTNESS_FOLLOW_SYSTEM = -1f

/** 控制器没给最小档时按百分之一调. */
private const val TABLET_LEVEL_MIN_STEP = 0.01f
