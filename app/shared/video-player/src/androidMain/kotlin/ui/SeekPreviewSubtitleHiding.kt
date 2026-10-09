/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.flow.StateFlow

/**
 * 拖动预览画在全屏上时 ([onFullScreen], 见 `SeekPreview.onFullScreen`) 藏起播放器的字幕视图: 全屏上是预览位置的画面,
 * 字幕却停在开始拖的那一刻 (见 `SubtitleObstructionEffect`), 盖在别处的画面上对不上. 预览结束 (确认或取消) 就露出来.
 * ASS 那一层也挂在 [view] 里, 跟着一起藏.
 */
@Composable
internal fun SeekPreviewSubtitleHiding(view: SubtitleView?, onFullScreen: StateFlow<Boolean>?) {
    LaunchedEffect(view, onFullScreen) {
        if (view == null || onFullScreen == null) return@LaunchedEffect
        try {
            onFullScreen.collect { view.visibility = if (it) View.INVISIBLE else View.VISIBLE }
        } finally {
            view.visibility = View.VISIBLE
        }
    }
}
