/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import kotlinx.atomicfu.atomic

/**
 * Bangumi 图床的按宽缩略图: `https://lain.bgm.tv/r/<宽>/pic/cover/l/…` 出一张该宽度的**基线** JPEG.
 *
 * 为什么要换: 条目封面 (`images.large`) 常见 850×1200, 大的到 2900×4096, 而且多是**渐进式** JPEG —— 缩小解码也得把整张的
 * 熵编码过一遍. 电视上一张海报卡只有 275×384 像素, 索尼上来回切行时后台解码占掉约 0.4 个核、一张 150~500ms, 还跟主线程抢 CPU.
 * 同一张 400 宽的缩略图约 60KB (原图 218KB 起), 解码快一个数量级, 在内存缓存里也更小.
 *
 * - 宽度只认 [BANGUMI_COVER_THUMBNAIL_WIDTHS] 这几档 (别的宽度图床回 400 "invalid size format"). 取**不小于显示宽度的最小一档**:
 *   请求尺寸是显示尺寸的 [ANI_IMAGE_REQUEST_OVERSAMPLE] 倍, 相邻两档至多差 2 倍, 于是缩略图总比请求尺寸小、按原尺寸解,
 *   解出来不小于显示尺寸 (不欠采样, 见 [ANI_IMAGE_REQUEST_OVERSAMPLE]). 宽过原图时图床按原尺寸出.
 * - 只改 `lain.` 开头的图床上 `/pic/cover/l/` 的地址: 原站与 Mirrox 镜像 (`lain.bangumi.vip`, 自建的同理) 都原样透传这个路径
 *   (实测字节一致). 数据里存的是镜像域名时照样改, 请求时再由 BangumiMirrorFeature 按线路换域名.
 * - 显示宽度超过最大一档时用原图. 缩略图请求失败时下载层当场改取原图 (见 ScopedHttpClientHttpStack); 连续几次都是缩略图失败、
 *   原图却能取到, 就当这条线路不支持缩略图, 本进程不再换 (见 [noteBangumiCoverThumbnailFallback]).
 */
internal val BANGUMI_COVER_THUMBNAIL_WIDTHS = intArrayOf(100, 200, 400, 600, 800, 1200)

private const val BANGUMI_COVER_PATH = "/pic/cover/l/"

/** 缩略图地址里 `/r/<宽>` 那一段, 换回原图时用. */
private val BANGUMI_COVER_THUMBNAIL_SEGMENT = Regex("""^(https?://lain\.[^/]+)/r/\d+(/pic/cover/l/.*)$""")

/**
 * [url] 若是 Bangumi 的大封面, 返回按 [displayWidthPx] 选档的缩略图地址; 不是或用不着 (显示宽度超过最大一档、本进程已停用) 时返回 null.
 */
internal fun bangumiCoverThumbnailUrl(url: String, displayWidthPx: Int): String? {
    if (displayWidthPx <= 0 || !bangumiCoverThumbnailsEnabled()) return null
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return null
    val scheme = url.substring(0, schemeEnd)
    if (scheme != "https" && scheme != "http") return null
    val hostStart = schemeEnd + 3
    val pathStart = url.indexOf('/', hostStart)
    if (pathStart < 0) return null
    val host = url.substring(hostStart, pathStart)
    if (!host.startsWith("lain.") || !url.startsWith(BANGUMI_COVER_PATH, pathStart)) return null
    val width = BANGUMI_COVER_THUMBNAIL_WIDTHS.firstOrNull { it >= displayWidthPx } ?: return null
    return url.substring(0, pathStart) + "/r/" + width + url.substring(pathStart)
}

/** [url] 是 [bangumiCoverThumbnailUrl] 换出来的缩略图地址时, 返回对应的原图地址; 否则 null. */
internal fun bangumiCoverOriginalUrl(url: String): String? =
    BANGUMI_COVER_THUMBNAIL_SEGMENT.matchEntire(url)?.let { it.groupValues[1] + it.groupValues[2] }

private val thumbnailsDisabled = atomic(false)

private val consecutiveFallbacks = atomic(0)

/** 连续这么多次「缩略图失败、原图取到了」就停用. 偶发的一两次 (图床抖动) 不算. */
private const val BANGUMI_COVER_THUMBNAIL_DISABLE_AFTER = 3

internal fun bangumiCoverThumbnailsEnabled(): Boolean = !thumbnailsDisabled.value

/**
 * 下载层回报一次缩略图的结果: [thumbnailFailedButOriginalWorked] = 缩略图请求失败、改取原图成功.
 * 缩略图成功一次就清零计数.
 */
internal fun noteBangumiCoverThumbnailFallback(thumbnailFailedButOriginalWorked: Boolean) {
    if (!thumbnailFailedButOriginalWorked) {
        consecutiveFallbacks.value = 0
        return
    }
    if (consecutiveFallbacks.incrementAndGet() >= BANGUMI_COVER_THUMBNAIL_DISABLE_AFTER) {
        thumbnailsDisabled.value = true
    }
}

/** 只给测试用: 恢复初始状态. */
internal fun resetBangumiCoverThumbnailsForTest() {
    thumbnailsDisabled.value = false
    consecutiveFallbacks.value = 0
}
