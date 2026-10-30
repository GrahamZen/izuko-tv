/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.continuation

import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.repository.media.EpisodePreferencesRepository
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 忘掉自动选源记住的源 (按番记的「记忆源」, 见 [EpisodePreferencesRepository.getPreferredWebMediaSource]):
 * 播放线索失效时, 记住的源正是线索所在的那个源才忘 (见 [PlaybackLineageSelection]).
 */
fun interface RememberedSourceForgetter {
    suspend fun forgetIf(subjectId: Int, mediaSourceId: String)

    companion object {
        val None = RememberedSourceForgetter { _, _ -> }
    }
}

/** 按 [preferences] 存的记住的源忘. */
class PreferencesRememberedSourceForgetter(
    private val preferences: EpisodePreferencesRepository,
) : RememberedSourceForgetter {
    override suspend fun forgetIf(subjectId: Int, mediaSourceId: String) {
        if (preferences.getPreferredWebMediaSource(subjectId).first() != mediaSourceId) return
        preferences.removePreferredWebMediaSource(subjectId)
        logger.info { "Forgot the remembered source $mediaSourceId of subject $subjectId together with its playback lineage" }
    }

    private companion object {
        private val logger = logger<PreferencesRememberedSourceForgetter>()
    }
}
