/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.request.ImageRequest
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleLanguageCode

@Composable
internal actual fun tvImeVisible(): Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0

internal actual val tvNoFontPadding: PlatformTextStyle? = null

internal actual fun tvLanguageDisplayName(language: String, uiLanguageTag: String): String =
    NSLocale(localeIdentifier = uiLanguageTag).displayNameForKey(NSLocaleLanguageCode, value = language)?.takeIf { it.isNotBlank() } ?: language

internal actual fun tvPreloadTitleLogo(sketch: Sketch, context: PlatformContext, url: String, widthPx: Int, heightPx: Int) {
    sketch.enqueue(ImageRequest(context, url) { size(widthPx, heightPx) })
}

internal actual val tvCollectionProgressAvailable: Boolean = false

internal actual val tvWebConsoleAvailable: Boolean = false
