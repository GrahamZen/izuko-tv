/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

/** 苹果 CMS 一条线路里的一集 (`名字$地址`); 没有名字只有地址时名字留空. */
internal class MacCmsEpisode(val label: String, val url: String)

/**
 * 苹果 CMS 片子的播放线路 (规则源的苹果 CMS 剧集步骤用): `vod_play_from` 是各线路的代号, `vod_play_url` 是各线路的剧集,
 * 线路之间都用 `$$$` 分隔, 剧集之间用 `#`.
 */
internal object MacCmsPlayLines {
    private val MEDIA_EXTENSIONS = listOf(".m3u8", ".mp4", ".mkv", ".flv", ".m4v")

    /** 各线路的代号与剧集; 代号缺了的线路按序号叫. */
    fun parse(playFrom: String, playUrl: String): List<Pair<String, List<MacCmsEpisode>>> {
        val froms = playFrom.split("$$$")
        return playUrl.split("$$$").mapIndexed { index, line ->
            val from = froms.getOrNull(index)?.trim().orEmpty().ifEmpty { "${index + 1}" }
            from to line.split('#').mapNotNull { parseEpisode(it) }
        }
    }

    /** 直接交给播放器的地址; 线路里还有一种是站点自己的网页播放器 (`/play/xxx`, `/share/xxx`), 播不了. */
    fun isMediaUrl(url: String): Boolean = pathOf(url).let { path -> MEDIA_EXTENSIONS.any { path.endsWith(it) } }

    fun isHls(url: String): Boolean = pathOf(url).endsWith(".m3u8")

    /** `第01集$https://…/index.m3u8`. */
    private fun parseEpisode(text: String): MacCmsEpisode? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val dollar = trimmed.indexOf('$')
        val (label, url) = if (dollar < 0) "" to trimmed else trimmed.substring(0, dollar).trim() to trimmed.substring(dollar + 1).trim()
        if (!url.startsWith("http", ignoreCase = true)) return null
        return MacCmsEpisode(label, url)
    }

    private fun pathOf(url: String): String = url.substringBefore('?').substringBefore('#').lowercase()
}
