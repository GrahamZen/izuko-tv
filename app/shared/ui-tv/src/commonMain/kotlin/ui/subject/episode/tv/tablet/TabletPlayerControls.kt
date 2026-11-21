/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import me.him188.ani.app.ui.foundation.interaction.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.him188.ani.app.data.repository.RepositoryRateLimitedException
import me.him188.ani.app.domain.player.tracks.AudioTrackSupport
import me.him188.ani.app.domain.together.TogetherManager
import me.him188.ani.app.domain.together.TogetherState
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.navigation.SettingsTab
import me.him188.ani.app.ui.danmaku.DanmakuEditorState
import me.him188.ani.app.ui.foundation.animation.AniAnimatedVisibility
import me.him188.ani.app.ui.foundation.icons.AniIcons
import me.him188.ani.app.ui.foundation.icons.Forward80
import me.him188.ani.app.ui.foundation.icons.Forward85
import me.him188.ani.app.ui.foundation.icons.Forward90
import me.him188.ani.app.ui.foundation.icons.SubtitleGear
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.episode_danmaku
import me.him188.ani.app.ui.lang.episode_send_danmaku
import me.him188.ani.app.ui.lang.episode_send_danmaku_failed
import me.him188.ani.app.ui.lang.episode_send_danmaku_quota_exceeded
import me.him188.ani.app.ui.lang.playback_history_episode_label
import me.him188.ani.app.ui.lang.subject_episode_external_links
import me.him188.ani.app.ui.lang.subject_episode_fast_forward_seconds
import me.him188.ani.app.ui.lang.subject_episode_more_options
import me.him188.ani.app.ui.lang.subject_episode_select_media_source
import me.him188.ani.app.ui.lang.tablet_player_danmaku_placeholder
import me.him188.ani.app.ui.lang.tablet_player_exit_fullscreen
import me.him188.ani.app.ui.lang.tablet_player_fullscreen
import me.him188.ani.app.ui.lang.tablet_player_more_settings
import me.him188.ani.app.ui.lang.tablet_player_stats
import me.him188.ani.app.ui.lang.tablet_player_together_members
import me.him188.ani.app.ui.lang.video_player_aspect_ratio
import me.him188.ani.app.ui.lang.video_player_disable_danmaku
import me.him188.ani.app.ui.lang.video_player_enter_picture_in_picture
import me.him188.ani.app.ui.lang.video_player_enable_danmaku
import me.him188.ani.app.ui.lang.video_player_next_episode
import me.him188.ani.app.ui.lang.video_player_screenshot
import me.him188.ani.app.ui.lang.video_player_select_episode
import me.him188.ani.app.ui.lang.video_player_tv_restart
import me.him188.ani.app.ui.lang.watch_together_title
import me.him188.ani.app.ui.mediaselect.common.SourceIcon
import me.him188.ani.app.ui.mediaselect.summary.MediaSelectorSummary
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.ui.subject.episode.details.components.ShareEpisodeDropdown
import me.him188.ani.app.ui.subject.episode.tv.TV_PLAYBACK_SPEED_RANGE
import me.him188.ani.app.ui.subject.episode.tv.TV_PLAYER_SEEK_STEP_MILLIS
import me.him188.ani.app.ui.subject.episode.tv.TvAudioSwitcher
import me.him188.ani.app.ui.subject.episode.tv.TvPlayerLayer
import me.him188.ani.app.ui.subject.episode.tv.TvPlayerOverlayState
import me.him188.ani.app.ui.subject.episode.tv.TvPlayerProgressRow
import me.him188.ani.app.ui.together.TvTogetherEntry
import me.him188.ani.app.videoplayer.ui.PlaybackSpeedControllerState
import me.him188.ani.app.videoplayer.ui.VideoAspectRatioControllerState
import me.him188.ani.app.videoplayer.screenshot.playerScreenshotFileName
import me.him188.ani.app.videoplayer.ui.gesture.GestureIndicator
import me.him188.ani.app.videoplayer.ui.gesture.GestureLock
import me.him188.ani.app.videoplayer.ui.screenshot.PlayerScreenshotOverlay
import me.him188.ani.app.videoplayer.ui.screenshot.rememberPlayerScreenshotController
import me.him188.ani.app.videoplayer.ui.progress.MediaProgressFramePreviewState
import me.him188.ani.app.videoplayer.ui.progress.PlayerControllerDefaults
import me.him188.ani.app.videoplayer.ui.progress.PlayerControllerDefaults.SpeedSwitcher
import me.him188.ani.app.videoplayer.ui.progress.PlayerProgressSliderState
import me.him188.ani.app.videoplayer.ui.progress.SubtitleSwitcher
import me.him188.ani.app.videoplayer.ui.renderAspectRatioMode
import me.him188.ani.danmaku.api.DanmakuContent
import me.him188.ani.danmaku.api.DanmakuLocation
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.features.audioTracks
import org.openani.mediamp.features.subtitleTracks
import org.openani.mediamp.togglePlayWhenReady

