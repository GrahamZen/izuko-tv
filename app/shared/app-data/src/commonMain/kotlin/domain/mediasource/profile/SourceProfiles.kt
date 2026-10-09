/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.profile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import me.him188.ani.app.domain.media.resolver.HttpStreamingMediaDataProvider
import me.him188.ani.app.domain.media.selector.MediaSelectorSourceTiers
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceTier
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * 数据源画像: 每个数据源最近 [KEEP] 次播放 (与控制台「深度测试」) 的实际情况 —— 开播用了多久、视频的分辨率 / 编码 / 码率、
 * 有没有删到插播广告、规则源是不是靠 WebView 才拿到视频、失败了没有. 用来看出源的好坏并给出建议层级 ([summary]).
 *
 * 播放时由 `SourceProfileExtension` 记, 每次搜索的用时与结果 (成功 / 失败 / 要验证码 / 被限流) 也顺手记下 ([recordSearch], 不多发请求);
 * 解析出的视频地址由 `ChainedMediaResolver` 记 ([noteResolved]),
 * 规则源走没走 WebView 由 `RuleMediaSource` 记 ([noteWebView]). 存在设置里, 启动时 [load].
 *
 * 规则源最近几次大多要 WebView 嗅探时, 选源用的层级降一级 ([webViewSources] / [demote]); 别的源的层级仍由订阅决定, 画像只给建议.
 */
object SourceProfiles {
    private const val KEY = "sourceProfiles"
    private const val KEEP = 10
    private const val RECENT = 5

    /** 有新记录后等这么久没再变才写盘: 一次搜索各源同时出结果, 合成一次写入 (设置是整份重写的). */
    private val SAVE_DELAY = 10.seconds

    /** 同一个源这么久内只探测一次视频 (分辨率 / 码率几天内基本不变), 见 [shouldProbe]. */
    private val PROBE_INTERVAL = 24.hours

    private val logger = logger<SourceProfiles>()
    private val json = Json { ignoreUnknownKeys = true }

    private val state = MutableStateFlow<Map<String, List<SourceObservation>>>(emptyMap())
    private val searches = MutableStateFlow<Map<String, List<SearchObservation>>>(emptyMap())
    private val settings = MutableStateFlow<SettingsRepository?>(null)
    private val pendingWebView = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val resolvedUrls = MutableStateFlow<Map<String, ResolvedUrl>>(emptyMap())
    private val changes = MutableStateFlow(0)
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** key 是 [Media.mediaSourceId]. */
    val observations: StateFlow<Map<String, List<SourceObservation>>> = state.asStateFlow()

    /** 最近几次大多要 WebView 嗅探才拿到视频的数据源 (只有规则源记这一项). */
    val webViewSources: Flow<Set<String>> = state.map { all -> all.filterValues { usesWebView(it) == true }.keys }.distinctUntilChanged()

    class ResolvedUrl(val uri: String, val headers: Map<String, String>)

    @Serializable
    private class Saved(
        val sources: Map<String, List<SourceObservation>> = emptyMap(),
        val searches: Map<String, List<SearchObservation>> = emptyMap(),
    )

    suspend fun load(settingsRepository: SettingsRepository) {
        settings.value = settingsRepository
        settingsRepository.readText(KEY)?.let { text ->
            runCatching { json.decodeFromString(Saved.serializer(), text) }
                .onSuccess { saved ->
                    state.update { current -> saved.sources + current }
                    searches.update { current -> saved.searches + current }
                }
                .onFailure { logger.warn(it) { "Failed to read source profiles" } }
        }
        saveScope.launch {
            changes.collectLatest { version ->
                if (version == 0) return@collectLatest
                delay(SAVE_DELAY)
                save()
            }
        }
    }

    /** 规则源这次播放是不是靠 WebView 嗅探拿到的视频 (下一条记录取走). */
    fun noteWebView(mediaSourceId: String, webView: Boolean) {
        pendingWebView.update { it + (mediaSourceId to webView) }
    }

