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
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionEntity
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlin.math.ln
import kotlin.math.pow

/**
 * 用户的兴趣画像: 几个最有代表性的兴趣方向, 外加几部"最能代表口味"的种子作品.
 *
 * **完全由本地数据算出, 一个请求都不发** —— `subject_collection` 表里该有的都有 (每部的标签及其
 * 票数、自己的评分、收藏状态、最近改动时间). 所以进探索页时算它是免费的.
 */
class InterestProfile(
    /**
     * 归一化后的兴趣方向, 权重降序, 至多 [MAX_TAGS] 个. **拿去当搜索词用的就是这几个**,
     * 所以它打了 IDF 折扣 (太宽泛的标签搜出来和没筛一样).
     */
    val tags: List<WeightedTag>,
    /** 最能代表口味的作品, 权重降序. 给"因为你喜欢《X》"用. */
    val seeds: List<Seed>,
    /**
     * **只由"看过且认可"的作品贡献**的标签 (自评 6 分以上, 或看完没打分), 至多
     * [MAX_LIKED_TAGS] 个, 归一化后权重降序.
     *
     * 与 [tags] 刻意分开, 因为两者回答的不是同一个问题:
     * - [tags] 回答"该拿什么去搜" —— 在看/想看都算数, 越近越重, 且打 IDF 折扣;
     * - 这一份回答"这部像不像你会喜欢的" —— **只认有正面表态的作品** (在看一半就弃的多了去了),
     *   **不看时间衰减** (三年前打 10 分的依然说明口味), **不打 IDF 折扣** (一半认可作品都带
     *   "奇幻"恰恰说明奇幻就是口味本身, 折掉等于把最强偏好压没).
     *
     * 用途是给候选打分 (「本季你可能会喜欢」), 不是拿去搜.
     */
    val likedTags: List<WeightedTag> = emptyList(),
    /** 算它时用了多少条真收藏; 只进 [key], 不参与排序. */
    val collectionCount: Int = 0,
) {
    class WeightedTag(val name: String, val weight: Double) {
        override fun toString(): String = "$name=${(weight * 100).toInt() / 100.0}"
    }

    /**
     * @param explicitlyLiked 有没有**明确的正面表态** (自己打了 6 分以上). 只有这一档才配得上
     *   "因为你喜欢《X》"这个说法; 否则界面上要换成"看过《X》的人还看了" —— 那才是
     *   `/p1/subjects/{id}/recs` 的真实语义, 说的是别人, 与本人喜不喜欢无关.
     */
    class Seed(
        val subjectId: Int,
        val name: String,
        val explicitlyLiked: Boolean,
        /**
         * 全站有多少人给它评过分.
         *
         * 种子是拿去问 `/p1/subjects/{id}/recs` 的, 而那是**共看数据** —— 种子自己太冷门时,
         * 与它共看的只会更冷, 整行都是没人看过的东西. 所以召回那边按这个数把冷门种子挡掉.
         */
        val audience: Int,
    )

    val isEmpty: Boolean get() = tags.isEmpty() && seeds.isEmpty()

    /**
     * 画像的**身份串**: 它变了就说明召回的输入变了, 缓存必须作废 —— 最典型的就是**登录**
     * (0 部收藏 → 上百部), 光靠 TTL 的话用户要对着登出时算的默认组等满 12 小时
     * (2026-09-06 用户实测发现).
     *
     * 刻意**只取决策相关的部分** (收藏数 + 标签名 + 种子): 不含权重, 所以看一集导致的时间衰减
     * 漂移不会触发重算 —— 那种漂移每看一集都发生, 拿它当判据等于每次进页都重算.
     */
    val key: String
        get() = buildString {
            // **不含收藏条数**: 收藏是分页一点点进来的 (真机上 50 分钟里 3→5→16→18→23),
            // 把精确条数放进来等于每来一批就作废重算一次, 而其中大多数次决策根本没变.
            // 有没有收藏这个"质变"由 tags/seeds 空不空表达, 够了.
            tags.joinTo(this, ",") { it.name }
            append('|')
            // 也要进来: 用户给某部打了个分, tags 可能一动不动 (名字没变), 而"认可过的口味"变了,
            // 「本季你可能会喜欢」那一行的输入就变了
            likedTags.joinTo(this, ",") { it.name }
            append('|')
            // 只取前几个: 后面那些只给「换一批」轮换用, 它们的次序随打分小幅变动时不必整批重算
            seeds.take(KEY_SEEDS).joinTo(this, ",") { "${it.subjectId}${if (it.explicitlyLiked) "!" else ""}" }
        }

    override fun toString(): String =
        "InterestProfile(tags=$tags, likedTags=$likedTags, seeds=${seeds.take(KEY_SEEDS).map { it.subjectId }}" +
                "${if (seeds.size > KEY_SEEDS) " 等 ${seeds.size} 个" else ""})"

    companion object {
        val Empty = InterestProfile(emptyList(), emptyList())

        /** 最终留几个兴趣方向. 再多就没有代表性了, 而且每个方向要花一个请求. */
        const val MAX_TAGS = 6

        /**
         * [likedTags] 留几个. 比 [MAX_TAGS] 宽得多: 它不花请求 (只用来给候选打分),
         * 而口味向量越全, 打出来的分越贴合.
         */
        const val MAX_LIKED_TAGS = 12
    }
}

