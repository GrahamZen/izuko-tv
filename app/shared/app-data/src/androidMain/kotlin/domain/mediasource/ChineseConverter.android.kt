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

internal actual fun String.toSimplifiedChinese(): String =
    transliterator?.transliterate(this) ?: this
