/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaExclusionReason
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.app.domain.media.selector.blocksSelection
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.media_selector_item_cache_not_ready
import me.him188.ani.app.ui.lang.media_selector_item_no_subtitle
import me.him188.ani.app.ui.lang.media_selector_item_season_mismatch
import me.him188.ani.app.ui.lang.media_selector_item_single_episode_resource
import me.him188.ani.app.ui.lang.media_selector_item_subject_title_mismatch
import me.him188.ani.app.ui.lang.media_selector_item_unsupported_playback
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import org.jetbrains.compose.resources.getString
import kotlin.reflect.KClass

/**
 * 候选资源列表的 JSON, 「播放器」标签 ([RemotePlayerHandle]) 与缓存页 ([RemoteCache]) 共用: 两边给的都是选择器的
 * `filteredCandidates` (放行的 / 被排除的), 手机上是同一种列表 —— 按数据源分组、每源最多 [MAX_ITEMS_PER_SOURCE] 条、
 * 分辨率 / 字幕 / 字幕组下拉筛选、可显示被排除的及原因.
 */
@OptIn(UnsafeOriginalMediaAccess::class)
internal object RemoteCandidates {
    /** 每个数据源最多列这么多条; BT 源一搜几百条, 手机上翻不完也没意义, 其余让用户在电视上看. */
    const val MAX_ITEMS_PER_SOURCE = 40

