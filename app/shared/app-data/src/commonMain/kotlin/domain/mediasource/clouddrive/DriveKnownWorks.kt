/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.app.domain.mediasource.search.AnimeNameIndex
import me.him188.ani.app.domain.mediasource.search.TitleForms
import me.him188.ani.app.domain.mediasource.search.TitleParts

/**
 * 认得的别的作品 (全部动画的名字表, [AnimeNameIndex]): 一段名字拆出的作品名 ([TitleParts]) 正是另一部动画的名字时, 那一段写的是那一部
 * (合集里的「你的名字」之于言叶之庭、「一杆青空」之于 AIR). 系列关系图只认得同系列的作品, 系列以外的靠它.
 * 本条目自己的名字、本条目名字里含着的 (系列名)、本条目或同系列的作品也叫这个名字的 (表里的别名、繁体写法) 不算: 同系列的作品按系列关系图认
 * ([DriveSubjectIdentity]); 太短的 ([TitleForms.isDistinctive]) 不查: 拆不干净时会碰上别的番的短名字.
 *
 * @param ownNames 本条目的全部名字 (含太短、不拿来认名字的), 已归一化
 * @param seriesIds 本条目与同系列的作品
 */
internal class DriveKnownWorks(
    private val index: AnimeNameIndex,
    private val seriesIds: Set<Int>,
    private val ownNames: Set<String>,
) {
    /** [segment] 写着的作品名里别的动画的名字, 已归一化 */
    fun otherWorkNamesIn(segment: String): List<String> = TitleParts.namesIn(segment).filter { name ->
        TitleForms.isDistinctive(name) && ownNames.none { name in it } &&
                index.worksNamed(name).let { works -> works.isNotEmpty() && works.none { it.id in seriesIds } }
    }
}
