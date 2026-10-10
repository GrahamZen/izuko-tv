/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector.filter

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 拆分季的页面规则 ([SplitSeasonPageMatcher]) 对每个站点页面 (源, 页名, 线路) 的判断: 认成哪一类、集号范围、按哪个序号认当前集.
 * 真机上确认拆分季在起作用、排查选错或漏掉的集用. 其他季的页面不打, 界面上它们本来就标着「季度不匹配」.
 *
 * 搜索期间资源逐源追加、整表重算很多次, 分阶段自动选择还会拿已到的部分结果另算一遍, 同一集里每个页面的每种判断
 * (类别与认哪一集) 只打一行.
 */
internal object SplitSeasonPageLog {
    private val logger = logger<SplitSeasonEpisodeMatcher>()
    private val lock = SynchronizedObject()
    private var episodeId: Int? = null

    /** 当前这一集已经打过的 (页面, 判断) */
    private val logged = HashSet<String>()

    /**
     * @param pages 页面的描述 (源 id、页名、线路) → 页面上各集的整数序号
     */
    fun log(episode: EpisodeInfo, matcher: SplitSeasonPageMatcher, pages: Map<Triple<String, String, String?>, Set<Int>>) {
        val lines = synchronized(lock) {
            if (episodeId != episode.episodeId) {
                episodeId = episode.episodeId
                logged.clear()
            }
            pages.mapNotNull { (key, numbers) ->
                val (sourceId, pageName, channel) = key
                val classification = matcher.classify(pageName)
                if (classification.kind == SplitSeasonPageMatcher.PageKind.OTHER_SEASON) return@mapNotNull null
                val page = SplitSeasonPageMatcher.PageNumbers(numbers)
                val accepted = numbers.sorted().mapNotNull { number ->
                    matcher.matchKind(classification, page, number)?.let { "$number ($it)" }
                }.ifEmpty { listOf("none") }.joinToString()
                val decision = "${classification.kind}" + (if (classification.exact) " exact" else "") + " -> $accepted"
                if (!logged.add("$sourceId|$pageName|$channel|$decision")) return@mapNotNull null
                "$sourceId 「$pageName」 ${channel.orEmpty()}: numbers ${numbers.min()}..${numbers.max()} (${numbers.size}), $decision"
            }
        }
        lines.forEach { logger.info { "Split season page for episode ${episode.episodeId} (sort ${episode.sort}, ep ${episode.ep}): $it" } }
    }
}
