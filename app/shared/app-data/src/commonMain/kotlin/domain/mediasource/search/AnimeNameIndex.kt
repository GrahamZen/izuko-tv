/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.app.data.network.SeriesNode
import me.him188.ani.app.domain.mediasource.toSimplifiedChinese
import kotlin.concurrent.Volatile

/**
 * 全部动画的名字 (原名、中文名、别名, 见 [SeriesGraphTable.namesOf]) 到条目的索引: 认一段名字 ([TitleParts]) 写的是不是认得的某部作品.
 *
 * 只存「名字哈希 → 行号」两列整数 (约 11 万个名字, 不到 1 MB), 查到哈希再回表里拆那一行核对名字, 不把名字都拆成字符串常驻.
 * 键用 [key] (小写、只留字母数字), 不做繁转简: Android 上繁转简一次要几十微秒, 十多万个名字建一次要好几秒;
 * 查的时候原样与转成简体各查一次 ([worksNamed]), Bangumi 的中文名本来是简体.
 */
internal class AnimeNameIndex private constructor(
    private val table: SeriesGraphTable,
    /** 高 32 位名字哈希, 低 32 位条目 id; 升序 */
    private val entries: LongArray,
) {
    /** 名字归一化后等于 [name] (原样或转成简体后) 的作品. */
    fun worksNamed(name: String): List<SeriesNode> {
        val keys = linkedSetOf(key(name), key(name.toSimplifiedChinese())).filter { it.isNotEmpty() }
        val found = LinkedHashSet<Int>()
        for (k in keys) {
            val hash = k.hashCode().toLong() shl 32
            var i = lowerBound(hash)
            while (i < entries.size && entries[i] and HIGH == hash) {
                val id = (entries[i] and LOW).toInt()
                if (table.namesOf(id).any { key(it) == k }) found += id
                i++
            }
        }
        return found.mapNotNull(table::nodeOf)
    }

    private fun lowerBound(hash: Long): Int {
        var lo = 0
        var hi = entries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid] < hash) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        private const val HIGH = -0x100000000L
        private const val LOW = 0xFFFFFFFFL

        /** 索引用的比较写法: 小写, 只留字母数字 (同 [TitleForms.compact], 但不繁转简) */
        fun key(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

        @Volatile
        private var last: AnimeNameIndex? = null

        /** [table] 的索引; 同一张表只建一次 (记最近一张). */
        fun of(table: SeriesGraphTable): AnimeNameIndex =
            last?.takeIf { it.table === table } ?: build(table).also { last = it }

        private fun build(table: SeriesGraphTable): AnimeNameIndex {
            var entries = LongArray(table.size * 4)
            var count = 0
            table.forEachNames { id, names ->
                for (name in names) {
                    val k = key(name)
                    if (k.isEmpty()) continue
                    if (count == entries.size) entries = entries.copyOf(entries.size * 2)
                    entries[count++] = (k.hashCode().toLong() shl 32) or (id.toLong() and LOW)
                }
            }
            entries = entries.copyOf(count)
            entries.sort()
            return AnimeNameIndex(table, entries)
        }
    }
}
