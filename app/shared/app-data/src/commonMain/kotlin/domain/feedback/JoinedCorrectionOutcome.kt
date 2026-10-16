/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.feedback

import me.him188.ani.app.data.models.feedback.FeedbackKind
import me.him188.ani.app.data.models.feedback.FeedbackRecord
import me.him188.ani.app.data.network.TmdbSubjectMapEntry
import me.him188.ani.app.data.network.TmdbSubjectMapRef
import kotlin.time.Duration.Companion.days

/**
 * 跟着别人提的修正请求 ([FeedbackRecord.joinedExisting]: 同一处已经有请求开着, 这台电视没开新的) 不单独去中转查结果
 * —— 同一部番的 logo 错了时可能有成千上万台电视跟着提, 各查各的会白白耗掉中转的额度. 改为看对应表 (每天从 GitHub 拉, 不经中转):
 * 表里那一处已经是这条请求要的样子 = 采纳了; 提交满 [TIMEOUT] 还没变 = 当作结束 (没采纳的理由看不到, 详情里有 GitHub 链接).
 * [FeedbackRecord.requested] 没记的 (较早的版本记下的、「列表里没有」) 推断不了, 照旧去中转查.
 */
internal object JoinedCorrectionOutcome {
    /** 提交后多久还没在表里看到就当作结束. */
    val TIMEOUT = 30.days

    /** 这条记录的结果看对应表推断, 不去中转查. */
    fun derivable(record: FeedbackRecord): Boolean =
        record.kind != FeedbackKind.TICKET && record.joinedExisting && record.requested != null && record.subjectId != null

    /** 对应表里这个条目 ([entry]) 已经是 [record] 要的样子. */
    fun applied(record: FeedbackRecord, entry: TmdbSubjectMapEntry?): Boolean {
        val requested = record.requested ?: return false
        if (entry == null) return false
        return when (record.kind) {
            FeedbackKind.LOGO -> {
                val (decided, logo) = entry.logos?.decided(record.language) ?: return false
                decided && if (requested == FeedbackRecord.REQUESTED_NONE) logo == null else logo?.filePath == requested
            }

            // 人工修正且什么都没给 = 确认 TMDB 上没有对应; 季号不算 (请求里只有作品)
            FeedbackKind.ENTRY -> if (requested == FeedbackRecord.REQUESTED_NONE) entry.manual && entry.backdrop == null
            else TmdbSubjectMapRef.parse(requested)?.let { want -> entry.backdrop?.let { it.type == want.type && it.id == want.id } } == true

            FeedbackKind.TICKET -> false
        }
    }
}
