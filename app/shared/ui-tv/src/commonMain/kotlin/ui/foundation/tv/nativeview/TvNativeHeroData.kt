/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.runtime.Immutable
import me.him188.ani.app.data.network.TmdbTitleLogo

/** 一段带颜色的字 (hero 信息行里的开播状态、总集数、标签等). */
@Immutable
data class TvNativeTextSpan(val text: String, val color: Int)

/**
 * hero 信息块里的「下一集」一行 (探索页继续观看 / 追番页): [lead] 集号 (不截断), [name] 集名 (放不下跑马灯), [tail] 尾段 (剩余分钟 /
 * 已看完, 不截断). 三段同色 [color]. [name] 前面接 [nameSeparator] (探索页「更多」卡那一行是一句话里嵌着名字, 不要分隔).
 * [wrap] = 整行就是 [lead] 一段字, 放不下就折行 (不用 [name] / [tail]).
 */
@Immutable
data class TvNativeHeroStatus(
    val lead: String,
    val name: String?,
    val tail: String?,
    val color: Int,
    val nameSeparator: String = " · ",
    val wrap: Boolean = false,
)

/**
 * hero 文字块要显示的内容. 字符串由页面在 Compose 里按字符串资源排好, 原生只管排版与过渡.
 *
 * @param rating 评分数字 (如 "8.7"), 画成「★ 8.7/10」; null 不画.
 * @param meta 评分之后的那一串 (开播状态 · 总集数 / 标签 / 开播年月), 按段着色.
 * @param infoReady 条目信息到了: 信息行、下一集行、简介才出现 (没到时只有标题).
 * @param summaryMaxLines 简介最多几行 (0 = 占满剩余高度).
 * @param vibrant 压在 hero 态铺着的模糊背景上 (深色主题, 由页面按状态给): 信息行 / 下一集行 / 简介 / 评分数字照 tvOS 的 vibrancy 画
 *   (见 setTvVibrancy) —— 次要色的字与简介换成次要那一档 ([tvVibrancySecondary])、评分数字换成主要文字色, 都以加法混合画在背景上;
 *   标题与评分旁边的星照常.
 * @param logo 标题 logo (见 ThemeSettings.tvTitleLogoDisplay), 样式里有 logo 框 ([TvNativeHeroTextStyle.logoBox]) 时用; null = 文字标题.
 * @param logoPending 还不知道有没有 [logo] (TMDB 还没查完): 换到这一部时进场先等一会儿 (见 [TvNativeHeroTextView]).
 */
@Immutable
data class TvNativeHeroText(
    val subjectId: Int,
    val title: String,
    val infoReady: Boolean,
    val rating: String? = null,
    val meta: List<TvNativeTextSpan> = emptyList(),
    val status: TvNativeHeroStatus? = null,
    val summary: String = "",
    val summaryMaxLines: Int = 0,
    val vibrant: Boolean = false,
    val logo: TmdbTitleLogo? = null,
    val logoPending: Boolean = false,
)

/**
 * 背景图要显示的内容: [url] 主图, [subjectId] 这张图属于哪个条目 (给放大转场登记、提前取色; null = 不是条目自己的图),
 * [underlayUrl] 应急垫底 (主图下载卡住时垫在下面的竖版封面, 半透明, 见 TvHeroMediaPipelineState.underlayUrl), [obscure] 打码,
 * [upgradeUrl] 剧照升档的原图 (视觉效果完整档 + 4K 界面的下一集剧照, 见 TvHeroMediaPipelineState.upgradeUrl): 主图上屏、停稳之后
 * 原地叠上去 (见 [TvNativeBackdropView.navigating]). 同一张主图只换它 (或 [seedUrl]) 时不算换图, 不交叉淡入.
 *
 * [seedUrl] = 提前取主色用的图, 即详情页铺的那张 (整部的横版背景, 见 TvHeroMediaPipelineState.seriesBackdropUrl): 默认就是主图;
 * 主图是继续观看的单集剧照时两张不同 —— 剧照算出的主色与详情页拿整部背景算的不一样, 进页会被换掉, 所以这时另解这一张取色;
 * null = 那张还不知道 (地址没解析出来), 先不取色.
 */
data class TvNativeBackdropTarget(
    val url: String,
    val subjectId: Int?,
    val underlayUrl: String? = null,
    val obscure: Boolean = false,
    val upgradeUrl: String? = null,
    val seedUrl: String? = url,
)

/**
 * 海报墙底下整屏背景要铺的图: [url] = 条目的横版背景图 (TMDB), 没有时是它的竖版封面; [subjectId] = 这张图属于哪个条目 (给放大转场登记、
 * 提前取色; null = 不是哪个条目自己的图, 如还没聚焦过卡片时的默认图); [sharp] = 能变清晰 (竖版封面裁成整屏太糊, 只铺模糊版).
 */
@Immutable
data class TvNativeWallBackdropTarget(
    val url: String,
    val subjectId: Int?,
    val sharp: Boolean,
)

/**
 * hero 的一路展示内容 (由页面在 Compose 里按换挡规则算好; 探索页有热门轮播 / 聚焦卡两路): [backdrop] 停稳后的背景图, [dimming] = 目标已换、
 * 展示还没跟上 (按下即压暗要等它放开), [rawSubjectId] = 此刻真实的目标 (换了就压暗), [text] = 文字 (null = 滚动 / 连发中藏起来),
 * [autoAdvanced] = 这次换页是自动轮播推进的 (文字用放慢的过渡), [wall] = hero 态整屏模糊背景铺的图 (开了「hero 态铺模糊背景」才用,
 * 见 [TvNativeHeroStage.heroBlur]): 整部的横版背景图 —— 继续观看的条目 [backdrop] 是单集剧照时它也还是整部那张, 就是详情页的背景.
 */
@Immutable
data class TvNativeHeroSource(
    val backdrop: TvNativeBackdropTarget?,
    val dimming: Boolean,
    val rawSubjectId: Int?,
    val text: TvNativeHeroText?,
    val autoAdvanced: Boolean = false,
    val wall: TvNativeWallBackdropTarget? = null,
)

/** 模糊层换图的交叉淡入. */
const val TV_WALL_BACKDROP_CROSSFADE_MILLIS = 400
