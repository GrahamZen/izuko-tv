/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.isLowEffort

/**
 * 自动选源里紧跟缓存的一档: 本机或局域网上的文件 (资源的位置是 Local / Lan: 本地文件夹、局域网里的 WebDAV), 不用下载、开播最快,
 * 有就选, 不看记住的源、不等在线源. 合偏好的在前; 缓存 ([MediaSourceKind.LocalCache]) 是前一档, 这里不算.
 *
 * 不等这些源查完: 盘没接上、NAS 关着时查询要等超时, 不能为它拖慢开播; 本地文件夹的索引存在盘上, 通常在在线源之前就有结果.
 */
internal fun localFileCandidate(
    preferred: List<MaybeExcludedMedia.Included>,
    candidates: List<MaybeExcludedMedia.Included>,
): MaybeExcludedMedia.Included? {
    fun List<MaybeExcludedMedia.Included>.firstLocal() =
        firstOrNull { it.result.kind != MediaSourceKind.LocalCache && it.result.location.isLowEffort() }
    return preferred.firstLocal() ?: candidates.firstLocal()
}
