/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.text.intl.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.episode.preferredDisplayName
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.preferredDisplayName
import me.him188.ani.app.data.models.subject.summaryIsJapaneseOnly
import me.him188.ani.app.data.models.subject.summaryWithoutLeadingJapanese
import me.him188.ani.app.data.models.subject.toTmdbMatchHints
import me.him188.ani.app.data.models.subject.tvPrefersTmdbText
import me.him188.ani.app.data.network.TmdbEpisodeStills
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.network.TmdbMatchHints
import me.him188.ani.app.data.network.TmdbSubjectText
import me.him188.ani.app.data.network.newestAiredDateStringOrNull
import me.him188.ani.app.data.network.subjectText
import me.him188.ani.app.data.network.toTmdbLanguage
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.LocalSubjectAppearanceSettings
import me.him188.ani.app.ui.subject.details.state.SubjectDetailsState
import me.him188.ani.datasources.api.toLocalDateOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * 番的 TMDB 文字 (界面语言的标题、简介与对应的季, 见 [TmdbSubjectText]), 进程级共享 —— 理由与容量策略同 [TvHeroMediaCache].
 * hero 的简介 (见 [tvHeroTmdbSummary]) 与非中文界面各页的标题 (见 [SubjectInfo.tvTitle]) 用它.
 */
object TvTmdbSubjectTexts {
    private const val MAX_ENTRIES = 300
    private const val EVICT_BATCH = 50

    /** subjectId -> 文字. 语言不是当前界面语言的条目当没有. */
    val texts: SnapshotStateMap<Int, TmdbSubjectText> = mutableStateMapOf()
    private val order = ArrayDeque<Int>()

    internal fun put(subjectId: Int, text: TmdbSubjectText) {
        synchronized(order) {
            if (subjectId !in texts) {
                order.addLast(subjectId)
                if (order.size > MAX_ENTRIES) {
                    repeat(EVICT_BATCH) { order.removeFirstOrNull()?.let(texts::remove) }
                }
            }
            texts[subjectId] = text
        }
    }

    /**
     * subjectId -> 已经有结果的界面语言: 取到了、确认没有、取失败, 或等够了 [TV_TMDB_TEXT_WAIT]. 非中文界面的 hero 在此之前不显示简介
     * (见 [tvHeroTmdbSummaryPending]). 只管显示, 不挡下次预取.
     */
    val settled: SnapshotStateMap<Int, String> = mutableStateMapOf()
    private val settledOrder = ArrayDeque<Int>()

    internal fun settle(subjectId: Int, language: String) {
        synchronized(settledOrder) {
            if (subjectId !in settled) {
                settledOrder.addLast(subjectId)
                if (settledOrder.size > MAX_ENTRIES) {
                    repeat(EVICT_BATCH) { settledOrder.removeFirstOrNull()?.let(settled::remove) }
                }
            }
            settled[subjectId] = language
        }
    }
}

/** 非中文界面等 TMDB 文字最多等多久: 过了就先显示 Bangumi 的简介, TMDB 那边接着取 (约一次请求的时间, 留些余量). */
val TV_TMDB_TEXT_WAIT = 2500.milliseconds

/** hero 上这部当前界面语言的 TMDB 文字还没有结果 (见 [TvTmdbSubjectTexts.settled]). */
@Composable
fun tvHeroTmdbTextPending(subjectId: Int): Boolean {
    val language = Locale.current.toTmdbLanguage()
    return TvTmdbSubjectTexts.texts[subjectId]?.language != language && TvTmdbSubjectTexts.settled[subjectId] != language
}

/**
 * 非中文界面 hero 上这部的 TMDB 文字还没有结果: 这时简介先空着, 免得先闪 Bangumi 的 (中文 / 日文) 再换成 TMDB 的.
 * 中文界面只在 Bangumi 的简介只有日文时才等 (见 [tvHeroBangumiSummary]).
 */
@Composable
fun tvHeroTmdbSummaryPending(subjectId: Int): Boolean = tvPrefersTmdbText() && tvHeroTmdbTextPending(subjectId)

