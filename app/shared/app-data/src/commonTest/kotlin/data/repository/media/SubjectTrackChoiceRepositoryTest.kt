/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.media

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.SubjectTrackChoice
import me.him188.ani.app.data.persistent.createTestPreferencesDataStore
import me.him188.ani.app.data.persistent.database.dao.createMemoryPreferredWebMediaSourceDao
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubjectTrackChoiceRepositoryTest {
    @Test
    fun `stored per subject - separate from MediaPreference in the same store - empty means removed`() = runTest {
        val store = createTestPreferencesDataStore()
        val repository = SubjectTrackChoiceRepository(store)
        val preferences = EpisodePreferencesRepositoryImpl(
            store,
            createMemoryPreferredWebMediaSourceDao(),
            defaultMediaPreference = flowOf(MediaPreference.Empty),
        )
        assertNull(repository.trackChoiceFlow(SUBJECT_ID).first())

        val choice = SubjectTrackChoice(subtitle = "zh-Hant", audio = "ja")
        repository.setTrackChoice(SUBJECT_ID, choice)
        val preference = MediaPreference.Empty.copy(alliance = "字幕组A")
        preferences.setMediaPreference(SUBJECT_ID, preference)
        assertEquals(choice, repository.trackChoiceFlow(SUBJECT_ID).first())
        assertEquals(preference, preferences.mediaPreferenceFlow(SUBJECT_ID).first())
        assertNull(repository.trackChoiceFlow(SUBJECT_ID + 1).first())

        repository.setTrackChoice(SUBJECT_ID, SubjectTrackChoice())
        assertNull(store.data.first()[stringPreferencesKey("track_choice:$SUBJECT_ID")])
        assertNull(repository.trackChoiceFlow(SUBJECT_ID).first())
    }

    @Test
    fun `broken json reads as not set`() = runTest {
        val store = createTestPreferencesDataStore()
        store.edit { it[stringPreferencesKey("track_choice:$SUBJECT_ID")] = "not json" }
        assertNull(SubjectTrackChoiceRepository(store).trackChoiceFlow(SUBJECT_ID).first())
    }

    private companion object {
        private const val SUBJECT_ID = 100
    }
}
