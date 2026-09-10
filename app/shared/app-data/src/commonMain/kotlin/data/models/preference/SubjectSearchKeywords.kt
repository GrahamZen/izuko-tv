/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import me.him188.ani.datasources.api.source.MediaFetchRequest

/**
 * 用户为某个条目手动改过的数据源搜索关键词, 按条目持久化.
 *
 * 只替换发给数据源的 [MediaFetchRequest.subjectNames] (首个即主搜索名). 这些词通常是为了让站内搜索
 * 能命中而削短过的, **不是**条目的正确名称, 所以选择器判定「条目名不匹配」时仍然用 Bangumi 的名字,
 * 不用这里的.
 *
 * 集数等分集字段每集都不同, 不在此持久化.
 */
@Immutable
@Serializable
data class SubjectSearchKeywords(
    val names: List<String>,
) {
    fun applyTo(request: MediaFetchRequest): MediaFetchRequest {
        val cleaned = names.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return request
        return request.copy(
            subjectNameCN = cleaned.first(),
            subjectNames = cleaned,
        )
    }
}
