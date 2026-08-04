/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import com.github.panpf.sketch.ComponentRegistry
import com.github.panpf.sketch.decode.SkiaGifDecoder

internal actual fun ComponentRegistry.Builder.addAniAnimatedDecoders() {
    // skiko 平台只有这一条路 (Skia 自带 Codec), 没有 Android 那个解码器的坑
    addDecoder(SkiaGifDecoder.Factory())
}
