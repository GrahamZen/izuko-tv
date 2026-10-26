/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.app.domain.mediasource.clouddrive.DrivePathAttribution.Attribution
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest

/**
 * 认过部 ([DrivePathAttribution]) 的视频是这一部的哪一集 (见 [DriveSubjectMatcher.matchEpisodes]). 按下面的顺序, 第一种对得上的证据说了算:
 * 1. 对应表 ([TmdbEpisodeNumbering]): 认出了季与集 (按 TMDB 整理的 `S02E05`, 或「Season 1」文件夹里的 `12.mkv`), 表里写着它是哪一集就是哪一集.
 *    TMDB 把后来的作品单列成一部时 ([DriveSubjectSeason.tmdbOwnShow]) 只在路径点名了这一部时查表.
 * 2. 拆分季按几段共有的那一季编的集号 ([splitSeasonEpisode]): 对应表把整部算一季接着排时 (Re:Zero 的 S1E26 起) 靠它认按季写的 `S02E05`;
 *    路径点名了这一段、文件不写季的, 集号超出本段自己的编号时也按整季编号认 (「进击的巨人最终季 Part.2 / … - 26」).
 * 3. 系列里后来的作品 ([DriveSubjectIdentity.laterWork]) 路径说不清是不是这一部时, 只认这一部的跨部连续集号 ([continuesSeries]):
 *    整个系列的合集里各部都从 01 编起, 分不清的不要.
 * 4. 季的规则 (路径点名了这一部、系列的第一部、用户指定的文件夹), 条目的季见 [DriveSubjectSeason]:
 *    - 认出了季与集却不在对应表里: 按本季写的 (`S04E01`, TMDB 却把整部算一季接着排), 季正是条目的季才往下认.
 *      文件名写的季要与条目写明或推出的季相同, 条目推不出季时就不是这个条目 (天降之物f 的 `S01E01`); 文件夹给的季在推不出季时当第一季比.
 *    - 推不出季 (第一季或只有一季): 写明是第 2 季及以后的不要. 条目名写明是第 N 季 (N ≥ 2): 写明第 N 季的留, 写明别的季的不要,
 *      都没写的只认系列绝对集号 (否则第一季文件夹里的 `03.mp4` 会被当成第 N 季第 3 集). 推出来是第 N 季: 写明别的季的不要, 其余留.
 *    - 网盘把系列里的一段当成单独一部、从这一段起编季 (「死神 千年血战篇 -祸进谭-」分享里的 `S01E41`, TMDB 记成 S2E41):
 *      路径点名了这一部、集号又正是这一部的系列连续集号 (sort 与 ep 不同) 时, 季对不上也照集号认.
 *
 * @param identity 按路径认部时的名字与系列; 用户指定的文件夹不给 (只认对应表与季的规则, 文件夹给的季不比)
 */