/**
 * 平板模式播放页画在视频上的控件 (代替电视的胶囊行 / 图标行 / 选集条, 只在平板模式下组合):
 *
 * - 左上: 返回 (分栏 = 退出播放页, 全屏 = 退出全屏) + 集标题 (全屏时上面再加作品名).
 * - 右上: (在一起看的房间里时) 「一起看 · N 人」· 数据源 (写着正在用的源) · 字幕 / 音轨 · 倍速 · ⋮ (不常用的, 见 [TabletMoreMenu]).
 * - 中间: 后退 5 秒 · 播放 / 暂停 · 快进 5 秒.
 * - 底下: 进度条 (触屏拖动, 同电视版那一行), 再一排 从头开始 · 下一集 · 快进 N 秒 · 弹幕开关 · 发弹幕 · 弹幕 (列表与设置) · 选集 · 小窗 · 全屏.
 *   分栏时这一排只有图标, 长按 (或鼠标停住) 出名字.
 *
 * 显隐沿用播放器原来的层级 (`TvPlayerLayer.HIDDEN / CONTROLS`) 与 5 秒自动隐藏; 下拉菜单开着时经 `onPopupExpandedChanged` 上报, 不自动隐藏.
 * 全屏的抽屉开着时控件收着 (抽屉盖在画面右边, 点外面关).
 *
 * [skipTip] 是 OP/ED 提示按钮 (取消跳过 / 跳过), 控件收着时也在, 停在进度条上方右边.
 * [levelGestures] 的亮度 / 音量指示也不跟控件显隐, 拖的时候出现在画面上方居中.
 * 全屏时右上多一个截图 (同上游播放器), 截完画面右下浮出缩略图面板 (分享 / 复制 / 点开看大图); 左边中间是锁屏 (见 [TabletPlayerState.gesturesLocked]).
 */
