/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.continuation

import me.him188.ani.app.data.repository.media.PlaybackLineageRepository

/** 只放在内存里的表: [saved] 是最后一次写进去的内容, [writes] 是写过几次 */
internal class MemorySubjectSourceStore(var saved: SubjectSourcesState? = null) : SubjectSourceTable.Store {
    var writes = 0

    override suspend fun read(): SubjectSourcesState? = saved

    override suspend fun write(state: SubjectSourcesState): Boolean {
        saved = state
        writes++
        return true
    }
}

/** 底下是一张只放在内存里的表的播放线索仓库 */
internal fun createTestLineageRepository() = PlaybackLineageRepository(SubjectSourceTable(MemorySubjectSourceStore()))
