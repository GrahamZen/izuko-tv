/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import me.him188.ani.app.pip.rememberPictureInPictureController
import me.him188.ani.app.pip.shouldAutoEnterPictureInPicture
import me.him188.ani.app.ui.foundation.LocalPlatform
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.playback_session_now_playing
import me.him188.ani.app.ui.lang.subject_episode_close
import me.him188.ani.app.ui.lang.video_player_pause
import me.him188.ani.app.ui.lang.video_player_play
import me.him188.ani.app.videoplayer.ui.VideoPlayer
import org.jetbrains.compose.resources.stringResource
import me.him188.ani.utils.platform.isIos
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.MediaStatus
import kotlin.time.Duration.Companion.seconds

/**
 * 触屏 (平板 / iPad) 上离开播放页后的应用内小窗: 保留着的播放会话 ([RetainedPlaybackSessionHolder]) 缩到角落里接着放.
 * 系统画中画在安卓上是把整个应用缩成小窗, 做不到「一边看一边翻别的页」, 所以两个平台都用这个自己画的.
 *
 * [vm] 是要在小窗里放的会话, 不显示时为 null: 在播放页上、用户点 × 收起过这个会话 (回到播放页后忘掉)、遥控器形态都不显示.
 * 显示着就算「看得见」: 根部把它与播放页一起报给 holder ([RetainedPlaybackSessionHolder.setPlayerPageVisible]), 离开播放页不按暂停.
 */
@Stable
class TouchMiniPlayer internal constructor(
    val vm: EpisodeViewModel?,
    /** 停在哪个角、宽多少 (见 [MiniPlayerFrame]): 收起再出来、换了会话都还是用户摆好的样子. */
    internal val corner: MutableIntState,
    internal val widthFraction: MutableFloatState,
    /**
     * 正在铺满全屏接住系统小窗的「还原」: 底下已经展开成播放页, 小窗还留着 (同一块视频图层, 系统收回落在它上面不黑),
     * 等播放页自己的画面出来再撤 (见 [MiniPlayerSystemPictureInPicture]).
     */
    internal val takeover: Boolean,
    private val onTakeover: (Boolean) -> Unit,
    private val onDismiss: () -> Unit,
) {
    val isShown: Boolean get() = vm != null

    internal fun setTakeover(on: Boolean) {
        logger.info { "[PiP] 应用内小窗${if (on) "铺满全屏接住还原" else "撤掉铺满的那一层"}" }
        onTakeover(on)
    }

    /** 收起 (× 按钮): 暂停并不再显示这个会话, 会话照样留着 (侧边栏「正在播放」回得去). */
    fun dismiss() {
        logger.info { "[PiP] 应用内小窗: 点了收起" }
        vm?.player?.pause()
        onDismiss()
    }
}

/** 见 [TouchMiniPlayer]; [holder] 为 null (本形态不保留会话) 时永远不显示. */
@Composable
fun rememberTouchMiniPlayer(holder: RetainedPlaybackSessionHolder?, onPlayerPage: Boolean): TouchMiniPlayer {
    val touch = LocalTvTouchInputEnabled.current
    var dismissed by remember { mutableStateOf<EpisodeViewModel?>(null) }
    // 铺满全屏接住还原的那个会话 (见 TouchMiniPlayer.takeover): 已经在播放页上也先留着
    var takeover by remember { mutableStateOf<EpisodeViewModel?>(null) }
    // 回到播放页就忘掉「收起过」: 下次离开照样缩成小窗
    LaunchedEffect(onPlayerPage) {
        if (onPlayerPage) dismissed = null
    }
    val corner = rememberSaveable { mutableIntStateOf(MINI_PLAYER_DEFAULT_CORNER) }
    val widthFraction = rememberSaveable { mutableFloatStateOf(MINI_PLAYER_SMALL_FRACTION) }
    val vm = holder?.currentViewModel?.takeIf { touch && (it === takeover || !onPlayerPage && it !== dismissed) }
    val shown = vm != null
    LaunchedEffect(shown) { logger.info { "[PiP] 应用内小窗${if (shown) "出现" else "不显示"} (在播放页=$onPlayerPage)" } }
    val takingOver = vm != null && vm === takeover
    return remember(vm, takingOver) {
        TouchMiniPlayer(vm, corner, widthFraction, takingOver, onTakeover = { on -> takeover = if (on) vm else null }) { dismissed = vm }
    }
}

