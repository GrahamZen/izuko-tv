/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.richtext

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.app.tools.HtmlColor
import me.him188.ani.app.ui.comment.BangumiStickers
import me.him188.ani.utils.bbcode.BBCode
import me.him188.ani.utils.bbcode.RichElement
import me.him188.ani.utils.bbcode.RichText
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.CoroutineContext

@Composable
fun rememberBBCodeRichTextState(
    initialText: String,
    defaultTextSize: TextUnit = LocalTextStyle.current.fontSize,
): BBCodeRichTextState {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        BBCodeRichTextState(initialText, defaultTextSize, scope)
    }
}

@Stable
class BBCodeRichTextState(
    initialText: String,
    defaultTextSize: TextUnit,
    scope: CoroutineScope,
    parseContext: CoroutineContext = Dispatchers.Default
) {
    private val logger = logger<BBCodeRichTextState>()

    private val textFlow = MutableStateFlow(initialText)
    var elements: List<UIRichElement> by mutableStateOf(listOf())
        private set

    init {
        scope.launch {
            textFlow.collectLatest { code ->
                val richText = withContext(parseContext) {
                    try {
                        BBCode.parse(code)
                    } catch (ex: Exception) {
                        logger.warn(ex) { "failed to parse bbcode \"$code\"" }
                        null
                    }
                }
                if (richText != null) {
                    elements = richText.toUIRichElements(defaultTextSize.value)
                }

            }
        }
    }

    fun setText(text: String) {
        textFlow.value = text
    }
}

// TODO: move to BBCodeRichTextState
fun RichText.toUIRichElements(overrideTextSize: Float? = null): List<UIRichElement> = buildList {
    val annotated = mutableListOf<UIRichElement.Annotated>()
    // 当前累积的 annotated 所属的对齐方式. 对齐是段落级属性, 对齐方式变化时必须切分出新的 AnnotatedText
    var currentAlign = TextAlign.Unspecified

    fun flushAnnotated() {
        if (annotated.isNotEmpty()) {
            add(UIRichElement.AnnotatedText(annotated.toList(), align = currentAlign))
            annotated.clear()
        }
        currentAlign = TextAlign.Unspecified
    }

    elements.forEach { e ->
        when (e) {
            is RichElement.Text -> {
                if (e.value.trim().isNotEmpty()) {
                    val align = e.align.toTextAlign()
                    if (align != currentAlign) flushAnnotated()
                    currentAlign = align

                    // toUIAnnotated 会把文本里的 `(bgm38)` 切成行内表情, 见它的注释
                    annotated.addAll(e.toUIAnnotated(overrideTextSize))
                }
            }

            is RichElement.BangumiSticker -> annotated.add(
                e.toUISticker(overrideTextSize ?: RichTextDefaults.FontSize),
            )

            is RichElement.Kanmoji -> annotated.add(
                e.toUISticker(overrideTextSize ?: RichTextDefaults.FontSize),
            )

            is RichElement.Quote -> {
                flushAnnotated()
                add(UIRichElement.Quote(e.contents.toUIRichElements()))
            }

            is RichElement.Image -> {
                flushAnnotated()
                // 贴的常常是图床的网页地址而不是图片直链, 见 normalizeImageUrl
                add(UIRichElement.Image(normalizeImageUrl(e.imageUrl), e.jumpUrl))
            }
        }
    }

    flushAnnotated()
}

private fun RichElement.Text.Align.toTextAlign(): TextAlign = when (this) {
    RichElement.Text.Align.DEFAULT -> TextAlign.Unspecified
    RichElement.Text.Align.LEFT -> TextAlign.Left
    RichElement.Text.Align.CENTER -> TextAlign.Center
    RichElement.Text.Align.RIGHT -> TextAlign.Right
}

fun RichText.toUIBriefText(): UIRichElement.AnnotatedText {
    val plainText = StringBuilder()
    val annotated = mutableListOf<UIRichElement.Annotated>()

    fun flushText() {
        if (plainText.isNotEmpty()) {
            annotated.add(UIRichElement.Annotated.Text(plainText.toString(), RichTextDefaults.FontSize))
            plainText.clear()
        }
    }

    elements.forEach { e ->
        when (e) {
            is RichElement.Image -> plainText.append("[图片]")
            is RichElement.Quote -> plainText.append("[引用]")

            // 表情在缩略行里也出图 (与展开后的正文一致), 认不出的代码退化成原文本
            is RichElement.Text -> e.toUIAnnotated(RichTextDefaults.FontSize).forEach { piece ->
                when (piece) {
                    is UIRichElement.Annotated.Text -> plainText.append(piece.content.replace('\n', ' '))
                    is UIRichElement.Annotated.Sticker -> {
                        flushText()
                        annotated.add(piece)
                    }
                }
            }

            is RichElement.Kanmoji -> e.toUISticker(RichTextDefaults.FontSize).let {
                if (it is UIRichElement.Annotated.Sticker) {
                    flushText()
                    annotated.add(it)
                } else {
                    plainText.append(e.id)
                }
            }

            is RichElement.BangumiSticker -> e.toUISticker(RichTextDefaults.FontSize).let {
                if (it is UIRichElement.Annotated.Sticker) {
                    flushText()
                    annotated.add(it)
                } else {
                    plainText.append("(bgm${e.id})")
                }
            }
        }
    }

    flushText()

    return UIRichElement.AnnotatedText(annotated)
}