/**
 * 非中文界面 hero 上这部的 TMDB 简介 (界面语言), 排在 Bangumi 的前面; 没有时为 null, 调用方接着用 Bangumi 的.
 * 中文界面一律为 null: 照用 Bangumi 的 (日文在前时从中文起, 见 [tvHeroBangumiSummary]).
 */
@Composable
fun tvHeroTmdbSummary(subjectId: Int): String? {
    if (!tvPrefersTmdbText()) return null
    val language = Locale.current.toTmdbLanguage()
    return TvTmdbSubjectTexts.texts[subjectId]?.takeIf { it.language == language }?.overview?.ifBlank { null }
}

/** hero 上这部的 TMDB 简介 (界面语言), 中文界面也取; 给 [tvHeroBangumiSummary] 在 Bangumi 没有简介或只有日文时用. */
@Composable
fun tvHeroTmdbFallbackSummary(subjectId: Int): String? {
    val language = Locale.current.toTmdbLanguage()
    return TvTmdbSubjectTexts.texts[subjectId]?.takeIf { it.language == language }?.overview?.ifBlank { null }
}

/**
 * hero 上 Bangumi 那段简介: 条目自己的 ([bangumiSummary]), 为空时 bgm.tv 兜底的 ([bgmTvSummary]: null = 还在查, 空 = 也没有),
 * 日文在前时从中文起 (见 [summaryWithoutLeadingJapanese]).
 * - 两边都确认没有 (新番刚开播常见): 用 TMDB 的 ([tmdbFallback], 同详情页的兜底);
 * - 只有日文 (见 [summaryIsJapaneseOnly]): 用 TMDB 的; TMDB 还没结果 ([tmdbPending]) 时先空着, 没有或等过了 [TV_TMDB_TEXT_WAIT] 才显示日文.
 *
 * Bangumi 补上 (中文) 简介后条目信息一刷新 (缓存一小时) 就换回 Bangumi 的. bgm.tv 还在查时先空着, 免得先闪 TMDB 的再换成 bgm.tv 的.
 */
fun tvHeroBangumiSummary(bangumiSummary: String, bgmTvSummary: String?, tmdbFallback: String?, tmdbPending: Boolean): String {
    val text = bangumiSummary.trim().ifEmpty { bgmTvSummary?.trim() ?: return "" }
    return when {
        text.isEmpty() -> tmdbFallback.orEmpty()
        summaryIsJapaneseOnly(text) -> tmdbFallback ?: if (tmdbPending) "" else text
        else -> summaryWithoutLeadingJapanese(text)
    }
}

/**
 * 标题要不要换成 TMDB 界面语言的: 非中文界面且没开「显示原名」(见 [tvUseOriginalTitle]: 那时番名本来是原名).
 * 中文界面照用 Bangumi 的中文名.
 */
@Composable
fun tvUsesTmdbTitle(): Boolean = !LocalSubjectAppearanceSettings.current.useOriginalTitle && tvPrefersTmdbText()

/**
 * 当前界面语言的 TMDB 标题表 (subjectId -> 标题), 给一次建好一批卡片的海报墙当 remember 的键;
 * 不换成 TMDB 标题时 (见 [tvUsesTmdbTitle]) 为空. 表里任一条写入都会换一个新表.
 */
@Composable
fun rememberTvTmdbTitles(): Map<Int, String> {
    if (!tvUsesTmdbTitle()) return emptyMap()
    val language = Locale.current.toTmdbLanguage()
    val titles by remember(language) {
        derivedStateOf {
            TvTmdbSubjectTexts.texts.mapNotNull { (id, text) ->
                text.title?.takeIf { text.language == language }?.let { id to it }
            }.toMap()
        }
    }
    return titles
}

/**
 * 这部番在电视上的标题: 非中文界面没开「显示原名」时先用 TMDB 的 (表里没有就从持久缓存补, 不发请求),
 * 否则按「显示原名」取 Bangumi 的.
 */
