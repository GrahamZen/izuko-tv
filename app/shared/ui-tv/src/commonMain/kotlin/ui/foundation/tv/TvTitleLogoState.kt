/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.isSpecified
import kotlinx.coroutines.CancellationException
import me.him188.ani.app.data.models.preference.TvTitleLogoDisplay
import me.him188.ani.app.data.models.preference.TvTitleLogoLanguage
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.network.TmdbTitleLogo
import me.him188.ani.app.data.network.toTmdbLanguage
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import kotlin.math.ceil

/**
 * 条目的标题 logo 查到哪一步了: [logo] = 查到的 logo (null = 没有或还没查完), [pending] = 还没查完 (查完才知道有没有). 设置里关了时
 * 两者都是「没有」.
 */
@Immutable
data class TvTitleLogoLookup(val logo: TmdbTitleLogo?, val pending: Boolean) {
    companion object {
        val None = TvTitleLogoLookup(null, pending = false)
    }
}

/**
 * hero 标题用的 logo (见 ThemeSettings.tvTitleLogoDisplay; 作品原版的 logo, 见 [TmdbImageService.getTitleLogo]) 与查没查完. 读 TMDB 服务的进程内热表
 * (快照订阅, 查完自动重组), 没查过就查一次: 对应表里的条目不要原名, 表里没有的按 [originalName] 搜 (条目信息到了才有, 到了再查).
 * 列表页 hero 流水线会替邻居先查好 (见 [prefetchTvTitleLogo]), 走过去时多半已在热表里. 设置为「看不清时显示文字」时判过看不清的 logo
 * 当作没有 (见 [shownTvTitleLogo]).
 */
@Composable
fun rememberTvTitleLogoLookup(subjectId: Int, originalName: String?): TvTitleLogoLookup {
    val display = LocalThemeSettings.current.tvTitleLogoDisplay
    if (display == TvTitleLogoDisplay.Off) return TvTitleLogoLookup.None
    val tmdb = remember { GlobalKoin.get<TmdbImageService>() }
    val language = rememberTvTitleLogoLanguage()
    LaunchedEffect(tmdb, subjectId, originalName, language) {
        if (!tmdb.peekTitleLogoResolved(subjectId, language)) tmdb.getTitleLogo(subjectId, originalName.orEmpty(), language)
    }
    return TvTitleLogoLookup(
        tvTitleLogoShown(tmdb.peekTitleLogo(subjectId, language), display),
        pending = !tmdb.peekTitleLogoResolved(subjectId, language),
    )
}

/**
 * 按设置 [display] 显不显示 [logo]: 关了, 或「看不清时显示文字」且列表页 / 详情页哪一处判过它看不清 (见 [TvTitleLogoUnreadable]) 时为 null.
 * 快照读 (记下看不清时重组).
 */
fun tvTitleLogoShown(logo: TmdbTitleLogo?, display: TvTitleLogoDisplay): TmdbTitleLogo? = when {
    logo == null || display == TvTitleLogoDisplay.Off -> null
    display == TvTitleLogoDisplay.TextWhenUnreadable && TvTitleLogoUnreadable.isMarked(logo) -> null
    else -> logo
}

/** 同 [tvTitleLogoShown], 按此刻的设置: 别处带过来的 logo (如放大转场的会话里列表页那张) 显示前过一遍. */
@Composable
fun shownTvTitleLogo(logo: TmdbTitleLogo?): TmdbTitleLogo? = tvTitleLogoShown(logo, LocalThemeSettings.current.tvTitleLogoDisplay)

/**
 * 判出来原样看不清的标题 logo (按图片路径记): 列表页 hero (模糊背景上按正下方的背景判, 没铺模糊背景时按标题的底色看要不要翻) 与详情页首屏
 * (按自己的清晰背景图判, 见 [TvTitleLogoDetailsLooks]) 任一处看不清就记下, 不论设置是哪一档 (换到「看不清时显示文字」当场生效).
 * 设置为「看不清时显示文字」时, 记下的 logo 各处 (连播放器) 都换回文字标题 (见 [tvTitleLogoShown]); 播放器上画面一直在变, 不判.
 * 只在主线程读写, 进程内有效.
 */
object TvTitleLogoUnreadable {
    private val marked = mutableStateMapOf<String, Boolean>()