    /**
     * 写入 `excludedCount` / `filters` / `groups`.
     *
     * 默认只列选择规则放行的 (Included); 勾了「显示被排除的」再加上 Excluded (附原因, 排在放行的后面).
     * 下拉筛选在截断之前做 —— 每个源最多 [MAX_ITEMS_PER_SOURCE] 条是筛选**之后**的, 不会漏掉符合条件的.
     * 下拉的选项与计数按「当前范围」(是否含被排除的) 算, 不受下拉本身影响, 选了一项其余选项不会消失.
     *
     * @param sourceOrder 分组顺序 (数据源列表的顺序); 缓存 (已下载好的) 若有, 放最前面
     * @param selected 当前选中的那个: 截断时挪到最前, 保证手机上看得到
     */
    fun JsonObjectBuilder.putCandidates(
        all: List<MaybeExcludedMedia>,
        sourceOrder: List<String>,
        sourceName: (sourceId: String, sample: Media) -> String,
        filter: RemoteMediaFilter,
        selected: Media? = null,
    ) {
        put("excludedCount", all.count { it is MaybeExcludedMedia.Excluded })
        val scope = if (filter.showExcluded) all else all.filter { it is MaybeExcludedMedia.Included }
        putJsonObject("filters") {
            putOptions("resolution", scope.map { it.original.properties.resolution }, { it }, ::resolutionRank)
            putOptions("subtitle", scope.flatMap { it.original.properties.subtitleLanguageIds }, ::subtitleLabel)
            putOptions("alliance", scope.map { it.original.properties.alliance }, { it })
        }
        val candidates = scope.filter { filter.accepts(it.original) }
            .sortedBy { if (it is MaybeExcludedMedia.Included) 0 else 1 } // 稳定排序: 各自保持原顺序
        val reasonTexts = if (candidates.any { it.exclusionReason != null }) loadReasonTexts() else emptyMap()

        val byId = candidates.groupBy { it.original.mediaSourceId }
        val order = buildList {
            byId.keys.filter { id -> byId.getValue(id).first().original.kind == MediaSourceKind.LocalCache }
                .let { addAll(it) }
            sourceOrder.filter { it in byId && it !in this }.let { addAll(it) }
            byId.keys.filter { it !in this }.let { addAll(it) }
        }
        putJsonArray("groups") {
            for (sourceId in order) {
                val list = byId[sourceId].orEmpty()
                if (list.isEmpty()) continue
                val selectedEntry = list.firstOrNull { it.original == selected }
                // 网页上对这个源勾了「显示全部」: 不截断
                val cap = if (sourceId == filter.fullSource) Int.MAX_VALUE else MAX_ITEMS_PER_SOURCE
                val shown = if (list.size <= cap) list else {
                    val head = list.take(cap)
                    if (selectedEntry != null && selectedEntry !in head) listOf(selectedEntry) + head.dropLast(1) else head
                }
                addJsonObject {
                    put("id", sourceId)
                    put("name", sourceName(sourceId, list.first().original))
                    // 网页按类型把候选分成可各自收起的几段 (本地缓存 / 在线 / BT), BT 一搜几百条, 不该挡在在线源前面
                    put(
                        "kind",
                        when (list.first().original.kind) {
                            MediaSourceKind.LocalCache -> "cache"
                            MediaSourceKind.BitTorrent -> "bt"
                            else -> "web"
                        },
                    )
                    put("total", list.size)
                    put("more", list.size - shown.size)
                    putJsonArray("items") {
                        for (entry in shown) addJsonObject {
                            putMedia(entry.original)
                            entry.exclusionReason?.let { reason ->
                                put("excluded", true)
                                put("reason", reasonTexts[reason::class] ?: reason.toString())
                                // 硬性不可用 (缓存还没下完) 电视上也不许选; 其余被排除的都能手动选
                                put("blocked", reason.blocksSelection)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun JsonObjectBuilder.putMedia(media: Media) {
        val props = media.properties
        put("id", media.mediaId)
        put("title", media.originalTitle)
        put("resolution", props.resolution.takeIf { it.isNotBlank() })
        put("subtitles", props.subtitleLanguageIds.map { subtitleLabel(it) }.distinct().joinToString("/").takeIf { it.isNotBlank() })
        put("alliance", props.alliance.takeIf { it.isNotBlank() })
        put("size", props.size.takeIf { it.inBytes > 0 }?.toString())
        put("cached", media.kind == MediaSourceKind.LocalCache)
    }

    /** 一个下拉框的选项: 取值 / 显示文字 / 条数; 空值不列. [rank] 非 null 时按它从高到低排, 否则按条数. */
    private fun JsonObjectBuilder.putOptions(
        key: String,
        values: List<String>,
        label: (String) -> String,
        rank: ((String) -> Int)? = null,
    ) {
        val counts = values.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        val ordered = if (rank != null) {
            counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { rank(it.key) }.thenByDescending { it.value })
        } else {
            counts.entries.sortedByDescending { it.value }
        }
        putJsonArray(key) {
            for ((value, count) in ordered) addJsonObject {
                put("value", value)
                put("label", label(value))
                put("count", count)
            }
        }
    }

    /** 分辨率按清晰度排: 4K/2160 最高, 其余取数字部分 (1080P -> 1080). */
    private fun resolutionRank(value: String): Int {
        val v = value.uppercase()
        if ("4K" in v || "2160" in v) return 2160
        return v.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
    }

    /** 排除原因的文字, 与电视上选择器卡片的一致 (MediaSelectorItem 的 mediaExclusionReasonText). */
    private fun loadReasonTexts(): Map<KClass<out MediaExclusionReason>, String> = runBlocking {
        mapOf(
            MediaExclusionReason.MediaWithoutSubtitle::class to getString(Lang.media_selector_item_no_subtitle),
            MediaExclusionReason.SingleEpisodeForCompleteSubject::class to
                    getString(Lang.media_selector_item_single_episode_resource),
            MediaExclusionReason.UnsupportedByPlatformPlayer::class to
                    getString(Lang.media_selector_item_unsupported_playback),
            MediaExclusionReason.FromSequelSeason::class to getString(Lang.media_selector_item_season_mismatch),
            MediaExclusionReason.FromSeriesSeason::class to getString(Lang.media_selector_item_season_mismatch),
            MediaExclusionReason.SubjectNameMismatch::class to getString(Lang.media_selector_item_subject_title_mismatch),
            MediaExclusionReason.CacheNotReady::class to getString(Lang.media_selector_item_cache_not_ready),
        )
    }

    fun subtitleLabel(id: String): String = when (id.uppercase()) {
        "CHS" -> tr("简中")
        "CHT" -> tr("繁中")
        "JPN", "JP" -> tr("日语")
        "ENG", "EN" -> tr("英语")
        else -> id
    }
}
