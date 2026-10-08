/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.ui.media.renderSubtitleLanguage
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.topic.Resolution

/*
 * 选源面板左栏最顶上的「筛选」: 分辨率 / 字幕两项, 对 BT 与缓存、一个文件一行的在线源 (网盘类, 见 webFilesOf) 一起生效.
 *
 * 取值就是这部番的偏好 (选择器的 resolution / subtitleLanguageId, 自动选源也按它), 选中一个资源时选择器会把它的分辨率 / 字幕记成偏好.
 * 所以每个源只认自己结果里有的取值: 播了一个 4K 的网盘文件, 没有 4K 的源照常列, 不会整页变成「低于偏好」(BT 那边同理, 见 projectBtList).
 * 有这个取值的源里, 不符合的调暗排在后面、写「低于偏好」(BT 的收进「已被排除」).
 *
 * 线路式的在线源不筛: 它们的分辨率 / 字幕是数据源配置里写死的默认值 (多半 1080P / 简中), 不是每条结果自己的.
 */

/** 把「筛选」放在左栏最顶上, 与下面各项之间画一道分隔线. */
internal fun List<TvSourceRow>.withFilterRow(row: TvSourceRow): List<TvSourceRow> =
    listOf(row) + mapIndexed { index, it -> if (index == 0) it.copy(dividerAbove = true) else it }

/** 左栏的「筛选」: 右端写当前的取值 (没选不写). */
internal fun filterRailRow(input: TvSourcePanelInput, strings: TvSourceStrings): TvSourceRow {
    val values = listOfNotNull(
        input.resolutionFilter?.let(::resolutionLabel),
        input.subtitleFilter?.let { renderSubtitleLanguage(it, strings.details) },
    )
    return TvSourceRow(
        id = TvSourceRailKeys.FILTER,
        style = TvSourceRowStyle.Rail,
        title = strings.filter,
        icon = TvSourceRowIcon.Filter,
        trailing = values.joinToString(" · "),
        accent = if (values.isEmpty()) TvSourceAccent.None else TvSourceAccent.Attention,
    )
}

/**
 * 「筛选」的分支: 分辨率、字幕两组取值排成一列, 确定就选 ([TvSourceAction.PickFilter], 同 BT 那排原来的胶囊). 取值来自 BT 的结果与
 * 一个文件一行的在线源对得上的文件 ([files]), 再加上当前选的 (万一眼下的结果里没有).
 */
internal fun filterPane(input: TvSourcePanelInput, files: List<Media>, strings: TvSourceStrings): TvSourceRight {
    val resolution = input.resolutionFilter
    val subtitle = input.subtitleFilter
    val resolutions = (input.bt.availableResolutions + files.map { it.properties.resolution } + listOfNotNull(resolution))
        .filter { it.isNotBlank() }
        .distinct()
        .sortedByDescending { resolutionOf(it)?.size ?: -1 }
    val subtitles = (input.bt.availableSubtitleLanguageIds + files.flatMap { it.properties.subtitleLanguageIds } + listOfNotNull(subtitle))
        .distinct()
    val rows = buildList {
        add(filterHeader(TvSourceFilter.Resolution, strings.resolution, dividerAbove = false))
        addFilterOptions(TvSourceFilter.Resolution, resolution, resolutions.map { it to resolutionLabel(it) }, strings)
        add(filterHeader(TvSourceFilter.Subtitle, strings.subtitle, dividerAbove = true))
        addFilterOptions(TvSourceFilter.Subtitle, subtitle, subtitles.map { it to renderSubtitleLanguage(it, strings.details) }, strings)
    }
    return TvSourceRight(
        key = TvSourceRailKeys.FILTER,
        rows = rows,
        focusId = rows.firstOrNull { it.selected }?.id,
    )
}

/**
 * 按筛选把一个文件一行的源对得上的文件分成 (符合的, 低于偏好的). 两项各自只在这批文件里有这个取值时才筛 (见文件头).
 */
internal fun partitionByFilters(files: List<Media>, input: TvSourcePanelInput): Pair<List<Media>, List<Media>> {
    val resolution = input.resolutionFilter?.takeIf { value -> files.any { it.properties.resolution == value } }
    val subtitle = input.subtitleFilter?.takeIf { value ->
        files.any { value in it.properties.subtitleLanguageIds }
    }
    if (resolution == null && subtitle == null) return files to emptyList()
    return files.partition {
        (resolution == null || it.properties.resolution == resolution) &&
                (subtitle == null || subtitle in it.properties.subtitleLanguageIds)
    }
}

/** 当前的分辨率 / 字幕筛选; null = 没选 (默认偏好里的「任意」也算没选). */
private val TvSourcePanelInput.resolutionFilter: String?
    get() = selector.resolution.finalSelected?.takeIf { it != MediaPreference.ANY_FILTER }

private val TvSourcePanelInput.subtitleFilter: String?
    get() = selector.subtitleLanguageId.finalSelected?.takeIf { it != MediaPreference.ANY_FILTER }

/** 分辨率给人看的名字 (2160P 写 4K). */
private fun resolutionLabel(value: String): String = resolutionOf(value)?.displayName ?: value

/** 资源里存的分辨率多半是显示名 (Resolution.toString(), 如 4K), tryParse 只认 2160P 这类写法. */
private fun resolutionOf(value: String): Resolution? =
    Resolution.tryParse(value) ?: Resolution.entries.firstOrNull { it.displayName.equals(value, ignoreCase = true) }

private fun filterHeader(filter: TvSourceFilter, title: String, dividerAbove: Boolean) = TvSourceRow(
    id = "filter-header:" + filter.name,
    style = TvSourceRowStyle.Status,
    title = title,
    dividerAbove = dividerAbove,
)

private fun MutableList<TvSourceRow>.addFilterOptions(
    filter: TvSourceFilter,
    current: String?,
    values: List<Pair<String, String>>,
    strings: TvSourceStrings,
) {
    for ((value, label) in listOf(null to strings.all) + values) {
        add(
            TvSourceRow(
                id = "filter:" + filter.name + ":" + (value ?: ""),
                style = TvSourceRowStyle.Option,
                title = label,
                icon = if (value == current) TvSourceRowIcon.Check else TvSourceRowIcon.None,
                selected = value == current,
                action = TvSourceAction.PickFilter(filter, value),
            ),
        )
    }
}
