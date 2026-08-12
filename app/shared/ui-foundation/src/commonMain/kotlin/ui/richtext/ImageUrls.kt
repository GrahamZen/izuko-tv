/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.richtext

/**
 * 把评论里贴的图片地址修正成能直接下载到图的地址.
 *
 * 用户往 `[img]` 里贴的常常是**网页地址**而不是图片地址 —— 浏览器打开能看到图, 但 GET 回来是一篇
 * HTML, 图床也不会重定向到图, 于是 Coil 解不出图, 评论里就剩一块加载不出来的占位.
 * 最常见的是 imgur (`https://imgur.com/aHiG1wL`), 按图床自己的规则换成直链即可.
 *
 * 认不出的地址原样返回 —— 宁可维持现状, 也不要猜错把本来能显示的图弄坏.
 *
 * 要支持新图床: 在 [REWRITES] 里加一条.
 */
fun normalizeImageUrl(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return url
    for ((pattern, replacement) in REWRITES) {
        if (pattern.matches(trimmed)) return pattern.replace(trimmed, replacement)
    }
    return url
}

/**
 * 图床网页地址 -> 直链. 正则一律整串匹配, 免得误伤本来就是直链的地址.
 */
private val REWRITES: List<Pair<Regex, String>> = listOf(
    // imgur 网页页面: https://imgur.com/aHiG1wL, /gallery/aHiG1wL, /a/aHiG1wL
    // -> https://i.imgur.com/aHiG1wL.jpg (imgur 按扩展名转格式, 给 .jpg 也能拿到原本是 png 的图)
    Regex("""https?://(?:www\.|m\.)?imgur\.com/(?:gallery/|a/|t/[^/]+/)?([A-Za-z0-9]{5,10})/?""")
        to "https://i.imgur.com/$1.jpg",
    // 直链域名但漏了扩展名的, 补一个
    Regex("""https?://i\.imgur\.com/([A-Za-z0-9]{5,10})/?""")
        to "https://i.imgur.com/$1.jpg",
)
