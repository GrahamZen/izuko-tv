/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

/**
 * 往文案模板里代入参数 (公共代码里没有 String.format, iOS 也要用).
 *
 * 认的写法只有文案里用到的这几种 (见 strings*.xml): 带序号的 `%1$s` / `%2$d`, 不带序号的 `%s` / `%d` (依次取下一个参数),
 * 数字前可带宽度与补零 (`%02d`; 不带 0 时补空格), `%%` 是百分号本身. 别的 `%` 原样留着. 缺参数时代入 `null`, 同 Java.
 */
fun String.tvFormat(vararg args: Any?): String {
    val out = StringBuilder(length + 16)
    var nextArg = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c != '%' || i + 1 >= length) {
            out.append(c)
            i++
            continue
        }
        if (this[i + 1] == '%') {
            out.append('%')
            i += 2
            continue
        }
        var j = i + 1
        // 序号 (n$)
        var position: Int? = null
        val numberStart = j
        while (j < length && this[j].isDigit()) j++
        if (j > numberStart && j < length && this[j] == '$') {
            position = substring(numberStart, j).toInt() - 1
            j++
        } else {
            j = numberStart
        }
        // 补零与宽度
        val zeroPad = j < length && this[j] == '0'
        if (zeroPad) j++
        val widthStart = j
        while (j < length && this[j].isDigit()) j++
        val width = if (j > widthStart) substring(widthStart, j).toInt() else 0
        val conversion = if (j < length) this[j] else null
        if (conversion != 's' && conversion != 'd') {
            out.append(c)
            i++
            continue
        }
        var text = args.getOrNull(position ?: nextArg++).toString()
        if (text.length < width) {
            val pad = if (zeroPad && conversion == 'd') '0' else ' '
            text = pad.toString().repeat(width - text.length) + text
        }
        out.append(text)
        i = j + 1
    }
    return out.toString()
}
