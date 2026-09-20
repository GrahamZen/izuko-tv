/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.ui.comment.BangumiStickers

/**
 * 手机「本集评论」表情面板 (见 RemoteControlPage 的 REVIEW_SCRIPT) 用的表情目录: 与电视评论弹窗的表情选择器同一份
 * [BangumiStickers.packs], 每枚给出代码与图片地址 (Bangumi 图片站, 手机直接去拉). 目录是死的, 算一次就缓存住.
 */
internal object RemoteStickers {
    /** `{ok, packs: [{name, items: [[代码, 图片地址], ...]}]}`. */
    val catalog: JsonObject by lazy {
        buildJsonObject {
            put("ok", true)
            putJsonArray("packs") {
                for (pack in BangumiStickers.packs) addJsonObject {
                    put("name", pack.name)
                    putJsonArray("items") {
                        for (token in pack.tokens) {
                            val url = BangumiStickers.imageUrlOf(token) ?: continue
                            addJsonArray {
                                add(token)
                                add(url)
                            }
                        }
                    }
                }
            }
        }
    }
}
