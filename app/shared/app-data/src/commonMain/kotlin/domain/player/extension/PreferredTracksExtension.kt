/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.preference.SubjectTrackChoice
import me.him188.ani.app.data.repository.media.SubjectTrackChoiceRepository
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.player.tracks.SubjectTrackChooser
import me.him188.ani.app.domain.player.tracks.TrackChooserHost
import org.koin.core.Koin

/**
 * 打开每一集时按界面语言与这部番记下的选择挑字幕与音轨 (见 [SubjectTrackChooser]); 用户手动换了按番记下来.
 * 播放器不支持按语言选轨 (不是 [TrackChooserHost]) 时什么也不做, 维持播放器自己的选择.
 *
 * @param uiLanguage 界面语言 (BCP 47, 如 `zh-CN`)
 */
class PreferredTracksExtension(
    private val context: PlayerExtensionContext,
    koin: Koin,
    private val uiLanguage: suspend () -> String,
) : PlayerExtension("PreferredTracks") {
    private val repository: SubjectTrackChoiceRepository by koin.inject()
    private var chooser: SubjectTrackChooser? = null

    override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
        val host = context.player as? TrackChooserHost ?: return
        backgroundTaskScope.launch("PreferredTracks") {
            val subjectId = context.subjectId
            val chooser = SubjectTrackChooser(uiLanguage()) { choice ->
                backgroundTaskScope.launch("SaveTrackChoice") { repository.setTrackChoice(subjectId, choice) }
            }
            chooser.choice = repository.trackChoiceFlow(subjectId).first() ?: SubjectTrackChoice()
            this@PreferredTracksExtension.chooser = chooser
            withContext(Dispatchers.Main) { host.trackChooser = chooser }
        }
    }

    override suspend fun onClose() {
        val chooser = chooser ?: return
        val host = context.player as? TrackChooserHost ?: return
        withContext(Dispatchers.Main) {
            if (host.trackChooser === chooser) host.trackChooser = null
        }
    }

    class Factory(
        private val uiLanguage: suspend () -> String,
    ) : EpisodePlayerExtensionFactory<PreferredTracksExtension> {
        override fun create(context: PlayerExtensionContext, koin: Koin): PreferredTracksExtension =
            PreferredTracksExtension(context, koin, uiLanguage)
    }
}
