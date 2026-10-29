/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.MediaPreference.Companion.ANY_FILTER
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.media.fetch.MediaSourceSearchLimiter
import me.him188.ani.app.domain.media.fetch.isFinal
import me.him188.ani.app.domain.media.fetch.isPaused
import me.him188.ani.app.domain.media.fetch.resumePausedSources
import me.him188.ani.app.domain.media.selector.MatchMetadata.SubjectMatchKind
import me.him188.ani.app.domain.mediasource.codec.MediaSourceTier
import me.him188.ani.app.domain.player.diagnostics.PlayTimeline
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Owns one automatic selection: source subscriptions, deadlines, decisions and the final write.
 * Startup and player-error replacement share this entry point. Each call has its own lifecycle.
 */
internal class MediaAutoSelector(private val mediaSelector: MediaSelector) {
    data class Config(
        val preferredSourceId: String? = null,
        /** Select available local caches first; otherwise wait for their lookups before considering network media. */
        val selectCache: Boolean = true,
        val blacklist: Set<String> = emptySet(),
        /** Null waits for the preferred source kind to complete, using the existing BT preference rules. */
        val web: Web? = null,
        /** Startup may fall back to another kind; player-error replacement stays within WEB. */
        val fallbackToOtherKinds: Boolean = false,
    )

    /**
     * Web deadlines start only after the remembered source has finished without a selection.
     * Deadlines only wait for pending sources: once every Web source has settled, the selection is the one the
     * last reachable stage would make. An infinite deadline is never reached, so its stages stay closed.
     */
    data class Web(
        val sourceTiers: MediaSelectorSourceTiers,
        val fastSelect: Boolean = true,
        val exactMatchAfter: Duration = 5.seconds,
        val fuzzyMatchAfter: Duration = 15.seconds,
        val instantTier: MediaSourceTier = DefaultInstantTier,
        val waitForPendingSources: Boolean = true,
        /**
         * 原本「立即」档会选的资源要 WebView 嗅探、只好先等直连的源时, 最多等这么久 (从第一次这样等起算):
         * 到时有直连的精确匹配就选它 (不论层级), 没有就选要嗅探的那个. 嗅探至少也要一两秒, 多等的不超过省下的.
         */
        val webViewGrace: Duration = 2.seconds,
    ) {
        init {
            require(exactMatchAfter >= Duration.ZERO)
            require(fuzzyMatchAfter >= Duration.ZERO)
        }

        companion object {
            /** First-deadline stage accepts exact matches whose effective tier is at most this. */
            val DefaultInstantTier = MediaSourceTier(0u)
        }
    }