    fun mark(logo: TmdbTitleLogo) {
        if (marked[logo.filePath] != true) marked[logo.filePath] = true
    }

    /** 快照读. */
    fun isMarked(logo: TmdbTitleLogo): Boolean = marked[logo.filePath] == true
}

/**
 * 标题 logo 用哪种语言的 (见 ThemeSettings.tvTitleLogoLanguage): null = 条目的原语言, 否则界面语言的两个字母 (如 `zh`).
 * 传给 [TmdbImageService.getTitleLogo] 等.
 */
@Composable
fun rememberTvTitleLogoLanguage(): String? {
    if (LocalThemeSettings.current.tvTitleLogoLanguage == TvTitleLogoLanguage.Original) return null
    return Locale.current.toTmdbLanguage().substringBefore('-').lowercase()
}

/** 同 [rememberTvTitleLogoLookup], 只要 logo: 设置里关了、这部没有原版 logo (或还没查完)、或看不清要显示文字时为 null. */
@Composable
fun rememberTvTitleLogo(subjectId: Int, originalName: String?): TmdbTitleLogo? =
    rememberTvTitleLogoLookup(subjectId, originalName).logo

/**
 * 标题 logo 的框 (见 [tvTitleLogoBox]): 列表页 hero 文字块、hero 流水线的 logo 预热、详情页首屏与播放器共用 —— 按同一个框挑图片档位
 * ([TmdbTitleLogo.url]), 预热的才是显示时要的那张. 行高取标题 (headlineLarge) 的, 按像素向上取整 (同 toTvNativeTextStyle).
 * [titleWidthPx] = 标题宽 (宽度上限另不超过它), 0 = 不限. 设置里关了为 null.
 */
@Composable
fun rememberTvTitleLogoBox(titleWidthPx: Int = 0): TvTitleLogoBox? {
    val enabled = LocalThemeSettings.current.tvTitleLogoDisplay != TvTitleLogoDisplay.Off
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    return remember(enabled, density, typography, titleWidthPx) {
        if (enabled) tvTitleLogoBox(typography.headlineLarge.lineHeightPx(density), titleWidthPx) else null
    }
}

private fun TextStyle.lineHeightPx(density: Density): Int =
    if (lineHeight.isSpecified) with(density) { ceil(lineHeight.toPx()).toInt() } else 0

/**
 * 预取条目的标题 logo ([language] 同 [rememberTvTitleLogoLanguage]; 查进热表与持久缓存, 见 [TmdbImageService.getTitleLogo]); 已查过的直接返回. [originalName] 为 null 时只有对应表里的条目
 * 查得到 (按条目 id 查, 不要名字), 别的等名字到了再查. 失败不外泄 (下次聚焦重试).
 */
suspend fun TmdbImageService.prefetchTvTitleLogo(subjectId: Int, originalName: String?, language: String?) {
    if (peekTitleLogoResolved(subjectId, language)) return
    try {
        getTitleLogo(subjectId, originalName.orEmpty(), language)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}

/**
 * 标题 logo 的色调: 不透明像素里接近纯黑 ([nearBlack], 三个通道都低于 64) 与接近纯白 ([nearWhite], 三个通道都高于 200) 的占比.
 * 由此判断 logo 压在文字的底色上看不看得清 (见 [needsFlip]).
 */
@Immutable
data class TvTitleLogoTone(val nearBlack: Float, val nearWhite: Float) {
    /**
     * 要不要翻色 (见 [flipTvTitleLogo]): logo 的主体与文字反色 (浅色字 = 深色底, 主体接近纯黑; 深色字 = 浅色底, 主体接近纯白)、自己又没有反色的描边或底.
     * 浅色字时接近纯黑的占六成以上且接近纯白的不到 8% 才算 (深色字反过来). 2026-10 抽查 18 部 TMDB 中文 / 日文 logo, 只命中两张纯黑字的
     * (四月是你的谎言、DARLING in the FRANXX); 黑字带黄描边、黑字白圆底、黑白各半的都不动. 照 Prime Video: 深红字、深色字加白描边原样放.
     */
    fun needsFlip(lightText: Boolean): Boolean =
        if (lightText) nearBlack >= 0.6f && nearWhite <= 0.08f else nearWhite >= 0.6f && nearBlack <= 0.08f
}

