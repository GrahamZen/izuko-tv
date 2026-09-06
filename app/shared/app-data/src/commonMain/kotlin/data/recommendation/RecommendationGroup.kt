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
 * 推荐分组. 探索页上一组画一行, 每行有自己的标题与来源.
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
     */
    ALSO_WATCHED("also_watched"),

    /** 符合你口味的高分动画: 用户高权重标签 + 排行榜. */
    FOR_YOU_HIGH_RATED("for_you_high_rated"),

    /** 高分经典: 没有画像时 (未登录 / 新装) 顶替上面那组. */
    TOP_RATED("top_rated"),

    /** 本季你可能会喜欢. 不加画像也成立, 所以未登录也能有. */
    THIS_SEASON("this_season"),

    /** 换换口味: 与已有兴趣同类但没碰过的方向. */
    CHANGE_TASTE("change_taste"),

    /** 大家最近在看. 冷启动主力. */
    TRENDING("trending"),
    ;

    companion object {
        fun ofKeyOrNull(key: String): RecommendationGroupKind? = entries.firstOrNull { it.key == key }
    }
}

@Immutable
class RecommendationGroup(
    val kind: RecommendationGroupKind,
    /** 标题里的填充参数, 目前只有 [RecommendationGroupKind.BECAUSE_YOU_LIKED] 的《X》用得上. */
    val titleArg: String?,
    val items: List<RecommendedSubjectInfo>,
)
