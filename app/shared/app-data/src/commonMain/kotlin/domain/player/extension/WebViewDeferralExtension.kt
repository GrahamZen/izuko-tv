/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.media.resolver.WebViewDeferral
import me.him188.ani.app.domain.media.selector.MatchMetadata
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaSelector
import me.him188.ani.app.domain.media.selector.MediaSelectorSourceTiers
import me.him188.ani.app.domain.mediasource.GetMediaSelectorSourceTiersUseCase
import me.him188.ani.app.domain.mediasource.profile.SourceProfiles
import me.him188.ani.app.domain.player.diagnostics.PlayTimeline
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.currentTimeMillis
import org.koin.core.Koin

/**
 * 给 [WebViewDeferral] 登记本会话的判断: 选中的网页资源解析到要转去 WebView 嗅探时, 手上有不用嗅探的资源就改选它
 * (见 [deferToDirectAlternative]).
 */
class WebViewDeferralExtension(
    private val context: PlayerExtensionContext,
    koin: Koin,
) : PlayerExtension("WebViewDeferral") {
    private val getSourceTiers: GetMediaSelectorSourceTiersUseCase by koin.inject()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
        backgroundTaskScope.launch("WebViewDeferral") {
            val bundle = context.sessionFlow.flatMapLatest { it.fetchSelectFlow }.stateIn(this, SharingStarted.Eagerly, null)
            // 本会话选中过的资源: 播不了或已被换掉的不再换回去
            val attempted = MutableStateFlow(emptySet<String>())
            launch {
                bundle.filterNotNull().flatMapLatest { it.mediaSelector.selected }.filterNotNull().collect { media ->
                    attempted.update { it + media.mediaId }
                }
            }
            val deferred = MutableStateFlow(emptySet<String>())
            val registration = WebViewDeferral.register { media ->
                val selector = bundle.value?.mediaSelector ?: return@register false
                deferToDirectAlternative(selector, media, getSourceTiers().first(), attempted.value, deferred)
            }
            try {
                awaitCancellation()
            } finally {
                registration.close()
            }
        }
    }

    companion object : EpisodePlayerExtensionFactory<WebViewDeferralExtension> {
        private val logger = logger<WebViewDeferralExtension>()

        override fun create(context: PlayerExtensionContext, koin: Koin): WebViewDeferralExtension =
            WebViewDeferralExtension(context, koin)

        /**
         * [media] 是 [selector] 选中的网页资源、要转去 WebView 嗅探时, 改选手上最好的不用嗅探的资源: 条目名精确匹配、
         * 来自别的源 (不在 [MediaSelectorSourceTiers.webViewSources] 里)、本会话没选过 ([attempted]) 的里层级最高的.
         * 选源时就知道要嗅探的 (兜底选上的) 不改; 每个资源只改选一次 ([deferred]), 再选到它就嗅探.
         * 改选了返回 true, 并把这次「要嗅探」记进数据源画像, 之后选源就把它当要嗅探的源兜底.
         */
        internal suspend fun deferToDirectAlternative(
            selector: MediaSelector,
            media: Media,
            tiers: MediaSelectorSourceTiers,
            attempted: Set<String>,
            deferred: MutableStateFlow<Set<String>>,
            now: () -> Long = { currentTimeMillis() },
        ): Boolean {
            if (media.kind != MediaSourceKind.WEB || selector.selected.value?.mediaId != media.mediaId) return false
            if (media.mediaId in deferred.value || media.mediaSourceId in tiers.webViewSources) return false
            val alternative = selector.filteredCandidates.first().asSequence()
                .filterIsInstance<MaybeExcludedMedia.Included>()
                .filter { candidate ->
                    val other = candidate.result
                    other.kind == MediaSourceKind.WEB && other.mediaSourceId != media.mediaSourceId &&
                            other.mediaSourceId !in tiers.webViewSources && other.mediaId !in attempted &&
                            candidate.metadata.subjectMatchKind == MatchMetadata.SubjectMatchKind.EXACT
                }
                .minByOrNull { tiers.get(it.result.mediaSourceId, it.result.properties.alliance) }
                ?.result ?: return false
            deferred.update { it + media.mediaId }
            SourceProfiles.recordWebViewDeferred(media.mediaSourceId, now())
            logger.info { "${media.mediaSourceId} needs the web view for ${media.mediaId}, switching to ${alternative.mediaId}" }
            PlayTimeline.mark("webview_deferred", "${media.mediaSourceId} -> ${alternative.mediaSourceId}")
            return selector.selectAutomatically(alternative, expectedSelection = media) != null
        }
    }
}
