/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import kotlinx.io.Buffer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.him188.ani.utils.io.DigestAlgorithm
import me.him188.ani.utils.io.readAndDigest
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.xml.Element

/**
 * 站点在搜索结果里给出的 Bangumi 条目编号. 不用配置, 认得出就用 ([pagesToOpenBySiteBangumiId]).
 *
 * 认两种写法:
 * - **字段**: 条目 JSON 第一层的 `bangumiId` / `bangumi_id` / `bgmId` / `bgm_id` (不分大小写), 值是数字或数字字符串;
 * - **封面路径**: Bangumi 图床的路径 `/pic/cover/<尺寸>/<两位>/<两位>/<编号>_<随机串>.<扩展名>`, 两层目录是编号的 MD5 前四位.
 *   照搬 Bangumi 封面的站点 (稀饭、AniWatch, 及用稀饭图床的 Moonci) 留着这套路径; 目录与编号的 MD5 对不上的不算, 别的图片地址不会被认成编号.
 *
 * 条目自己的字段、封面才算: 只看 JSON 第一层 (嵌套的常是关联作品), 一个条目里认出两个不同编号时不认.
 */
object SiteBangumiIds {
    private val FIELD_NAMES = setOf("bangumiid", "bangumi_id", "bgmid", "bgm_id")

    private val COVER_PATH = Regex("""/pic/cover/[a-z]/([0-9a-f]{2})/([0-9a-f]{2})/(\d+)_[A-Za-z0-9]+\.(?:jpe?g|png|webp|gif)""", RegexOption.IGNORE_CASE)

    /** HTML 条目卡片上可能放封面地址的属性 (懒加载常用 `data-original` / `data-src`, 背景图在 `style`). */
    private val IMAGE_ATTRS = listOf("src", "data-src", "data-original", "data-lazy-src", "data-bg", "data-background", "srcset", "style")

    /** JSON 条目 [item] 给的编号: 先看字段, 再看第一层字符串值里的封面路径. */
    fun ofJson(item: JsonElement): Int? {
        val obj = item as? JsonObject ?: return null
        val fields = obj.entries.filter { it.key.lowercase() in FIELD_NAMES }.mapNotNull { (it.value as? JsonPrimitive)?.positiveIntOrNull() }.distinct()
        if (fields.isNotEmpty()) return fields.singleOrNull()
        return ofText(obj.values.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }.joinToString(" "))
    }

    /** HTML 条目卡片 [card] (含自身的属性) 里的封面路径给的编号. */
    fun ofHtml(card: Element): Int? = ofText(
        buildString {
            card.html(this)
            for (name in IMAGE_ATTRS) append(' ').append(card.attr(name))
        },
    )

    /** [text] 里 Bangumi 封面路径给的编号; 没有或有几个不同的时为 `null`. */
    fun ofText(text: String): Int? {
        if ("/pic/cover/" !in text) return null
        return COVER_PATH.findAll(text).mapNotNull { coverId(it) }.distinct().toList().singleOrNull()
    }

    private fun coverId(match: MatchResult): Int? {
        val id = match.groupValues[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val md5 = md5Hex(id.toString())
        val dirs = (match.groupValues[1] + match.groupValues[2]).lowercase()
        return id.takeIf { md5.startsWith(dirs) }
    }

    private fun JsonPrimitive.positiveIntOrNull(): Int? =
        if (this is JsonNull) null else content.trim().toIntOrNull()?.takeIf { it > 0 }

    private fun md5Hex(text: String): String {
        val buffer = Buffer().apply { write(text.encodeToByteArray()) }
        return buffer.readAndDigest(DigestAlgorithm.MD5).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    internal val logger = logger<SiteBangumiIds>()
}

/**
 * 自动匹配要打开的站点条目, 先按站点给的 Bangumi 编号 ([SiteBangumiIds]) 认, 再按名字 ([SubjectSearchPlan.pagesToOpen]):
 * - 编号是这一部 ([subjectId]) 的是这一部, 排在最前, 名字认不认得出都开. 条目名不是这一部的名字时换成这一部的名字 ([nameForSiteConfirmed]),
 *   选择器按资源的条目名再认一次时才算精确; 几个条目标着同一个编号 (正片与特别篇) 时都开, 没有这一集的那页自然没有资源.
 * - 编号是别的条目的不是, 不开: 站点给同系列各季起的名字、别名常常分不清 (只差一个问号; 第二季的别名是第一季的名字), 编号分得清.
 * - 没给编号的照旧按名字认.
 *
 * [subjectId] 为 `null` (请求没有 Bangumi 条目) 时只按名字认.
 */
fun <T> SubjectSearchPlan.pagesToOpenBySiteBangumiId(
    pages: List<T>,
    subjectId: Int?,
    preferShorterName: Boolean,
    siteBangumiIdOf: (T) -> Int?,
    nameOf: (T) -> String,
    withName: (T, String) -> T,
): List<T> {
    if (subjectId == null) return pagesToOpen(pages, preferShorterName, nameOf)
    val byId = pages.groupBy { page ->
        when (siteBangumiIdOf(page)) {
            null -> SiteIdMatch.NONE
            subjectId -> SiteIdMatch.OWN
            else -> SiteIdMatch.OTHER
        }
    }
    val own = byId[SiteIdMatch.OWN].orEmpty().map { page ->
        val name = nameOf(page)
        val judged = nameForSiteConfirmed(name)
        if (judged == name) page else withName(page, judged)
    }
    val other = byId[SiteIdMatch.OTHER].orEmpty()
    if (own.isNotEmpty() || other.isNotEmpty()) {
        SiteBangumiIds.logger.info {
            "Site Bangumi ids for $subjectId: ${own.size} pages are this subject ${own.map(nameOf)}, " +
                "${other.size} are other subjects ${other.take(5).map { "${nameOf(it)}=${siteBangumiIdOf(it)}" }}"
        }
    }
    return own + pagesToOpen(byId[SiteIdMatch.NONE].orEmpty(), preferShorterName, nameOf)
}

private enum class SiteIdMatch { OWN, OTHER, NONE }

/**
 * 站点用 Bangumi 编号认定是这一部的条目, 资源与搜索缓存里用的条目名: 页名本来就是这一部的名字 (选择器算精确、[SubjectSearchPlan.identify] 也认) 时照用,
 * 否则换成这一部的名字里 [SubjectSearchPlan.identify] 认得出的第一个 (都认不出时用第一个).
 */
internal fun SubjectSearchPlan.nameForSiteConfirmed(pageName: String): String {
    if (isOwnName(pageName) && identify(pageName) == SubjectMatch.OWN) return pageName
    return ownNames.firstOrNull { identify(it) == SubjectMatch.OWN } ?: ownNames.firstOrNull() ?: pageName
}
