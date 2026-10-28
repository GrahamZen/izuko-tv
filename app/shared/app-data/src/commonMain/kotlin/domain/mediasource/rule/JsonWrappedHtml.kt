/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 苹果 CMS (ThinkPHP) 一类站点见到请求头 Accept 里有 json (数据源共用的 HTTP 客户端装了 JSON 内容协商, 请求都带着它) 时,
 * 把整页 HTML 编成一个 JSON 字符串返回 (`"<!DOCTYPE html>…"`, 引号与斜杠都转义了), 按网页解析时什么都选不中
 * (MiFun 的详情页「没有选中剧集列表」). 响应整个是一个 JSON 字符串、解开是 HTML 时还原成 HTML; 别的响应原样返回.
 */
internal fun unwrapJsonEncodedHtml(text: String): String {
    val trimmed = text.trim()
    if (trimmed.length < 2 || !trimmed.startsWith('"') || !trimmed.endsWith('"')) return text
    val decoded = try {
        Json.decodeFromString<String>(trimmed)
    } catch (_: SerializationException) {
        return text
    } catch (_: IllegalArgumentException) {
        return text
    }
    return if (decoded.trimStart().startsWith('<')) decoded else text
}
