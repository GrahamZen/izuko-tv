/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.profile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.app.platform.ProfileSwitchFrame
import me.him188.ani.app.platform.ProfileSwitchFrameDrawable
import me.him188.ani.app.ui.foundation.AniStartupProgress
import kotlin.math.roundToInt

/**
 * 换人重启进来时盖在主界面最上面的那一帧 ([ProfileSwitchFrame.landing]: 旧进程定格截下的「正在切换」与进度条, 与重启途中的
 * 中转页、落地页画的是同一个东西), 首屏封面加载好了进度条走满再淡出, 露出新用户的首页. 等首屏用的是冷启动启动页那一套
 * ([AniStartupProgress]; 这次启动不出启动页, 见 TvStartupLogoHost), 进度条在这一段按它的进度往上走.
 * 装在 TV 根部、页面内容之上; 不是换人重启进来的什么都不画. 盖着期间的按键与返回由主界面拦下 (见 MainActivity).
 */
@Composable
fun TvProfileSwitchLandingHost() {
    val landing by ProfileSwitchFrame.landing.collectAsState()
    val drawable = landing ?: return
    val context = LocalContext.current
    val alpha = remember(drawable) { Animatable(1f) }
    LaunchedEffect(drawable) {
        val coversProgress = launch {
            AniStartupProgress.fraction.collect { drawable.floor = COVERS_FLOOR_BASE + COVERS_FLOOR_SPAN * it }
        }
        AniStartupProgress.awaitFirstScreenReady()
        coversProgress.cancel()
        AniStartupProgress.stop()
        drawable.finish()
        delay(ProfileSwitchFrameDrawable.FINISH_MILLIS)
        alpha.animateTo(0f, tween(FADE_OUT_MILLIS, easing = FastOutLinearInEasing))
        withContext(Dispatchers.IO) { ProfileSwitchFrame.release(context) }
    }
    ProfileSwitchFrameImage(drawable, Modifier.graphicsLayer { this.alpha = alpha.value })
}

/** 铺满画 [drawable] (换人过场的那一帧与进度条), 进度条在走所以逐帧重画 (只在绘制阶段读帧时间, 不重组). */
@Composable
internal fun ProfileSwitchFrameImage(drawable: ProfileSwitchFrameDrawable, modifier: Modifier = Modifier) {
    val frameTime = remember { mutableLongStateOf(0L) }
    LaunchedEffect(drawable) {
        while (true) withFrameMillis { frameTime.longValue = it }
    }
    Canvas(modifier.fillMaxSize()) {
        frameTime.longValue
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}

/** 主界面这一段进度条的下限: 首屏一点没加载时在这, 全加载完到 [COVERS_FLOOR_BASE] + [COVERS_FLOOR_SPAN] */
private const val COVERS_FLOOR_BASE = 0.5f
private const val COVERS_FLOOR_SPAN = 0.4f

private const val FADE_OUT_MILLIS = 320
