/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_episode_danmaku_settings_title
import me.him188.ani.app.ui.subject.episode.EpisodeVideoDefaults
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.ui.subject.episode.video.components.EpisodeVideoSideSheetPage
import me.him188.ani.app.ui.subject.episode.video.components.EpisodeVideoSideSheets
import me.him188.ani.app.ui.subject.episode.video.components.SideSheets
import me.him188.ani.app.ui.subject.episode.video.settings.EpisodeVideoSettings
import me.him188.ani.app.ui.subject.episode.video.settings.EpisodeVideoSettingsViewModel
import me.him188.ani.app.ui.subject.episode.video.sidesheet.DanmakuRegexFilterSettings
import me.him188.ani.app.ui.subject.episode.video.sidesheet.EpisodeSelectorSheet
import me.him188.ani.app.videoplayer.ui.VideoSideSheetsController
import org.jetbrains.compose.resources.stringResource

/**
 * 播放器内二级页 (选集 / 弹幕设置). 全部为 TV 半透明居中弹窗
 * ([AniCenteredPanelDialog], 返回键由 Dialog 自行消费): Dialog 是独立窗口,
 * 关闭时系统自动把焦点还给打开它的按钮; 窗口内的侧边 sheet 做不到这一点
 * (内容连同焦点一起被移除, 焦点悬空按键失效).
 */
@Composable
internal fun TvPlayerSideSheets(
    vm: EpisodeViewModel,
    sheetsController: VideoSideSheetsController<EpisodeVideoSideSheetPage>,
) {
    EpisodeVideoDefaults.SideSheets(
        sheetsController = sheetsController,
        playerControllerState = vm.playerControllerState,
        playerSettingsPage = {
            val viewModel = remember { EpisodeVideoSettingsViewModel() }
            AniCenteredPanelDialog(
                onDismissRequest = { goBack() },
                title = { Text(stringResource(Lang.subject_episode_danmaku_settings_title)) },
                widthFraction = TV_PLAYER_SETTINGS_DIALOG_WIDTH_FRACTION,
            ) {
                // 初始焦点走事件驱动: 锚点挂在设置内容根上, 请求悬挂到它附着那一刻,
                // requestFocus(Enter) 随即落到里面第一个可聚焦项 —— 与原先"挂在第一项上的
                // 请求器"落点相同, 但不再靠 delay(300ms) 猜布局完成时机 (那是迁移前的老写法,
                // 也是本仓库 TV 代码里最后一处)
                EpisodeVideoSettings(
                    viewModel,
                    onNavigateToFilterSettings = {
                        sheetsController.navigateTo(EpisodeVideoSideSheetPage.EDIT_DANMAKU_REGEX_FILTER)
                    },
                    modifier = Modifier.tvWindowInitialFocus(),
                )
            }
        },
        editDanmakuRegexFilterPage = {
            AniCenteredPanelDialog(
                onDismissRequest = { goBack() },
                widthFraction = TV_PLAYER_SETTINGS_DIALOG_WIDTH_FRACTION,
            ) {
                // expanded = false (竖屏形态): 内容自带标题行、全宽透明铺开, 正好嵌进弹窗;
                // expanded = true 是带右侧悬浮容器的侧边 sheet 形态, 不适合弹窗内嵌
                DanmakuRegexFilterSettings(
                    state = vm.danmakuRegexFilterState,
                    onDismissRequest = { goBack() },
                    expanded = false,
                )
            }
        },
        // 选源不走这里: 遥控器上是播放器的一层 (TvPlayerLayer.SOURCES, 见 TvPlayerSourcePanel)
        mediaSelectorPage = {},
        episodeSelectorPage = {
            EpisodeVideoSideSheets.EpisodeSelectorSheet(
                vm.episodeSelectorState,
                onDismissRequest = { goBack() },
            )
        },
    )
}

/** 弹幕设置/正则过滤弹窗宽度: 设置列表按侧边栏宽度设计, 用比数据源选择更窄的容器. */
private const val TV_PLAYER_SETTINGS_DIALOG_WIDTH_FRACTION = 0.45f
