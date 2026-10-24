/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import me.him188.ani.app.data.repository.media.PlaybackLineageRepository
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.media.DroppedFileMedia
import me.him188.ani.app.domain.media.continuation.EpisodeContinuation
import me.him188.ani.app.domain.media.continuation.PlaybackLineage
import me.him188.ani.app.domain.media.selector.SelectOrigin
import me.him188.ani.datasources.api.Media
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin

/**
 * 记下这部番的播放线索 ([PlaybackLineage], 切集时先顺着它找, 见 [me.him188.ani.app.domain.media.continuation.PlaybackLineageSelection]).
 * 资源真正播起来 (时钟开始走) 时判断:
 *
 * - 用户手动选的: 换成它的线索 (记不了线索的资源就清掉, 不再顺着旧的找);
 * - 还没有线索: 记下它的;
 * - 自动选上的、不在线索上 (线索这一集暂时找不到而兜底选的): 不换, 下一集照样先顺着原来的找.
 *
 * 线索彻底失效时由选源那一步忘掉 (见 [me.him188.ani.app.domain.media.continuation.LineageResolution.Gone]), 之后播起来的资源接着记下.
 */
class PlaybackLineageExtension(
    private val context: PlayerExtensionContext,
    koin: Koin,
) : PlayerExtension("PlaybackLineage") {
    private val repository: PlaybackLineageRepository by koin.inject()
    private val continuation: EpisodeContinuation by koin.inject()

    override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
        backgroundTaskScope.launch("PlaybackLineage") {
            context.sessionFlow.flatMapLatest { it.fetchSelectFlow }.collectLatest { bundle ->
                if (bundle == null) return@collectLatest
                coroutineScope {
                    // 最近一次选择是谁发起的; 选中项先于时钟开始走
                    val lastSelection = MutableStateFlow<Pair<Media, SelectOrigin>?>(null)
                    launch {
                        bundle.mediaSelector.events.onSelect.collect { event ->
                            lastSelection.value = event.media?.let { it to event.origin }
                        }
                    }
                    context.player.playbackStarts().collect {
                        val media = bundle.mediaSelector.selected.value ?: return@collect
                        if (DroppedFileMedia.isDroppedFile(media)) return@collect
                        val manual = lastSelection.value?.takeIf { it.first == media }?.second == SelectOrigin.MANUAL
                        record(media, manual)
                    }
                }
            }
        }
    }

    private suspend fun record(media: Media, manual: Boolean) {
        val subjectId = context.subjectId
        val current = repository.lineageOf(subjectId)
        if (current != null && !manual) {
            if (!continuation.continues(current, media)) {
                logger.info { "Playing ${media.mediaId} off the playback lineage of subject $subjectId, keeping the lineage" }
            }
            return
        }
        val lineage = continuation.lineageOf(media)
        if (lineage != current) repository.setLineage(subjectId, lineage)
    }

    companion object : EpisodePlayerExtensionFactory<PlaybackLineageExtension> {
        private val logger = logger<PlaybackLineageExtension>()

        override fun create(context: PlayerExtensionContext, koin: Koin): PlaybackLineageExtension {
            return PlaybackLineageExtension(context, koin)
        }
    }
}
