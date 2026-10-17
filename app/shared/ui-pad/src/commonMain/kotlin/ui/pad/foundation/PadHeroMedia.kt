/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.pad.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.text.intl.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.TvTitleLogoDisplay
import me.him188.ani.app.data.models.preference.TvTitleLogoLanguage
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.toTmdbMatchHints
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.network.TmdbTitleLogo
import me.him188.ani.app.data.network.newestAiredDateStringOrNull
import me.him188.ani.app.data.network.toTmdbLanguage
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings

/**
 * 轮播 hero 的材料: 条目信息 (评分 / 开播 / 简介) 与 TMDB 横版背景图.
 */
@Immutable
class PadHeroMedia(
    val info: SubjectCollectionInfo?,
    val backdropUrl: String?,
    /** 横版图这次已有结论 (找到、确认没有或这次取不到): 没有图时才拿竖版封面兜底, 免得先闪一下封面再换成横版图. */
    val backdropResolved: Boolean,
)

/**
 * 解析链同电视版 (ui-tv 的 resolveTvHeroMedia): 先拉条目信息拿到日文原名, 再按原名找 TMDB 横版图 —— TMDB 只能按原名匹配;
 * 对应表按条目 id 就能查, 与第一跳同时进行, 查到就先出图.
 */
@Composable
fun rememberPadHeroMedia(subjectId: Int): State<PadHeroMedia> {
    val collectionRepo = remember { GlobalKoin.get<SubjectCollectionRepository>() }
    val tmdb = remember { GlobalKoin.get<TmdbImageService>() }
    return produceState(
        initialValue = PadHeroMedia(
            info = PadHeroInfoCache.get(subjectId),
            backdropUrl = tmdb.peekBackdropUrl(subjectId),
            backdropResolved = tmdb.peekBackdropResolved(subjectId),
        ),
        subjectId,
    ) {
        launch {
            ignoringErrors { tmdb.prefetchBackdropFromMap(subjectId) }
            tmdb.peekBackdropUrl(subjectId)?.let { url -> value = PadHeroMedia(value.info, url, backdropResolved = true) }
        }
        val info = PadHeroInfoCache.get(subjectId)
            ?: ignoringErrors { collectionRepo.subjectCollectionFlow(subjectId).first() }
                ?.also { PadHeroInfoCache.put(subjectId, it) }
        if (info == null) {
            value = PadHeroMedia(null, value.backdropUrl, backdropResolved = true)
            return@produceState
        }
        value = PadHeroMedia(info, value.backdropUrl, value.backdropResolved)
        val url = value.backdropUrl ?: ignoringErrors {
            tmdb.getBackdropUrl(
                subjectId,
                info.subjectInfo.name,
                activeAsOfDate = info.episodes.newestAiredDateStringOrNull(),
                hints = info.subjectInfo.toTmdbMatchHints(),
            )
        }
        value = PadHeroMedia(info, url, backdropResolved = true)
    }
}

/**
 * 片名 logo (TMDB), 按设置「片名 logo」: 关了为 null; 语言同电视版 (界面语言, 或设置成原版). 对应表里的条目不等原名就能查,
 * 别的等原名到了再查 (同 ui-tv 的 rememberTvTitleLogoLookup). 取不到为 null, 调用方照旧显示文字标题.
 *
 * 电视版「自动」档会把压在底色上看不清的 logo 翻色, 这里原样显示.
 */
@Composable
fun rememberPadTitleLogo(subjectId: Int, originalName: String?): TmdbTitleLogo? {
    val settings = LocalThemeSettings.current
    if (settings.tvTitleLogoDisplay == TvTitleLogoDisplay.Off) return null
    val tmdb = remember { GlobalKoin.get<TmdbImageService>() }
    val language = if (settings.tvTitleLogoLanguage == TvTitleLogoLanguage.Original) {
        null
    } else {
        Locale.current.toTmdbLanguage().substringBefore('-').lowercase()
    }
    val logo by produceState(tmdb.peekTitleLogo(subjectId, language), subjectId, originalName, language) {
        if (!tmdb.peekTitleLogoResolved(subjectId, language)) {
            ignoringErrors { tmdb.getTitleLogo(subjectId, originalName.orEmpty(), language) }
        }
        value = tmdb.peekTitleLogo(subjectId, language)
    }
    return logo
}

/** 条目信息的进程内缓存: 轮播来回翻页时不重拉. 只在主线程读写 (组合与 produceState 的协程). */
private object PadHeroInfoCache {
    private const val CAPACITY = 64
    private val map = LinkedHashMap<Int, SubjectCollectionInfo>()

    fun get(subjectId: Int): SubjectCollectionInfo? = map[subjectId]

    fun put(subjectId: Int, info: SubjectCollectionInfo) {
        map.remove(subjectId)
        map[subjectId] = info
        while (map.size > CAPACITY) map.remove(map.keys.first())
    }
}

/** 取不到就当没有 (下次进来再试), 不写负缓存; 取消照常往外抛. */
private inline fun <T> ignoringErrors(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
