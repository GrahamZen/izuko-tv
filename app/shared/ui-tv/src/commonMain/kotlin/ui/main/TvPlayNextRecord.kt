/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import me.him188.ani.app.domain.profile.UserProfile

/**
 * 系统主屏「接下来播放」这一行的对账记录 (见 app/android 的 TvPlayNextSync): 上次写进去的行, 与写它的用户.
 *
 * 这一行整台电视只有一份, 放的是最后一个退到后台的用户的在看 (见 TvHomeChannels). 用户在主屏上移除 / 添加卡片,
 * 算在写这一行的那个人头上; 别人的进程来对账时 (应用不在时动了主屏, 下次打开的是另一个人) 不认, 免得改到别人的收藏.
 *
 * @property writerProfileId 写这一行的用户; null = 记录里没有 (记下写的人之前的版本写的, 那时按 1 号算)
 * @property rows 写进去的行 id -> 条目
 */
data class TvPlayNextRecord(
    val writerProfileId: Int?,
    val rows: Map<Long, Int>,
)

/**
 * 这一行里现在的一张卡.
 * @property manuallyAdded 用户在频道卡片上「添加到接下来播放」时系统复制进来的 (类型 WATCHLIST, 我们自己不写这种)
 * @property subjectId 对应的条目; 认不出为 null
 */
data class TvPlayNextRow(
    val id: Long,
    val manuallyAdded: Boolean,
    val subjectId: Int?,
)

/**
 * 认出来的用户操作.
 * @property removed 用户移除的我们写的卡对应的条目 (在看的要标成搁置)
 * @property added 用户手动加进来的卡, 行 id -> 条目 (要标成在看)
 * @property foreign 这一行是别的用户写的: 什么都不认, 重写时把别人手动加的卡也删掉 (留着的话之后会算成这个人加的)
 */
class TvPlayNextChanges(
    val removed: Set<Int>,
    val added: Map<Long, Int>,
    val foreign: Boolean = false,
) {
    val isEmpty: Boolean get() = removed.isEmpty() && added.isEmpty()
}

/**
 * @property remainingRows 认过的移除去掉后的记录 (只认一次); null = 记录不用改
 * @property externalReset 记着的行 (两张以上) 一下子全没了, 当成系统那边清了表, 不算用户移除
 */
class TvPlayNextDetection(
    val changes: TvPlayNextChanges,
    val remainingRows: Map<Long, Int>?,
    val externalReset: Boolean,
)

/**
 * 对照记录与这一行现在的卡, 认出用户的操作.
 *
 * 记着的行不见了 = 用户移除; 主屏上只能一张张删, 两张以上一下子全没了不算 (见 [TvPlayNextDetection.externalReset]).
 * 记录是别的用户写的时什么都不认 ([TvPlayNextChanges.foreign]).
 *
 * @param ignoreAdded 已经认过 / 正在处理的手动卡, 不再算一遍
 */
fun detectPlayNextChanges(
    record: TvPlayNextRecord,
    currentProfileId: Int,
    existing: List<TvPlayNextRow>,
    ignoreAdded: (rowId: Long) -> Boolean = { false },
): TvPlayNextDetection {
    val writer = record.writerProfileId ?: UserProfile.PRIMARY_ID
    if (writer != currentProfileId) {
        return TvPlayNextDetection(TvPlayNextChanges(emptySet(), emptyMap(), foreign = true), remainingRows = null, externalReset = false)
    }
    val ids = existing.mapTo(HashSet()) { it.id }
    var removed = record.rows.filterKeys { it !in ids }
    val remaining = if (removed.isEmpty()) null else record.rows - removed.keys
    val externalReset = record.rows.size >= 2 && removed.size == record.rows.size
    if (externalReset) removed = emptyMap()
    val added = existing
        .filter { it.manuallyAdded && !ignoreAdded(it.id) }
        .mapNotNull { row -> row.subjectId?.let { row.id to it } }
        .toMap()
    return TvPlayNextDetection(TvPlayNextChanges(removed.values.toSet(), added), remaining, externalReset)
}