    /** A selection made while suspended ends this call; only [expectedSelection] may be replaced. */
    suspend fun select(
        session: MediaFetchSession,
        config: Config = Config(),
        expectedSelection: Media? = null,
    ): Media? = coroutineScope {
        if (mediaSelector.selected.value != expectedSelection) return@coroutineScope null
        val stage = MutableStateFlow(Stage.PreferredSource)
        var result: Media? = null
        var deadlines: Job? = null
        // 放开记住的源时它的暂停状态 (见 decide)
        var resumedPreferred: MediaSourceFetchState? = null
        val tuning = PreferredSourceTuning.current()
        val trace = AutoSelectTrace()
        AutoSelectTrace.step("selector started")
        // 记忆源阶段等够了 (见 DevSwitches.searchMemoryWaitCap): 不再只等记住的源
        val preferredTimedOut = MutableStateFlow(false)
        val preferredTimer = config.preferredSourceId?.let { id ->
            if (tuning.prioritize) MediaSourceSearchLimiter.Default.prioritize(id)
            tuning.waitCap?.let { cap ->
                launch {
                    delay(cap)
                    if (stage.value == Stage.PreferredSource) logger.info { "Preferred source $id still has nothing after $cap, considering other sources" }
                    preferredTimedOut.value = true
                }
            }
        }
        // 等直连的源等够了 (见 Web.webViewGrace)
        val webViewGraceOver = MutableStateFlow(false)
        var webViewGraceTimer: Job? = null
        try {
            combine(
                mediaSelector.autoSelectSnapshots(sourceSnapshots(session).onEach { trace.sourcesEmitted(it.size) })
                    .let { if (config.selectCache) it.awaitCacheReadiness(logger) else it },
                stage, mediaSelector.selected, preferredTimedOut, webViewGraceOver,
            ) { snapshot, phase, currentSelection, timedOut, graceOver ->
                if (currentSelection != expectedSelection) Decision.Exhausted
                else decide(snapshot, config, phase, resumedPreferred, timedOut, graceOver)
                    .also { trace.observe(snapshot, phase.name, it::class.simpleName.orEmpty()) }
            }.first { decision ->
                when (decision) {
                    is Decision.ResumePreferredSource -> {
                        resumedPreferred = decision.paused
                        for (source in session.mediaSourceResults) {
                            if (source.mediaSourceId == config.preferredSourceId && source.state.value.isPaused) source.restart()
                        }
                        false
                    }

                    Decision.StartFallback -> {
                        if (stage.value == Stage.PreferredSource) {
                            // 记住的源 (或手上已有的结果) 给不出能选的资源: 上一次开播后被暂停的数据源
                            // (切到下一集 / 播放失败换源时) 放开重新查
                            session.resumePausedSources()
                            val web = config.web
                            if (web == null) {
                                // 离开记住源的阶段, 之后按完成条件选 (见 decideOnCompletion)
                                stage.value = Stage.Exact
                            } else {
                                stage.value = if (web.fastSelect) Stage.Instant else Stage.Exact
                                deadlines = launch {
                                    delay(web.exactStageAfter)
                                    stage.value = Stage.Exact
                                    delay((web.fuzzyMatchAfter - web.exactStageAfter).coerceAtLeast(Duration.ZERO))
                                    stage.value = Stage.Fuzzy
                                }
                            }
                        }
                        false
                    }
                    Decision.Wait -> false
                    Decision.WaitForDirect -> {
                        val grace = config.web?.webViewGrace
                        if (webViewGraceTimer == null && grace != null) {
                            webViewGraceTimer = launch {
                                delay(grace)
                                webViewGraceOver.value = true
                            }
                        }
                        false
                    }
                    Decision.Exhausted -> true
                    is Decision.Select -> {
                        logger.info {
                            "Auto select: reason=${decision.reason}, source=${decision.media.mediaSourceId}, media=${decision.media.mediaId}"
                        }
                        PlayTimeline.mark("auto_select_done", decision.reason)
                        result = mediaSelector.selectAutomatically(decision.media, expectedSelection)
                        true
                    }
                }
            }
        } finally {
            deadlines?.cancel()
            preferredTimer?.cancel()
            webViewGraceTimer?.cancel()
        }
        result
    }

    private fun sourceSnapshots(session: MediaFetchSession): Flow<List<MediaSourceSelectionSnapshot>> {
        if (session.mediaSourceResults.isEmpty()) return flowOf(emptyList())
        return combine(session.mediaSourceResults.map { source ->
            // Keep a stable subscription to results: these subscriptions also drive lazy source queries.
            combine(source.state, source.results) { state, results ->
                // combine can observe a terminal state before delivering the corresponding results event.
                // Fetcher publishes terminal state after results have entered its replay cache, so read
                // that cache while our stable subscription is still alive. Never pair Succeed with an
                // older result list (including an empty list).
                val currentResults = if (state.isFinal) source.results.first() else results
                val currentState = source.state.value
                MediaSourceSelectionSnapshot(
                    source.mediaSourceId, source.kind,
                    if (currentState == state) state else MediaSourceFetchState.Working,
                    currentResults,
                )
            }
        }) { it.toList() }
    }

    private enum class Stage { PreferredSource, Instant, Exact, Fuzzy }

    private sealed interface Decision {
        data object Wait : Decision

        /** 原本立即能选的资源要 WebView 嗅探, 先等直连的源 (见 [Web.webViewGrace]) */
        data object WaitForDirect : Decision
        data object StartFallback : Decision

        /** 只放开记住的源 ([paused] 是它此刻的暂停状态), 留在记住源的阶段等它的结果 */
        data class ResumePreferredSource(val paused: MediaSourceFetchState) : Decision
        data object Exhausted : Decision
        data class Select(val media: Media, val reason: String) : Decision
    }

