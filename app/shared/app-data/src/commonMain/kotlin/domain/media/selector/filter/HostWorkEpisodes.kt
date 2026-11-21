/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector.filter

import me.him188.ani.app.domain.media.selector.MediaSelectorContext
import me.him188.ani.app.domain.mediasource.search.isHostPage
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.topic.contains

/**
 * [media] 出自本条目所依附的本篇的页 ([me.him188.ani.app.data.models.subject.SubjectSeriesInfo.hostWork], 见 [isHostPage]),
 * 并按本条目的系列内序号 (sort) 认领了这一集. 数据源在本篇的页上只留本条目的集、集号换成本条目的 sort
 * (见 `asEpisodesOf`), 本篇自己的正片一集都不交出来, 所以这样的资源就是本条目的这一集.
 *
 * 资源的条目名是本篇的名字, 名字一侧的排除 (别的季、名字对不上) 不适用; 匹配程度照常按名字算, 是模糊匹配,
 * 站点给本条目单独开的页 (精确匹配) 优先.
 */
internal fun MediaSelectorContext.claimsAsHostWorkEpisode(media: Media): Boolean {
    val host = subjectSeriesInfo?.hostWork ?: return false
    if (media.kind != MediaSourceKind.WEB) return false
    val pageName = media.properties.subjectName ?: return false
    val episode = episodeInfo?.takeIf { hasEpisode } ?: return false
    val range = media.episodeRange ?: return false
    if (episode.sort == episode.ep || !range.contains(episode.sort)) return false
    return host.isHostPage(pageName)
}
