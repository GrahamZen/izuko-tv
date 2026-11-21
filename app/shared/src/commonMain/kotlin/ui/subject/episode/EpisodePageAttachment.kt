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

/**
 * 播放页在组合里时一并组合的附加内容, 不画东西, 拿播放页的 [EpisodeViewModel] 与 [EpisodePageState] 做登记之类的事.
 * 不管用的是播放页变体 ([LocalEpisodeScreenVariant]) 还是默认布局都会组合. 电视包在平板模式 (用默认的触屏播放页) 下用它把播放页
 * 登记给 Web 控制台; 电视播放页的变体自己登记, 不经这里.
 */
fun interface EpisodePageAttachment {
    @Composable
    fun Content(vm: EpisodeViewModel, page: EpisodePageState)
}

val LocalEpisodePageAttachment = staticCompositionLocalOf<EpisodePageAttachment?> { null }
