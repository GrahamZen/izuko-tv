/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.datasources.api.EpisodeType

/**
 * 对应表里「每一集对应 TMDB 第几季第几集」的编码 (bangumi-tmdb-map 对应表的 episodes 列): 空格分隔的若干段.
 *
 * - `S3E1`: 本篇 (MainStory, 集号是整数或 .5 的那些) 按集号从小到大, 第 k 集 (k 从 0 起) 对第 3 季第 1+k 集.
 *   最常见的一对一接续, 只写起点; 表生成之后才新增的集也照此往下接.
 * - `1-12:S3E1` / `7:S3E8`: 本篇集号 1..12 (逐个 +1) 对 S3E1..E12; 单集只写一个集号.
 * - `SP1-2:S0E5` / `SP12.1:S0E7`: 其他类型带前缀 (SP / OP / ED / PV / MAD, 没有类型的写 O), 写法同上.
 *
 * 集号写法: 能解析成数的写成 `12` / `12.5`; Bangumi 夹在两集之间的特别篇常用 `12.1` 这种, 照存储时的原文写.
 * 区间只用于整数集号. 没写到的集 = 离线对集时没对上, 不出图.
 * 编码由 bangumi-tmdb-map 的匹配器按本应用的 [matchToEpisodes] 对出结果后反推, 两边逐集核对过一致.
 */
internal class TmdbEpisodeMap private constructor(
    /** 本篇一对一接续的起点 (季, 集), 即 `S3E1` 那种写法 */
    private val mainContinuation: Pair<Int, Int>?,
    /** (类型前缀, 集号写法) → (季, 集) */
    private val explicit: Map<Pair<String, String>, Pair<Int, Int>>,
) {
    /** 要取的季 */
    val seasons: Set<Int> = buildSet {
        mainContinuation?.let { add(it.first) }
        explicit.values.forEach { add(it.first) }
    }

    /** 分集 id → (季, 集); 没写到的集不在结果里. */
    fun resolve(episodes: List<EpisodeCollectionInfo>): Map<Int, Pair<Int, Int>> {
        val result = mutableMapOf<Int, Pair<Int, Int>>()
        mainContinuation?.let { (season, first) ->
            episodes.map { it.episodeInfo }
                .filter { it.type == EpisodeType.MainStory && it.sort.number != null }
                .sortedBy { it.sort.number }
                .forEachIndexed { k, info -> result[info.episodeId] = season to first + k }
        }
        for (episode in episodes) {
            val key = keyOf(episode.episodeInfo) ?: continue
            explicit[key]?.let { result[episode.episodeInfo.episodeId] = it }
        }
        return result
    }

    companion object {
        private val CONTINUATION = Regex("""^S(\d+)E(\d+)$""")
        private val SEGMENT = Regex("""^([A-Z]*)(\d+(?:\.\d+)?)(?:-(\d+))?:S(\d+)E(\d+)$""")
        private val NUMBER_TEXT = Regex("""^\d+(?:\.\d+)?$""")
        private val PREFIXES = setOf("", "SP", "OP", "ED", "PV", "MAD", "O")

        /** 解析编码; 认不出来返回 null (调用方照旧全量索引、自己对集). */
        fun parse(spec: String): TmdbEpisodeMap? {
            var continuation: Pair<Int, Int>? = null
            val explicit = mutableMapOf<Pair<String, String>, Pair<Int, Int>>()
            for (token in spec.trim().split(' ').filter { it.isNotEmpty() }) {
                val c = CONTINUATION.matchEntire(token)
                if (c != null) {
                    if (continuation != null) return null
                    continuation = c.groupValues[1].toInt() to c.groupValues[2].toInt()
                    continue
                }
                val m = SEGMENT.matchEntire(token) ?: return null
                val prefix = m.groupValues[1].takeIf { it in PREFIXES } ?: return null
                val from = m.groupValues[2]
                val to = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
                val season = m.groupValues[4].toInt()
                val firstEpisode = m.groupValues[5].toInt()
                if (to == null) {
                    explicit[prefix to from] = season to firstEpisode
                } else {
                    val start = from.toIntOrNull() ?: return null
                    if (to < start) return null
                    for (offset in 0..(to - start)) {
                        explicit[prefix to (start + offset).toString()] = season to firstEpisode + offset
                    }
                }
            }
            if (continuation == null && explicit.isEmpty()) return null
            return TmdbEpisodeMap(continuation, explicit)
        }

        /** 分集在编码里的键: (类型前缀, 集号写法); 集号写不成数的分集没法编码. */
        fun keyOf(info: EpisodeInfo): Pair<String, String>? {
            val text = info.sort.number?.let { numberText(it) } ?: info.sort.toString()
            if (!NUMBER_TEXT.matches(text)) return null
            return typePrefix(info.type) to text
        }

        /** `12` / `12.5`: 整数不带小数点. */
        fun numberText(number: Float): String =
            if (number == number.toInt().toFloat()) number.toInt().toString() else number.toString()

        fun typePrefix(type: EpisodeType?): String = when (type) {
            EpisodeType.MainStory -> ""
            EpisodeType.SP -> "SP"
            EpisodeType.OP -> "OP"
            EpisodeType.ED -> "ED"
            EpisodeType.PV -> "PV"
            EpisodeType.MAD -> "MAD"
            EpisodeType.OVA, EpisodeType.OAD, null -> "O"
        }
    }
}