    /**
     * Pure decision over a coherent snapshot. Only [select] advances time or writes selection.
     *
     * @param resumedPreferred 这次选择里放开记住的源时它的暂停状态, 还没放开过为 null
     * @param preferredTimedOut 记忆源阶段等够了, 不再只等记住的源 (见 DevSwitches.searchMemoryWaitCap)
     */
    private fun decide(
        snapshot: MediaAutoSelectSnapshot,
        config: Config,
        stage: Stage,
        resumedPreferred: MediaSourceFetchState?,
        preferredTimedOut: Boolean = false,
        webViewGraceOver: Boolean = false,
    ): Decision {
        val preferredSource = snapshot.sources.firstOrNull {
            it.kind == MediaSourceKind.WEB && it.mediaSourceId == config.preferredSourceId
        }
        val candidates = snapshot.candidates.filter { it.result.mediaId !in config.blacklist }
        val preferred = snapshot.preferred.filter { it.result.mediaId !in config.blacklist }

        // A ready cache wins immediately, even over a completed remembered WEB source.
        if (config.selectCache) {
            // Caches of the whole subject are candidates until the episode is known, so wait for it.
            if (!snapshot.context.hasEpisode) return Decision.Wait
            (preferred.firstOrNull { it.result.kind == MediaSourceKind.LocalCache }
                ?: candidates.firstOrNull { it.result.kind == MediaSourceKind.LocalCache })?.let {
                return Decision.Select(it.result, "local cache")
            }
            if (snapshot.sources.any { it.kind == MediaSourceKind.LocalCache && !it.state.isFinal }) {
                return Decision.Wait
            }
        }
        localFileCandidate(preferred, candidates)?.let { return Decision.Select(it.result, "local file") }

        if (stage == Stage.PreferredSource && preferredSource?.state?.isFinal == true &&
            snapshot.context.allFieldsLoaded()
        ) {
            findWebCandidate(snapshot, preferred.filter { it.result.mediaSourceId == preferredSource.mediaSourceId })?.let {
                return Decision.Select(it, "preferred source")
            }
        }
        // 记住的源还在查、已经给出条目名精确匹配的资源 (每开完一页交一页) 时就选, 不等它把别的页开完; 拆分季要看整季才认得准哪一集
        if (stage == Stage.PreferredSource && preferredSource != null && !preferredSource.state.isFinal &&
            snapshot.context.allFieldsLoaded() && snapshot.context.subjectSeriesInfo?.splitSeason == null
        ) {
            val exact = preferred.filter {
                it.result.mediaSourceId == preferredSource.mediaSourceId && it.metadata.subjectMatchKind == SubjectMatchKind.EXACT
            }
            findWebCandidate(snapshot, exact)?.let { return Decision.Select(it, "preferred source (exact, still searching)") }
        }
        if (stage == Stage.PreferredSource && preferredTimedOut) return Decision.StartFallback

        // 记住的源在上一次开播后被暂停着 (查询按条目共用, 切到下一集时常见): 暂停不算查完。暂停前拿到的结果里没有能选的,
        // 就只放开它、在这一阶段等它的结果, 给不出资源再放开其它源。快照里还是放开时那个暂停状态 = 新状态还没到;
        // 换成了另一个暂停 = 放开后又被暂停, 不再等它。
        if (stage == Stage.PreferredSource && preferredSource != null && preferredSource.state.isPaused) {
            if (preferredSource.results.isNotEmpty() && !snapshot.context.allFieldsLoaded()) return Decision.Wait
            return when (resumedPreferred) {
                null -> Decision.ResumePreferredSource(preferredSource.state)
                preferredSource.state -> Decision.Wait
                else -> Decision.StartFallback
            }
        }

        if (config.web == null) {
            // 有源在上一次开播后被暂停着: 先按记住的源选, 它给不出资源再放开其它源 (select 收到 StartFallback 时放开),
            // 否则被暂停的源一直不会查完, 按完成条件会一直等下去
            if (stage == Stage.PreferredSource && snapshot.sources.any { it.state.isPaused }) {
                return if (preferredSource != null && !preferredSource.state.isFinal) Decision.Wait
                else Decision.StartFallback
            }
            return decideOnCompletion(snapshot, preferred)
        }
        if (stage == Stage.PreferredSource) {
            if (preferredSource != null && !preferredSource.state.isFinal) return Decision.Wait
            if (preferredSource != null && preferredSource.results.isNotEmpty() &&
                !snapshot.context.allFieldsLoaded()
            ) return Decision.Wait
            return Decision.StartFallback
        }
        return decideWeb(snapshot, config, stage, candidates, preferred, webViewGraceOver)
    }

