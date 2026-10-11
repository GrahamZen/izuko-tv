/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.subscription

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscriptionUpdater.Diff

/**
 * 订阅更新时认出改了名的数据源. 更新按名字对应新旧, 改了名的源会被当成删掉一个、新加一个, 用户的启用 / 停用与顺序跟着丢 ——
 * 同一类型 (factoryId)、同一站点 (配置里第一个网址的域名) 的源, 一边只少了一个、一边只多了一个时当作改名,
 * 原地更新配置 (实例、启停、位置都不变). 同一站点同时多个改名的分不清, 照旧删了重建.
 */
internal object SubscriptionRenames {
    fun pairRenames(diff: Diff): Diff {
        if (diff.removed.isEmpty() || diff.added.isEmpty()) return diff
        val removedBySite = diff.removed.groupBy { siteOf(it.save.factoryId.value, it.save.config.serializedArguments) }
        val addedBySite = diff.added.groupBy { siteOf(it.factoryId.value, it.data.arguments) }
        val renamed = removedBySite.mapNotNull { (site, removed) ->
            val added = addedBySite[site]
            if (site == null || removed.size != 1 || added?.size != 1) null else removed.single() to added.single()
        }
        if (renamed.isEmpty()) return diff
        return Diff(
            removed = diff.removed - renamed.map { it.first }.toSet(),
            added = diff.added - renamed.map { it.second }.toSet(),
            changed = diff.changed + renamed,
        )
    }

    private val URL = Regex("""https?://(?:www\.)?([^/"'\s:?#]+)""")

    /**
     * 类型 + 配置里第一个网址的域名 (不看图标地址: 不同的源常用同一个图床放图标); 配置里没有网址时为 null (不配对).
     */
    internal fun siteOf(factoryId: String, arguments: JsonElement?): String? {
        val withoutIcon = (arguments as? JsonObject)?.let { JsonObject(it - "iconUrl") } ?: arguments
        val host = withoutIcon?.let { URL.find(it.toString())?.groupValues?.get(1)?.lowercase() } ?: return null
        return "$factoryId $host"
    }
}
