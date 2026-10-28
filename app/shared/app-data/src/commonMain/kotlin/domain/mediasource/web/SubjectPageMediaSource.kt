/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

import me.him188.ani.app.domain.media.fetch.wholeSubjectEpisodeSortsOrNull
import me.him188.ani.app.domain.mediasource.latestAiredEpisode
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest

/**
 * 认得站点条目页的网页类数据源 (规则源、网页抓取源). 在线资源的播放线索记着上一集所在的条目页
 * (`WebSourceLineage.subjectUrl`) 时, 下一集直接打开这一页挑: 选中的条目页一般不会变, 连载中的番页上会多出新的集,
 * 不必再搜索、认部、逐页判断.
 */
interface SubjectPageMediaSource {
    /**
     * 打开站点上叫 [subjectName] 的条目页 [subjectUrl], 返回其中 [request] 那一集的资源 (各条线路); 这一页没有这一集时为空.
     * 页打不开时抛异常.
     */
    suspend fun mediaOnSubjectPage(subjectUrl: String, subjectName: String, request: MediaFetchRequest): List<Media>
}

/** 按 [this] 请求在站点上用 [name] 搜索 (或挑一个条目页的集) 时的条件; 规则源与网页抓取源共用. */
internal fun MediaFetchRequest.selectorSearchQuery(name: String) = SelectorSearchQuery(
    subjectName = name,
    episodeSort = episodeSort,
    allSubjectNames = subjectNames.toSet(),
    episodeEp = episodeEp,
    episodeName = episodeName,
    freshnessProbe = latestAiredEpisode()?.let {
        SelectorEpisodeProbe(episodeSort = it.sort, episodeEp = it.ep, episodeName = it.name)
    },
    subjectEpisodeSorts = wholeSubjectEpisodeSortsOrNull(),
    subjectEpisodeCount = mainEpisodeCountOrNull(),
    ovaSubject = isOvaSubject(),
)