@Composable
internal fun TabletPlayerControls(
    vm: EpisodeViewModel,
    page: EpisodePageState,
    overlay: TvPlayerOverlayState,
    tablet: TabletPlayerState,
    progressSliderState: PlayerProgressSliderState,
    framePreview: MediaProgressFramePreviewState?,
    danmakuEditorState: DanmakuEditorState,
    onExitPage: () -> Unit,
    skipTip: @Composable () -> Unit,
    levelGestures: TabletLevelGestures?,
    pictureInPicture: TabletPictureInPicture?,
    modifier: Modifier = Modifier,
) {
    // 小窗里只有视频
    if (pictureInPicture?.isActive == true) return
    val chromeVisible = overlay.layer == TvPlayerLayer.CONTROLS && tablet.drawer == null
    val large = tablet.fullScreen
    val sizes = if (large) TabletControlSizes.Large else TabletControlSizes.Compact
    val screenshot = rememberPlayerScreenshotController(vm.player)
    val takeScreenshot: (() -> Unit)? = if (screenshot.isSupported) {
        {
            screenshot.take(
                playerScreenshotFileName(
                    subjectId = vm.subjectId,
                    episodeSort = page.episodePresentation.ep,
                    positionMillis = vm.player.currentPositionMillis.value,
                ),
            )
        }
    } else {
        null
    }
    Box(modifier.fillMaxSize()) {
        AniAnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.matchParentSize(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // 锁着时只留锁的按钮
                if (!tablet.gesturesLocked) {
                    Box(
                        Modifier.align(Alignment.TopStart).fillMaxWidth().height(sizes.topScrim)
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))),
                    )
                    Box(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().height(sizes.bottomScrim)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))),
                    )
                    TabletTopBar(
                        vm, page, overlay, tablet, sizes,
                        onScreenshot = takeScreenshot.takeIf { tablet.fullScreen },
                        onBack = { if (tablet.fullScreen) tablet.exitFullScreen() else onExitPage() },
                        modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
                    )
                    TabletCenterButtons(vm, sizes, Modifier.align(Alignment.Center))
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = sizes.edge / 2)) {
                        TvPlayerProgressRow(
                            vm = vm,
                            progressSliderState = progressSliderState,
                            framePreview = framePreview,
                            overlay = overlay,
                            downFocus = null,
                            upFocus = null,
                            live = chromeVisible,
                            focusable = { false },
                            modifier = Modifier.padding(horizontal = sizes.edge),
                        )
                        TabletBottomRow(
                            vm, page, overlay, tablet, danmakuEditorState, pictureInPicture, sizes,
                            Modifier.fillMaxWidth().padding(horizontal = sizes.edge - 8.dp),
                        )
                    }
                }
                if (tablet.fullScreen) {
                    GestureLock(
                        isLocked = tablet.gesturesLocked,
                        onClick = { tablet.gesturesLocked = !tablet.gesturesLocked },
                        Modifier.align(Alignment.CenterStart).padding(start = sizes.edge),
                    )
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = sizes.edge, bottom = sizes.skipTipBottom)) {
            skipTip()
        }
        if (levelGestures != null) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = sizes.edge + 8.dp)) {
                GestureIndicator(levelGestures.indicator)
            }
        }
        PlayerScreenshotOverlay(
            screenshot.panelState,
            onShare = screenshot::share,
            onCopy = screenshot::copy,
            onOpen = screenshot::open,
            Modifier.matchParentSize(),
            bottomOffset = if (chromeVisible) sizes.bottomScrim else 0.dp,
        )
    }
}

/** 分栏 (视频约屏宽 65%) 与全屏两档尺寸. */
internal class TabletControlSizes private constructor(
    val edge: Dp,
    val icon: Dp,
    val button: Dp,
    val centerSmall: Dp,
    val centerLarge: Dp,
    val centerGap: Dp,
    val topScrim: Dp,
    val bottomScrim: Dp,
    val skipTipBottom: Dp,
    val showLabels: Boolean,
) {
    companion object {
        val Compact = TabletControlSizes(
            edge = 16.dp, icon = 20.dp, button = 40.dp,
            centerSmall = 44.dp, centerLarge = 60.dp, centerGap = 40.dp,
            topScrim = 96.dp, bottomScrim = 132.dp, skipTipBottom = 104.dp, showLabels = false,
        )
        val Large = TabletControlSizes(
            edge = 28.dp, icon = 24.dp, button = 52.dp,
            centerSmall = 56.dp, centerLarge = 76.dp, centerGap = 56.dp,
            topScrim = 150.dp, bottomScrim = 200.dp, skipTipBottom = 140.dp, showLabels = true,
        )
    }
}

