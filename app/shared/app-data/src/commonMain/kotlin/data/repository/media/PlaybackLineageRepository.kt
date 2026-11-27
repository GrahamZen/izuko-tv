/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.media

import me.him188.ani.app.domain.media.continuation.PlaybackLineage
import me.him188.ani.app.domain.media.continuation.SubjectSourceTable
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/** 每部番的播放线索 ([PlaybackLineage]), 存在 [SubjectSourceTable] 里. */
class PlaybackLineageRepository(
    private val table: SubjectSourceTable,
) {
    private val logger = logger<PlaybackLineageRepository>()

    suspend fun lineageOf(subjectId: Int): PlaybackLineage? = table.state().lineageOf(subjectId)

    /** [lineage] 为 null 时删掉. */
    suspend fun setLineage(subjectId: Int, lineage: PlaybackLineage?) {
        logger.info { "Playback lineage of subject $subjectId: $lineage" }
        table.setPlayed(subjectId, lineage)
    }

    /** 顺着线索播起来了: 表放不下时久没播的先丢. */
    suspend fun markPlayed(subjectId: Int) = table.markPlayed(subjectId)
}
