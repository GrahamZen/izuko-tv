/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toArgb
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCard
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHeroSource
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHeroText
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTextSpan
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.exploration_tv_air_date
import me.him188.ani.app.ui.lang.tv_wall_scale_sample_episodes
import me.him188.ani.app.ui.lang.tv_wall_scale_sample_group
import me.him188.ani.app.ui.lang.tv_wall_scale_sample_query
import me.him188.ani.app.ui.lang.tv_wall_scale_sample_status
import me.him188.ani.app.ui.lang.tv_wall_scale_sample_summary
import me.him188.ani.app.ui.lang.tv_wall_scale_sample_title_long
import me.him188.ani.app.ui.lang.tv_wall_scale_sample_title_medium
import me.him188.ani.app.ui.lang.tv_wall_scale_sample_title_short
import org.jetbrains.compose.resources.stringResource

/*
 * 「海报墙大小」的假页面 (探索 / 追番 / 搜索各一个, 见 TvPosterWallScalePage) 共用的部分: 进页的焦点交接与示例内容.
 * 假页面用的是真页面同一套原生海报墙与同一个几何函数, 只是数据换成这里的示例 (有字没图), 按键语义里去掉进详情页.
 */

/**
 * 从左边的滑块按右 / 确认进入假页面: 滑块调 [request], 假页面看到请求就把焦点送进自己 (上次停的那张卡, 没停过就是页面的默认落点) 并清掉.
 *
 * 缩放一变假页面就整个重建 (见 TvPosterWallScalePage), 所以请求带着**要进的那份**的缩放值 ([pendingScale]), 只有缩放值相同的那一份接
 * (见 [awaitFor]): 刚调完马上按右键时旧的那份还在, 它接了就把焦点送进一个马上被拆掉的视图; 用"待处理"而不是计数, 重建出来的那份也不会把
 * 上一次的请求再执行一遍 —— 焦点那时在滑块上.
 */
@Stable
class TvWallPreviewEntry {
    var pendingScale: Float? by mutableStateOf(null)
        private set

    fun request(scale: Float) {
        pendingScale = scale
    }

    /** 挂起收请求: 缩放值是 [scale] 的请求一来就清掉并调 [onEnter]. 在假页面的 LaunchedEffect 里调. */
    suspend fun awaitFor(scale: Float, onEnter: () -> Unit) {
        snapshotFlow { pendingScale }.collect { pending ->
            if (pending == scale) {
                pendingScale = null
                onEnter()
            }
        }
    }
}

/** 假页面的示例内容: 几档长短不同的番名 (一行 / 刚好两行 / 两行放不下), 与一份 hero 文字. */
@Immutable
class TvWallPreviewSamples internal constructor(
    private val titles: List<String>,
    private val status: String,
    private val episodes: String,
    private val airDate: String,
    val summary: String,
    private val groupTitles: List<String>,
    val query: String,
) {
    fun titleAt(index: Int): String = titles[index % titles.size]

    /** 第 [index] 个示例分组的标题 (从 0 数; 超过 [TV_WALL_PREVIEW_GROUPS] 个时循环). */
    fun groupTitle(index: Int): String = groupTitles[index % groupTitles.size]

    /** 第 [index] 张示例卡: 没有封面 (灰底骨架), 番名按 [titleAt]; 条目号见 [tvWallPreviewSubjectId]. */
    fun card(index: Int, progress: Float? = null): TvNativeCard =
        TvNativeCard(imageUrl = null, title = titleAt(index), progress = progress, subjectId = tvWallPreviewSubjectId(index))

    /** 示例条目 [subjectId] 的 hero 内容: 没有背景图, 文字齐全 (评分 / 开播状态 · 总集数 / 开播年月 / 简介). */
    fun heroSource(subjectId: Int, title: String, secondary: Int, onSurface: Int): TvNativeHeroSource =
        TvNativeHeroSource(
            backdrop = null,
            dimming = false,
            rawSubjectId = subjectId,
            text = TvNativeHeroText(
                subjectId = subjectId,
                title = title,
                infoReady = true,
                rating = "8.0",
                meta = listOf(
                    TvNativeTextSpan(status, secondary),
                    TvNativeTextSpan(" · ", onSurface),
                    TvNativeTextSpan(episodes, onSurface),
                    TvNativeTextSpan("    $airDate", secondary),
                ),
                summary = summary,
            ),
        )
}

@Composable
fun rememberTvWallPreviewSamples(): TvWallPreviewSamples {
    val short = stringResource(Lang.tv_wall_scale_sample_title_short)
    val medium = stringResource(Lang.tv_wall_scale_sample_title_medium)
    val long = stringResource(Lang.tv_wall_scale_sample_title_long)
    val status = stringResource(Lang.tv_wall_scale_sample_status)
    val episodes = stringResource(Lang.tv_wall_scale_sample_episodes)
    val airDate = stringResource(Lang.exploration_tv_air_date, 2026, 10)
    val summary = stringResource(Lang.tv_wall_scale_sample_summary)
    val groups = List(TV_WALL_PREVIEW_GROUPS) { stringResource(Lang.tv_wall_scale_sample_group, it + 1) }
    val query = stringResource(Lang.tv_wall_scale_sample_query)
    return remember(short, medium, long, status, episodes, airDate, summary, groups, query) {
        TvWallPreviewSamples(
            // 长短交错: 一行放得下的、两行的、两行放不下截断的, 三种番名都看得到
            titles = listOf(short, medium, long, medium, short, long, short, medium),
            status = status,
            episodes = episodes,
            airDate = airDate,
            summary = summary,
            groupTitles = groups,
            query = query,
        )
    }
}

/** hero 文字的两种颜色 (次要色 / 正文色), 按真页面的取法. */
@Composable
fun tvWallPreviewTextColors(): Pair<Int, Int> =
    tvHeroSecondaryContentColor().toArgb() to LocalContentColor.current.toArgb()

/**
 * 示例条目的条目号: 取在真实条目号 (Bangumi 的号远没到这么大) 之外, 假页面的标题登记给放大转场时不会跟真页面的条目撞上.
 * [index] 相同的卡与 hero 内容用同一个号 (网格页进 hero 态只认 rawSubjectId 与聚焦卡相同的内容).
 */
fun tvWallPreviewSubjectId(index: Int): Int = TV_WALL_PREVIEW_SUBJECT_ID_BASE + index

private const val TV_WALL_PREVIEW_SUBJECT_ID_BASE = 1_900_000_000

/** 示例分组的个数 (探索页假页面的推荐区有几组). */
const val TV_WALL_PREVIEW_GROUPS = 4
