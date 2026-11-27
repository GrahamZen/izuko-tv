/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.continuation

import me.him188.ani.app.domain.mediasource.search.TitleForms

/**
 * BT 单集发布的发布者 (数据源给的字幕组 `alliance`), 顺着找 ([TorrentLineage]) 时认的就是它.
 *
 * 标题开头没有字幕组方括号时 (如 `冰之城墙「氷の城壁」The Ramparts of Ice S02E06 1080p 日英双语-多国字幕`), 数据源把整个标题
 * (或第一个 `]` 前那段) 当字幕组, 里面带着这一集的集号, 每集都不一样. [of] 把带标记的集号 (`S02E06` 的 `06`、`EP06`、`E06`、`第06集`、
 * ` - 06`、末尾的 `[06`) 换成 [EPISODE_MARK], 同一个发布者的各集就是同一个. 不带标记的数字分不出是集号、分辨率还是年份, 不动;
 * 字幕组名里没有这些写法, 原样.
 */
internal object TorrentReleaseGroup {
    /** 换掉的集号 */
    const val EPISODE_MARK = "*"

    /** 换掉集号的发布者; 已经换过的再换不变 */
    fun of(alliance: String): String = EPISODE_NUMBERS.fold(alliance) { text, (pattern, replacement) -> pattern.replace(text, replacement) }

    /** 给人看的发布者: 换掉的集号连同标记去掉 (`S02E*` 写成 `S02`) */
    fun label(group: String): String =
        MARKED_EPISODES.fold(group) { text, (pattern, replacement) -> pattern.replace(text, replacement) }.replace(SPACES, " ").trim()

    /** 全站列表里的标题 ([compactTitle] 是 [TitleForms.compact] 过的) 是 [group] 发的: 集号两边的每一段都在标题里 */
    fun mentionedIn(group: String, compactTitle: String): Boolean {
        val parts = group.split(EPISODE_MARK).map(TitleForms::compact).filter { it.isNotEmpty() }
        return parts.isNotEmpty() && parts.all { it in compactTitle }
    }

    private val EPISODE_NUMBERS = listOf(
        Regex("""(?<![A-Za-z0-9])(S\d{1,2}\s?E)\d{1,4}(?:v\d+)?(?!\d)""", RegexOption.IGNORE_CASE) to "$1$EPISODE_MARK",
        Regex("""(?<![A-Za-z0-9])(EP?\s?)\d{1,4}(?:v\d+)?(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE) to "$1$EPISODE_MARK",
        Regex("""第\s*\d{1,4}\s*([话話集])""") to "第$EPISODE_MARK$1",
        Regex("""(\s[-–—－]\s?)\d{1,4}(?:v\d+)?(?=\s|$|[\[【(（])""") to "$1$EPISODE_MARK",
        Regex("""([\[【])\d{1,4}(?:v\d+)?$""") to "$1$EPISODE_MARK",
    )

    private val MARKED_EPISODES = listOf(
        Regex("""(S\d{1,2})\s?E\*""", RegexOption.IGNORE_CASE) to "$1",
        Regex("""(?<![A-Za-z0-9])EP?\s?\*""", RegexOption.IGNORE_CASE) to "",
        Regex("""第\*[话話集]""") to "",
        Regex("""\s[-–—－]\s?\*""") to "",
        Regex("""\s*[\[【]\*$""") to "",
    )

    private val SPACES = Regex("""\s{2,}""")
}