@Composable
private fun TabletTopBar(
    vm: EpisodeViewModel,
    page: EpisodePageState,
    overlay: TvPlayerOverlayState,
    tablet: TabletPlayerState,
    sizes: TabletControlSizes,
    /** 截图 (只在全屏给, 同上游播放器); null 不显示. */
    onScreenshot: (() -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Row(
            modifier.padding(start = sizes.edge - 4.dp, end = sizes.edge - 8.dp, top = sizes.edge / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(sizes.button).clip(CircleShape).background(Color.White.copy(alpha = 0.16f))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, Modifier.size(sizes.icon))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val episode = page.episodePresentation
                val episodeLabel = stringResource(Lang.playback_history_episode_label, episode.ep) + "  " + episode.title
                if (tablet.fullScreen) {
                    Text(
                        page.subjectPresentation.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    episodeLabel,
                    style = if (tablet.fullScreen) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                    color = if (tablet.fullScreen) Color.White.copy(alpha = 0.85f) else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TabletTogetherStatus(sizes, Modifier.padding(end = 6.dp))
            TabletSourceButton(page, sizes, onClick = { tablet.toggle(TabletSidePanel.SOURCES) })
            Spacer(Modifier.width(4.dp))
            vm.player.subtitleTracks?.let {
                PlayerControllerDefaults.SubtitleSwitcher(
                    it,
                    onExpandedChanged = { open -> overlay.onPopupExpandedChanged(open) },
                )
            }
            vm.player.audioTracks?.let {
                TvAudioSwitcher(
                    it,
                    vm.player as? AudioTrackSupport,
                    onExpandedChanged = { open -> overlay.onPopupExpandedChanged(open) },
                )
            }
            TabletSpeedSwitcher(vm, overlay)
            if (onScreenshot != null) {
                IconButton(onClick = onScreenshot, Modifier.size(sizes.button)) {
                    Icon(Icons.Rounded.PhotoCamera, contentDescription = stringResource(Lang.video_player_screenshot), Modifier.size(sizes.icon))
                }
            }
            TabletMoreMenu(vm, page, overlay, tablet, sizes)
        }
    }
}

/**
 * 在一起看的房间里时, 画面右上一颗「一起看 · N 人」, 点开一起看弹窗 (成员、跟随、离开房间). 不在房间里时不画
 * (发起一起看在视频下面那一排, 全屏时在 ⋮ 里).
 */
@Composable
private fun TabletTogetherStatus(sizes: TabletControlSizes, modifier: Modifier = Modifier) {
    val manager = remember { KoinPlatform.getKoin().getOrNull<TogetherManager>() } ?: return
    val state by manager.state.collectAsState()
    val session = (state as? TogetherState.InRoom)?.session ?: return
    val snapshot by session.snapshot.collectAsState()
    Row(
        modifier
            .height(if (sizes.showLabels) 36.dp else 32.dp)
            .clip(CircleShape)
            .background(tabletAccent.copy(alpha = 0.22f))
            .clickable { TvTogetherEntry.open() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.SyncAlt, contentDescription = null, Modifier.size(16.dp), tint = tabletAccent)
        Text(
            stringResource(Lang.tablet_player_together_members, snapshot.members.size),
            style = MaterialTheme.typography.labelLarge,
            color = tabletAccent,
            maxLines = 1,
        )
    }
}

/**
 * 数据源按钮: 正在用的源的图标与名字 (没选出来时是通用图标与「数据源」), 点开侧边的数据源页.
 */
@Composable
private fun TabletSourceButton(page: EpisodePageState, sizes: TabletControlSizes, onClick: () -> Unit) {
    val source = (page.mediaSelectorSummary as? MediaSelectorSummary.Selected)?.source
    val label = source?.sourceName ?: stringResource(Lang.subject_episode_select_media_source)
    Row(
        Modifier
            .height(if (sizes.showLabels) 36.dp else 32.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (source != null) {
            SourceIcon(source.sourceIconUrl, source.sourceName, Modifier.size(18.dp))
        } else {
            Icon(Icons.Rounded.Layers, contentDescription = null, Modifier.size(16.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
    }
}

/** 倍速: 同电视版那颗 (固定默认范围, 改了写回配置), 见 TvPlayerControls 里 PLAYBACK_SPEED 那一支的说明. */
@Composable
private fun TabletSpeedSwitcher(vm: EpisodeViewModel, overlay: TvPlayerOverlayState) {
    val scope = rememberCoroutineScope()
    val controller = remember(vm) {
        vm.player.features[PlaybackSpeed]?.let {
            PlaybackSpeedControllerState(
                playbackSpeed = it,
                rangeProvider = { TV_PLAYBACK_SPEED_RANGE },
                onCommitSpeed = { speed -> vm.setPlaybackSpeed(speed) },
                scope = scope,
            )
        }
    } ?: return
    SpeedSwitcher(controller, onExpandedChanged = { open -> overlay.onPopupExpandedChanged(open) })
}

/**
 * ⋮ (分栏与全屏共用): 播放信息 (开 / 关画面上的统计) · 画面比例 (一行写着当前的, 点进去三选一)
 * · 全屏时再加外部链接与一起看 (分栏时它们在视频下面那一排, 不重复) · 更多播放设置 (整个播放器设置页).
 */
@Composable
private fun TabletMoreMenu(
    vm: EpisodeViewModel,
    page: EpisodePageState,
    overlay: TvPlayerOverlayState,
    tablet: TabletPlayerState,
    sizes: TabletControlSizes,
) {
    var expanded by remember { mutableStateOf(false) }
    // 画面比例的那一层 (菜单里点「画面比例」进去, 选完或点 ‹ 回来)
    var aspectPage by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val aspect = remember(vm) { vm.player.features[VideoAspectRatio]?.let { VideoAspectRatioControllerState(it, scope = scope) } }
    // 开着的下拉 / 一起看弹窗都抑制控件自动隐藏
    val togetherVisible by TvTogetherEntry.dialogVisible.collectAsState()
    ReportPopupWhile(overlay, expanded)
    ReportPopupWhile(overlay, sharing)
    ReportPopupWhile(overlay, togetherVisible)
    val close = {
        expanded = false
        aspectPage = false
    }
    Box {
        IconButton(onClick = { expanded = true }, Modifier.size(sizes.button)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(Lang.subject_episode_more_options), Modifier.size(sizes.icon))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = close) {
            if (aspectPage && aspect != null) {
                TabletMenuItem(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Lang.video_player_aspect_ratio)) { aspectPage = false }
                HorizontalDivider()
                for (mode in VideoAspectRatioControllerState.Entries) {
                    DropdownMenuItem(
                        text = { Text(renderAspectRatioMode(mode)) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = if (aspect.currentMode == mode) tabletAccent else Color.Transparent,
                            )
                        },
                        onClick = {
                            aspect.setMode(mode)
                            close()
                        },
                    )
                }
                return@DropdownMenu
            }
            TabletMenuItem(Icons.Outlined.Analytics, stringResource(Lang.tablet_player_stats)) {
                close()
                overlay.showPlayerStats = !overlay.showPlayerStats
            }
            if (aspect != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Lang.video_player_aspect_ratio)) },
                    leadingIcon = { Icon(Icons.Rounded.AspectRatio, contentDescription = null) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                renderAspectRatioMode(aspect.currentMode),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                        }
                    },
                    onClick = { aspectPage = true },
                )
            }
            if (tablet.fullScreen) {
                TabletMenuItem(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(Lang.subject_episode_external_links)) {
                    close()
                    sharing = true
                }
                TabletMenuItem(Icons.Rounded.SyncAlt, stringResource(Lang.watch_together_title)) {
                    close()
                    TvTogetherEntry.open()
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            TabletMenuItem(Icons.Rounded.Settings, stringResource(Lang.tablet_player_more_settings)) {
                close()
                navigator.navigateSettings(SettingsTab.PLAYER)
            }
        }
        // 外部链接 (全屏时从 ⋮ 打开): 同视频下面那颗, 复制地址、外部应用播放、打开来源网页
        ShareEpisodeDropdown(page.shareData, sharing, onDismissRequest = { sharing = false })
    }
}

