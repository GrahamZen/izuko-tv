/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.recommendation

import androidx.compose.runtime.Immutable
import me.him188.ani.app.data.models.recommend.RecommendedSubjectInfo

/**
 * 推荐分组. 探索页上一组画一行, 每行有自己的标题与来源 ([RecommendationGroupKind.FEED] 例外, 见它的说明).
 *
 * 分组而不是一锅端的理由: 单一来源喂不饱也不好看 —— 种子那一路对追新番的用户几乎空转
 * (新番在 bangumi 上没有"看过这部的人也看过"数据), 而纯高分榜又会让推荐区变成"换了名字的
 * 排行榜". 各组来源不同, 用户也能一眼看懂为什么推这个.
 *
 * [key] 会落库, **不要改已有的值** —— 改了等于旧缓存认不出来.
 */
@Immutable
enum class RecommendationGroupKind(val key: String) {
    /**
     * 因为你喜欢《X》: 取种子的"看过这部的人也看过".
     *
     * **只有种子有明确正面表态 (自评 6 分以上) 时才用这个说法**; 否则用 [ALSO_WATCHED] ——
     * 拿"在看但没打分"的作品说"你喜欢"是硬套, 追一半弃掉的多了去了.
     */
    BECAUSE_YOU_LIKED("because_you_liked"),

    /**
     * 看过《X》的人还看了: 与 [BECAUSE_YOU_LIKED] 同一个数据源, 只是措辞不替用户表态 ——
     * 这也正是 `/p1/subjects/{id}/recs` 的真实语义 (说的是别人看了什么).
     *
     * **只在 `/recs` 自己就填满一行时用**: 这句话宣称的是共看数据, 一旦要拿标签相似的作品
     * 补齐就不再全是"别人看了什么", 那时换成 [SIMILAR_TO].
     */
    ALSO_WATCHED("also_watched"),

    /**
     * 和《X》相似的作品: 种子那一路填不满一行、要拿"标签与《X》最像的高分作品"补齐时的措辞.
     *
     * 为什么要单独一个 kind: 补齐来的是**内容相似**而不是共看数据, 继续叫"看过《X》的人还看了"
     * 就是假的; 而种子没被打过高分时也不能说"因为你喜欢". 三种措辞各自对应一种证据,
     * 与 [THIS_SEASON] / [THIS_SEASON_NEW] 是同一个规矩.
     */
    SIMILAR_TO("similar_to"),

    /** 符合你口味的高分动画: 用户高权重标签 + 排行榜. */
    FOR_YOU_HIGH_RATED("for_you_high_rated"),

    /** 高分经典: 画像里没有口味标签可搜时顶替上面那组. */
    TOP_RATED("top_rated"),

    /**
     * 本季你可能会喜欢: 本季新番里**标签与"你认可过的作品"对得上**的那些
     * (见 `InterestProfile.likedTags`).
     *
     * 一个标签都对不上的不进这一组; 连半行都凑不出来时换成 [THIS_SEASON_NEW] —— 与
     * [BECAUSE_YOU_LIKED]/[ALSO_WATCHED] 同一个规矩: 措辞跟着证据走.
     */
    THIS_SEASON("this_season"),

    /**
     * 本季新番: 没有口味依据时 (一部都没打过分) 顶替上面那组, 按热度排.
     *
     * 分成两个 kind 而不是共用一个标题, 是因为"你可能会喜欢"这句话在没有任何依据时是空口
     * 许诺 —— 那时它其实就是"本季热门" (2026-09-07 用户指出这一行名不副实).
     */
    THIS_SEASON_NEW("this_season_new"),

    /** 换换口味: 与已有兴趣同类但没碰过的方向. */
    CHANGE_TASTE("change_taste"),

    /** 大家最近在看: 近半年开播的, 按热度排. */
    TRENDING("trending"),

    /**
     * 推荐: 没登录、或者一部收藏都没有时, 整个推荐区就是这一组. 按 Ani 服务端匿名首页的规则排:
     * 老番按看过人数、近半年的按在看人数, 两条榜 2:3 交织 (见 `RecommendationRepository.computeFeed`).
     *
     * 两百条上下, 远不止一行: 探索页把它切成多行画, 只有首行带标题.
     */
    FEED("feed"),
    ;

    companion object {
        /**
         * 落库的 groupKey 反解成 kind.
         *
         * **同一个 kind 可以出多行** (「因为你喜欢《A》」「因为你喜欢《B》」), 落库时写成
         * `key#序号`, 所以这里要先把 `#` 后面切掉.
         */
        fun ofKeyOrNull(key: String): RecommendationGroupKind? {
            val base = key.substringBefore('#')
            return entries.firstOrNull { it.key == base }
        }
    }
}

@Immutable
class RecommendationGroup(
    val kind: RecommendationGroupKind,
    /** 标题里的填充参数, 目前只有 [RecommendationGroupKind.BECAUSE_YOU_LIKED] 的《X》用得上. */
    val titleArg: String?,
    val items: List<RecommendedSubjectInfo>,
)