    private fun decideOnCompletion(
        snapshot: MediaAutoSelectSnapshot,
        preferred: List<MaybeExcludedMedia.Included>,
    ): Decision {
        // With no enabled sources of the preferred kind, wait for every source (CompletedConditions semantics).
        val preferredSources = snapshot.sources.filter {
            it.kind == snapshot.settings.preferKind && it.state !is MediaSourceFetchState.Disabled
        }
        val waitingFor = preferredSources.ifEmpty { snapshot.sources }
        if (waitingFor.any { !it.state.isDone }) return Decision.Wait
        if (preferred.isEmpty()) return Decision.Exhausted
        if (!snapshot.context.allFieldsLoaded()) return Decision.Wait
        val media = MediaSelectionDecider.findByPreference(
            preferred, snapshot.preference, snapshot.availableAlliances, snapshot.context, snapshot.settings,
        ) ?: return Decision.Exhausted
        return Decision.Select(media, "preferred kind completed")
    }

    private fun decideWeb(
        snapshot: MediaAutoSelectSnapshot,
        config: Config,
        stage: Stage,
        candidates: List<MaybeExcludedMedia.Included>,
        preferred: List<MaybeExcludedMedia.Included>,
        webViewGraceOver: Boolean,
    ): Decision {
        val web = checkNotNull(config.web)
        val webSources = snapshot.sources.filter { it.kind == MediaSourceKind.WEB }
        val allCompleted = webSources.all { it.state.isDone }
        if (!web.fastSelect && !allCompleted) return Decision.Wait
        // Settled sources produce no new candidates, so waiting for the remaining deadlines cannot change the choice.
        val effectiveStage = if (webSources.all { it.state.isSettled }) web.lastStage else stage
        val succeededIds = webSources.filter { it.state is MediaSourceFetchState.Succeed }.map { it.mediaSourceId }.toSet()
        // 还在查的源已经交出的 (每开完一页交一页, 见 KeywordSearchScope.emitEarly) 条目名精确匹配也当作候选, 不等它把别的页开完:
        // 能不能选与查完的源一样由下面的阶段、层级与 WebView 兜底的规则判断 (如等直连的源等够了时, 任意层级的直连精确匹配胜过要嗅探的);
        // 模糊匹配要等它查完, 后面的页可能有精确的; 拆分季要看整季各页的集号才认得准哪一集, 只用查完的
        val workingIds = if (snapshot.context.subjectSeriesInfo?.splitSeason != null) emptySet()
        else webSources.filter { it.state == MediaSourceFetchState.Working }.mapTo(HashSet()) { it.mediaSourceId }
        // 解析时可能要 WebView 嗅探的源只兜底 (见 WebViewSources): 别的网页源还在查、又没到最后的截止时间时不选它们;
        // 都查完后有能直接取到地址的资源时, 它们当作最低层级 (仍按匹配程度排, 精确的排在模糊的前面), 没有时按原层级选
        val viaWebView = web.sourceTiers.webViewSources
        val directSources = webSources.filter { it.mediaSourceId !in viaWebView && !it.state.isSettled }
        val directPending = viaWebView.isNotEmpty() && !webViewGraceOver && effectiveStage != Stage.Fuzzy && directSources.isNotEmpty()
        val reachableCandidates = candidates.filter {
            it.result.kind == MediaSourceKind.WEB &&
                    (it.result.mediaSourceId in succeededIds ||
                            it.result.mediaSourceId in workingIds && it.metadata.subjectMatchKind == SubjectMatchKind.EXACT)
        }
        val webCandidates = reachableCandidates.filter { !(directPending && it.result.mediaSourceId in viaWebView) }
        val webViewLowest = webCandidates.any { it.result.mediaSourceId !in viaWebView }
        val tierOf = { candidate: MaybeExcludedMedia.Included ->
            if (webViewLowest && candidate.result.mediaSourceId in viaWebView) MediaSourceTier.MaximumValue
            else web.sourceTiers.get(candidate.result.mediaSourceId, candidate.result.properties.alliance)
        }
        if (webSources.any { it.results.isNotEmpty() } && !snapshot.context.allFieldsLoaded()) return Decision.Wait

        // 要 WebView 的源排到最后之后, 「立即」档也选还在查的直连源里没有更高层级能胜过的精确匹配:
        // 原本立即选的 T0 若都要 WebView, 不然要白等到下一个截止时间, 而那时选的也还是它
        val beatenByPending = { tier: MediaSourceTier ->
            viaWebView.isEmpty() || !webViewGraceOver && directSources.any { web.sourceTiers.getBestTier(it.mediaSourceId) < tier }
        }
        val eligible = webCandidates.filter { candidate ->
            val exact = candidate.metadata.subjectMatchKind == SubjectMatchKind.EXACT
            when (effectiveStage) {
                Stage.Instant -> exact && (tierOf(candidate) <= web.instantTier || !beatenByPending(tierOf(candidate)))
                Stage.Exact -> exact
                Stage.Fuzzy -> true
                Stage.PreferredSource -> error("Handled before Web fallback")
            }
        }
        // Preferences apply within each match/tier group, never ahead of match quality or tier.
        val groups = eligible.groupBy {
            Pair(
                it.metadata.subjectMatchKind != SubjectMatchKind.EXACT,
                tierOf(it),
            )
        }.toList().sortedWith(compareBy({ it.first.first }, { it.first.second }))
        val preferredIds = preferred.map { it.result.mediaId }.toSet()
        for ((_, group) in groups) {
            val media = findWebCandidate(snapshot, group.filter { it.result.mediaId in preferredIds })
                ?: findWebCandidate(snapshot, group, relax = true)
            if (media != null) {
                val candidate = group.first { it.result == media }
                return Decision.Select(
                    media,
                    "${effectiveStage.name}${if (effectiveStage != stage) " (sources settled)" else ""}, " +
                            "match=${candidate.metadata.subjectMatchKind}, tier=${tierOf(candidate)}",
                )
            }
        }

        // Empty final results can finish early; candidates belonging to a later phase must wait.
        // 要 WebView 的资源只是暂不选 (直连源还会重试) 时也要等, 到最后的截止时间就选它们
        if (allCompleted && reachableCandidates.isEmpty()) {
            return if (config.fallbackToOtherKinds) {
                decideOnCompletion(snapshot, preferred.filter { it.result.kind != MediaSourceKind.WEB })
            } else Decision.Exhausted
        }
        if (effectiveStage == Stage.Fuzzy && (allCompleted || !web.waitForPendingSources)) return Decision.Exhausted
        // 原本立即会选的精确匹配要 WebView 嗅探, 只是在等直连的源: 等的时间有上限 (见 Web.webViewGrace)
        val deferredInstant = directPending && reachableCandidates.any {
            it.result.mediaSourceId in viaWebView && it.metadata.subjectMatchKind == SubjectMatchKind.EXACT &&
                    web.sourceTiers.get(it.result.mediaSourceId, it.result.properties.alliance) <= web.instantTier
        }
        return if (deferredInstant) Decision.WaitForDirect else Decision.Wait
    }

