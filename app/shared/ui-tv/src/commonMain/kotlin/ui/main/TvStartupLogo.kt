/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.platform.ProfileSwitchFrame
import me.him188.ani.app.ui.foundation.AniStartupProgress
import me.him188.ani.app.ui.foundation.Res
import me.him188.ani.app.ui.foundation.StartupProgressTracker
import me.him188.ani.app.ui.foundation.app_icon
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.tv.TvPolishFlags
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.jetbrains.compose.resources.painterResource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * 冷启动的启动页盖不盖着、走到哪一步. 进程里只有一份 (见 [TvStartupLogoHost]): 一打开应用就由入口的占位先画上 (那时主题设置还没读出来),
 * 读出来之后根部接着盖同一份.
 *
 * - 进主页: 首屏在它背后照常加载, 封面出来了再撤 ([dismissWhenFirstScreenReady], 见 [StartupProgressTracker.awaitFirstScreenReady]).
 * - 走首次引导 ([onboarding]): 没有要等的, 不画进度条; 欢迎页排好后图标移到欢迎页的图标上、底色淡出露出引导页 ([handOffToWelcome]).
 * - 打开应用先选人: 首屏同样在背后加载完, 再把选人页放出来盖在上面, 盖住了才撤 ([handOffToPicker]).
 */
@Stable
class TvStartupLogoState(private val tracker: StartupProgressTracker, val onboarding: Boolean) {
    var visible by mutableStateOf(true)
        private set

    /** 进度条的进度, 0..1. */
    val fraction: Flow<Float> get() = tracker.fraction

    /** 此刻的进度, 进度条第一帧用 (见 [StartupProgressTracker.currentFraction]). */
    val currentFraction: Float get() = tracker.currentFraction

    /** 欢迎页图标的位置 (根坐标系), 由欢迎页报上来; 交接时图标照它的最新位置走. */
    var welcomeIcon: Rect? by mutableStateOf(null)

    /** 交接给欢迎页图标的进度: 0 = 启动页原样, 1 = 图标落在 [welcomeIcon] 上. 在绘制里读. */
    var handoff by mutableFloatStateOf(0f)
        private set

    /** 图标落地之后底色淡出的进度: 0 = 不透明, 1 = 全透明 (露出引导页). 在绘制里读. */
    var reveal by mutableFloatStateOf(0f)
        private set

    /** 启动页的图标还没落到欢迎页图标上: 欢迎页先别画自己的图标, 不然两个图标叠着走位. 在绘制里读. */
    val coversWelcomeIcon: Boolean get() = visible && handoff < 1f

    private val shownAt = TimeSource.Monotonic.markNow()

    /** 等首屏准备好再撤. */
    suspend fun dismissWhenFirstScreenReady() {
        val start = TimeSource.Monotonic.markNow()
        tracker.awaitFirstScreenReady()
        val (finished, started) = tracker.coverCounts
        logger.info { "Startup logo dismissed after ${start.elapsedNow().inWholeMilliseconds}ms, covers $finished/$started" }
        dismiss()
    }

    /**
     * 交接给选人页 (打开应用先选人时): 首屏在背后加载完、主线程闲下来, 再 [reveal] 放出选人页 (在启动页上淡入),
     * 选人页进场放完 (整页盖住、头像都停下) 才撤, 不淡出.
     * 建主页的那几秒主线程很忙 (探索页第一次组合、海报墙第一次布局各要大半秒), 选人页的进场动画放在那时会卡住再跳过去;
     * 启动页这段掉帧不碍事. 选人页本身先建好藏着 (见 TvUserProfilePicker.show), 放出来时不用再等它组合.
     */
    suspend fun handOffToPicker(reveal: suspend () -> Unit) {
        val start = TimeSource.Monotonic.markNow()
        tracker.awaitFirstScreenReady()
        val ready = start.elapsedNow()
        awaitMainThreadIdle()
        val (finished, started) = tracker.coverCounts
        logger.info {
            "Startup logo handing off to the profile picker: first screen ready after ${ready.inWholeMilliseconds}ms, " +
                    "idle after ${start.elapsedNow().inWholeMilliseconds}ms, covers $finished/$started"
        }
        val revealStart = TimeSource.Monotonic.markNow()
        reveal()
        logger.info { "Startup logo dismissed under the profile picker, picker entrance took ${revealStart.elapsedNow().inWholeMilliseconds}ms" }
        dismiss(fadeOut = false)
    }

    /**
     * 交接给引导的欢迎页: 先在底色上把图标从中间移到欢迎页图标上并变成它的大小, 落地后底色再淡出露出引导页, 最后撤掉
     * (欢迎页的图标接着画, 位置大小都一样). 分两段是因为欢迎页的标题就在图标下面, 图标一路从下往上压着它走:
     * 边走边淡的话, 图标会从半透明的字上扫过去.
     * 至少先露 [TV_STARTUP_LOGO_ONBOARDING_MIN_MILLIS] —— 快的机器上设置一读好欢迎页就排好了, 那样 logo 一闪就走.
     * 没有欢迎页 (进程重建恢复到了检测网络那几页) 就直接淡出.
     */
    suspend fun handOffToWelcome() {
        tracker.stop()
        delay((TV_STARTUP_LOGO_ONBOARDING_MIN_MILLIS.milliseconds - shownAt.elapsedNow()).coerceAtLeast(0.milliseconds))
        val target = withTimeoutOrNull(TV_STARTUP_LOGO_WELCOME_WAIT_MILLIS) {
            snapshotFlow { welcomeIcon }.filterNotNull().first()
        }
        if (target == null) {
            dismiss()
            return
        }
        // 欢迎页先画出一帧, 底色淡出时下面不是空的
        withFrameNanos {}
        animate(0f, 1f, animationSpec = tween(TV_STARTUP_LOGO_HANDOFF_MILLIS, easing = FastOutSlowInEasing)) { value, _ ->
            handoff = value
        }
        animate(0f, 1f, animationSpec = tween(TV_STARTUP_LOGO_REVEAL_MILLIS)) { value, _ -> reveal = value }
        logger.info { "Startup logo handed off to the onboarding welcome page" }
        dismiss()
    }

    /** 撤的时候淡出 (见 [dismiss]). */
    var fadeOutOnDismiss by mutableStateOf(true)
        private set

    /**
     * @param fadeOut 淡出着撤. 盖在选人页底下时不淡 (看不见; 淡出的每一帧主页都要整页重画, 白占选人页也要用的渲染线程)
     */
    fun dismiss(fadeOut: Boolean = true) {
        fadeOutOnDismiss = fadeOut
        visible = false
        tracker.stop()
    }
}

/**
 * 本进程的启动页: 第一次问时决定出不出 —— 冷启动 ([StartupProgressTracker.claimColdStart]) 且开关开着 ([TvPolishFlags.startupLogo]);
 * 之后问到的是同一份, 入口的占位与根部的启动页因此接得上. 撤掉之后返回 null (Activity 重建不再出).
 * 换人重启进来的那次不出: 盖着的是换人的过场 (见 ProfileSwitchFrame.landing), 它用同一套首屏计数等首屏, 自己收尾.
 */
object TvStartupLogoHost {
    private var state: TvStartupLogoState? = null

    /** [onboarding]: 这次启动走首次引导. */
    fun coldStart(onboarding: Boolean): TvStartupLogoState? {
        if (AniStartupProgress.claimColdStart()) {
            state = when {
                ProfileSwitchFrame.landing.value != null -> null
                TvPolishFlags.startupLogo -> TvStartupLogoState(AniStartupProgress, onboarding)
                else -> {
                    AniStartupProgress.stop()
                    null
                }
            }
        }
        return state?.takeIf { it.visible }
    }
}

/** 当前盖着的启动页 (没有时为 null), 给引导的欢迎页报图标位置用 (见 [TvStartupLogoState.handOffToWelcome]). */
val LocalTvStartupLogo: ProvidableCompositionLocal<TvStartupLogoState?> = staticCompositionLocalOf { null }

/** 启动页的颜色. 根部取主题的 ([tvStartupLogoColors]); 主题设置读出来之前的占位用上次记下的. */
@Immutable
data class TvStartupLogoColors(val background: Color, val track: Color, val fill: Color)

/** 主题里的启动页颜色: 外壳底色 (与窗口底色同一个), 进度条的底轨与填充. */
@Composable
fun tvStartupLogoColors(): TvStartupLogoColors {
    val background = AniThemeDefaults.shellBackgroundColor
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val fill = MaterialTheme.colorScheme.primary
    return remember(background, track, fill) { TvStartupLogoColors(background, track, fill) }
}

/**
 * 冷启动的启动页: 整屏底色 + 应用图标 + 进度条, 盖在主页上; 撤的时候淡出. 走首次引导时没有进度条 (位置照样占着, 图标与进主页时在同一处).
 *
 * 底色与窗口底色是同一个 (见 MainActivity 的 WindowBackgroundMirror): 界面组合出来之前窗口露出的那一屏接到它不会变色.
 * 盖着期间吞掉触摸; 按键由调用方在根部拦.
 */
@Composable
fun TvStartupLogo(state: TvStartupLogoState, colors: TvStartupLogoColors, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        state.visible,
        modifier,
        enter = EnterTransition.None,
        exit = if (state.fadeOutOnDismiss) fadeOut(tween(STARTUP_LOGO_FADE_OUT_MILLIS)) else ExitTransition.None,
    ) {
        Box(
            Modifier.fillMaxSize()
                // 交接给欢迎页时, 图标落地后底色淡出, 露出引导页
                .drawBehind { drawRect(colors.background, alpha = 1f - state.reveal) }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                var own by remember { mutableStateOf<Rect?>(null) }
                Image(
                    painterResource(Res.drawable.app_icon),
                    contentDescription = null,
                    Modifier.size(STARTUP_LOGO_ICON_SIZE)
                        .onGloballyPositioned { own = it.boundsInRoot() }
                        // 交接: 从自己的位置移到欢迎页图标上并缩放成它的大小, 以左上角为原点
                        .graphicsLayer {
                            val p = state.handoff
                            val from = own
                            val to = state.welcomeIcon
                            if (p > 0f && from != null && to != null && from.width > 0f) {
                                transformOrigin = TransformOrigin(0f, 0f)
                                val scale = lerp(1f, to.width / from.width, p)
                                scaleX = scale
                                scaleY = scale
                                translationX = lerp(0f, to.left - from.left, p)
                                translationY = lerp(0f, to.top - from.top, p)
                            }
                        }
                        .clip(RoundedCornerShape(STARTUP_LOGO_ICON_CORNER)),
                )
                Spacer(Modifier.height(STARTUP_LOGO_BAR_GAP))
                if (state.onboarding) {
                    Spacer(Modifier.size(STARTUP_LOGO_BAR_WIDTH, STARTUP_LOGO_BAR_HEIGHT))
                } else {
                    val target by state.fraction.collectAsState(state.currentFraction)
                    val shown = animateFloatAsState(target, tween(STARTUP_LOGO_PROGRESS_MILLIS))
                    Box(
                        Modifier.size(STARTUP_LOGO_BAR_WIDTH, STARTUP_LOGO_BAR_HEIGHT).drawBehind {
                            val radius = CornerRadius(size.height / 2)
                            drawRoundRect(colors.track, cornerRadius = radius)
                            drawRoundRect(
                                colors.fill,
                                size = Size(size.width * shown.value, size.height),
                                cornerRadius = radius,
                            )
                        },
                    )
                }
            }
        }
    }
}

private val logger = logger("TvStartupLogo")

private val STARTUP_LOGO_ICON_SIZE = 96.dp

/** 与欢迎页的图标同一个比例 (120dp 配 28dp 圆角): 交接时缩放过去, 圆角正好对上. */
private val STARTUP_LOGO_ICON_CORNER = 22.4.dp
private val STARTUP_LOGO_BAR_GAP = 32.dp
private val STARTUP_LOGO_BAR_WIDTH = 168.dp
private val STARTUP_LOGO_BAR_HEIGHT = 4.dp

private const val STARTUP_LOGO_FADE_OUT_MILLIS = 200
private const val STARTUP_LOGO_PROGRESS_MILLIS = 200

/** 走引导时 logo 至少露多久再交接给欢迎页. */
private const val TV_STARTUP_LOGO_ONBOARDING_MIN_MILLIS = 800

/** 等欢迎页报图标位置最多等多久; 等不到 (恢复到了别的步骤) 就直接淡出. */
private const val TV_STARTUP_LOGO_WELCOME_WAIT_MILLIS = 500L

/** 图标移到欢迎页图标上的时长. */
private const val TV_STARTUP_LOGO_HANDOFF_MILLIS = 550

/** 图标落地之后底色淡出 (露出欢迎页) 的时长. */
private const val TV_STARTUP_LOGO_REVEAL_MILLIS = 250

/**
 * 等主线程闲下来: 连续 [TV_STARTUP_LOGO_IDLE_FRAMES] 帧都按时出 (与上一帧相隔不超过 [TV_STARTUP_LOGO_IDLE_FRAME_MILLIS]),
 * 最多等 [TV_STARTUP_LOGO_IDLE_WAIT_MILLIS]. 首屏的封面都加载完之后主页还会忙一阵 (解码好的图上屏、行内重组).
 */
private suspend fun awaitMainThreadIdle() {
    val frameNanos = TV_STARTUP_LOGO_IDLE_FRAME_MILLIS * 1_000_000L
    withTimeoutOrNull(TV_STARTUP_LOGO_IDLE_WAIT_MILLIS) {
        var last = withFrameNanos { it }
        var onTime = 0
        while (onTime < TV_STARTUP_LOGO_IDLE_FRAMES) {
            val now = withFrameNanos { it }
            onTime = if (now - last <= frameNanos) onTime + 1 else 0
            last = now
        }
    }
}

/** 连续这么多帧按时出算主线程闲下来 (60Hz 下约 130ms). */
private const val TV_STARTUP_LOGO_IDLE_FRAMES = 8

/** 一帧与上一帧最多相隔多久算按时 (60Hz 一帧 16.7ms, 掉一帧就是 33ms; 50Hz 一帧 20ms). */
private const val TV_STARTUP_LOGO_IDLE_FRAME_MILLIS = 25

/** 等主线程闲下来最多等多久, 等不到也放出选人页. */
private const val TV_STARTUP_LOGO_IDLE_WAIT_MILLIS = 2000L
