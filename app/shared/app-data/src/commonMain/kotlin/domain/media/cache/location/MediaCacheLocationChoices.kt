/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.location

/**
 * 设置里「缓存位置」的一个选项. 相等只看 [id]: 剩余空间这类值每次重列都变, 选中项要认得出来.
 *
 * @property location 这个位置此刻的样子; `null` = 选着的盘没接上
 */
class MediaCacheLocationChoice(val id: String?, val location: MediaCacheLocation?) {
    override fun equals(other: Any?): Boolean = other is MediaCacheLocationChoice && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "MediaCacheLocationChoice(${id ?: "internal"})"
}

/**
 * 能选的位置: 接着且能用的 (内部存储在第一个). 设置里选着的盘没接上或变得不能用时也列在最后, 让人看得出选的是它、新缓存暂时放在内部存储.
 */
fun mediaCacheLocationChoices(locations: List<MediaCacheLocation>, selected: String?): List<MediaCacheLocationChoice> {
    val usable = locations.filter { it.unusable == null }.map { MediaCacheLocationChoice(it.id, it) }
    if (selected == null || usable.any { it.id == selected }) return usable
    return usable + MediaCacheLocationChoice(selected, locations.firstOrNull { it.id == selected })
}
