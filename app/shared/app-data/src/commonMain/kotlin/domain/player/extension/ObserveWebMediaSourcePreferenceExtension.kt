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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.media.DroppedFileMedia
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchResult
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.media.selector.eventHandling
import me.him188.ani.app.domain.mediasource.GetPreferredWebMediaSourceUseCase
import me.him188.ani.app.domain.mediasource.SetPreferredWebMediaSourceUseCase
import me.him188.ani.app.domain.settings.GetMediaSelectorSettingsFlowUseCase
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin

/**
 * 维护本条目记住的在线源: 用户手动选的、真正播起来的在线源记下来, 记住的源查询失败时删掉.
 * 自动选源时记住的源优先 (见 MediaAutoSelector).
 */
class ObserveWebMediaSourcePreferenceExtension(
    private val context: PlayerExtensionContext,
    koin: Koin
) : PlayerExtension("ObserveWebMediaSourcePreference") {
    private val getPreferredWebMediaSource: GetPreferredWebMediaSourceUseCase by koin.inject()
    private val setPreferredWebMediaSource: SetPreferredWebMediaSourceUseCase by koin.inject()
    private val getMediaSelectorSettings: GetMediaSelectorSettingsFlowUseCase by koin.inject()

    private val logger = logger<ObserveWebMediaSourcePreferenceExtension>()

    override fun onStart(
        episodeSession: EpisodeSession,
        backgroundTaskScope: ExtensionBackgroundTaskScope
    ) {
        backgroundTaskScope.launch("ObserveWebMediaSourcePreference") {
            context.sessionFlow.flatMapLatest { it.fetchSelectFlow }.collectLatest { bundle ->
                if (bundle == null) return@collectLatest
                coroutineScope {
                    // 监听用户喜欢的 Web 源变更, 增加偏好
                    launch {
                        bundle.mediaSelector.eventHandling.preferWebMediaSource { event ->
                            if (event.subjectId != context.subjectId) return@preferWebMediaSource
                            val currentPreference = getPreferredWebMediaSource(event.subjectId).first()
                            if (currentPreference != event.mediaSourceId) {
                                logger.info { "Set web source preference for subject ${context.subjectId} to ${event.mediaSourceId}" }
                                setPreferredWebMediaSource(event.subjectId, event.mediaSourceId)
                            }
                        }
                    }

                    // 在线源真正播起来 (时钟开始走) 就记为本条目的源, 自动选中的也算:
                    // 之后自动选源优先用它. 拖入的本地文件与缓存不算.
                    // 只在开始走的那一刻取选中项: 换源时选中项先变、旧资源的时钟还没停, 不能把新资源当成播起来了.
                    // 偏好 BT 时不记自动选中的: 那多半是 BT 这集没资源才兜底选上的, 记住了下次会越过 BT 直接选它
                    // (手动选的照样由上面的事件记下).
                    // drop(1): 挂上时已经在播 (如重新搜索换了会话) 不算播起来, 等新选的资源真正开始播
                    launch {
                        context.player.state.map { it.isPlaying }.distinctUntilChanged().drop(1).filter { it }.collect {
                            val media = bundle.mediaSelector.selected.value ?: return@collect
                            if (media.kind != MediaSourceKind.WEB || DroppedFileMedia.isDroppedFile(media)) return@collect
                            if (getMediaSelectorSettings().first().preferKind == MediaSourceKind.BitTorrent) return@collect
                            if (getPreferredWebMediaSource(context.subjectId).first() == media.mediaSourceId) return@collect
                            logger.info { "Playing web source ${media.mediaSourceId}, remembering it for subject ${context.subjectId}" }
                            setPreferredWebMediaSource(context.subjectId, media.mediaSourceId)
                        }
                    }

                    // 监听 Web 源加载失败的情况, 删除偏好.
                    // 条目级查询会话跨集共用, 只有源自身失败 (Failed) 才算; 被中途取消 (Abandoned) 不算.
                    combine(
                        // 如果这个 subject 没有偏好, 则不继续监听, 这里将会一直挂起
                        getPreferredWebMediaSource(context.subjectId).filterNotNull(),
                        combine(
                            bundle.mediaFetchSession.mediaSourceResults
                                .filter { it.kind == MediaSourceKind.WEB }
                                .map { r -> r.state.map { r } },
                            Array<MediaSourceFetchResult>::toList,
                        ),
                    ) { preferredWebMediaSourceId, results ->
                        results.forEach {
                            if (it.mediaSourceId != preferredWebMediaSourceId) return@forEach
                            if (it.state.value is MediaSourceFetchState.Failed) {
                                logger.info {
                                    "Remove web source preference for subject ${context.subjectId} from ${it.mediaSourceId}. " +
                                            "because source state in this session is ${it.state.value.str()}."
                                }
                                setPreferredWebMediaSource(context.subjectId, null)
                            }
                        }
                    }.launchIn(this)
                }
            }
        }
    }

    private fun MediaSourceFetchState.str(): String {
        return when (this) {
            is MediaSourceFetchState.Failed -> "failed"
            is MediaSourceFetchState.Abandoned -> "abandoned"
            else -> this::class.simpleName!!.lowercase()
        }
    }

    companion object : EpisodePlayerExtensionFactory<ObserveWebMediaSourcePreferenceExtension> {
        override fun create(context: PlayerExtensionContext, koin: Koin): ObserveWebMediaSourcePreferenceExtension {
            return ObserveWebMediaSourcePreferenceExtension(context, koin)
        }
    }
}