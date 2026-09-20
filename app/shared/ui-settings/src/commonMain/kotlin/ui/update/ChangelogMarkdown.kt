/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import androidx.compose.ui.graphics.Color
import me.him188.ani.app.ui.richtext.UIRichElement

/**
 * **更新说明 (GitHub release body) 的 markdown 解析**: 文本 → 富文本块.
 *
 * release 说明就是 markdown, 而更新详情弹窗原先是逐行纯文本显示的, 于是 `**加粗**`、
 * `[文字](链接)` 这些标记会原样糊在正文里 —— 写说明的人越用心, 显示出来越乱.
 *
 * 这里只做**一个够用的子集**, 输出复用评论那套富文本 ([UIRichElement] + `RichText`), 因此
 * 加粗/斜体/删除线/行内代码/链接/图片全都由已有的渲染器负责, 图片走的也是评论区那条加载链路.
 * 没有引入 markdown 库: 需要的只是这几个行内标记加三种块, 而库要处理的是整个 CommonMark
 * (表格、脚注、HTML 内联……), 体积与行为都不值当.
 *
 * **不支持的一律原样显示**, 绝不吞字: 表格行、分隔线、HTML 标签等都会当成普通段落画出来 ——
 * 更新说明是给人看的, 少显示一行比多显示一行糟得多.
 */
internal sealed interface ChangelogBlock {
    /** 小节标题 (`### 修复`). */
    data class Section(val slice: List<UIRichElement.Annotated>) : ChangelogBlock

    /** 一条更新内容; [indent] 是嵌套层级 (0 起, 最多 2). */
    data class Item(val indent: Int, val slice: List<UIRichElement.Annotated>) : ChangelogBlock

    /** 普通段落 (正文开头那句概述). */
    data class Paragraph(val slice: List<UIRichElement.Annotated>) : ChangelogBlock

    /** 引用块 (`> …`). */
    data class Quote(val slice: List<UIRichElement.Annotated>) : ChangelogBlock

    /** 独占一行的图片 (`![alt](url)`). */
    data class Image(val url: String, val alt: String) : ChangelogBlock
}

/** 行内标记解析出来的一段文字该长什么样. 块级样式 (标题/正文) 由调用方给基线值. */
internal data class ChangelogTextStyle(
    val size: Float,
    val color: Color = Color.Unspecified,
    /** 链接文字的颜色; 渲染器不会自动给链接上色. */
    val linkColor: Color = Color.Unspecified,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
)

/**
 * 整段更新说明切成块. 空行只作分隔, 不产生块.
 */
internal fun parseChangelogBlocks(
    changes: String,
    bodyStyle: ChangelogTextStyle,
    sectionStyle: ChangelogTextStyle,
): List<ChangelogBlock> = buildList {
    for (rawLine in changes.lineSequence()) {
        val line = rawLine.trimEnd()
        if (line.isBlank()) continue
        val indentSpaces = line.length - line.trimStart().length
        val trimmed = line.trimStart()

        when {
            trimmed.startsWith("#") -> {
                val text = trimmed.trimStart('#').trim()
                if (text.isNotEmpty()) add(ChangelogBlock.Section(parseMarkdownInline(text, sectionStyle)))
            }

            trimmed.startsWith(">") -> {
                val text = trimmed.removePrefix(">").trim()
                if (text.isNotEmpty()) add(ChangelogBlock.Quote(parseMarkdownInline(text, bodyStyle)))
            }

            // 整行就是一张图: 单独成块 (行内图会退化成链接文字, 见 parseMarkdownInline)
            trimmed.asStandaloneImage() != null -> {
                val (alt, url) = trimmed.asStandaloneImage()!!
                add(ChangelogBlock.Image(url, alt))
            }

            // 分隔线: 画出来只是一条没有信息的横杠, 而这个弹窗本来就按小节分段
            trimmed.all { it == '-' || it == '*' || it == '_' } && trimmed.length >= 3 -> Unit

            trimmed.isBulletLine() -> {
                val text = trimmed.removeBulletMarker()
                // 2 空格与 4 空格两种缩进约定都当一级
                val indent = when {
                    indentSpaces >= 8 -> 2
                    indentSpaces >= 2 -> 1
                    else -> 0
                }
                add(ChangelogBlock.Item(indent, parseMarkdownInline(text, bodyStyle)))
            }

            else -> add(ChangelogBlock.Paragraph(parseMarkdownInline(trimmed, bodyStyle)))
        }
    }
}

private fun String.isBulletLine(): Boolean =
    startsWith("- ") || startsWith("* ") || startsWith("+ ") ||
            // 有序列表 (`1. xxx`): 也按条目画点, 序号不重要
            Regex("""^\d+[.)]\s""").containsMatchIn(this)

private fun String.removeBulletMarker(): String = when {
    startsWith("- ") || startsWith("* ") || startsWith("+ ") -> substring(2)
    else -> replaceFirst(Regex("""^\d+[.)]\s+"""), "")
}

/** `![alt](url)` 且整行只有它时返回 (alt, url). */
private fun String.asStandaloneImage(): Pair<String, String>? {
    if (!startsWith("![") || !endsWith(")")) return null
    val altEnd = indexOf("](")
    if (altEnd < 0) return null
    val url = substring(altEnd + 2, length - 1)
    if (url.isBlank() || url.contains(' ')) return null
    return substring(2, altEnd) to url
}

