/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.PlatformTextStyle
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch
import me.him188.ani.app.ui.foundation.TvNativeImages
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal actual fun tvImeVisible(): Boolean = WindowInsets.isImeVisible

internal actual val tvNoFontPadding: PlatformTextStyle? = PlatformTextStyle(includeFontPadding = false)

internal actual fun tvLanguageDisplayName(language: String, uiLanguageTag: String): String {
    val ui = Locale.forLanguageTag(uiLanguageTag)
    return Locale.forLanguageTag(language).getDisplayLanguage(ui).ifBlank { language }
}

internal actual fun tvPreloadTitleLogo(sketch: Sketch, context: PlatformContext, url: String, widthPx: Int, heightPx: Int) {
    TvNativeImages.preloadLogo(sketch, context, url, widthPx, heightPx, transformation = null)
}

internal actual val tvWebConsoleAvailable: Boolean = true
