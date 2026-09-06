/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import me.him188.ani.app.data.network.TmdbTitleLogo

/**
 * 解好的标题 logo, 按 logo 与翻色 (TvTitleLogoFlip 的 lightText; null = 原样) 记下, 给交接时同步取用.
 *
 * 同一张 logo 进出详情页时由好几个图片实例接力画: 列表页的原生文字块、详情页占位页与真页、放大 / 缩回层. Compose 的图片组件要等布局量出
 * 尺寸才发请求, 新建的实例即使图在内存缓存里也要空一两帧 —— 交接那一刻 logo 就闪一下. 各处解好图都记到这里, 新实例组合时先从这里取,
 * 第一帧就画出来. 只在主线程读写; 只留最近的几张.
 */
object TvTitleLogoBitmaps {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > MAX_ENTRIES
    }

    private fun key(logo: TmdbTitleLogo, flipLightText: Boolean?, force: Boolean): String =
        logo.filePath + when (flipLightText) {
            null -> "#original"
            true -> "#light"
            false -> "#dark"
        } + if (flipLightText != null && force) "!" else ""

    /** [logo] 按 [flipLightText] 翻色后的样子 ([force] 同 TvTitleLogoFlip); 没记过为 null. */
    fun get(logo: TmdbTitleLogo, flipLightText: Boolean?, force: Boolean = false): ImageBitmap? = cache[key(logo, flipLightText, force)]

    /** 记下按 [flipLightText] ([force] 同 TvTitleLogoFlip) 请求、解好的 [bitmap] (翻过色的就是翻过的样子). */
    fun put(logo: TmdbTitleLogo, flipLightText: Boolean?, bitmap: ImageBitmap, force: Boolean = false) {
        cache[key(logo, flipLightText, force)] = bitmap
    }

    /**
     * 记下原生图片请求解好的 [bitmap]: 按 [flipLightText] ([force] 同 TvTitleLogoFlip) 请求, [transformed] = 真的翻了色. 没翻 = 就是原图,
     * 原样与「这种字色下不用翻」的样子也都是它 (按 logo 的色调判, 见 [TvTitleLogoTone.needsFlip]), 一并记下.
     */
    fun putDecoded(logo: TmdbTitleLogo, flipLightText: Boolean?, transformed: Boolean, bitmap: Bitmap, force: Boolean = false) {
        val image = bitmap.asImageBitmap()
        put(logo, flipLightText, image, force)
        if (transformed) return
        put(logo, null, image)
        val tone = tvTitleLogoTone(bitmap)
        for (lightText in LIGHT_TEXT) {
            if (!tone.needsFlip(lightText)) put(logo, lightText, image)
        }
    }

    private val LIGHT_TEXT = listOf(true, false)
    private const val MAX_ENTRIES = 12
}