/**
 * 一段文本 -> 行内元素.
 *
 * 会把文本里的表情代码切出来单独成一枚表情: `(musume_06)` 这类**带下划线**的代码 BBCode 文法没有
 * 对应产生式 (`bgm_sticker` 只认 `(bgm` + 数字), 会被当普通文本吐出来, 所以在这一层补一道扫描.
 * 见 [BangumiStickers.findTokens].
 */
private fun RichElement.Text.toUIAnnotated(overrideTextSize: Float?): List<UIRichElement.Annotated> {
    // 与改动前一致: 纯空白的文本段不产生元素
    if (value.isBlank()) return emptyList()

    fun text(content: String) = UIRichElement.Annotated.Text(
        content = content,
        size = overrideTextSize ?: size.toFloat(),
        color = HtmlColor.parse(color),
        italic = italic,
        underline = underline,
        strikethrough = strikethrough,
        bold = bold,
        mask = mask,
        code = code,
        url = jumpUrl,
    )

    // 剧透遮罩里不切表情: [UIRichElement.Annotated.Sticker] 没有 mask 字段, 切出来的那枚图会
    // 直接露在遮罩外面; 更糟的是 [RichTextDefaults.AnnotatedMaskState] 按"上一片是不是带 mask
    // 的 Text"分块, 中间插一枚 Sticker 就把一段 [mask] 裂成前后两个互不相干的遮罩块, 要点两下
    // 才揭得开. 遮罩里本来也不该出图, 整段留作一片文本 (与改动前一致)
    if (mask) return listOf(text(value))

    val tokens = BangumiStickers.findTokens(value)
    if (tokens.isEmpty()) return listOf(text(value))

    return buildList {
        var consumed = 0
        tokens.forEach { token ->
            if (token.range.first > consumed) add(text(value.substring(consumed, token.range.first)))
            add(uiStickerOf(token.value, jumpUrl = jumpUrl))
            consumed = token.range.last + 1
        }
        if (consumed < value.length) add(text(value.substring(consumed)))
    }
}

/**
 * `(bgm38)` -> 行内表情. 最早那 125 张随包, 其余按地址现拉.
 * 代码不认识 (Bangumi 又上新了表情包而 [BangumiStickers] 没跟上) 时退化成原文本 `(bgm999)`,
 * 不然 [UIRichElement.Annotated.Sticker] 会渲染成一块看不见的空白, 等于整条表情凭空消失.
 */
private fun RichElement.BangumiSticker.toUISticker(textSize: Float): UIRichElement.Annotated =
    uiStickerOf("(bgm$id)", jumpUrl = jumpUrl, textSize = textSize)

/** 颜文字 (`(=A=)` 这类) -> 行内表情. 与 [RichElement.BangumiSticker.toUISticker] 同理. */
private fun RichElement.Kanmoji.toUISticker(textSize: Float): UIRichElement.Annotated =
    uiStickerOf(id, jumpUrl = jumpUrl, textSize = textSize)

/**
 * 表情代码 -> 行内表情; 随包没图、地址也拼不出来的, 退化成原文本.
 *
 * 随包图查找与"认不出"的判定都交给 [BangumiStickers.stickerOf] —— 回应条上那份也走同一个入口,
 * 两处对同一枚新表情的表现才不会不一致. 这里只剩"画成什么"这一层.
 */
private fun uiStickerOf(
    token: String,
    jumpUrl: String?,
    textSize: Float = RichTextDefaults.FontSize,
): UIRichElement.Annotated {
    val sticker = BangumiStickers.stickerOf(token)
    if (!sticker.hasImage) {
        return UIRichElement.Annotated.Text(content = sticker.token, size = textSize, url = jumpUrl)
    }
    return UIRichElement.Annotated.Sticker(
        id = sticker.token,
        resource = sticker.resource,
        imageUrl = sticker.imageUrl,
        url = jumpUrl,
    )
}
