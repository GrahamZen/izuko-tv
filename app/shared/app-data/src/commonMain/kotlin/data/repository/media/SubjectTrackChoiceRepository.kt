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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.him188.ani.app.data.models.preference.SubjectTrackChoice
import me.him188.ani.app.data.persistent.DataStoreJson
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 用户在各条目的播放器里手动换过的字幕与音轨 (见 [SubjectTrackChoice]), 按条目持久化.
 *
 * 与 [EpisodePreferencesRepository] 共用同一个 DataStore (它的 MediaPreference 键是裸条目 id), 这里的键加 `track_choice:` 前缀区分.
 */
class SubjectTrackChoiceRepository(
    private val store: DataStore<Preferences>,
) {
    private val logger = logger<SubjectTrackChoiceRepository>()

    private fun key(subjectId: Int) = stringPreferencesKey("track_choice:$subjectId")

    /** 没换过时为 `null`. */
    fun trackChoiceFlow(subjectId: Int): Flow<SubjectTrackChoice?> {
        return store.data.map { it[key(subjectId)] }.map { encoded ->
            if (encoded.isNullOrBlank()) return@map null
            runCatching {
                DataStoreJson.decodeFromString(SubjectTrackChoice.serializer(), encoded)
            }.getOrNull()
        }
    }

    /** 存空的 ([SubjectTrackChoice] 两项都是 null) 等于删掉. */
    suspend fun setTrackChoice(subjectId: Int, choice: SubjectTrackChoice) {
        logger.info { "Saved track choice for subject $subjectId: $choice" }
        store.edit {
            if (choice == SubjectTrackChoice()) {
                it.remove(key(subjectId))
            } else {
                it[key(subjectId)] = DataStoreJson.encodeToString(SubjectTrackChoice.serializer(), choice)
            }
        }
    }
}
