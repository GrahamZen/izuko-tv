/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.app.data.network.SeriesGraphTableRepository
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.concurrent.Volatile

/**
 * 网页类数据源认部 ([SubjectSearchPlan.identify]) 用的全部动画的名字表. 请求 ([MediaFetchRequest]) 要能序列化, 带不了表, 所以从这里取:
 * app 启动时把 [source] 接到 [SeriesGraphTableRepository.loaded]; 没接上 (单测) 或表还没读进来时只按名字认 ([TitleIdentity.namesOnly]).
 */
object AnimeNames {
    @Volatile
    var source: (() -> SeriesGraphTable?)? = null

    @Volatile
    private var last: Pair<List<Any?>, TitleIdentity>? = null

    /** 按名字表给 [request] 认部 ([TitleIdentity]); 一次查询的几个数据源共用 (记最近一个条目). 没有表时只按名字认. */
    internal fun identityOf(request: MediaFetchRequest): TitleIdentity {
        val table = source?.invoke()
        val key = listOf(table?.dump, table?.size, request.subjectId, request.subjectNames, request.nameHints)
        last?.takeIf { it.first == key }?.let { return it.second }
        val identity = if (table == null) TitleIdentity.namesOnly(request) else TitleIdentity.of(request, table, AnimeNameIndex.of(table))
        return identity.also { last = key to it }
    }
}