@Composable
private fun TabletMenuItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

/** [open] 为真期间算作一个打开的弹层 (控件不自动隐藏). */
@Composable
private fun ReportPopupWhile(overlay: TvPlayerOverlayState, open: Boolean) {
    DisposableEffect(open) {
        if (open) overlay.onPopupExpandedChanged(true)
        onDispose { if (open) overlay.onPopupExpandedChanged(false) }
    }
}

@Composable
private fun TabletCenterButtons(vm: EpisodeViewModel, sizes: TabletControlSizes, modifier: Modifier = Modifier) {
    val player = vm.player
    val playWhenReady by remember(player) { player.state.map { it.playWhenReady } }
        .collectAsState(player.state.value.playWhenReady)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(sizes.centerGap), verticalAlignment = Alignment.CenterVertically) {
        TabletCircleButton(Icons.Rounded.Replay5, sizes.centerSmall) { player.skip(-TV_PLAYER_SEEK_STEP_MILLIS) }
        TabletCircleButton(
            if (playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            sizes.centerLarge,
        ) { player.togglePlayWhenReady() }
        TabletCircleButton(Icons.Rounded.Forward5, sizes.centerSmall) { player.skip(TV_PLAYER_SEEK_STEP_MILLIS) }
    }
}

@Composable
private fun TabletCircleButton(icon: ImageVector, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = 0.38f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(size * 0.5f), tint = Color.White)
    }
}