/**
 * 从收藏算兴趣画像. 纯函数, 没有副作用, 方便直接测.
 *
 * @param nowMillis 当前时间, 算时间衰减用.
 */
fun computeInterestProfile(
    collections: List<SubjectCollectionEntity>,
    nowMillis: Long,
): InterestProfile {
    if (collections.isEmpty()) return InterestProfile.Empty

    // 每部作品一个权重, 再累加到它的标签上. **不能按"出现次数"简单统计**: 看完并打了高分,
    // 与"想看"点了一下, 说明的东西完全不同.
    val weighted = collections.map { it to weightOf(it, nowMillis) }
    val normalized = accumulateTags(weighted, applyIdf = true, limit = InterestProfile.MAX_TAGS)

    // 另算一份"只由认可过的作品贡献"的标签, 给候选打分用 (见 InterestProfile.likedTags).
    // 用 affinityOf 而不是 weightOf: 那才是"有正面表态"这件事, 且不带时间衰减.
    val likedTags = accumulateTags(
        collections.mapNotNull { entity -> affinityOf(entity)?.let { entity to it } },
        applyIdf = false,
        limit = InterestProfile.MAX_LIKED_TAGS,
        // **不收 Source 类** ("漫画改"/"原创"/"小说改"): 那是**制作来源**, 不是口味. 一半以上的
        // 动画都是漫画改, 它必然累计到最高分 (2026-09-07 真机: likedTags 第一名就是 漫画改=1.0),
        // 于是"这部像不像你喜欢的"实际上在问"这部是不是漫画改" —— 本季 60 条里 59 条都对得上,
        // 筛选等于没做. 拿去**搜**的那份 (tags) 留着它: 它至少能当个有效的搜索词.
        excludeKinds = setOf(CanonicalTagKind.Source),
    )

    // 种子**不用上面那个权重**: 那个回答的是"你的口味是什么"(在看/想看都算数, 且越近越重),
    // 而种子回答的是"能点名说你喜欢的是哪部" —— 得有明确表态, 而且三年前打 10 分的依然算.
    val seeds = collections.asSequence()
        .mapNotNull { entity -> affinityOf(entity)?.let { entity to it } }
        .sortedWith(
            // 喜爱度优先; 同档才看谁更近 (不做衰减, 否则老作品永远进不了种子)
            compareByDescending<Pair<SubjectCollectionEntity, Double>> { it.second }
                .thenByDescending { it.first.lastUpdated },
        )
        .take(maxOf(MIN_SEEDS, collections.size / 2))
        .map { (entity, affinity) ->
            InterestProfile.Seed(
                entity.subjectId,
                entity.nameCn.ifEmpty { entity.name },
                explicitlyLiked = affinity >= EXPLICIT_LIKE_AFFINITY,
                audience = entity.ratingInfo.total,
            )
        }
        .toList()

    return InterestProfile(normalized, seeds, likedTags, collectionCount = collections.size)
}

/**
 * 把一批"作品 + 权重"累加成标签权重: 降序取前 [limit] 个, 再按最大值归一化到 (0, 1].
 *
 * @param applyIdf 要不要按"出现在几部作品里"打折. 见 [InterestProfile.tags] 与
 *   [InterestProfile.likedTags] 的分工 —— 拿去搜的那份要打, 拿去打分的那份不能打.
 */