internal class DriveEpisodeResolver(
    request: MediaFetchRequest,
    private val season: DriveSubjectSeason,
    private val tmdbEpisodes: Map<Pair<Int, Int>, EpisodeSort>?,
    private val identity: DriveSubjectIdentity?,
    private val trustFolders: Boolean = false,
) {
    sealed interface Resolved {
        class Episode(val sort: EpisodeSort) : Resolved
        class Dropped(val reason: String) : Resolved
    }

    private val normalEpisodes = request.episodes.filter { it.sort is EpisodeSort.Normal }.sortedBy { it.sort }
    private val ownSorts = normalEpisodes.mapTo(HashSet()) { it.sort }
    private val ownEps = normalEpisodes.mapNotNullTo(HashSet()) { it.ep }

    /** 系列连续集号与条目内序号不同的集的连续集号 */
    private val absoluteSorts = request.episodes.filter { it.ep != null && it.ep != it.sort }.mapTo(HashSet()) { it.sort }

    /**
     * 视频 [candidate] (文件名解析出 [parsed], 集号 [episode], 认部结果 [named]) 是哪一集.
     * [continuousFolder]: 所在文件夹按连续编号排 (见 [continuousFolders]).
     */
    fun resolve(
        candidate: DriveSubjectMatcher.Candidate,
        parsed: DriveNameParser.ParsedFile,
        episode: EpisodeSort,
        named: Attribution?,
        continuousFolder: Boolean,
    ): Resolved {
        val fileEpisode = (parsed.episode as? EpisodeSort.Normal)?.number?.takeIf { it % 1f == 0f }?.toInt()
        val fileSeason = parsed.season ?: candidate.folders.asReversed().firstNotNullOfOrNull { DriveNameParser.parseFolderSeason(it) }
        val inTmdb = tmdbEpisodes != null && fileSeason != null && fileEpisode != null && (!season.tmdbOwnShow || named == Attribution.OWN)
        if (inTmdb) tmdbEpisodes?.get(fileSeason to fileEpisode)?.let { return Resolved.Episode(it) }
        splitSeasonEpisode(fileSeason, fileEpisode, episode, named)?.let { return Resolved.Episode(it) }
        if (named == Attribution.UNCLEAR && identity?.laterWork == true) {
            // 特别篇 (`S00E31`) 不按连续编号认
            if (parsed.season != 0 && continuesSeries(candidate, episode, continuousFolder)) return Resolved.Episode(episode)
            return Resolved.Dropped("not named as this subject")
        }
        val continuesOwnPart = episode in absoluteSorts && named == Attribution.OWN
        if (inTmdb) {
            val expected = if (parsed.season != null) season.number else season.matchingNumber
            if (fileSeason != expected && !(trustFolders && parsed.season == null)) {
                return if (continuesOwnPart) Resolved.Episode(episode) else Resolved.Dropped("S${fileSeason}E$fileEpisode not in this subject")
            }
        }
        if (!trustFolders && !seasonMatches(fileSeason, episode)) {
            return if (continuesOwnPart) Resolved.Episode(episode) else Resolved.Dropped("season $fileSeason, wanted $season")
        }
        return Resolved.Episode(episode)
    }

    /**
     * 有只能是跨部连续编号的集号的文件夹 (按 [DriveSubjectMatcher.Candidate.folders] 认): 按连续编号排, 里面也是本条目序号的集号
     * (「我推的孩子」第二季的 12) 照连续编号认. 只在按路径认部时有用.
     */
    fun continuousFolders(parsed: Map<DriveSubjectMatcher.Candidate, DriveNameParser.ParsedFile>): Set<List<String>> {
        if (identity == null) return emptySet()
        return parsed.filter { (_, file) -> file.episode?.let { it in ownSorts && it !in ownEps } == true }.keys.mapTo(HashSet()) { it.folders }
    }

    /**
     * 集号 [episode] 是这一部的跨部连续编号 (系列连续集号: 「死神 千年血战篇 / 27.mkv」之于相克谭), 而且路径里离文件最近、提到这个系列的那一层
     * 点的是系列名 ([DriveSubjectIdentity.mentionOf]): 同一个分享里常附着集号与它重叠的作品 (「死神系列动漫 TV版 / 1-30 / 027.mkv」是
     * 2004 年 TV 版的第 27 集). 集号也是本条目序号时分不清是哪一种编号, 只在所在文件夹按连续编号排 ([continuousFolder]) 时算.
     */
    private fun continuesSeries(candidate: DriveSubjectMatcher.Candidate, episode: EpisodeSort, continuousFolder: Boolean): Boolean {
        val identity = identity ?: return false
        if (episode !in ownSorts || (episode in ownEps && !continuousFolder)) return false
        return (listOf(candidate.file.fileName) + candidate.folders.asReversed()).firstNotNullOfOrNull { identity.mentionOf(it) } == true
    }

    /**
     * 本条目是拆分季的一段, 文件按几段共有的那一季编号 (第 [seasonEpisode] 集): 落在本段 (前几段之后的
     * [me.him188.ani.app.data.models.subject.SplitSeason.Part.episodeCount] 集) 里的是本段的哪一集; 不是本段的或认不出时为 null.
     * 按整季编号的: 写的季 [fileSeason] 正是本季, 或不写季而路径点名了这一段 ([named])、集号 [episode] 又不是本段自己的编号.
     */
    private fun splitSeasonEpisode(fileSeason: Int?, seasonEpisode: Int?, episode: EpisodeSort, named: Attribution?): EpisodeSort? {
        val split = season.split ?: return null
        if (seasonEpisode == null) return null
        val seasonNumbered = if (fileSeason == null) {
            named == Attribution.OWN && episode !in ownEps && episode !in ownSorts
        } else {
            fileSeason == season.ownNumber
        }
        if (!seasonNumbered) return null
        val index = seasonEpisode - split.previousPartsEpisodeCount
        return normalEpisodes.getOrNull(index - 1)?.takeIf { index <= split.self.episodeCount }?.sort
    }

    private fun seasonMatches(fileSeason: Int?, episode: EpisodeSort): Boolean {
        val target = season.matchingNumber
        if (target == 1) return fileSeason == null || fileSeason == 1
        if (fileSeason != null) return fileSeason == target
        // 不写季的: 条目名写明了季的只认系列绝对集号, 推出来的季照留
        return !season.declared || episode in absoluteSorts
    }
}
