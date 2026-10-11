/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.SubjectSeriesInfo
import me.him188.ani.app.domain.media.selector.filter.FilterResultMemo
import me.him188.ani.datasources.api.Media

/**
 * 逐条筛选结果的记忆表 (交给 `MediaSelectorFilterSortAlgorithm.filterMediaList` 的 memo): 搜索期间列表逐源追加, 每次都整表重算的代价太高
 * (真机实测 127 条 280~1238ms, 三秒九次). 一张表只给一条候选流用, 不能并发调用.
 */
internal class MediaFilterMemo : FilterResultMemo {
    private var key: Key? = null

    /**
     * **键必须是 media 本身而不是 mediaId**: 同一个 mediaId 可能对应内容不同的两条
     * (例如同一资源的缓存版与原版), 按 id 记忆会把先算的那条的结果错配给后一条.
     */
    private val memo = HashMap<Media, Entry>()

    private class Entry(val page: Any?, val result: MaybeExcludedMedia)

    override fun get(media: Media, page: Any?): MaybeExcludedMedia? = memo[media]?.takeIf { it.page == page }?.result

    override fun put(media: Media, page: Any?, result: MaybeExcludedMedia) {
        memo[media] = Entry(page, result)
    }

    /**
     * 上一次见到的"不可播缓存 id" —— 单独记, 不进 [Key].
     *
     * 缓存下完那一刻这个集合会变 (某条从"不可播"变成可播), 若把它并进整表失效键, 就会在用户
     * 正盯着选源列表的时候把 100+ 条全部重算一遍 (真机实测 126 条 295ms). 实际上受影响的
     * 只有 id 出现在**新旧集合对称差**里的那几条, 定点剔除即可.
     */
    private var lastUnplayableCacheMediaIds: Set<String>? = null

    /**
     * 按这一轮的输入整理记忆表后交出去: 影响单条结果的输入变了就整表作废, 不可播缓存集合变了只剔除涉及的几条,
     * 不在 [list] 里的项删掉 (本地源每次 restart 都会生成新的 CachedMedia 对象, 不清的话旧对象会一直积在表里).
     */
    fun prepare(
        list: List<Media>,
        preference: MediaPreference,
        settings: MediaSelectorSettings,
        context: MediaSelectorContext,
    ): FilterResultMemo {
        val newKey = Key(
            preference, settings,
            context.subjectInfo, context.episodeInfo, context.subjectSeriesInfo, context.subjectFinished,
            context.subtitlePreferences,
        )
        val invalidated = key != newKey
        if (invalidated) {
            key = newKey
            memo.clear()
        }
        // null 当空集看待 —— 生产者按设计先发一个 null (见 MediaSelectorContextFlowProducer),
        // 那一轮本地缓存会被算成可选并记进表; 真集合到达时若不剔除, 没下完的缓存就会一直可选.
        val unplayableIds = context.unplayableCacheMediaIds
        if (unplayableIds != lastUnplayableCacheMediaIds) {
            val old = lastUnplayableCacheMediaIds.orEmpty()
            val new = unplayableIds.orEmpty()
            lastUnplayableCacheMediaIds = unplayableIds
            if (!invalidated) {
                val changed = (old - new) + (new - old)
                if (changed.isNotEmpty()) {
                    memo.keys.removeAll { it.mediaId in changed }
                }
            }
        }
        if (memo.size > list.size) {
            val alive = list.toHashSet()
            memo.keys.retainAll(alive)
        }
        return this
    }

    /**
     * 失效键: **只含真正影响单条筛选结果的输入**.
     *
     * 刻意不含整个 [MediaSelectorContext] —— 它里面的 `mediaSourceTiers` / `mediaSourcePrecedence`
     * 只被排序用到 (见 `MediaSelectorFilterSortAlgorithm.sortMediaList`), 而它们来自数据源实例与
     * 设置, 在搜索期间会反复变化. 用整个 context 作键的话, 每变一次就把记忆表整个作废, 于是又回到
     * "每次全表重算" (真机实测每次 180~552ms).
     */
    private data class Key(
        val preference: MediaPreference,
        val settings: MediaSelectorSettings,
        val subjectInfo: SubjectInfo?,
        val episodeInfo: EpisodeInfo?,
        val subjectSeriesInfo: SubjectSeriesInfo?,
        val subjectFinished: Boolean?,
        val subtitlePreferences: MediaSelectorSubtitlePreferences?,
        // 注意: 不含 unplayableCacheMediaIds —— 它变化时只有涉及的那几条要重算, 见 prepare 里的定点失效
    )
}
