/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.maccms

import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.him188.ani.app.domain.mediasource.quark.DriveNameParser
import me.him188.ani.app.domain.mediasource.quark.QuarkSubjectMatcher
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.direct.DirectLink
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 苹果 CMS 采集接口上的一部片子 (`?ac=detail` 结果里的一项).
 *
 * @param lines 线路代号 (`vod_play_from`) 到这条线路的剧集 (`名字$地址`, 以 `#` 分隔).
 */
internal class MacCmsVod(
    val id: String,
    val name: String,
    val lines: List<Pair<String, List<MacCmsEpisode>>>,
)

internal class MacCmsEpisode(val label: String, val url: String)

/**
 * 按条目名搜苹果 CMS 采集接口, 挑出名字与季都对得上的片子, 把可以直接播放的线路逐集对到条目的剧集.
 *
 * 名字比较 (归一化后, 见 [DriveNameParser.normalize]):
 * 1. 去掉季标记后整名相同、季相同 (`葬送的芙莉莲第二季` 对 `葬送的芙莉莲 第二季`);
 * 2. 都对不上时, 去掉副标题后的主标题相同、季相同;
 * 3. 还对不上时, 片名末尾写着年份、去掉年份后相同, 且年份就是条目开播那年 (`凉宫春日的忧郁2006版`; 年份取自剧集的播出日期).
 * 名字末尾紧跟在汉字后的单个数字也当作季 (`为美好的世界献上祝福！3`). 解说、国语配音之类的版本名字多出字, 自然对不上.
 *
 * 剧集: 站点的集号先按条目各集在本季的序号 (`ep`) 对, 对不上再按条目的集号 (`sort`) 对; 只有一集的条目 (剧场版) 直接用那一集.
 */