private fun accumulateTags(
    weighted: List<Pair<SubjectCollectionEntity, Double>>,
    applyIdf: Boolean,
    limit: Int,
    excludeKinds: Set<CanonicalTagKind> = emptySet(),
): List<InterestProfile.WeightedTag> {
    // tag -> 累计权重; tag -> 出现在几部作品里 (算 IDF 用)
    val scores = HashMap<String, Double>()
    val documentFrequency = HashMap<String, Int>()

    for ((entity, weight) in weighted) {
        if (weight == 0.0) continue
        val tags = entity.tags
        val maxCount = tags.maxOfOrNull { it.count } ?: continue
        if (maxCount <= 0) continue

        for (tag in tags) {
            if (!isInterestTag(tag.name)) continue
            if (excludeKinds.isNotEmpty() && CanonicalTagKind.matchOrNull(tag.name) in excludeKinds) continue
            // 条目内可信度: 只有极少数用户打的标签不作数
            val confidence = tag.count.toDouble() / maxCount
            if (confidence < MIN_TAG_CONFIDENCE) continue

            scores[tag.name] = (scores[tag.name] ?: 0.0) + weight * confidence
            documentFrequency[tag.name] = (documentFrequency[tag.name] ?: 0) + 1
        }
    }

    // IDF: 在很多作品里都出现的标签区分度低 (收藏里一半都带"奇幻", 那它说明不了什么)
    val total = weighted.size.toDouble()
    val ranked = scores.mapNotNull { (name, score) ->
        if (score <= 0.0) return@mapNotNull null
        if (!applyIdf) return@mapNotNull InterestProfile.WeightedTag(name, score)
        val df = documentFrequency[name] ?: return@mapNotNull null
        InterestProfile.WeightedTag(name, score * ln(total / df + 1.0))
    }.sortedByDescending { it.weight }.take(limit)

    val maxWeight = ranked.firstOrNull()?.weight ?: 0.0
    return if (maxWeight > 0.0) {
        ranked.map { InterestProfile.WeightedTag(it.name, it.weight / maxWeight) }
    } else {
        emptyList()
    }
}

/**
 * 一部作品对口味的说明力.
 *
 * 越近期的行为权重越高 (半衰期 [HALF_LIFE_DAYS] 天): 三年前追的番不该和上周看完的一样重.
 * 负分是"明确不喜欢", 会把对应标签往下压.
 *
 * **没有把追番进度算进去**: 那要连 `episode_collection` 一起查, 而"在看"本身已经是够强的信号了.
 */
private fun weightOf(entity: SubjectCollectionEntity, nowMillis: Long): Double {
    val base = when (entity.collectionType) {
        UnifiedCollectionType.DONE -> when (entity.selfRatingInfo.score) {
            0 -> 1.5 // 看完了没评分: 中等正反馈
            in 8..10 -> 3.0
            in 6..7 -> 1.0
            else -> -2.0 // 看完了给低分 = 明确不喜欢
        }

        UnifiedCollectionType.DOING -> 2.0
        UnifiedCollectionType.WISH -> 0.6
        UnifiedCollectionType.ON_HOLD -> -0.5
        UnifiedCollectionType.DROPPED -> -2.0
        UnifiedCollectionType.NOT_COLLECTED -> return 0.0
    }
    // lastUpdated 为 0 = 没拿到收藏时间 (p1 的 interest 可能缺 updatedAt). 当成"刚刚"而不是
    // 1970 年 —— 否则衰减按两万天算, 权重被压成 1e-33, **整个画像静默变空**, 而日志上只看得到
    // "tags=[] seeds=[]", 完全不知道是时间戳的问题.
    if (entity.lastUpdated <= 0L) return base
    val days = ((nowMillis - entity.lastUpdated).coerceAtLeast(0L)).toDouble() / MILLIS_PER_DAY
    return base * 0.5.pow(days / HALF_LIFE_DAYS)
}

/**
 * 这部作品能不能当"你喜欢的那部", 以及有多够格; `null` = 不够格.
 *
 * 与 [weightOf] 刻意分开:
 * - **不看时间衰减** —— 三年前打 10 分的作品依然是个有效的锚点, 而衰减会让种子永远只剩"最近
 *   动过的那几部"。
 * - **在看且没打分不算** —— 那只说明"正在追", 追一半弃掉的多了去了, 拿它说"你喜欢"是硬套
 *   (2026-09-06 用户实测就是被这条挑中的)。
 * - **看完给低分不算**, 那是明确的不喜欢。
 */
