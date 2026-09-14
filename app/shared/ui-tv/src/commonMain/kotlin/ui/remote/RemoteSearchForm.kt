/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import me.him188.ani.app.data.models.subject.CanonicalTagKind
import me.him188.ani.app.domain.search.RatingRange
import me.him188.ani.app.domain.search.SearchSort
import me.him188.ani.app.domain.search.SubjectSearchQuery
import me.him188.ani.app.ui.exploration.search.buildSearchFilterState
import me.him188.ani.app.ui.foundation.lan.escapeHtml
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.exploration_search_filter_audience
import me.him188.ani.app.ui.lang.exploration_search_filter_category
import me.him188.ani.app.ui.lang.exploration_search_filter_character
import me.him188.ani.app.ui.lang.exploration_search_filter_custom
import me.him188.ani.app.ui.lang.exploration_search_filter_emotion
import me.him188.ani.app.ui.lang.exploration_search_filter_genre
import me.him188.ani.app.ui.lang.exploration_search_filter_rating
import me.him188.ani.app.ui.lang.exploration_search_filter_region
import me.him188.ani.app.ui.lang.exploration_search_filter_series
import me.him188.ani.app.ui.lang.exploration_search_filter_setting
import me.him188.ani.app.ui.lang.exploration_search_filter_source
import me.him188.ani.app.ui.lang.exploration_search_filter_technology
import me.him188.ani.app.ui.lang.exploration_search_sort_collection
import me.him188.ani.app.ui.lang.exploration_search_sort_date
import me.him188.ani.app.ui.lang.exploration_search_sort_match
import me.him188.ani.app.ui.lang.exploration_search_sort_rank
import me.him188.ani.app.ui.lang.search_tv_filter_any
import me.him188.ani.app.ui.lang.search_tv_filter_rating_min
import me.him188.ani.app.ui.lang.search_tv_filter_sort
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * 手机网页上提交的一次搜索: 关键词 + 与电视筛选弹窗同一套的筛选项 (排序 / 最低评分 / 标签).
 */
class RemoteSearchSubmission(
    val keywords: String,
    val sort: SearchSort,
    val minRating: Int?,
    val tags: List<String>,
) {
    /** 套在电视当前查询上 (保留季度等网页上没有的字段), 与电视筛选弹窗的确认逻辑一致. */
    fun applyTo(base: SubjectSearchQuery): SubjectSearchQuery = base.copy(
        keywords = keywords,
        tags = tags.ifEmpty { null },
        sort = sort,
        rating = minRating?.let { RatingRange(it, null) },
    )
}

/** 搜索表单里的值; 打开页面时来自电视当前查询, 提交时来自手机. */
internal class RemoteSearchFormValues(
    val keywords: String,
    val sort: SearchSort,
    val minRating: Int?,
    val tags: Set<String>,
) {
    fun toSubmission(): RemoteSearchSubmission? {
        val hasFilters = sort != SearchSort.MATCH || minRating != null || tags.isNotEmpty()
        if (keywords.isEmpty() && !hasFilters) return null
        return RemoteSearchSubmission(keywords, sort, minRating, tags.toList())
    }

    companion object {
        fun from(query: SubjectSearchQuery) = RemoteSearchFormValues(
            keywords = query.keywords,
            sort = query.sort,
            minRating = query.rating?.min,
            tags = query.tags.orEmpty().toSet(),
        )

        fun parse(fields: List<Pair<String, String>>): RemoteSearchFormValues {
            var keywords = ""
            var sort = SearchSort.MATCH
            var minRating: Int? = null
            val tags = LinkedHashSet<String>()
            for ((name, value) in fields) {
                when (name) {
                    "q" -> keywords = value.trim().take(MAX_KEYWORD_LENGTH)
                    "sort" -> sort = SearchSort.entries.firstOrNull { it.name == value } ?: SearchSort.MATCH
                    "rating" -> minRating = value.toIntOrNull()?.takeIf { it in RATING_OPTIONS }
                    "tag" -> {
                        val tag = value.trim().take(MAX_TAG_LENGTH)
                        if (tag.isNotEmpty() && tags.size < MAX_TAGS) tags += tag
                    }
                }
            }
            return RemoteSearchFormValues(keywords, sort, minRating, tags)
        }
    }
}

/**
 * 「搜索」标签页的表单: 关键词 + 排序 / 最低评分 / 各类标签. 选项与电视上的筛选弹窗 (TvSearchFilterDialog) 同源:
 * 排序 = [SearchSort] 全部, 最低评分 = [RATING_OPTIONS], 标签 = [buildSearchFilterState] 给出的分类
 * (含当前查询里不属于任何分类的「自定义」标签). 提交由页面脚本走 `api/search`, 不整页刷新.
 */
