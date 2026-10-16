/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.feedback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.data.models.feedback.FeedbackRecord
import me.him188.ani.app.data.models.feedback.FeedbackRecords
import me.him188.ani.app.data.repository.user.Settings

/** 这台电视提交过的反馈 (见 [FeedbackRecords]). */
class FeedbackRecordRepository(private val settings: Settings<FeedbackRecords>) {
    // 设置的 update 是先读后写: 提交与后台查结果同时改会丢一边, 改都排队
    private val lock = Mutex()

    /** 新的在前. */
    val records: Flow<List<FeedbackRecord>> = settings.flow.map { it.items }

    /** 全部 (含录制状态). */
    val all: Flow<FeedbackRecords> get() = settings.flow

    suspend fun current(): FeedbackRecords = settings.flow.first()

    /** 记下一条; 同一个 issue (同一类) 已经记过就换成新的这条、挪到最前. */
    suspend fun add(record: FeedbackRecord) = edit {
        val rest = items.filterNot { it.kind == record.kind && it.issue == record.issue }
        copy(items = (listOf(record) + rest).take(FeedbackRecords.MAX_ITEMS))
    }

    /** 改一条 (查到处理结果时); 没有这条就什么都不做. */
    suspend fun update(record: FeedbackRecord) = edit {
        copy(items = items.map { if (it.kind == record.kind && it.issue == record.issue) record else it })
    }

    suspend fun edit(transform: FeedbackRecords.() -> FeedbackRecords) {
        lock.withLock { settings.update(transform) }
    }
}
