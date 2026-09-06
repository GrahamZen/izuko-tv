/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.recommendation

/**
 * 把"同一部作品的不同季度"归一到同一个键, 用来在推荐里去重.
 *
 * **为什么不用 `SubjectCollectionEntity.relations.seriesMainSubjectIds`**: 那是收藏表里的字段,
 * 只有**已收藏**的条目才有 —— 而推荐候选恰恰是没收藏的, 拿不到. 给每个候选跑一次系列索引 BFS
 * 的话是每个候选一串请求, 代价太大. 所以退到名字启发式.
 *
 * **为什么不复用 TMDB 匹配那份季号正则** (`SEASON_MARKER_CJK` / `SEASON_MARKER_LATIN`):
 * 那份被锚点测试 (254 条背景图 + 233 条剧照) 钉着, 动一下就要重跑一遍全真打, 而且它服务的是
 * "削字找 TMDB 条目"这个完全不同的目的. 这里独立一份, 改这边不会牵动图片匹配。
 *
 * 只求"把明显的同一系列并到一起", 宁可漏并也不要错并 —— 错并会把不相干的作品从推荐里抹掉。
 */
internal fun seriesKeyOf(name: String): String {
    var s = name.trim()
    // 先去掉括号里的补充说明: 「(2015)」「（TV版）」
    s = s.replace(PARENTHESES, " ")
    // 季号标记, 从标记处一路截到串尾 —— 标记后面往常还挂着副标题 (「第2期 覚醒編」)
    SEASON_MARKERS.find(s)?.let { s = s.substring(0, it.range.first) }
    // 结尾的「…篇」副标题: 总集编与分章剧场版几乎都长这样
    s = s.replace(TRAILING_ARC, "")
    // 结尾的裸序号: 「XXX 2」「XXX II」「XXX Ⅲ」
    s = s.replace(TRAILING_ORDINAL, "")
    // 汉字/假名后面紧跟的结尾 S: 「小林家的龙女仆S」「某科学的超电磁炮S」
    s = s.trimEnd().replace(TRAILING_S, "$1")
    // 剩下的标点与空白一律不作数 (各站点写法不一)
    return s.replace(PUNCTUATION, "").lowercase()
}

/**
 * 名字里的**续作迹象**有多强:
 * - [SEQUEL_HINT_STRONG]: 明确的续作写法 —— 2 以上的季号、结尾 Ⅱ 以上的罗马数字、「…篇」「最终季」「Final Season」;
 * - [SEQUEL_HINT_WEAK]: 只是带个阿拉伯数字 (「超时空要塞7」「机动警察剧场版2」), 或者汉字/假名后面紧跟一个
 *   `S` (「小林家的龙女仆S」「某科学的超电磁炮S」);
 * - [SEQUEL_HINT_NONE]: 看不出来. 没有任何标记的副标题续作 (「石纪元 新世界」) 在这一档.
 *
 * 只给推荐行排序用: 还没查完前传的格子按它排, 迹象越强越靠行尾 (随后可能被换成系列里最早的一季,
 * 见 `RecommendationRepository`). 判错的代价只是位置靠后, 所以数字也算; 汉字数字不算 —— 「一拳超人」
 * 「三月的狮子」「七大罪」里的数字不是季号, 算进去会把一批正常条目挤到行尾.
 */
internal fun sequelHint(name: String): Int {
    val s = name.trim()
    SEASON_MARKERS.findAll(s).forEach { match ->
        val number = SEASON_NUMBER.find(match.value)?.value ?: return@forEach
        if ((number.toIntOrNull() ?: CJK_NUMBERS.indexOf(number).takeIf { it >= 0 }?.plus(1) ?: 0) >= 2) {
            return SEQUEL_HINT_STRONG
        }
    }
    TRAILING_ORDINAL.find(s)?.let { match ->
        if (match.value.trim().uppercase() !in setOf("I", "Ⅰ")) return SEQUEL_HINT_STRONG
    }
    if (TRAILING_ARC.containsMatchIn(s) || FINAL_SEASON.containsMatchIn(s)) return SEQUEL_HINT_STRONG
    if (DIGIT.containsMatchIn(s) || TRAILING_S.containsMatchIn(s)) return SEQUEL_HINT_WEAK
    return SEQUEL_HINT_NONE
}

internal const val SEQUEL_HINT_NONE = 0
internal const val SEQUEL_HINT_WEAK = 1
internal const val SEQUEL_HINT_STRONG = 2

private val PARENTHESES = Regex("""[(（\[【][^)）\]】]*[)）\]】]""")

/**
 * 季号标记. 三支:
 * - CJK: `第2期` / `第二季` / `第3クール` / `第2部` —— **数字与汉字数字都要**, bangumi 上两种都有
 *   (「第二季」在中文条目名里比「第2季」更常见)
 * - 拉丁: `Season 2` / `2nd Season` / `Part 2` / `Cour 2`
 * - 单字: 结尾的 `Ⅱ`~`Ⅴ` 之类
 */
private val SEASON_MARKERS = Regex(
    """第\s*[0-9０-９一二三四五六七八九十]+\s*(?:期|季|部|クール|シーズン)""" +
            """|\s(?:Season|Part|Cour)\s*[0-9]+""" +
            """|\s[0-9]+(?:st|nd|rd|th)\s*(?:Season|シーズン)""",
    RegexOption.IGNORE_CASE,
)

/**
 * 结尾的「…篇」副标题: 「天元突破红莲螺岩 **螺岩篇**」「浪客剑心 **追忆篇**」.
 *
 * 总集编与分章剧场版基本都是这个形状, 而它们与正片是同一部作品 —— 不并的话一行里能堆进
 * 同一系列的三四个条目 (2026-09-07 真机: 「换换口味」12 个里 3 个是天元突破).
 *
 * **必须要求前面有分隔空白**: 贴着写的「攻壳机动队 STAND ALONE COMPLEX 笑面男篇」削掉副标题
 * 是对的, 而没有空白的话「短篇」「长篇」这类词本身就会被当副标题削掉.
 * 长度封到 6 字以内, 免得把整个正标题吃掉.
 */
private val TRAILING_ARC = Regex("""\s+[^\s]{1,6}篇\s*$""")

/** 结尾的裸序号; 必须贴着串尾, 否则「攻壳机动队 2045」这种年份/编号会被误削. */
private val TRAILING_ORDINAL = Regex("""\s+(?:[IVX]{1,4}|[ⅠⅡⅢⅣⅤ])\s*$""", RegexOption.IGNORE_CASE)

/** 季号标记里的那个数 (阿拉伯数字或单个汉字数字). */
private val SEASON_NUMBER = Regex("""[0-9０-９]+|[一二三四五六七八九十]""")

/** 汉字数字, 下标 + 1 即数值. */
private const val CJK_NUMBERS = "一二三四五六七八九十"

/** 不带数字的「最后一季」写法. */
private val FINAL_SEASON = Regex("""最[终終](?:季|章)|Final\s+Season|The\s+Final""", RegexOption.IGNORE_CASE)

private val DIGIT = Regex("""[0-9０-９]""")

/** 汉字/假名后面紧跟的结尾 `S`: 日本动画第二季的常见写法 (とある科学の超電磁砲S). 分组 1 是那个汉字/假名. */
private val TRAILING_S = Regex("""([぀-ヿ一-鿿])S$""")

private val PUNCTUATION = Regex("""[\s:：·・~〜\-—_,，.。!！?？'"“”'']+""")