/**
 * **行内标记解析**: `**粗**` `*斜*` `~~删~~` `` `代码` `` `[文字](链接)` `![alt](图)`.
 *
 * 手写扫描而不是正则: 这几个标记要**互相嵌套** (`**粗里有 `代码`**`), 正则要么写不出来, 要么
 * 得靠后顾断言 —— 而这份代码要在 commonMain 里跨平台编译, 各平台的正则引擎对断言的支持并不一致.
 *
 * 配对不上的标记 (单个 `*`、没闭合的反引号) **原样保留**: 更新说明里 `3*4` 这种写法很常见,
 * 把它当成斜体开头会吃掉后面一大段.
 *
 * 行内图片退化成链接文字 (显示 alt): 一行字中间插一张图, 在这个弹窗里排不出好版; 而整行只有
 * 一张图时块级解析已经把它拦下来了.
 */
internal fun parseMarkdownInline(
    text: String,
    style: ChangelogTextStyle,
): List<UIRichElement.Annotated> {
    val out = mutableListOf<UIRichElement.Annotated>()
    val buffer = StringBuilder()

    fun flush() {
        if (buffer.isEmpty()) return
        out += style.toText(buffer.toString())
        buffer.clear()
    }

    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            // 行内代码: 内部不再解析其它标记 (markdown 的规矩, 也正是它存在的意义)
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    out += style.toText(text.substring(i + 1, end)).copy(code = true)
                    i = end + 1
                } else {
                    buffer.append(c); i++
                }
            }

            c == '!' && text.startsWith("![", i) -> {
                val link = text.readLink(i + 1)
                if (link != null) {
                    flush()
                    // 退化成链接文字, 见函数文档
                    out += style.toText(link.text.ifBlank { link.url }).copy(url = link.url, color = style.linkColor)
                    i = link.end
                } else {
                    buffer.append(c); i++
                }
            }

            c == '[' -> {
                val link = text.readLink(i)
                if (link != null) {
                    flush()
                    out += parseMarkdownInline(link.text, style.copy(color = style.linkColor))
                        .map { if (it is UIRichElement.Annotated.Text) it.copy(url = link.url) else it }
                    i = link.end
                } else {
                    buffer.append(c); i++
                }
            }

            text.startsWith("**", i) -> {
                val end = text.findClosing("**", i + 2)
                if (end > i + 2) {
                    flush()
                    out += parseMarkdownInline(text.substring(i + 2, end), style.copy(bold = true))
                    i = end + 2
                } else {
                    buffer.append(c); i++
                }
            }

            text.startsWith("~~", i) -> {
                val end = text.findClosing("~~", i + 2)
                if (end > i + 2) {
                    flush()
                    out += parseMarkdownInline(text.substring(i + 2, end), style.copy(strikethrough = true))
                    i = end + 2
                } else {
                    buffer.append(c); i++
                }
            }

            c == '*' || c == '_' -> {
                val end = text.indexOf(c, i + 1)
                // 单个下划线常出现在标识符里 (`media_source`), 要求两侧都不是字母数字才算斜体
                val isEmphasis = end > i + 1 &&
                        (c == '*' || (!text.charAtIsWord(i - 1) && !text.charAtIsWord(end + 1)))
                if (isEmphasis) {
                    flush()
                    out += parseMarkdownInline(text.substring(i + 1, end), style.copy(italic = true))
                    i = end + 1
                } else {
                    buffer.append(c); i++
                }
            }

            else -> {
                buffer.append(c); i++
            }
        }
    }
    flush()
    return out
}

private fun ChangelogTextStyle.toText(content: String) = UIRichElement.Annotated.Text(
    content = content,
    size = size,
    color = color,
    bold = bold,
    italic = italic,
    strikethrough = strikethrough,
)

private class MarkdownLink(val text: String, val url: String, val end: Int)

/** 从 [start] 处的 `[` 读一个 `[文字](链接)`; 读不出来返回 null. */
private fun String.readLink(start: Int): MarkdownLink? {
    if (getOrNull(start) != '[') return null
    val textEnd = indexOf(']', start + 1)
    if (textEnd < 0 || getOrNull(textEnd + 1) != '(') return null
    val urlEnd = indexOf(')', textEnd + 2)
    if (urlEnd < 0) return null
    val url = substring(textEnd + 2, urlEnd).trim()
    if (url.isEmpty() || url.any { it.isWhitespace() }) return null
    return MarkdownLink(substring(start + 1, textEnd), url, urlEnd + 1)
}

/**
 * 找 [marker] 的收尾, **跳过还连着同一个字符的那一处**.
 *
 * `**粗里有 *斜***` 收尾处是三个星号: 直接 `indexOf("**")` 会咬住前两个 (那其实是斜体的收尾
 * 加粗体收尾的头一个), 于是粗体提前结束、剩一个孤零零的星号原样显示. 要求收尾之后不再是同一个
 * 字符, 就正好落在真正的那一对上; 而 `**甲** 与 **乙**` 这种两段独立的写法不受影响 (它们的收尾
 * 后面是空格).
 */
private fun String.findClosing(marker: String, from: Int): Int {
    var at = indexOf(marker, from)
    while (at >= 0 && getOrNull(at + marker.length) == marker[0]) {
        at = indexOf(marker, at + 1)
    }
    return at
}

private fun String.charAtIsWord(index: Int): Boolean =
    getOrNull(index)?.let { it.isLetterOrDigit() } == true
