/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rss

import me.him188.ani.app.domain.mediasource.directapi.applyTransforms

/**
 * [name] 交给站点搜索时用的关键词: 按 [RssSearchConfig.searchKeywordTransforms] 改写. 各站搜索语法不同, 规则写在源的配置里
 * (例如有的站遇到单独成词或结尾的「-」就报错, 配一条 `regexReplace` 换成空格). 没配, 或者改写失败、改成了空串时用原名字.
 */
internal fun RssSearchConfig.searchKeywordOf(name: String): String =
    if (searchKeywordTransforms.isEmpty()) name else applyTransforms(name, searchKeywordTransforms) ?: name