    fun takeWebView(mediaSourceId: String): Boolean? {
        val value = pendingWebView.value[mediaSourceId]
        pendingWebView.update { it - mediaSourceId }
        return value
    }

    /** 资源解析出的视频地址与请求头, 开播后探测码率用. 只记直接给出网址的 ([HttpStreamingMediaDataProvider]). */
    suspend fun noteResolved(media: Media, provider: MediaDataProvider<*>) {
        if (provider !is HttpStreamingMediaDataProvider) return
        val data = coroutineScope { provider.open(this) }
        val entry = ResolvedUrl(data.uri, data.headers)
        resolvedUrls.update { (it - media.mediaId + (media.mediaId to entry)).entries.toList().takeLast(32).associate { (k, v) -> k to v } }
    }

    fun resolvedOf(mediaId: String): ResolvedUrl? = resolvedUrls.value[mediaId]

    fun record(mediaSourceId: String, observation: SourceObservation) {
        state.update { it + (mediaSourceId to (it[mediaSourceId].orEmpty() + observation).takeLast(KEEP)) }
        changes.update { it + 1 }
    }

    fun recordSearch(mediaSourceId: String, observation: SearchObservation) {
        searches.update { it + (mediaSourceId to (it[mediaSourceId].orEmpty() + observation).takeLast(KEEP)) }
        changes.update { it + 1 }
    }

    /** 这个源离上次测到视频信息 (播放时探测或深度测试) 是否已过 [PROBE_INTERVAL]. */
    fun shouldProbe(mediaSourceId: String, now: Long): Boolean {
        val last = state.value[mediaSourceId].orEmpty().lastOrNull { it.height != null || it.kbps != null }?.at ?: return true
        return now - last >= PROBE_INTERVAL.inWholeMilliseconds
    }

