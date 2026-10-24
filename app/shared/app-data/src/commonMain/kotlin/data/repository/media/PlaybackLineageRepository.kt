/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.media

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.persistent.DataStoreJson
import me.him188.ani.app.domain.media.continuation.PlaybackLineage
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/** 每部番的播放线索 ([PlaybackLineage]). 认不出的记录 (以后版本加的线索类型) 当作没有. */
class PlaybackLineageRepository(
    private val store: DataStore<Preferences>,
) {
    private val logger = logger<PlaybackLineageRepository>()

    private fun key(subjectId: Int) = stringPreferencesKey("playback_lineage:$subjectId")

    suspend fun lineageOf(subjectId: Int): PlaybackLineage? {
        val encoded = store.data.first()[key(subjectId)]?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { DataStoreJson.decodeFromString(PlaybackLineage.serializer(), encoded) }.getOrNull()
    }

    /** [lineage] 为 null 时删掉. */
    suspend fun setLineage(subjectId: Int, lineage: PlaybackLineage?) {
        logger.info { "Playback lineage of subject $subjectId: $lineage" }
        store.edit {
            if (lineage == null) it.remove(key(subjectId))
            else it[key(subjectId)] = DataStoreJson.encodeToString(PlaybackLineage.serializer(), lineage)
        }
    }
}
