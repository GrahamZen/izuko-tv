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

// 标题 logo 按背景判明暗、翻色要读位图像素: 安卓上照原来的做法 (见安卓的实现); 别的端不判, logo 原样画.

/** 各条目 logo 的格子取色 (列表页 hero 判 logo 压在背景上看不看得清用). */
expect object TvTitleLogoGrids {
    /** 取 [logo] 按 [size] 解好的格子 (取不到为 null); 邻居预取时提前算好. */
    suspend fun obtain(sketch: Sketch, context: PlatformContext, logo: TmdbTitleLogo, size: TvTitleLogoSize): TvTitleLogoGrid?
}

/** 详情页首屏 logo 的样子: 按背景图 (连同图左压着的渐变) 判出来的. */
expect object TvTitleLogoDetailsLooks {
    /** 判好的样子; 还没判完为 null. */
    fun of(logo: TmdbTitleLogo, backdropUrl: String): TvHeroTitleLogoLook?

    /** 背景图解好了 (判的时候读它的像素). */
    fun putBackdrop(url: String, bitmap: ImageBitmap)

    /** 判一次 (判过 / 在判的不重复). */
    fun request(
        sketch: Sketch,
        context: PlatformContext,
        logo: TmdbTitleLogo,
        size: TvTitleLogoSize,
        backdropUrl: String,
        logoRect: Rect,
        backdropRect: Rect,
        leftScrim: TvBackdropFade?,
    )
}

/** 解好的 logo 位图 (按翻色方向分开存): 交接时别处已经解好过就同步画出来. */
expect object TvTitleLogoBitmaps {
    fun get(logo: TmdbTitleLogo, flipLightText: Boolean?, force: Boolean = false): ImageBitmap?

    fun put(logo: TmdbTitleLogo, flipLightText: Boolean?, bitmap: ImageBitmap, force: Boolean = false)
}

/** 画成 [this] 这个样子要的图片变换 (翻色); 不用翻为 null. */
expect fun TvHeroTitleLogoLook.tvLogoTransformation(): Transformation?

/** 把 logo 翻成浅色字用的变换 (压在深色底上); 不支持翻色的端为 null. */
expect fun tvTitleLogoLightTransformation(): Transformation?