internal suspend fun renderRemoteSearchForm(values: RemoteSearchFormValues): String {
    val labels = SearchLabels.load()
    val sortHtml = SearchSort.entries.joinToString("\n") { sort ->
        pill("radio", "sort", sort.name, labels.sort(sort), checked = values.sort == sort)
    }
    val ratingHtml = buildString {
        append(pill("radio", "rating", "", labels.any, checked = values.minRating == null))
        for (min in RATING_OPTIONS) {
            append("\n").append(pill("radio", "rating", min.toString(), "$min+", checked = values.minRating == min))
        }
    }
    val tagSections = buildSearchFilterState(values.tags.toList()).chips.joinToString("\n") { chip ->
        val pills = chip.values.joinToString("\n") { tag ->
            pill("checkbox", "tag", tag, tag, checked = tag in values.tags)
        }
        "<h2>${labels.kind(chip.kind).escapeHtml()}</h2>\n<div class=\"pills\">\n$pills\n</div>"
    }
    return "<form id=\"search-form\">\n" +
            "<div class=\"qbox\"><input type=\"text\" id=\"q\" name=\"q\" value=\"${values.keywords.escapeHtml()}\" " +
            "autocomplete=\"off\" enterkeyhint=\"search\" placeholder=\"${tr("想看什么？")}\">" +
            "<div class=\"sugg\" id=\"sugg\" hidden></div></div>\n" +
            "<h2>${labels.sortTitle.escapeHtml()}</h2>\n<div class=\"pills\">\n$sortHtml\n</div>\n" +
            "<h2>${labels.ratingTitle.escapeHtml()}</h2>\n<div class=\"pills\">\n$ratingHtml\n</div>\n" +
            tagSections + "\n" +
            "<div class=\"bar\"><button type=\"submit\" class=\"primary wide\">${tr("搜索")}</button></div>\n" +
            "</form>"
}

private fun pill(type: String, name: String, value: String, label: String, checked: Boolean): String =
    "<label><input type=\"$type\" name=\"$name\" value=\"${value.escapeHtml()}\"" +
            (if (checked) " checked" else "") + "><span>${label.escapeHtml()}</span></label>"

/** 分区标题与选项文案, 与电视筛选弹窗共用同一批字符串资源 (按系统语言). */
private class SearchLabels(
    val sortTitle: String,
    val ratingTitle: String,
    val any: String,
    private val sorts: Map<SearchSort, String>,
    private val kinds: Map<CanonicalTagKind?, String>,
) {
    fun sort(sort: SearchSort): String = sorts.getValue(sort)
    fun kind(kind: CanonicalTagKind?): String = kinds[kind] ?: kinds.getValue(null)

    companion object {
        suspend fun load(): SearchLabels = SearchLabels(
            sortTitle = getString(Lang.search_tv_filter_sort),
            ratingTitle = getString(Lang.search_tv_filter_rating_min),
            any = getString(Lang.search_tv_filter_any),
            sorts = SearchSort.entries.associateWith { getString(it.label) },
            kinds = KIND_LABELS.mapValues { getString(it.value) },
        )

        private val SearchSort.label: StringResource
            get() = when (this) {
                SearchSort.MATCH -> Lang.exploration_search_sort_match
                SearchSort.RANK -> Lang.exploration_search_sort_rank
                SearchSort.COLLECTION -> Lang.exploration_search_sort_collection
                SearchSort.DATE -> Lang.exploration_search_sort_date
            }

        private val KIND_LABELS: Map<CanonicalTagKind?, StringResource> = mapOf(
            CanonicalTagKind.Audience to Lang.exploration_search_filter_audience,
            CanonicalTagKind.Category to Lang.exploration_search_filter_category,
            CanonicalTagKind.Character to Lang.exploration_search_filter_character,
            CanonicalTagKind.Emotion to Lang.exploration_search_filter_emotion,
            CanonicalTagKind.Genre to Lang.exploration_search_filter_genre,
            CanonicalTagKind.Rating to Lang.exploration_search_filter_rating,
            CanonicalTagKind.Region to Lang.exploration_search_filter_region,
            CanonicalTagKind.Series to Lang.exploration_search_filter_series,
            CanonicalTagKind.Setting to Lang.exploration_search_filter_setting,
            CanonicalTagKind.Source to Lang.exploration_search_filter_source,
            CanonicalTagKind.Technology to Lang.exploration_search_filter_technology,
            null to Lang.exploration_search_filter_custom,
        )
    }
}

/** 最低评分的档位, 与电视筛选弹窗一致. */
private val RATING_OPTIONS = listOf(7, 8, 9)

private const val MAX_KEYWORD_LENGTH = 200
private const val MAX_TAG_LENGTH = 50
private const val MAX_TAGS = 30