    /** Fast select disabled skips the instant stage. */
    private val Web.exactStageAfter: Duration get() = if (fastSelect) exactMatchAfter else Duration.ZERO

    /** The last stage the deadline timer reaches; it stays behind an infinite deadline. */
    private val Web.lastStage: Stage
        get() = when {
            exactStageAfter.isInfinite() -> Stage.Instant
            fuzzyMatchAfter.isInfinite() -> Stage.Exact
            else -> Stage.Fuzzy
        }

    /**
     * Final with no automatic retry pending. A rate-limited source restarts itself after its cooldown and may
     * still return results, so it keeps the deadlines in effect like a running source.
     */
    private val MediaSourceFetchState.isSettled: Boolean
        get() = isDone && this !is MediaSourceFetchState.RateLimited

    /**
     * 查完了: 终态且没被暂停. 过了记忆源阶段, 被暂停的数据源都已放开重查 (见 [select] 收到 StartFallback 时),
     * 快照里还显示暂停只是新状态还没传过来, 要按没查完等它, 不能当成查完了、判为没有资源.
     */
    private val MediaSourceFetchState.isDone: Boolean
        get() = isFinal && !isPaused

    private fun findWebCandidate(
        snapshot: MediaAutoSelectSnapshot,
        candidates: List<MaybeExcludedMedia.Included>,
        relax: Boolean = false,
    ): Media? = MediaSelectionDecider.findByPreference(
        candidates,
        if (relax) snapshot.preference.copy(
            alliance = ANY_FILTER, resolution = ANY_FILTER, subtitleLanguageId = ANY_FILTER, mediaSourceId = ANY_FILTER,
        ) else snapshot.preference.copy(alliance = ANY_FILTER),
        snapshot.availableAlliances, snapshot.context, snapshot.settings,
    )

    private companion object {
        val logger = logger<MediaAutoSelector>()
    }
}