private fun affinityOf(entity: SubjectCollectionEntity): Double? {
    val score = entity.selfRatingInfo.score
    return when {
        // 明确表态: 自己打了 6 分以上 (在看时也能打分, 一样算)
        score >= 8 -> 3.0
        score in 6..7 -> 2.0
        score in 1..5 -> null // 打了低分 = 明确不喜欢
        // 没打分: 只有"看完了"算隐含认可, 在看/想看/搁置/抛弃都不算
        entity.collectionType == UnifiedCollectionType.DONE -> 1.0
        else -> null
    }
}

/** 到这一档才算"明确表态", 界面上才配说"因为你喜欢". */
private const val EXPLICIT_LIKE_AFFINITY = 2.0

/**
 * 这个标签算不算"兴趣方向"; 见 [MEANINGFUL_KINDS].
 *
 * 画像与"给候选打分"**必须用同一套词汇**: 打分时候选那边不过滤的话, 分母里会混进
 * "2026年7月""TV""京都动画"这类根本不可能匹配上的标签, 于是标签越多的热门长番分越低 —— 一个
 * 与口味无关的量在决定排序.
 */
internal fun isInterestTag(name: String): Boolean =
    CanonicalTagKind.matchOrNull(name) in MEANINGFUL_KINDS

/**
 * 哪几类标签算"兴趣方向".
 *
 * 丢掉的那几类要么**过于宽泛**, 要么**不是口味**. 2026-09-07 拿排行榜前 500 条量过基础出现率,
 * 这几类的取舍都有数:
 * - `Category`: "TV" **64.6%**、"剧场版" 25.0% —— 搜出来和没筛一样;
 * - `Region`: "日本" 几乎每部都有;
 * - `Rating`/`Technology`: 不是口味;
 * - `Series` (高达/Fate): 是"同一个系列", 拿去搜只会推同系列续作 —— 那叫相关条目, 不叫猜你喜欢.
 *
 * `Source` (漫画改 44.6% / 原创 29.0%) 是**制作来源不是口味**, 所以它只留给"拿去搜"的那份
 * ([InterestProfile.tags], 至少是个有效搜索词), 打分那份 ([InterestProfile.likedTags]) 排掉它
 * —— 见 computeInterestProfile 里的 `excludeKinds`.
 *
 * `Character` (傲娇/群像/兽耳/吸血鬼…) **2026-09-07 加进来**: 它比"战斗""剧情"这种宽泛 Genre
 * 说明力强得多, 是实打实的口味。**代价是它窄**, 拿去搜召回少一个数量级 (实测 rank>=1 且上千人
 * 评分的: 群像 74 / 傲娇 37 / 女仆 14 / 制服 1, 而"校园"是 1000+), 而 IDF 又偏爱罕见标签 ——
 * 所以召回那边必须有兜底, 见 `RecommendationRepository` 里"标签池太薄就拿高分榜垫"那一段.
 */
private val MEANINGFUL_KINDS = setOf(
    CanonicalTagKind.Genre,
    CanonicalTagKind.Emotion,
    CanonicalTagKind.Setting,
    CanonicalTagKind.Audience,
    CanonicalTagKind.Character,
    CanonicalTagKind.Source,
)

/** 标签票数低于条目内最高票数的这个比例就不作数. */
private const val MIN_TAG_CONFIDENCE = 0.15

private const val HALF_LIFE_DAYS = 180.0
private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000.0

/**
 * 种子至少备几个; 实际备收藏数的一半 (见 [computeInterestProfile]), 取两者大的.
 *
 * 比"最多出几行"宽得多, 因为**种子经常是废的**: 新番条目在 bangumi 上根本没有 `/recs`
 * (真机上高 id 种子一条不出), 太冷门的种子给出的共看数据也全是冷门作品 —— 只备 3 个的话,
 * 废掉两个就只剩一行了. 请求数由召回那边的 `MAX_SEED_REQUESTS` 封顶, 备得多不花钱.
 *
 * 按收藏数的一半备, 是给「换一批」用的: 每按一次都要换一组种子、一轮用完才重复 —— 收藏几百部的人,
 * 能当理由的作品远不止七八部, 只备那么几个的话两三次就轮回来了 (用户定的比例).
 */
private const val MIN_SEEDS = 8

/** 画像身份串 ([InterestProfile.key]) 里取前几个种子. */
private const val KEY_SEEDS = 8
