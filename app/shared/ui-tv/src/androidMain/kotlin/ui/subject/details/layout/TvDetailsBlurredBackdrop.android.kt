/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import me.him188.ani.app.ui.foundation.tv.nativeview.TvBackdropBlurSpec
import me.him188.ani.app.ui.foundation.tv.nativeview.tvBlurredBackdropImage

/** 安卓的海报墙模糊层交来的是解好的位图: 量好亮度再放进平板模式的最近几张里 (见公共代码里同名的那个). */
internal fun tvRememberTouchBlurredBackdrop(url: String, spec: TvBackdropBlurSpec, size: IntSize, bitmap: Bitmap) {
    tvRememberTouchBlurredBackdrop(url, spec, size, tvBlurredBackdropImage(bitmap))
}
