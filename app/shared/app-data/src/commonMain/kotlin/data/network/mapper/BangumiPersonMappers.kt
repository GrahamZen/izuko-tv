/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network.mapper

import me.him188.ani.app.data.models.subject.CharacterInfo
import me.him188.ani.app.data.models.subject.PersonCareer
import me.him188.ani.app.data.models.subject.PersonInfo
import me.him188.ani.app.data.models.subject.PersonType
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSlimCharacter
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSlimPerson
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubjectCharacter

/**
 * p1 的人物/角色 DTO 到领域模型的映射.
 *
 * 与 Ani 那套的两处差异 (都是多出来的, 没有丢):
 * - **头像直出**. Ani 给的是 `api.animeko.org/v2/persons/{id}/image?size=large` 这个代理地址,
 *   p1 直接给 `lain.bgm.tv` 的图.
 * - **`careers` 不再是空的**. Ani 的映射写死 `emptyList()`, p1 带 `career` 字段.
 */
/**
 * 没有图的人物/角色/条目, bangumi 自己的页面用的占位图.
 *
 * p1 对没图的对象直接不给 `images` 字段. 不能就这么留空串: 卡片会画成一个黑块.
 * Ani 那条路是 `api.animeko.org/v2/persons/{id}/image` 代理, 对没图的人物返回的正是这张
 * (实测 sha256 与 `lain.bgm.tv/img/no_icon_subject.png` 逐字节相同), 所以填它就是保持原样.
 *
 * **凡是从 bangumi 取图的映射点都要过 [orBangumiPlaceholder]**: 漏一处就是一处黑块,
 * 而漏了的那处只有"恰好碰上没图的那个条目/人物"时才看得出来 (2026-09-06 用户在人物页的
 * 「出演角色」上撞到, 那条路径有自己的一套局部映射, 没走这里).
 */
const val BANGUMI_NO_ICON_IMAGE = "https://lain.bgm.tv/img/no_icon_subject.png"

internal fun BangumiNextSlimPerson.toPersonInfo(): PersonInfo = PersonInfo(
    id = id,
    name = name,
    type = PersonType.fromId(type),
    careers = career.mapNotNull { it.toPersonCareerOrNull() },
    imageLarge = images?.large.orBangumiPlaceholder(),
    imageMedium = images?.medium.orBangumiPlaceholder(),
    // Ani 的 summary 实测基本都是空串; p1 的 info 是 "性别 男 / 生日 ... / 血型 ..." 这种一行简介
    summary = info,
    locked = lock,
    nameCn = nameCN,
)

internal fun BangumiNextSlimCharacter.toCharacterInfo(actors: List<PersonInfo>): CharacterInfo = CharacterInfo(
    id = id,
    name = name,
    nameCn = nameCN,
    actors = actors,
    imageMedium = images?.medium.orBangumiPlaceholder(),
    imageLarge = images?.large.orBangumiPlaceholder(),
)

internal fun BangumiNextSubjectCharacter.toCharacterInfo(): CharacterInfo =
    character.toCharacterInfo(casts.map { it.person.toPersonInfo() })

fun String?.orBangumiPlaceholder(): String =
    if (isNullOrBlank()) BANGUMI_NO_ICON_IMAGE else this

private fun String.toPersonCareerOrNull(): PersonCareer? = when (this) {
    "producer" -> PersonCareer.PRODUCER
    "mangaka" -> PersonCareer.MANGAKA
    "artist" -> PersonCareer.ARTIST
    "seiyu" -> PersonCareer.SEIYU
    "writer" -> PersonCareer.WRITER
    "illustrator" -> PersonCareer.ILLUSTRATOR
    "actor" -> PersonCareer.ACTOR
    else -> null
}
