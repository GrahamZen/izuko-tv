/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

/**
 * 名字写明的作品类别. 条目本身不是这一类时, 写着它的名字不是条目的正片: 网盘的文件夹与文件
 * (见 [me.him188.ani.app.domain.mediasource.clouddrive.DriveSubjectMatcher.matchEpisodes]), 在线源的条目页
 * (见 [WebWorkKindCheck]) 都按它否决.
 * [inCollections]: 合集的名字 ([WorkKinds.isCollectionName]) 写着它也算. 剧场版、外传、OVA、真人版常与正片放在一个合集里; 短剧不是动画,
 * 不与动画放在一起 (分享站上的「短剧更新汇总」列着几十部短剧, 条目名常是其中一部名字的一段: 「新婚夜和夫君间谍过家家」).
 */
enum class WorkKind(val inCollections: Boolean = false) { MOVIE, SPIN_OFF, OVA, LIVE_ACTION, SHORT_DRAMA(inCollections = true) }

object WorkKinds {
    fun of(text: String): Set<WorkKind> = buildSet {
        // 分享里常在字间插竖线防屏蔽 (「海丨贼丨王丨真丨人丨版」)
        val name = OBFUSCATION.replace(text, "")
        if (MOVIE_MARKER.containsMatchIn(name)) add(WorkKind.MOVIE)
        if (SPIN_OFF_MARKER.containsMatchIn(name)) add(WorkKind.SPIN_OFF)
        if (OVA_MARKER.containsMatchIn(name)) add(WorkKind.OVA)
        if (NotAnimeMarkers.isLiveAction(name)) add(WorkKind.LIVE_ACTION)
        if (NotAnimeMarkers.isShortDrama(name)) add(WorkKind.SHORT_DRAMA)
    }

    /**
     * Bangumi 记的类型 (系列关系图、名字表的「类型」列) 对应的作品类别. 条目名字里常不写自己的类别 (「FLCL」本身是 OVA),
     * 算条目自己的类别时与名字里写的一起算.
     */
    fun ofCategory(metaTags: Collection<String>): Set<WorkKind> = buildSet {
        if ("OVA" in metaTags) add(WorkKind.OVA)
        if ("剧场版" in metaTags) add(WorkKind.MOVIE)
    }

    /** 名字写明的作品类别; 合集的名字 ([isCollectionName]) 只算合集里也不放在一起的 ([WorkKind.inCollections]). */
    fun outsideCollectionsOf(name: String): Set<WorkKind> {
        val collection = isCollectionName(name)
        return of(name).filterTo(HashSet()) { it.inCollections || !collection }
    }

    /** 去掉名字里的 OVA / OAD 标记: 条目本身是 OVA 时, 文件名里的这个标记不是「附带的番外」(「[OVA] FLCL 02」是正片) */
    fun withoutOvaMarker(name: String): String = OVA_MARKER.replace(name, " ")

    /** 合集的名字 (「TV+剧场版」「1-4季全」「XX 系列合集」): 写着剧场版也可能装着正片. */
    fun isCollectionName(name: String): Boolean = COLLECTION.containsMatchIn(name)

    private val MOVIE_MARKER = Regex("""剧场版|劇場版|电影版|電影版|映画|gekijou-?ban|(?<![A-Za-z])movie(?![A-Za-z])""", RegexOption.IGNORE_CASE)
    private val SPIN_OFF_MARKER = Regex("""外传|外傳|(?<![A-Za-z])gaiden(?![A-Za-z])""", RegexOption.IGNORE_CASE)
    private val OVA_MARKER = Regex("""(?<![A-Za-z])(?:OVA|OAD)(?![A-Za-z])""", RegexOption.IGNORE_CASE)
    private val OBFUSCATION = Regex("""(?<=\p{L})[丨｜](?=\p{L})""")
    private val COLLECTION = Regex("""合集|全集|全季|系列|[+＋&＆]|附|\d\s*[-~～至]\s*\d""")
}