@Composable
fun SubjectInfo.tvTitle(): String = tvTmdbSubjectTitleFilled(subjectId) ?: preferredDisplayName(tvUseOriginalTitle())

/**
 * 组合之外 (焦点 / 点击回调里) 取标题, 不订阅表的变化: [enabled] 是在组合里取好的 [tvUsesTmdbTitle];
 * 为 true 且表里有这部当前界面语言的 TMDB 标题时用它, 否则 [fallback].
 */
inline fun tvTmdbTitleOr(subjectId: Int, enabled: Boolean, fallback: () -> String): String {
    val text = if (enabled) TvTmdbSubjectTexts.texts[subjectId] else null
    return text?.title?.takeIf { text.language == Locale.current.toTmdbLanguage() } ?: fallback()
}

/**
 * 详情状态 (详情页、播放页各有一份) 取到的 TMDB 文字写进 [TvTmdbSubjectTexts]: 页上各处标题、回到海报墙后的卡片与 hero 都用它.
 * 标题与简介都没取到 (网络失败) 时不写 —— 写个空的会挡住之后的预取.
 */
@Composable
fun SyncTvTmdbSubjectText(state: SubjectDetailsState?) {
    if (state == null) return
    LaunchedEffect(state) {
        state.tmdbSubjectTextFlow.filterNotNull().collect { text ->
            if (text.title != null || text.overview != null) TvTmdbSubjectTexts.put(state.subjectId, text)
        }
    }
}

/**
 * 表里这部番当前界面语言的 TMDB 标题; 不换 (见 [tvUsesTmdbTitle]) 或表里没有时为 null.
 * 只查表: 列表页在页面上一次补一批 (见 [FillTvTmdbTextsFromCache]), 单部的页面用 [tvTmdbSubjectTitleFilled].
 */
@Composable
fun tvTmdbSubjectTitle(subjectId: Int): String? {
    if (!tvUsesTmdbTitle()) return null
    val language = Locale.current.toTmdbLanguage()
    return TvTmdbSubjectTexts.texts[subjectId]?.takeIf { it.language == language }?.title
}

/** 同 [tvTmdbSubjectTitle], 表里没有时从持久缓存补这一部 (hero、详情页、播放器). */
@Composable
fun tvTmdbSubjectTitleFilled(subjectId: Int): String? {
    FillTvTmdbTextsFromCache(listOf(subjectId))
    return tvTmdbSubjectTitle(subjectId)
}

/**
 * 从分集剧照的持久缓存补 [TvTmdbSubjectTexts] (一次读盘, 不发请求): 海报墙换一批卡片时用, 看过的番直接有标题与简介.
 */
suspend fun TmdbImageService.fillTvTmdbTextsFromCache(subjectIds: Collection<Int>) {
    val language = Locale.current.toTmdbLanguage()
    val missing = subjectIds.filter { TvTmdbSubjectTexts.texts[it]?.language != language }
    if (missing.isEmpty()) return
    for ((id, stills) in peekCachedEpisodeStills(missing, language)) {
        if (stills.textVersion >= TmdbEpisodeStills.TEXT_VERSION) TvTmdbSubjectTexts.put(id, stills.subjectText())
    }
}

/** 海报墙换一批卡片时从持久缓存补 TMDB 文字 (见 [fillTvTmdbTextsFromCache]). */
@Composable
fun FillTvTmdbTextsFromCache(subjectIds: List<Int>) {
    LaunchedEffect(subjectIds) {
        GlobalKoin.get<TmdbImageService>().fillTvTmdbTextsFromCache(subjectIds)
    }
}

/**
 * **hero 简介与标题的预取**: 先看分集剧照的持久缓存 (同一份数据, 不发请求), 没有才走剧照那条链 (同详情页选集卡的口径).
 * 网络失败不写文字 (下次聚焦再取), 只记一笔「有结果了」让 hero 先显示 Bangumi 的简介 (见 [tvHeroTmdbSummaryPending]).
 *
 * 中文界面只在用得上时取 —— Bangumi 的简介 ([bangumiSummary]) 为空或只有日文 (见 [tvHeroBangumiSummary]); 有中文就不发请求
 * (连不上 TMDB 的用户每聚焦一部就多一个失败的请求)。[bangumiSummary] 为 null = 还不知道, 中文界面先不取, 等调用方知道了再来.
 */
