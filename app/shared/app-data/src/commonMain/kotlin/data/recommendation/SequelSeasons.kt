/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.recommendation

import me.him188.ani.app.data.models.subject.CanonicalTagKind
import me.him188.ani.app.data.network.PrequelChain
import me.him188.ani.app.data.network.SeriesNode

/*
 * 推荐里的续作换成「用户没看过的最早一季」时, 与用户无关的那一半: 顺前传最多走几跳、哪些前传能当「一季」、按什么顺序挑.
 *
 * 运行时 (RecommendationRepository 的 SequelBatch) 与 bangumi-sequel-seasons 仓库离线出的「续作 → 候选季」表
 * (见 SequelSeasonTableRepository) 用的是这同一份判据: 表是那条流水线拿 Bangumi 数据导出、调这里的
 * [sequelSeasonCandidates] 算出来的.
 */

/**
 * 判据的版本, 写进离线表的表头. **改了本文件的判据 (或 walkPrequelChain 的走法) 就 +1**: 版本对不上的表
 * app 不用, 照旧运行时回溯, 等流水线用新代码重出一版.
 */
internal const val SEQUEL_SEASON_RULES = 1

/**
 * 续作换季时顺前传最多走几跳. 一般 1~2 跳就到头, 长的 (水星领航员、黑塔利亚) 4 跳; 再往上多半是走进了
 * 长寿系列的外传网.
 */
internal const val MAX_PREQUEL_HOPS = 6

/** 能当「一季」的最少集数 (见 [isSeasonFormat]): 6 集的网络番还算, 一两集的特别篇不算. */
internal const val MIN_SEASON_EPISODES = 6

/**
 * 能当「一季」的: 形态是 TV / WEB, 且至少 [MIN_SEASON_EPISODES] 集. 剧场版、OVA、总集篇不算 —— 续作前面常夹着
 * 一部剧场版 (来自深渊第二季的前传是剧场版「深沉灵魂的黎明」), 换成它不是用户要的"从头看"; 一两集的特别篇、
 * 前导短篇也不算 (苍穹之法芙娜的 RIGHT OF LEFT 标的是 TV、1 话; 普罗米亚的前日谭是 WEB、2 话).
 * 官方标签里没写形态、没写集数的当不知道, 放行.
 */
internal fun isSeasonFormat(node: SeriesNode): Boolean =
    when (node.metaTags.firstOrNull { it in CanonicalTagKind.Category.values }) {
        null, "TV", "WEB" -> node.episodes.let { it == null || it >= MIN_SEASON_EPISODES }
        else -> false
    }

/**
 * 续作可以换成的那几季, 按挑选顺序: 比它**播得早**、非 nsfw 的「一季」([isSeasonFormat]) 前传, **播出早的在前**.
 * 调用方按顺序跳过用户收藏过的, 第一个就是「最早的没看过的那季」; 一个都没有 = 不换.
 *
 * 按播出日期挑, 不按前传链走到头: bangumi 的「前传」是故事时间线上的, 后来才做的前传会挂在第一季
 * 前面 (苍穹之法芙娜 2004 年 TV 版的前传是 2005 年的特别篇 RIGHT OF LEFT), 走到头就换成了它.
 * 前面几季看过、中间某季没看的, 挑出来的就是中间那季: 推荐的意思是"接下来看什么".
 * 日期缺的排在有日期的后面, 日期相同 (都缺时也一样) 按前传链上更远的在前.
 */
internal fun sequelSeasonCandidates(chain: PrequelChain): List<SeriesNode> {
    val selfDate = chain.self?.airDate
    return chain.prequels.withIndex()
        .filter { (_, node) ->
            !node.nsfw && isSeasonFormat(node) &&
                    !(node.airDate.isValid && selfDate != null && selfDate.isValid && node.airDate >= selfDate)
        }
        .sortedWith(compareBy<IndexedValue<SeriesNode>>({ it.value.airDate }, { -it.index }))
        .map { it.value }
}
