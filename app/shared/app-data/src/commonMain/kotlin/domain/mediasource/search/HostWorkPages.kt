/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import me.him188.ani.app.domain.mediasource.MediaListFilters
import me.him188.ani.app.domain.mediasource.toSimplifiedChinese
import me.him188.ani.datasources.api.source.MediaFetchHostWork

/**
 * 站点上叫 [pageName] 的条目是本篇 [this] 的页: 与本篇的某个名字相同 (去掉标点空格比, 繁体页名再转简体比一次).
 *
 * 本篇的页上只有接在本篇后面编号的那几集是本条目的 (见 `asEpisodesOf`), 所以搜索计划把这种页当作说不准、照样打开 ([SubjectSearchPlan.identify]),
 * 选择器只按模糊匹配收下其中按本条目集号认领的资源.
 */
internal fun MediaFetchHostWork.isHostPage(pageName: String): Boolean {
    val page = normalized(pageName)
    if (page.isEmpty()) return false
    val simplified = normalized(pageName.toSimplifiedChinese())
    return names.any { name -> normalized(name).let { it.isNotEmpty() && (it == page || it == simplified) } }
}

private fun normalized(name: String): String = MediaListFilters.normalizeForCompare(name).lowercase()