    private suspend fun save() {
        val repository = settings.value ?: return
        try {
            repository.writeText(KEY, json.encodeToString(Saved.serializer(), Saved(state.value, searches.value)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to save source profiles" }
        }
    }

    /** 选源用的层级: [ids] 里的源 (见 [webViewSources]) 降一级. */
    fun demote(tiers: MediaSelectorSourceTiers, ids: Set<String>): MediaSelectorSourceTiers =
        if (ids.isEmpty()) tiers else tiers.copy(
            tiers = tiers.tiers.mapValues { (id, tier) -> if (id in ids) MediaSourceTier(tier.value + 1u) else tier },
        )

    fun summary(mediaSourceId: String): SourceProfileSummary? =
        summarize(state.value[mediaSourceId].orEmpty(), searches.value[mediaSourceId].orEmpty())

    internal fun usesWebView(list: List<SourceObservation>): Boolean? {
        val marks = list.mapNotNull { it.webView }.takeLast(RECENT)
        return if (marks.isEmpty()) null else marks.count { it } * 2 > marks.size
    }

    internal fun summarize(all: List<SourceObservation>, searchList: List<SearchObservation> = emptyList()): SourceProfileSummary? {
        if (all.isEmpty() && searchList.isEmpty()) return null
        val video = all.lastOrNull { it.height != null }
        val kbps = all.mapNotNull { it.kbps }.takeLast(RECENT).sorted().let { it.getOrNull(it.size / 2) }
        val startup = all.filter { it.ok && !it.test }.mapNotNull { it.startupMillis }.takeLast(RECENT).sorted().let { it.getOrNull(it.size / 2) }
        val hls = all.filter { it.adSegments != null }
        val adPlays = hls.count { (it.adSegments ?: 0) > 0 }
        val webView = usesWebView(all)
        val failures = all.count { !it.ok }
        val searchMillis = searchList.filter { it.outcome == SearchObservation.Outcome.OK }.map { it.millis }
            .takeLast(RECENT).sorted().let { it.getOrNull(it.size / 2) }
        val searchFailures = searchList.count { it.outcome != SearchObservation.Outcome.OK }
        val reasons = buildList {
            if (adPlays > 0) add(SuggestReason.ADS)
            if (webView == true) add(SuggestReason.WEB_VIEW)
            if (video != null && (video.width ?: 0) < 1800 && (video.height ?: 0) < 1000) add(SuggestReason.BELOW_1080P)
            if (failures >= 2 && failures * 2 > all.size) add(SuggestReason.FAILURES)
            if (searchList.size >= 3 && searchFailures * 2 > searchList.size) add(SuggestReason.SEARCH_FAILS)
        }
        val measured = all.any { it.ok } && (video != null || kbps != null)
        return SourceProfileSummary(
            plays = all.count { it.ok && !it.test },
            tests = all.count { it.test },
            failures = failures,
            width = video?.width,
            height = video?.height,
            codec = video?.codec,
            kbps = kbps,
            webView = webView,
            hlsPlays = hls.size,
            adPlays = adPlays,
            startupMillis = startup,
            searches = searchList.size,
            searchMillis = searchMillis,
            searchFailures = searchFailures,
            lastAt = maxOf(all.lastOrNull()?.at ?: 0L, searchList.lastOrNull()?.at ?: 0L),
            suggestedTier = if (measured) minOf(4, reasons.sumOf { it.weight }) else null,
            reasons = reasons,
        )
    }
}

@Serializable
data class SourceObservation(
    /** 记录时刻 (毫秒). */
    val at: Long,
    /** 播起来了; false = 解析或打开失败, 或播放中出错. */
    val ok: Boolean,
    /** 从选中到开始播放 (毫秒). */
    val startupMillis: Long? = null,
    /** 规则源这次是不是靠 WebView 嗅探拿到的视频; 别的源为 null. */
    val webView: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    val codec: String? = null,
    val kbps: Int? = null,
    /** HLS 去广告删掉的插播段数; 不是 HLS 或没判为 null. */
    val adSegments: Int? = null,
    /** 失败原因 (`PlaybackFailureLog.Reason` 的名字). */
    val reason: String? = null,
    /** 控制台「深度测试」测出来的, 不是真实播放. */
    val test: Boolean = false,
)

/** 一次搜索 (播放页查资源时每个源一条): 用时与结果, 来自查询状态, 不多发请求. */
@Serializable
data class SearchObservation(
    val at: Long,
    val millis: Long,
    val outcome: Outcome,
) {
    enum class Outcome { OK, FAILED, CAPTCHA, RATE_LIMITED }
}

/** 层级建议的理由, [weight] 是各自加的级数 (从 T0 加起, 最多到 T4). */
enum class SuggestReason(val weight: Int) {
    /** 删到过插播广告. */
    ADS(2),

    /** 规则源大多要 WebView 嗅探才拿到视频. */
    WEB_VIEW(1),

    /** 不到 1080p. */
    BELOW_1080P(1),

    /** 失败超过一半. */
    FAILURES(1),

    /** 最近的搜索大多失败 (含要验证码、被限流). */
    SEARCH_FAILS(1),
}

class SourceProfileSummary(
    /** 真实播放成功的次数. */
    val plays: Int,
    /** 深度测试的次数. */
    val tests: Int,
    val failures: Int,
    val width: Int?,
    val height: Int?,
    val codec: String?,
    /** 最近几次的码率中位数. */
    val kbps: Int?,
    /** 规则源最近几次大多要 WebView; 别的源为 null. */
    val webView: Boolean?,
    /** 记到了去广告结果的 HLS 播放次数, 与其中删到插播广告的次数. */
    val hlsPlays: Int,
    val adPlays: Int,
    /** 最近几次开播用时的中位数. */
    val startupMillis: Long?,
    /** 记到的搜索次数、成功那些的用时中位数、没成功的次数. */
    val searches: Int,
    val searchMillis: Long?,
    val searchFailures: Int,
    val lastAt: Long,
    /** 建议层级; 还没测到视频信息时为 null. */
    val suggestedTier: Int?,
    val reasons: List<SuggestReason>,
)
