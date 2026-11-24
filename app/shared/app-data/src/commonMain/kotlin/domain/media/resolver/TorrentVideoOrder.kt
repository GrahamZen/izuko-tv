/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.resolver

/**
 * [TorrentMediaResolver.selectVideoFileEntry] 挑一集的文件时看的先后: 名字带黑名单词的往后放 (越靠前的词越不往后), 同一档里大的在前.
 *
 * BD 合集里除了正片还有光盘菜单、预告、特典, 名字里也常带着集号 (「[menu][D1][01]」是第 1 张盘的第 1 个菜单), 解析出来与正片同一集;
 * 种子里它们又常排在正片前面, 只取第一个对上集号的, 第 1 集就选成了 10 MB 的菜单. 这些附带的视频 ([EXTRA_WORDS]) 排在原有黑名单词之后;
 * 名单里没有的附带视频靠大小兜底: 正片比菜单、预告大几十倍.
 */
internal object TorrentVideoOrder {
    /** BD 附带的视频: 菜单、预告、特典. 放在黑名单最后 (最不优先) */
    val EXTRA_WORDS: List<Regex> = listOf(
        Regex("""(?<![A-Za-z])menu(?![A-Za-z])""", RegexOption.IGNORE_CASE),
        Regex("""(?<![A-Za-z])(?:preview|trailer|teaser)(?![A-Za-z])""", RegexOption.IGNORE_CASE),
        Regex("""予告|特典"""),
        Regex("""(?<![A-Za-z])(?:extras?|bonus)(?![A-Za-z])""", RegexOption.IGNORE_CASE),
    )

    /**
     * 把 [videos] 排成挑的先后: 不含黑名单词 [blacklist] 的最前, 含的照词在 [blacklist] 里的先后; 同一档按 [getSize] 大的在前
     * (没给大小时照原来的先后).
     */
    fun <T> sort(videos: MutableList<T>, blacklist: Collection<Regex>, getPath: T.() -> String, getSize: (T.() -> Long)?) {
        val words = blacklist.toList()
        val rank = videos.associateWith { video ->
            val path = video.getPath()
            val index = words.indexOfFirst { path.contains(it) }
            if (index < 0) 1 else -index
        }
        val size = if (getSize == null) emptyMap() else videos.associateWith { it.getSize() }
        videos.sortWith(compareByDescending<T> { rank.getValue(it) }.thenByDescending { size[it] ?: 0L })
    }
}