suspend fun TmdbImageService.prefetchTvTmdbSummary(
    subjectId: Int,
    originalName: String,
    subjectAirDate: String?,
    hints: TmdbMatchHints,
    bangumiSummary: String?,
    episodes: List<EpisodeCollectionInfo> = emptyList(),
): Unit = coroutineScope {
    val language = Locale.current.toTmdbLanguage()
    if (!tvHeroNeedsTmdbText(tvPrefersTmdbText(language), bangumiSummary)) return@coroutineScope
    if (TvTmdbSubjectTexts.texts[subjectId]?.language == language) return@coroutineScope
    // 网络慢时非中文界面的 hero 最多等这么久就先显示 Bangumi 的简介 (见 tvHeroTmdbSummaryPending), 这里接着取
    val waitOver = launch {
        delay(TV_TMDB_TEXT_WAIT)
        TvTmdbSubjectTexts.settle(subjectId, language)
    }
    val stills = peekCachedEpisodeStills(subjectId, language)?.takeIf { it.textVersion >= TmdbEpisodeStills.TEXT_VERSION }
        ?: try {
            getEpisodeStills(
                subjectId, originalName, language,
                newestWantedAirDate = episodes.newestAiredDateStringOrNull(),
                subjectAirDate = subjectAirDate,
                subjectEpisodeCount = episodes.size.takeIf { it > 0 },
                subjectEpisodeNames = episodes.map { it.episodeInfo.name },
                hints = hints,
            )
        } catch (e: CancellationException) {
            throw e // 见 prefetchTvBackdrop: 吞掉取消会让已取消的流水线继续跑
        } catch (_: Exception) {
            null
        }
    waitOver.cancel()
    stills?.let { TvTmdbSubjectTexts.put(subjectId, it.subjectText()) }
    TvTmdbSubjectTexts.settle(subjectId, language)
}

/**
 * hero 要不要取 TMDB 文字 (见 [prefetchTvTmdbSummary]): 非中文界面 ([prefersTmdb]) 都要; 中文界面只在 Bangumi 简介为空或只有日文时要,
 * 还不知道 ([bangumiSummary] 为 null) 时先不取.
 */
internal fun tvHeroNeedsTmdbText(prefersTmdb: Boolean, bangumiSummary: String?): Boolean =
    prefersTmdb || (bangumiSummary != null && (bangumiSummary.isBlank() || summaryIsJapaneseOnly(bangumiSummary)))

/**
 * hero 「下一集」那一行的集名: 非中文界面先用 TMDB 的 (随下一集剧照一起取到, 见 [TvNextEpisodeMedia.name]),
 * 没有时按「显示原名」取 Bangumi 的.
 */
@Composable
fun tvHeroNextEpisodeName(subjectId: Int, episodeId: Int, episode: EpisodeInfo): String {
    val tmdbName = TvHeroMediaCache.nextEpisodeMedia[subjectId]
        ?.takeIf { it.episodeId == episodeId && tvPrefersTmdbText() }?.name
    return tmdbName ?: episode.preferredDisplayName(tvUseOriginalTitle())
}

/** 同上, 条目信息已经到手 (探索 / 追番 / 时间表 hero 解析链的第一跳); 条目简介为空时看 bgm.tv 兜底的那份. */
suspend fun TmdbImageService.prefetchTvTmdbSummary(info: SubjectCollectionInfo) = prefetchTvTmdbSummary(
    info.subjectId,
    info.subjectInfo.name,
    subjectAirDate = info.subjectInfo.airDate.toLocalDateOrNull()?.toString(),
    hints = info.subjectInfo.toTmdbMatchHints(),
    bangumiSummary = info.subjectInfo.summary.ifBlank { TvHeroMediaCache.summaryFallbacks[info.subjectId].orEmpty() },
    episodes = info.episodes,
)
