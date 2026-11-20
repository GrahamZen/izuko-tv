/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

/**
 * 日志里一句话说清为什么失败: `类名: 消息`, 根因不是它自己时附上根因, 各截 [MAX_LENGTH] 字.
 * 写法与请求日志 (`IO_EXCEPTION (…)`) 一致, 好对着看: 光一个类名分不出 DNS 解析失败 / 连接超时 / 握手失败 / 读超时.
 */
internal fun Throwable.summarizeForLog(): String {
    val root = generateSequence(this) { current -> current.cause?.takeIf { it !== current } }.last()
    val self = toString().take(MAX_LENGTH)
    return if (root === this) self else "$self, cause: ${root.toString().take(MAX_LENGTH)}"
}

private const val MAX_LENGTH = 200
