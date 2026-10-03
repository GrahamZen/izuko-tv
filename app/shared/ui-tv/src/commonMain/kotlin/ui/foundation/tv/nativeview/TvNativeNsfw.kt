/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import me.him188.ani.app.ui.foundation.NsfwPolicy

/*
 * 原生海报墙的 NSFW 处理 (所有页面同一套, 见 NsfwPolicy): 卡片与 hero 背景交给原生视图之前过一遍这里 —— 设置为模糊时按条目 id 打码.
 * 各宿主 (网格页 TvNativeGridPageHost、探索页、详情页 / 人物页的横滑行) 都调它, 页面只管在卡片上填 [TvNativeCard.subjectId].
 * 设置为隐藏时的去掉在数据那头 (分页 / 列表过一遍 withoutHiddenNsfw 或 NsfwPolicy.visible), 这里不删卡: 原生行按下标绑定, 删了对不上.
 */

/** 卡片按设置打码: 带了 [TvNativeCard.subjectId] 的才认得出是哪部. */
fun TvNativeCard.withNsfw(nsfw: NsfwPolicy): TvNativeCard {
    val id = subjectId ?: return this
    return if (!obscure && nsfw.blurs(id)) copy(obscure = true) else this
}

/** 一行 / 一格的卡片按设置打码, 见 [TvNativeCard.withNsfw]. */
fun List<TvNativeCard?>.withNsfw(nsfw: NsfwPolicy): List<TvNativeCard?> = map { it?.withNsfw(nsfw) }

/** 探索页列表里各行的卡片按设置打码, 见 [TvNativeCard.withNsfw]. */
fun List<TvNativeExploreItem>.withNsfwCards(nsfw: NsfwPolicy): List<TvNativeExploreItem> = map { item ->
    if (item is TvNativeExploreItem.Row) item.copy(cards = item.cards.withNsfw(nsfw)) else item
}

/** 整屏模糊背景按设置处理: 要打码的不对焦变清晰 (只铺模糊版). */
fun TvNativeWallBackdropTarget.withNsfw(nsfw: NsfwPolicy): TvNativeWallBackdropTarget {
    val id = subjectId ?: return this
    return if (sharp && nsfw.blurs(id)) copy(sharp = false) else this
}

/** hero 的背景按设置打码: 背景大图解成小图糊掉, 整屏模糊背景不对焦变清晰. */
fun TvNativeHeroSource.withNsfw(nsfw: NsfwPolicy): TvNativeHeroSource {
    val id = backdrop?.subjectId ?: wall?.subjectId ?: return this
    if (!nsfw.blurs(id)) return this
    return copy(
        backdrop = backdrop?.let { if (it.obscure) it else it.copy(obscure = true) },
        wall = wall?.withNsfw(nsfw),
    )
}
