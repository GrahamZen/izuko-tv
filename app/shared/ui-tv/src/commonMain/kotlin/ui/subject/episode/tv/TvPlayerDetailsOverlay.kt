/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import me.him188.ani.app.ui.subject.details.SubjectDetailsScreen
import me.him188.ani.app.ui.subject.details.SubjectDetailsUIState
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel

/**
 * L3 详情页覆盖层: 隐藏全部播放器组件, 正在播放的视频画面作为背景 (透明容器 + 视频遮罩,
 * 首屏只压底部, 滚动后整屏变暗 —— 与独立详情页视觉一致).
 *
 * 功能与独立详情页完全一致 (可导航到评论区); 唯一差别是选集卡片点击 = 切换当前播放集
 * 并关闭覆盖层 (复用 EpisodeSelectorState 切集链路). 返回键由 TvEpisodeScreen 根路由
 * 处理 (隐藏整个覆盖层回纯视频).
 */
@Composable
internal fun TvPlayerDetailsOverlay(
    vm: EpisodeViewModel,
    page: EpisodePageState,
    onClose: () -> Unit,
    /** 介绍页顶部按上键: 关闭详情层回到控制层的选集条 (展开态并聚焦). */
    onExitUpToStrip: () -> Unit,
    /**
     * 上报"页面内容 (加载完成后的 SubjectDetailsScreen) 是否在组合树上". false = 只有加载占位
     * (转圈), 那时按键接线与可聚焦节点都不存在, 上/下键由根路由代管
     * (见 [TvPlayerOverlayState.detailsContentComposed]).
     */
    onContentComposedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val detailsState = vm.episodeDetailsState

    // 打开时确保加载的是这部番 (已加载好就什么都不做, 上次失败了就重试)
    LaunchedEffect(Unit) {
        vm.ensureTvSubjectDetails()
    }
    val subjectDetailsState by detailsState.subjectDetailsStateLoader.state
        .collectAsStateWithLifecycle(SubjectDetailsUIState.Placeholder(detailsState.subjectId))

    Box(modifier) {
        when (val state = subjectDetailsState) {
            is SubjectDetailsUIState.Ok, is SubjectDetailsUIState.Err -> {
                // 开合状态经 DisposableEffect 上报 (不在组合里直接赋值): 本分支随数据到达/页面
                // 销毁反复进出组合, onDispose 保证退出时一定收回
                DisposableEffect(Unit) {
                    onContentComposedChanged(true)
                    onDispose { onContentComposedChanged(false) }
                }
                SubjectDetailsScreen(
                    state,
                    page.selfInfo,
                    onPlay = { episodeId ->
                        // 与手机版 onSwitchEpisode 一致: 选集列表里有这集就地切换, 否则整页导航
                        if (!vm.episodeSelectorState.selectEpisodeId(episodeId)) {
                            navigator.navigateEpisodeDetails(vm.subjectId, episodeId)
                        }
                        onClose()
                    },
                    onLoadErrorRetry = { vm.reloadTvSubjectDetails() },
                    onClickTag = { navigator.navigateSubjectSearch(it.name) },
                    onEpisodeCollectionUpdate = { request ->
                        scope.launch {
                            vm.setEpisodeCollectionType.invokeSafe(request)?.let {
                                toaster.showLoadError(it)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    showTopBar = false,
                    showBlurredBackground = false,
                    videoBackground = true,
                    onVideoBackgroundExitUp = onExitUpToStrip,
                )
            }

            // 加载中: 透明暗层 + 指示器 (不走不透明的占位页, 避免视频背景闪黑).
            // 压暗量与加载完成后的基础遮罩 (TV_VIDEO_SCRIM_BASE_ALPHA) 取同一档, 免得数据到位那一刻明暗跳一下
            else -> Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

/**
 * 确保详情 loader 加载的是**本播放器这部番**: 已加载好 (或正在加载这部番) 就什么都不做; 没加载过、失败了、或者停在别的条目上
 * 就 (重新) 加载. 进屏预载、选集条、详情层、角色 / 制作人员面板都走这里 —— 面板只读不加载的话, 一次失败之后就一直空着.
 *
 * **条目 id 用 [EpisodeViewModel.subjectId]** (建 VM 时就定了), 别用 `episodeDetailsState.subjectId`: 那个取自 subjectInfo,
 * 起播那一刻信息包还没到时是 `SubjectInfo.Empty` 的 0. 网页直接跳进播放器时进屏预载正好落在这个窗口里, 去请求「条目 0」
 * (服务器 404), 重试 5 次后 loader 停在 Err, 角色 / 制作人员胶囊跟着空着 (2026-09-11 真机日志).
 */
internal fun EpisodeViewModel.ensureTvSubjectDetails() {
    val loader = episodeDetailsState.subjectDetailsStateLoader
    val current = loader.state.value
    // 正在加载这部番: 别打断 (load 会取消了重来)
    if (current is SubjectDetailsUIState.Placeholder && current.subjectId == subjectId) return
    loader.load(subjectId, tvSubjectPlaceholder())
}

/** 详情层错误页的「重试」. */
internal fun EpisodeViewModel.reloadTvSubjectDetails() {
    episodeDetailsState.subjectDetailsStateLoader.reload(subjectId, tvSubjectPlaceholder())
}

/** 首屏占位: 信息包到了才有; 还没到 (那时是 `SubjectInfo.Empty`) 就不给. */
private fun EpisodeViewModel.tvSubjectPlaceholder(): SubjectInfo? =
    episodeDetailsState.subjectInfo.value.takeIf { it.subjectId == subjectId }
