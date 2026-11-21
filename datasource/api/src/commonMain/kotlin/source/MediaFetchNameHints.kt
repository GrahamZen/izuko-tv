/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.api.source

import kotlinx.serialization.Serializable

/**
 * 由条目服务、系列关系与对应表推出的名字, 帮数据源认站点上的条目是不是这一部 ([MediaFetchRequest.nameHints]).
 * 与用户能编辑的搜索名 ([MediaFetchRequest.subjectNames]) 分开: 搜索名改了, 这一部还是这一部.
 */
@Serializable
data class MediaFetchNameHints(
    /**
     * 也算这一部的名字, 只拿来认站点上的条目, 不拿去搜: 条目服务上的全部名字、港台译名、
     * 系列关系推出的「系列名 第N季」(Bangumi 不一定有这个别名, 站点给后面各季起名却多用它).
     */
    val ownNames: List<String> = emptyList(),
    /**
     * 系列名: 同系列别的作品里被本条目某个名字包含的那些 (「继 怪怪守护神」的「怪怪守护神」), 长的在前.
     * 整个系列都叫这个名字, 按它搜会把各季都搜出来, 再按 [ownNames] 与 [otherWorkNames] 挑出这一季.
     */
    val seriesNames: List<String> = emptyList(),
    /** 同系列别的作品的名字 (含推出的「系列名 第N季」): 站点上叫这个名字的条目是别的季. */
    val otherWorkNames: List<String> = emptyList(),
    /** 本条目是拆分季的一段: 站点可能用别的段或整季的名字给本季起名, 名字对不上的条目也可能是这一部. */
    val splitSeason: Boolean = false,
    /** 本条目接在这部本篇后面编号 (见 [MediaFetchHostWork]): 站点常把它放在本篇的页里. */
    val hostWork: MediaFetchHostWork? = null,
) {
    companion object {
        val Empty = MediaFetchNameHints()
    }
}

/**
 * 本条目所依附的本篇: Bangumi 上本条目的「主线故事」, 本条目的集号接着它的正片往下编 (TV 13 集之后的 OVA 是第 14 集).
 * 站点常不给这种 OVA、特别篇单独开页, 而是放在本篇的页里, 写成「OVA」或接着编的集号.
 *
 * @property names 本篇的全部名字 (原名、中文名与别名), 认站点上本篇的页用
 * @property firstSort 本篇正片第一集的 sort
 * @property episodeCount 本篇的正片集数; 本条目正片第一集的 sort 是 [firstSort] + [episodeCount]
 */
@Serializable
data class MediaFetchHostWork(
    val names: List<String>,
    val firstSort: Int,
    val episodeCount: Int,
)
