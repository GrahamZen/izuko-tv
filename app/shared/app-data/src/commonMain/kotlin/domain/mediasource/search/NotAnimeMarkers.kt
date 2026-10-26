/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import me.him188.ani.app.domain.mediasource.clouddrive.DriveNameParser

/**
 * 名字写明不是动画: 真人作品、短剧. 网盘 (`DriveNameParser.workKindsOf`) 与网页类数据源 ([SelectorNameRules]) 共用;
 * 条目自己的名字也写着的 (真人版条目) 由用的地方放过.
 *
 * 「真人版」「実写版」这类版本词在名字里哪儿都算. 「日剧」「日影」「2024电影」这类分类词只认括号里的标签
 * (「【2019日影】辉夜大小姐想让我告白」「【23年日剧】重启人生」): 标题本身也可能有这些字 (《电影少女》).
 */
internal object NotAnimeMarkers {
    fun isLiveAction(name: String): Boolean =
        LIVE_ACTION_VERSION.containsMatchIn(name) || TAG.findAll(name).any { LIVE_ACTION_CATEGORY.containsMatchIn(it.groupValues[1]) }

    fun isShortDrama(name: String): Boolean = SHORT_DRAMA.containsMatchIn(name)

    /** 真人作品或短剧 */
    fun isNotAnime(name: String): Boolean = isLiveAction(name) || isShortDrama(name)

    private val LIVE_ACTION_VERSION =
        Regex("""真人版|真人电影|真人電影|真人剧|真人劇|实写版|實寫版|実写版|実写映画|live[ ._-]?action""", RegexOption.IGNORE_CASE)

    /** 括号里的一段标签 */
    private val TAG = Regex("""[【\[（(《]([^【】\[\]（）()《》]{1,20})[】\]）)》]""")

    /** 标签里写的分类: 日剧、日影、某年的电影 (动画电影写「剧场版」「映画」) */
    private val LIVE_ACTION_CATEGORY = Regex("""日剧|日劇|日影|(?:19|20)\d\d\s*年?\s*[电電]影""")

    private val SHORT_DRAMA = Regex("""短剧|短劇""")
}