internal class MacCmsEngine(
    private val config: MacCmsConfig,
    /** 用作线路名, 让选择器里看得出是哪个站. */
    private val sourceName: String,
    /** 请求接口; 失败时抛异常. */
    private val fetch: suspend (url: String) -> ByteArray,
) {
    suspend fun queryLinks(request: MediaFetchRequest): List<DirectLink> {
        val names = QuarkSubjectMatcher.subjectNamesOf(request)
        // 末尾的季号数字 (`为美好的世界献上祝福！3`) 站内搜不到, 搜主标题
        val keywords = QuarkSubjectMatcher.keywordsOf(names.map { KEYWORD_SEASON_DIGIT.find(it)?.groupValues?.get(1) ?: it })
            // 站点片名是中文, 日文原名搜不到东西, 放到最后
            .sortedBy { keyword -> keyword.any { it in '぀'..'ヿ' } }
            .take(config.maxKeywords.coerceAtLeast(1))
        if (keywords.isEmpty() || config.apiUrl.isBlank()) return emptyList()

        var failure: Throwable? = null
        var succeeded = false
        for (keyword in keywords) {
            val vods = try {
                parseVodList(fetch(searchUrlOf(config.apiUrl, keyword)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure = e
                continue
            }
            succeeded = true
            val matched = matchVods(names, vods, subjectYearOf(request))
            logger.info {
                "MacCMS $sourceName: 「$keyword」 ${vods.size} results, matched " +
                        matched.joinToString(prefix = "[", postfix = "]") { it.vod.name }
            }
            if (matched.isNotEmpty()) return matched.flatMap { linksOf(request, it) }
        }
        // 接口打不开时报错, 让用户看得出是这个源坏了, 而不是没有这部番
        if (!succeeded) failure?.let { throw it }
        return emptyList()
    }

    /** 对上的片子, 以及它对上的是条目的哪个名字. */
    internal class MatchedVod(val vod: MacCmsVod, val subjectName: String)

    private fun linksOf(request: MediaFetchRequest, matched: MatchedVod): List<DirectLink> {
        val vod = matched.vod
        val playable = vod.lines.filter { (_, episodes) -> episodes.isNotEmpty() && episodes.all { isMediaUrl(it.url) } }
        return playable.flatMap { (from, episodes) ->
            val channel = if (playable.size > 1) "$sourceName·$from" else sourceName
            episodes.mapNotNull { episode ->
                val sort = resolveEpisode(request, episode.label, single = episodes.size == 1) ?: return@mapNotNull null
                DirectLink(
                    url = episode.url,
                    title = "${vod.name} ${episode.label}",
                    channel = channel,
                    episodeRange = EpisodeRange.single(sort),
                    subjectName = matched.subjectName,
                    episodeName = episode.label,
                )
            }
        }
    }

    internal companion object {
        private val logger = logger<MacCmsEngine>()

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private val MEDIA_EXTENSIONS = listOf(".m3u8", ".mp4", ".mkv", ".flv", ".m4v")
        private val EPISODE_RANGE = Regex("""\d\s*[-~～]\s*\d""")
        private val CHINESE_EPISODE = Regex("""第\s*(\d{1,4}(?:\.\d)?)\s*[集话話回]""")
        private val BARE_EPISODE = Regex("""^\s*(?:EP|Ep|ep|E)?\s*(\d{1,4}(?:\.\d)?)\s*$""")
        private val TRAILING_YEAR = Regex("""((?:19|20)\d\d)版?$""")
        private val KEYWORD_SEASON_DIGIT = Regex("""^(.*[一-鿿])[\s!！?？~～]*[2-9]$""")
        private val TRAILING_SEASON_DIGIT = Regex("""[一-鿿]([2-9])$""")

        /** 搜索地址: 接口地址上已有的 `ac` 换成 `detail`, 其余参数 (如 `from=`) 保留. */
        fun searchUrlOf(apiUrl: String, keyword: String): String {
            val base = apiUrl.trim()
            val path = base.substringBefore('?')
            val query = base.substringAfter('?', "")
                .split('&')
                .filter { it.isNotBlank() && !it.startsWith("ac=") && !it.startsWith("wd=") }
            return path + "?" + (query + "ac=detail" + "wd=${keyword.encodeURLParameter()}").joinToString("&")
        }

        fun parseVodList(bytes: ByteArray): List<MacCmsVod> {
            val root = json.parseToJsonElement(bytes.decodeToString().trimStart('﻿', ' ', '\n', '\r', '\t')) as? JsonObject
                ?: return emptyList()
            val list = root["list"] as? JsonArray ?: return emptyList()
            return list.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val name = item.string("vod_name")?.trim().orEmpty()
                if (name.isEmpty()) return@mapNotNull null
                val froms = item.string("vod_play_from").orEmpty().split("$$$")
                val urls = item.string("vod_play_url").orEmpty().split("$$$")
                val lines = urls.mapIndexed { index, line ->
                    val from = froms.getOrNull(index)?.trim().orEmpty().ifEmpty { "${index + 1}" }
                    from to line.split('#').mapNotNull { parseEpisode(it) }
                }
                MacCmsVod(item.string("vod_id").orEmpty(), name, lines)
            }
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        /** `第01集$https://…/index.m3u8`; 没有名字只有地址时名字留空. */
        private fun parseEpisode(text: String): MacCmsEpisode? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val dollar = trimmed.indexOf('$')
            val (label, url) = if (dollar < 0) "" to trimmed else trimmed.substring(0, dollar).trim() to trimmed.substring(dollar + 1).trim()
            if (!url.startsWith("http", ignoreCase = true)) return null
            return MacCmsEpisode(label, url)
        }

        /** 直接交给播放器的地址; 线路里还有一种是站点自己的网页播放器 (`/play/xxx`, `/share/xxx`), 播不了. */
        fun isMediaUrl(url: String): Boolean {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return MEDIA_EXTENSIONS.any { path.endsWith(it) }
        }

        /** 条目开播那年 (最早一集的播出日期); 不知道时为 null. */
        fun subjectYearOf(request: MediaFetchRequest): Int? =
            request.episodes.filter { it.airDate.isValid }.minOfOrNull { it.airDate.year }

        fun matchVods(subjectNames: List<String>, vods: List<MacCmsVod>, subjectYear: Int? = null): List<MatchedVod> {
            val subjects = subjectNames.map { it to keysOf(it) }
            fun matchBy(key: (NameKey) -> NameKey?): List<MatchedVod> = vods.mapNotNull { vod ->
                val vodKeys = keysOf(vod.name).mapNotNull(key).toSet()
                subjects.firstOrNull { (_, keys) -> keys.mapNotNull(key).any { it in vodKeys } }
                    ?.let { (subjectName, _) -> MatchedVod(vod, subjectName) }
            }
            matchBy { it.copy(baseTitle = "") }
                .ifEmpty { matchBy { it.copy(title = it.baseTitle, baseTitle = "") } }
                .let { if (it.isNotEmpty()) return it }
            // 片名末尾带年份 (同名翻拍分年份): 年份得是条目开播那年
            subjectYear ?: return emptyList()
            return vods.mapNotNull { vod ->
                val vodKeys = keysOf(vod.name).mapNotNull { key ->
                    val year = TRAILING_YEAR.find(key.title) ?: return@mapNotNull null
                    if (year.groupValues[1].toInt() != subjectYear) return@mapNotNull null
                    key.copy(title = key.title.removeRange(year.range), baseTitle = "").takeIf { it.title.length >= 2 }
                }.toSet()
                subjects.firstOrNull { (_, keys) -> keys.any { it.copy(baseTitle = "") in vodKeys } }
                    ?.let { (subjectName, _) -> MatchedVod(vod, subjectName) }
            }
        }

        /** 用来比较的名字: 去掉季标记后归一化的全名、主标题, 以及季. */
        internal data class NameKey(val title: String, val baseTitle: String, val season: Int)

        fun keysOf(name: String): List<NameKey> {
            val explicit = DriveNameParser.parseSubjectSeason(name)
            val title = DriveNameParser.normalize(DriveNameParser.withoutSeasonMarkers(name))
            val baseTitle = DriveNameParser.normalize(DriveNameParser.baseTitle(name))
            val keys = mutableListOf(NameKey(title, baseTitle, explicit ?: 1))
            if (explicit == null) {
                TRAILING_SEASON_DIGIT.find(title)?.let { match ->
                    val withoutDigit = title.dropLast(1)
                    keys += NameKey(withoutDigit, baseTitle.removeSuffix(match.groupValues[1]), match.groupValues[1].toInt())
                }
            }
            return keys.filter { it.title.length >= 2 }
        }

        /** 站点的集名对到条目的集; 对不上为 null. */
        fun resolveEpisode(request: MediaFetchRequest, label: String, single: Boolean): EpisodeSort? {
            val number = episodeNumberOf(label)
            if (request.episodes.isEmpty()) return number
            val onlyEpisode = request.episodes.singleOrNull()?.sort
            if (onlyEpisode != null && (single || number == null)) return onlyEpisode
            number ?: return null
            request.episodes.firstOrNull { it.ep == number }?.let { return it.sort }
            return request.episodes.firstOrNull { it.sort == number }?.sort
        }

        fun episodeNumberOf(label: String): EpisodeSort? {
            if (EPISODE_RANGE.containsMatchIn(label)) return null
            CHINESE_EPISODE.find(label)?.let { return EpisodeSort(it.groupValues[1]) }
            BARE_EPISODE.find(label)?.let { return EpisodeSort(it.groupValues[1]) }
            val parsed = DriveNameParser.parseFile(label)
            return if (parsed.isExtra) null else parsed.episode
        }
    }
}
