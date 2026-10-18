/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import me.him188.ani.app.ui.mediafetch.request.EditingMediaFetchRequest
import me.him188.ani.app.ui.mediafetch.request.toMediaFetchRequestOrNull
import me.him188.ani.datasources.api.source.MediaFetchRequest

/**
 * 网页上改的搜索名与集数套回 [current]; 请求无效 (条目或剧集 ID 不是数字) 时为 `null`.
 *
 * 编辑器只管名字与集数. 剧集列表、回退搜索关键词、拆分季的整季序号沿用 [current] 的: 丢了剧集列表, 在线源退回只产出当前这一集,
 * 切集后就没有结果; 丢了回退关键词, 主名字搜不到时也不再用系列名搜.
 */
internal fun EditingMediaFetchRequest.applyTo(current: MediaFetchRequest): MediaFetchRequest? {
    val edited = toMediaFetchRequestOrNull() ?: return null
    return current.copy(
        subjectNameCN = edited.subjectNameCN,
        subjectNames = edited.subjectNames,
        episodeSort = edited.episodeSort,
        episodeEp = edited.episodeEp,
    )
}
