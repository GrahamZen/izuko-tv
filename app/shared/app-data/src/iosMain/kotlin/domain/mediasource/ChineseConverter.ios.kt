/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import platform.Foundation.NSString
import platform.Foundation.stringByApplyingTransform

/**
 * 转换过的结果, 按放进来的先后排 (满了丢最早的). 同一批名字 (条目别名、页名、网盘目录名) 在每批搜索结果的筛选里都要再转一遍, 而 ICU 转写很慢.
 */
private val converted = LinkedHashMap<String, String>()
private val convertedLock = SynchronizedObject()

private const val CACHE_SIZE = 4096

/** Foundation 的 ICU 转写 (与安卓用的是同一个转写规则). */
internal actual fun String.toSimplifiedChinese(): String {
    // 繁转简只改中日韩字符, 没有的原样返回
    if (none { it.code >= 0x2E80 }) return this
    synchronized(convertedLock) { converted[this] }?.let { return it }
    @Suppress("CAST_NEVER_SUCCEEDS")
    val result = (this as NSString).stringByApplyingTransform("Traditional-Simplified", reverse = false) ?: this
    synchronized(convertedLock) {
        if (converted.size >= CACHE_SIZE) converted.remove(converted.keys.first())
        converted[this] = result
    }
    return result
}
