/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.transform.Transformation
import me.him188.ani.app.data.network.TmdbTitleLogo

// iOS: 不按背景判明暗、不翻色, logo 原样画.

actual object TvTitleLogoGrids {
    actual suspend fun obtain(sketch: Sketch, context: PlatformContext, logo: TmdbTitleLogo, size: TvTitleLogoSize): TvTitleLogoGrid? = null
}

actual object TvTitleLogoDetailsLooks {
    private val ORIGINAL = TvHeroTitleLogoLook(flipLightText = null)

    actual fun of(logo: TmdbTitleLogo, backdropUrl: String): TvHeroTitleLogoLook? = ORIGINAL

    actual fun putBackdrop(url: String, bitmap: ImageBitmap) {}

    actual fun request(
        sketch: Sketch,
        context: PlatformContext,
        logo: TmdbTitleLogo,
        size: TvTitleLogoSize,
        backdropUrl: String,
        logoRect: Rect,
        backdropRect: Rect,
        leftScrim: TvBackdropFade?,
    ) {
    }
}

actual object TvTitleLogoBitmaps {
    private const val MAX_ENTRIES = 24

    /** 按最近用过排: 取到就挪到末尾, 超出从头上淘汰. 只在主线程用. */
    private val cache = LinkedHashMap<String, ImageBitmap>()

    private fun key(logo: TmdbTitleLogo, flipLightText: Boolean?, force: Boolean): String =
        "${logo.filePath}|$flipLightText|$force"

    actual fun get(logo: TmdbTitleLogo, flipLightText: Boolean?, force: Boolean): ImageBitmap? {
        val k = key(logo, flipLightText, force)
        return cache.remove(k)?.also { cache[k] = it }
    }

    actual fun put(logo: TmdbTitleLogo, flipLightText: Boolean?, bitmap: ImageBitmap, force: Boolean) {
        cache[key(logo, flipLightText, force)] = bitmap
        if (cache.size > MAX_ENTRIES) cache.remove(cache.keys.first())
    }
}

actual fun TvHeroTitleLogoLook.tvLogoTransformation(): Transformation? = null

actual fun tvTitleLogoLightTransformation(): Transformation? = null