@Composable
private fun TabletBottomRow(
    vm: EpisodeViewModel,
    page: EpisodePageState,
    overlay: TvPlayerOverlayState,
    tablet: TabletPlayerState,
    danmakuEditorState: DanmakuEditorState,
    pictureInPicture: TabletPictureInPicture?,
    sizes: TabletControlSizes,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TabletBarButton(Icons.Rounded.Replay, stringResource(Lang.video_player_tv_restart), sizes) {
                vm.player.seekTo(0)
            }
            TabletBarButton(
                Icons.Rounded.SkipNext, stringResource(Lang.video_player_next_episode), sizes,
                enabled = vm.episodeSelectorState.hasNextEpisode,
            ) { vm.episodeSelectorState.selectNext() }
            val skipSeconds = vm.videoScaffoldConfig.opEdSkipDuration.inWholeSeconds
            TabletBarButton(
                when (skipSeconds) {
                    85L -> AniIcons.Forward85
                    90L -> AniIcons.Forward90
                    else -> AniIcons.Forward80
                },
                stringResource(Lang.subject_episode_fast_forward_seconds, skipSeconds), sizes,
            ) { vm.onClickSkipOpEd(vm.player.currentPositionMillis.value) }
            TabletBarButton(
                if (page.danmakuEnabled) Icons.Rounded.Subtitles else Icons.Rounded.SubtitlesOff,
                stringResource(if (page.danmakuEnabled) Lang.video_player_disable_danmaku else Lang.video_player_enable_danmaku),
                sizes,
            ) { vm.setDanmakuEnabled(!page.danmakuEnabled) }
            TabletDanmakuInput(vm, overlay, danmakuEditorState, sizes, Modifier.weight(1f).padding(horizontal = 6.dp))
            // 弹幕面板 (弹幕列表 / 弹幕设置两页)
            TabletBarButton(AniIcons.SubtitleGear, stringResource(Lang.episode_danmaku), sizes) {
                tablet.toggle(TabletSidePanel.DANMAKU)
            }
            TabletBarButton(Icons.AutoMirrored.Rounded.PlaylistPlay, stringResource(Lang.video_player_select_episode), sizes) {
                tablet.toggle(TabletSidePanel.EPISODES)
            }
            // 小窗与全屏挨着: 都是换观看方式
            if (pictureInPicture?.isSupported == true) {
                TabletBarButton(Icons.Rounded.PictureInPictureAlt, stringResource(Lang.video_player_enter_picture_in_picture), sizes) {
                    pictureInPicture.enter()
                }
            }
            TabletBarButton(
                if (tablet.fullScreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                stringResource(if (tablet.fullScreen) Lang.tablet_player_exit_fullscreen else Lang.tablet_player_fullscreen),
                sizes,
            ) { tablet.toggleFullScreen() }
        }
    }
}

