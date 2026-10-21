/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector.filter

import kotlinx.datetime.atStartOfDayIn
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.UTC9
import me.him188.ani.datasources.api.toLocalDateOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * 资源的发布时间比这一集开播早 [AIRING_LEEWAY] 以上: 不可能是这一集, 多半是同名的前几季或老番. 新番刚开播、这一集还没出的时候
 * 搜到的全是这种, 不排除就会被自动选中 (BT 的种子、网盘里旧季的文件最明显). 余量留给提前一周左右上线的先行配信.
 *
 * 按单集选源时才判断 (整个条目的资源列表里别的集本来就早于这一集). 不判断的: 发布时间不知道 (在线源基本都是 0)、
 * 不像毫秒时间戳的 (见 [EARLIEST_PLAUSIBLE_MILLIS])、这一集没有开播日期; 本地缓存在这条规则之前就放行了.
 *
 * @param airDate 这一集的开播日期 (bangumi 的, 按日本时间算这一天的开始)
 */
internal fun isPublishedLongBeforeAiring(publishedTime: Long, airDate: PackedDate?): Boolean {
    if (publishedTime < EARLIEST_PLAUSIBLE_MILLIS) return false
    val date = airDate?.toLocalDateOrNull() ?: return false
    val airStart = date.atStartOfDayIn(UTC9).toEpochMilliseconds()
    return publishedTime < airStart - AIRING_LEEWAY.inWholeMilliseconds
}

/** 先行配信一般提前几天到一周; 同名前几季的资源早几个月以上. */
internal val AIRING_LEEWAY: Duration = 7.days

/** 2000-01-01: 比它早的不当毫秒时间戳 (有的源可能给的是秒), 当作不知道. */
private const val EARLIEST_PLAUSIBLE_MILLIS = 946_684_800_000L
