/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.exploration.TV_WALL_POSITION_MAX_DP
import me.him188.ani.app.ui.exploration.TV_WALL_POSITION_MIN_DP
import me.him188.ani.app.ui.exploration.TV_WALL_POSITION_STEP_DP
import me.him188.ani.app.ui.exploration.TvExplorationWallPreview
import me.him188.ani.app.ui.foundation.TvPageAdjustAction
import me.him188.ani.app.ui.foundation.TvPageAdjustActions
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.session.LocalTvRailEnter
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.LocalTvPosterWallTone
import me.him188.ani.app.ui.foundation.tv.ProvideTvScrollActivity
import me.him188.ani.app.ui.foundation.tv.TvPosterWallScaled
import me.him188.ani.app.ui.foundation.tv.TvPosterWallTheme
import me.him188.ani.app.ui.foundation.tv.TvWallPreviewEntry
import me.him188.ani.app.ui.foundation.tv.rememberTvPosterWallTone
import me.him188.ani.app.ui.foundation.tv.tvPosterWallBackground
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_wall_position_hint
import me.him188.ani.app.ui.lang.tv_wall_position_preview
import me.him188.ani.app.ui.lang.tv_wall_position_title
import org.jetbrains.compose.resources.stringResource

/** 首页动作面板的纵向位置入口; 设置写回后按页面自己的焦点簿记回到原来的卡. */
@Composable
internal fun TvWallPositionEntry(page: TvPosterWallPreviewPage, beforeOpen: () -> Unit, refocus: () -> Unit) {
    if (page != TvPosterWallPreviewPage.EXPLORATION) return
    val windows = LocalTvAdjustWindows.current ?: return
    val offset = LocalThemeSettings.current.tvExplorationWallOffsetDp
    var pending by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(offset, pending) {
        if (pending != null && pending == offset) {
            withFrameNanos { }
            refocus()
            pending = null
        }
    }
    TvPageAdjustActions(
        TvPageAdjustAction(Icons.Rounded.SwapVert, stringResource(Lang.tv_wall_position_title)) {
            beforeOpen()
            windows.open(TvAdjustWindow.WallPosition { committed ->
                if (committed == null) refocus() else pending = committed
            })
        },
    )
}

/** 调整期间只更新预览草稿, 离开窗口时保存一次. 卡片大小沿用当前海报墙设置. */
@Composable
internal fun TvPosterWallPositionPage(onNavigateBack: () -> Unit, onCommit: (Int) -> Unit) {
    val settings = remember { GlobalKoin.get<SettingsRepository>() }
    val scope = rememberCoroutineScope()
    val theme = LocalThemeSettings.current
    val initial = remember { theme.tvExplorationWallOffsetDp.coerceIn(TV_WALL_POSITION_MIN_DP, TV_WALL_POSITION_MAX_DP) }
    var offset by remember { mutableIntStateOf(initial) }
    var saved by remember { mutableIntStateOf(initial) }
    fun commit() {
        val value = offset
        if (value == saved) return
        saved = value
        onCommit(value)
        scope.launch(NonCancellable) { settings.themeSettings.update { copy(tvExplorationWallOffsetDp = value) } }
    }
    DisposableEffect(Unit) { onDispose { commit() } }
    BackHandler { commit(); onNavigateBack() }

    val controls = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val entry = remember { TvWallPreviewEntry() }
    val wall = tvPosterWallBackground()
    val tone = rememberTvPosterWallTone(wall = wall, hero = wall, wallPage = true)
    val previewTheme = remember(theme, offset) { theme.copy(tvExplorationWallOffsetDp = offset) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().drawBehind { tone.drawBackground(this) })
        CompositionLocalProvider(
            LocalThemeSettings provides previewTheme,
            LocalTvPosterWallTone provides tone,
            LocalTvRailEnter provides controls,
        ) {
            ProvideTvScrollActivity {
                TvPosterWallTheme {
                    TvPosterWallScaled(theme.effectivePosterWallScale) {
                        key(offset) {
                            TvExplorationWallPreview(entry, { runCatching { controls.requestFocus() } })
                        }
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxHeight().width(if (focused) 260.dp else 48.dp)
                .background(wall.copy(alpha = 0.96f))
                .focusRequester(controls)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionUp, Key.DirectionDown -> {
                            if (event.type == KeyEventType.KeyDown) {
                                val step = if (event.key == Key.DirectionUp) -TV_WALL_POSITION_STEP_DP else TV_WALL_POSITION_STEP_DP
                                offset = (offset + step).coerceIn(TV_WALL_POSITION_MIN_DP, TV_WALL_POSITION_MAX_DP)
                            }
                            true
                        }
                        Key.DirectionLeft -> {
                            if (event.type == KeyEventType.KeyDown) offset = 0
                            true
                        }
                        Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (event.type == KeyEventType.KeyUp) entry.request(theme.effectivePosterWallScale)
                            true
                        }
                        else -> false
                    }
                }.focusable(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(Modifier.padding(if (focused) 24.dp else 4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (focused) Text(stringResource(Lang.tv_wall_position_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    (if (offset > 0) "+$offset" else "$offset") + " dp",
                    color = MaterialTheme.colorScheme.primary,
                    style = if (focused) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.labelSmall,
                )
                if (focused) {
                    Text(stringResource(Lang.tv_wall_position_hint), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(Lang.tv_wall_position_preview), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { controls.requestFocus() } }
    }
}