/**
 * 底下那一排的按钮 (同上游播放器: 白色图标, 不带底色): 分栏只有图标 (长按或鼠标停住出名字), 全屏图标下面带字.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabletBarButton(
    icon: ImageVector,
    label: String,
    sizes: TabletControlSizes,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (sizes.showLabels) {
        TabletBarButtonContent(icon, label, sizes, enabled, onClick)
    } else {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
        ) {
            TabletBarButtonContent(icon, label, sizes, enabled, onClick)
        }
    }
}

@Composable
private fun TabletBarButtonContent(
    icon: ImageVector,
    label: String,
    sizes: TabletControlSizes,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
    Column(
        Modifier
            .widthIn(min = sizes.button)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = if (sizes.showLabels) 6.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = label, Modifier.size(sizes.icon), tint = tint)
        if (sizes.showLabels) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 1,
            )
        }
    }
}

/**
 * 发弹幕: 平时是一条写着提示的胶囊, 点了展开成输入框并弹键盘 (状态用播放器原来的 `danmakuInputExpanded`:
 * 根路由在它开着时把返回键用来收输入框, 其余按键交给输入法). 发送与失败处理同电视版 (TvDanmakuSendEntry).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TabletDanmakuInput(
    vm: EpisodeViewModel,
    overlay: TvPlayerOverlayState,
    danmakuEditorState: DanmakuEditorState,
    sizes: TabletControlSizes,
    modifier: Modifier = Modifier,
) {
    val expanded = overlay.danmakuInputExpanded
    val height = if (sizes.showLabels) 40.dp else 34.dp
    if (!expanded) {
        Box(
            modifier
                .height(height)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f))
                .clickable { overlay.danmakuInputExpanded = true }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                stringResource(Lang.tablet_player_danmaku_placeholder),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
        return
    }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val keyboard = LocalSoftwareKeyboardController.current
    val sendFailedText = stringResource(Lang.episode_send_danmaku_failed)
    val quotaExceededText = stringResource(Lang.episode_send_danmaku_quota_exceeded)
    val isSending by danmakuEditorState.isSending.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    // 打开时按设置暂停, 收起时恢复 (同电视版)
    DisposableEffect(Unit) {
        val pausedForInput = vm.videoScaffoldConfig.pauseVideoOnEditDanmaku && vm.player.state.value.playWhenReady
        if (pausedForInput) vm.player.pause()
        onDispose {
            keyboard?.hide()
            if (pausedForInput) vm.player.play()
        }
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    // 键盘被收起 (多半是输入法自己吃了返回键) 就收回胶囊
    val imeVisible = rememberUpdatedState(isImeVisible())
    LaunchedEffect(Unit) {
        snapshotFlow { imeVisible.value }.first { it }
        snapshotFlow { imeVisible.value }.first { !it }
        overlay.danmakuInputExpanded = false
    }
    val send: () -> Unit = send@{
        val text = danmakuEditorState.text.trim()
        if (text.isEmpty() || isSending) return@send
        scope.launch {
            val failure = danmakuEditorState.post(
                DanmakuContent(
                    vm.player.currentPositionMillis.value,
                    text = text,
                    color = Color.White.toArgb(),
                    location = DanmakuLocation.NORMAL,
                ),
            )
            when (failure) {
                null -> overlay.danmakuInputExpanded = false
                is RepositoryRateLimitedException -> toaster.toast(quotaExceededText)
                else -> toaster.toast(sendFailedText)
            }
        }
    }
    Row(
        modifier
            .height(height)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.22f))
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = danmakuEditorState.text,
            onValueChange = { danmakuEditorState.text = it },
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            textStyle = TextStyle(color = Color.White, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
            cursorBrush = SolidColor(Color.White),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        IconButton(onClick = send, enabled = !isSending) {
            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(Lang.episode_send_danmaku), tint = Color.White)
        }
    }
}

/** 平板播放页的强调色: 主题的 primary (视频上是深色配色的那一份, 视频外跟用户主题, 见 TabletPlayerFrame). */
internal val tabletAccent: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary
