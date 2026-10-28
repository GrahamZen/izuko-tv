/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.httpdownloader

/**
 * 继续下载前对一遍分段文件. 分段只在合并成功之后才删, 所以「标着下完、文件却没了」只有两种来历:
 * 合并其实已经做完 (合并中被暂停: 拼接不会中途停, 做完后状态却是暂停), 或分段被外部删掉了.
 * 不对这一遍的话, 继续时会直接去合并, 先清空合好的输出, 再读不存在的分段而失败, 已下完的内容就没了.
 */
internal sealed interface SegmentFilesCheck {
    /** 标着下完的分段文件都在, 照常继续. */
    data object Intact : SegmentFilesCheck

    /**
     * 分段全没了, 输出文件大小正好是各段之和: 早就合并完了, 直接算完成.
     * 只认按顺序拼接的 MP4 / MKV; HLS 要转封装, 输出大小和分段对不上, 只能重下.
     */
    data object AlreadyMerged : SegmentFilesCheck

    /** 这些分段 (按 [SegmentInfo.index]) 文件没了, 要重新下. */
    data class Redownload(val indexes: Set<Int>) : SegmentFilesCheck
}

/**
 * @param segmentExists 这一段的分段文件还在不在
 * @param outputSize 输出文件的大小, 不存在时为 `null`
 */
internal fun DownloadState.checkSegmentFiles(
    segmentExists: (SegmentInfo) -> Boolean,
    outputSize: Long?,
): SegmentFilesCheck {
    val missing = segments.filter { it.isDownloaded && !segmentExists(it) }
    if (missing.isEmpty()) return SegmentFilesCheck.Intact
    val concatenated = mediaType == MediaType.MP4 || mediaType == MediaType.MKV
    if (concatenated && missing.size == segments.size && outputSize != null && outputSize > 0 &&
        outputSize == segments.sumOf { it.byteSize }
    ) {
        return SegmentFilesCheck.AlreadyMerged
    }
    return SegmentFilesCheck.Redownload(missing.mapTo(HashSet()) { it.index })
}

/** 把 [indexes] 这些分段标回没下, 已下字节数扣掉它们 (重下完会再加上). */
internal fun DownloadState.withSegmentsToRedownload(indexes: Set<Int>): DownloadState {
    val removedBytes = segments.filter { it.index in indexes }.sumOf { it.byteSize.coerceAtLeast(0) }
    return copy(
        segments = segments.map { if (it.index in indexes) it.copy(isDownloaded = false) else it },
        downloadedBytes = (downloadedBytes - removedBytes).coerceAtLeast(0),
    )
}
