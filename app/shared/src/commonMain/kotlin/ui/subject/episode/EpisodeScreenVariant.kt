/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.danmaku.DanmakuEditorState
import me.him188.ani.danmaku.ui.DanmakuHostState

/**
 * 播放页变体: 应用入口可提供一个替代的播放页布局 (如遥控器形态的全屏播放器,
 * 统一按键路由/浮出面板/详情页覆盖层), 取代默认的手机/平板/桌面布局.
 *
 * 评论编辑等弹层仍由 [EpisodePage] 持有 (组合在变体外层), 变体经 [setShowEditCommentSheet]
 * 控制其显隐. 未提供 (null, 默认) 时按窗口尺寸选择默认布局.
 */
fun interface EpisodeScreenVariant {
    @Composable
    fun Content(
        vm: EpisodeViewModel,
        page: EpisodePageState,
        danmakuHostState: DanmakuHostState,
        danmakuEditorState: DanmakuEditorState,
        setShowEditCommentSheet: (Boolean) -> Unit,
        pauseOnPlaying: () -> Unit,
        modifier: Modifier,
    )
}

val LocalEpisodeScreenVariant = staticCompositionLocalOf<EpisodeScreenVariant?> { null }

/**
 * 播放页还没拿到页面状态 (进页后第一批数据到之前) 时画的东西, 与 [LocalEpisodeScreenVariant] 配套: 让进页那一刻就有和变体一样的底,
 * 不露出外层底色. null (默认) = 不画.
 */
val LocalEpisodeScreenPlaceholder = staticCompositionLocalOf<(@Composable (EpisodeViewModel, Modifier) -> Unit)?> { null }
