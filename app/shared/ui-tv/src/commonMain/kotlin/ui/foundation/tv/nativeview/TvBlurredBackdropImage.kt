/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.ui.graphics.ImageBitmap
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch

/** 整屏模糊背景那张小图 (见 [tvBackdropBlurSpec]) 与量好的亮度: 浅色字 / 深色字各自最难看清那一处 (见 tvBackdropWorstLuminance). */
internal class TvBlurredBackdropImage(
    val bitmap: ImageBitmap,
    val worstForLightText: Float,
    val worstForDarkText: Float,
)

/**
 * 解 [url] 的模糊小图 (铺满 [widthPx] × [heightPx], 按 [spec] 解; 同海报墙那一层, 解法与参数相同就是同一个内存缓存键) 并量好亮度.
 * 解不了为 null.
 */
internal expect suspend fun tvFetchBlurredBackdrop(
    sketch: Sketch,
    context: PlatformContext,
    url: String,
    widthPx: Int,
    heightPx: Int,
    spec: TvBackdropBlurSpec,
): TvBlurredBackdropImage?