/**
 * 小窗本身, 盖在导航之上 (挂在应用根部), 摆法与手势见 [MiniPlayerFrame]: 默认右下角, 可拖、可捏合缩放、双击切两档大小.
 * 点一下出控件 (播放 / 暂停、回到播放页、收起), 3 秒后自己收; 没开播 (找源、缓冲) 时转圈.
 */
@Composable
fun TouchMiniPlayerHost(miniPlayer: TouchMiniPlayer, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val vm = miniPlayer.vm ?: return
    var controlsVisible by remember { mutableStateOf(false) }
    // 控件开着时每点一次按钮就加一, 收起的倒计时重新开始
    var interactions by remember { mutableIntStateOf(0) }
    LaunchedEffect(controlsVisible, interactions) {
        if (controlsVisible) {
            delay(MINI_PLAYER_CONTROLS_TIMEOUT)
            controlsVisible = false
        }
    }
    // iOS: 小窗开着时回桌面也接成系统画中画. 安卓的系统画中画是整个应用缩小 (连着别的页面), 只在播放页上开
    if (LocalPlatform.current.isIos()) MiniPlayerSystemPictureInPicture(vm, onExpand, miniPlayer::setTakeover)
    val takeover = miniPlayer.takeover
    // 铺满接住还原时收尾的信号万一没来 (系统小窗收起回调丢了), 也别一直盖着播放页
    LaunchedEffect(takeover) {
        if (!takeover) return@LaunchedEffect
        delay(MINI_PLAYER_TAKEOVER_MAX)
        logger.warn { "[PiP] 铺满接住还原 ${MINI_PLAYER_TAKEOVER_MAX.inWholeSeconds} 秒还没收尾, 撤掉" }
        miniPlayer.setTakeover(false)
    }
    MiniPlayerFrame(
        corner = miniPlayer.corner,
        widthFraction = miniPlayer.widthFraction,
        onTap = { controlsVisible = !controlsVisible },
        modifier = modifier.fillMaxSize()
            .then(if (takeover) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(MINI_PLAYER_MARGIN)),
        fullscreen = takeover,
        video = { VideoPlayer(vm.player, Modifier.fillMaxSize()) },
        controls = {
            if (!takeover) {
                MiniPlayerControls(
                    vm,
                    visible = controlsVisible,
                    onInteract = { interactions++ },
                    onExpand = onExpand,
                    onClose = miniPlayer::dismiss,
                )
            }
        },
    )
}

/**
 * 小窗里的视频也挂一个系统画中画控制器, 策略同播放页 (EpisodePage 的 PictureInPicturePolicyEffect): 设置是「切到别的应用时开小窗」且在播时,
 * 回桌面由系统自动接成画中画; 同时告诉会话宿主这时退到后台别按暂停 ([EpisodeViewModel.setAutoPictureInPictureArmed], 按本组件登记,
 * 播放页退场撤销它自己那份时不影响这里).
 * 控制器找的是播放器最后登记的视频图层, 也就是小窗这个 (见 IosVideoLayerRegistry).
 * 用户点系统小窗上的「还原」回来时直接到全屏的播放页 ([onExpand]), 不停在应用内小窗 (系统小窗只有「还原」这一个回来的按钮, 回来就是接着看):
 * 小窗先铺满全屏接住 ([onTakeover], 系统收回落在当初开小窗的这块图层上, 它留着最后一帧, 不黑), 底下展开成播放页;
 * 系统小窗收完、播放页自己的画面出来了 ([PictureInPictureController.restoreFinishedCount] 比开始接住时大了) 再撤掉铺满的这一层.
 */
