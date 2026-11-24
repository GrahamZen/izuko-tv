/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector.filter

import me.him188.ani.app.domain.media.selector.MediaExclusionReason
import me.him188.ani.app.domain.media.selector.MediaSelectorContext
import me.him188.ani.app.domain.mediasource.search.WorkKind
import me.him188.ani.app.domain.mediasource.search.WorkKinds

/**
 * BT 发布标题上的检查. BT 站没有条目名, 选择器原本只认标题里完整出现的续作名; 这里再看标题写明的:
 * - 季号是本系列别的季 ([BtTitleSeason]): 以 [MediaExclusionReason.FromSeriesSeason] 排除;
 * - 是剧场版、外传、OVA 而条目不是这一类 ([workKindsOf], 条件同在线源的 [WebWorkKindCheck]): 以 [MediaExclusionReason.SubjectNameMismatch] 排除.
 *   「剧场版 约会大作战 万由里裁决」的种子原本被当成 TV 第一季的资源.
 */
internal object BtTitleRules {
    fun exclusionOf(title: String, context: MediaSelectorContext): MediaExclusionReason? = when {
        BtTitleSeason.isOtherSeason(title, context) -> MediaExclusionReason.FromSeriesSeason
        WebWorkKindCheck.rejects(workKindsOf(title), context) -> MediaExclusionReason.SubjectNameMismatch
        else -> null
    }

    /**
     * 标题写明的作品类别 ([WorkKinds.of]). 合集装着正片, 写着别的类别也不算 (只算合集里也不放在一起的, [WorkKind.inCollections]):
     * 写着「合集」「全集」「全季」「系列」「全5季」「1-4季」, 或类别前后紧挨着「+」「&」「含」「附」(「TV+OVA」「全5季+剧场版」「含OVA」).
     * 页名的合集判断 ([WorkKinds.isCollectionName]) 不能照搬: BT 标题里字幕组名常带「&」(「澄空学园&华盟字幕社」),
     * 集号范围「01-12」、「正片+特典映像」也不说明装着正片.
     */
    fun workKindsOf(title: String): Set<WorkKind> {
        val kinds = WorkKinds.of(title)
        if (kinds.isEmpty()) return kinds
        if (COLLECTION.containsMatchIn(title) || KIND_JOINED.containsMatchIn(title)) return kinds.filterTo(HashSet()) { it.inCollections }
        return kinds
    }

    private val COLLECTION = Regex("""合集|全集|全季|系列|全\s*[0-9一二三四五六七八九十]+\s*[季部]|[0-9]\s*[-~～至]\s*[0-9]+\s*季""")

    private const val KINDS = """(?:剧场版|劇場版|外传|外傳|OVA|OAD)"""
    private val KIND_JOINED = Regex("""(?:[+＋&＆]|含|附)\s*$KINDS|$KINDS\s*[+＋&＆]""", RegexOption.IGNORE_CASE)
}
