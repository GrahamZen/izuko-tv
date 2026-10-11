/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource

/**
 * 单元测试跑在桩 `android.jar` 上, `android.icu` 的方法一律抛 "not mocked",
 * 因此取不到时退化为不转换 (与 desktop / iOS 的 actual 一致); 真机上照常做繁简转换.
 */
private val transliterator by lazy {
    runCatching { android.icu.text.Transliterator.getInstance("Traditional-Simplified") }.getOrNull()
}

/**
 * 转换过的结果. 同一批名字 (条目别名、页名、网盘目录名) 在每批搜索结果的筛选里都要再转一遍, 而 ICU 转写很慢.
 */
private val converted = object : LinkedHashMap<String, String>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > CACHE_SIZE
}

private const val CACHE_SIZE = 4096

internal actual fun String.toSimplifiedChinese(): String {
    // 繁转简只改中日韩字符, 没有的原样返回
    if (none { it.code >= 0x2E80 }) return this
    synchronized(converted) { converted[this] }?.let { return it }
    val result = transliterator?.transliterate(this) ?: this
    synchronized(converted) { converted[this] = result }
    return result
}