@Composable
private fun MiniPlayerSystemPictureInPicture(vm: EpisodeViewModel, onExpand: () -> Unit, onTakeover: (Boolean) -> Unit) {
    val controller = rememberPictureInPictureController(vm.player)
    val currentOnExpand by rememberUpdatedState(onExpand)
    val currentOnTakeover by rememberUpdatedState(onTakeover)
    // 开始接住时的收尾次数: 收尾那一刻控制器可能已经换了 (播放页的新图层一登记就跟着换), 按次数比, 换了也看得到
    var takeoverFrom by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(controller) {
        controller.restoreRequests.collect {
            logger.info { "[PiP] 系统小窗点了还原: 应用内小窗铺满全屏接住, 底下展开成全屏播放页" }
            takeoverFrom = controller.restoreFinishedCount.value
            currentOnTakeover(true)
            vm.isFullscreen = true
            currentOnExpand()
        }
    }
    LaunchedEffect(controller, takeoverFrom) {
        val from = takeoverFrom ?: return@LaunchedEffect
        controller.restoreFinishedCount.first { it > from }
        takeoverFrom = null
        currentOnTakeover(false)
    }
    val state by vm.player.state.collectAsStateWithLifecycle()
    val autoEnter = shouldAutoEnterPictureInPicture(vm.videoScaffoldConfig.backgroundBehavior, playWhenReady = state.playWhenReady)
    val armOwner = remember { Any() }
    LaunchedEffect(controller, autoEnter) {
        vm.setAutoPictureInPictureArmed(armOwner, autoEnter)
        controller.updatePolicy(autoEnterEnabled = autoEnter, aspectWidth = null, aspectHeight = null)
    }
    DisposableEffect(controller) {
        onDispose {
            vm.setAutoPictureInPictureArmed(armOwner, false)
            controller.updatePolicy(autoEnterEnabled = false, aspectWidth = null, aspectHeight = null)
        }
    }
}

@Composable
private fun MiniPlayerControls(
    vm: EpisodeViewModel,
    visible: Boolean,
    /** 点了播放 / 暂停: 控件重新计时再收. */
    onInteract: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
) {
    val state by vm.player.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        if (!visible && (state.mediaStatus != MediaStatus.Ready || state.isBuffering)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = Color.White, strokeWidth = 3.dp)
        }
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = MINI_PLAYER_SCRIM_ALPHA))) {
                IconButton(onExpand, Modifier.align(Alignment.TopStart)) {
                    Icon(Icons.Rounded.OpenInFull, stringResource(Lang.playback_session_now_playing), tint = Color.White)
                }
                IconButton(onClose, Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Rounded.Close, stringResource(Lang.subject_episode_close), tint = Color.White)
                }
                IconButton(
                    onClick = {
                        if (state.playWhenReady) vm.player.pause() else vm.player.play()
                        onInteract()
                    },
                    Modifier.align(Alignment.Center).size(MINI_PLAYER_PLAY_BUTTON_SIZE),
                ) {
                    if (state.playWhenReady) {
                        Icon(Icons.Rounded.Pause, stringResource(Lang.video_player_pause), Modifier.size(36.dp), tint = Color.White)
                    } else {
                        Icon(Icons.Rounded.PlayArrow, stringResource(Lang.video_player_play), Modifier.size(36.dp), tint = Color.White)
                    }
                }
            }
        }
    }
}

/** 离屏幕 (安全区) 边的距离. */
private val MINI_PLAYER_MARGIN = 16.dp

private const val MINI_PLAYER_SCRIM_ALPHA = 0.35f
private val MINI_PLAYER_PLAY_BUTTON_SIZE = 56.dp
private val MINI_PLAYER_CONTROLS_TIMEOUT = 3.seconds

/** 铺满接住还原最多盖多久 (正常是系统小窗收起 + 播放页出画面, 两三秒内收尾). */
private val MINI_PLAYER_TAKEOVER_MAX = 5.seconds

private val logger = logger("PictureInPicture")
