/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.directapi

import io.ktor.http.decodeURLPart
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 依次应用 [transforms]. 任何一步失败 (例如 base64 解不出来) 都返回 `null`, 表示这条数据不可用.
 */
internal fun applyTransforms(input: String, transforms: List<Transform>): String? {
    var current = input
    for (transform in transforms) {
        current = apply(current, transform) ?: return null
    }
    return current.takeIf { it.isNotEmpty() }
}

private fun apply(value: String, transform: Transform): String? = when (transform.op) {
    TransformOp.RemoveCharAt ->
        if (transform.index in value.indices) value.removeRange(transform.index, transform.index + 1) else value

    TransformOp.Base64Decode -> decodeBase64(value)
    TransformOp.SwapCase -> buildString(value.length) {
        for (c in value) {
            append(
                when {
                    c.isUpperCase() -> c.lowercaseChar()
                    c.isLowerCase() -> c.uppercaseChar()
                    else -> c
                },
            )
        }
    }

    TransformOp.UrlDecode -> runCatching { value.decodeURLPart() }.getOrNull()
    TransformOp.SubstringBefore -> value.substringBefore(transform.value)
    TransformOp.SubstringAfter -> value.substringAfter(transform.value)
    TransformOp.SubstringBeforeLast -> value.substringBeforeLast(transform.value)
    TransformOp.SubstringAfterLast -> value.substringAfterLast(transform.value)
    TransformOp.RegexReplace -> runCatching {
        Regex(transform.value).replace(value, transform.replacement)
    }.getOrNull()

    TransformOp.Prepend -> transform.value + value
    TransformOp.Append -> value + transform.value
    TransformOp.Trim -> value.trim()
}

/**
 * 自动补 `=`; 标准字母表解不出来时再试 URL-safe 字母表.
 */
@OptIn(ExperimentalEncodingApi::class)
private fun decodeBase64(value: String): String? {
    val padded = value + "=".repeat((4 - value.length % 4) % 4)
    runCatching { Base64.Default.decode(padded).decodeToString() }.getOrNull()?.let { return it }
    return runCatching { Base64.UrlSafe.decode(padded).decodeToString() }.getOrNull()
}
