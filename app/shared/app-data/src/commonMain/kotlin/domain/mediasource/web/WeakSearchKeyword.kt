/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

/**
 * 归一化后的回退关键词只剩一个很短的英文 / 数字词时不拿去搜: 「To LOVEる -とらぶる-」取首词是「To」,
 * 「NEW GAME!」是「NEW」. 站点按子串搜, 这种词能命中一大片无关的番, 每个还要打开条目页取剧集.
 * 主关键词不经过这里.
 */
internal fun isWeakSearchKeyword(keyword: String): Boolean =
    keyword.length <= WEAK_KEYWORD_MAX_LENGTH && keyword.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

private const val WEAK_KEYWORD_MAX_LENGTH = 3
