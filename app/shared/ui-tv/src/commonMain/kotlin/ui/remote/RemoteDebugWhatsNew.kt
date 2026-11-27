/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.app.domain.update.UpdateManager
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.update.parseReleaseHighlights
import me.him188.ani.app.ui.update.releaseNotesStore
import me.him188.ani.app.ui.whatsnew.TvWhatsNew
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.mp.KoinPlatform

/**
 * 「本版新功能」页的调试入口: 自己编的包在 GitHub 上没有这一版的更新说明, 冷启动不出这一页.
 *
 * `POST api/debug/whatsnew notes=<更新说明>`: 把 `notes` (release 正文的写法, 要有「## 本次更新」一节) 存成当前版本的说明,
 * 看过的版本改回旧的 —— 下次冷启动出「本版新功能」(如 `adb shell am force-stop` 后再打开). 读不出新功能的 `notes` 回 `ok:false`, 什么都不改.
 */
internal object RemoteDebugWhatsNew {
    private val logger = logger<RemoteDebugWhatsNew>()

    fun handle(request: LanHttpRequest): JsonObject? {
        if (request.method != "POST") return null
        val notes = request.formFields()["notes"].orEmpty()
        val version = currentAniBuildConfig.versionName
        val highlights = parseReleaseHighlights(version, notes)
        if (highlights == null || highlights.features.isEmpty()) {
            return buildJsonObject {
                put("ok", false)
                put("message", "notes 里读不出新功能: 要有「## 本次更新」一节, 里面列新功能")
            }
        }
        runBlocking { KoinPlatform.getKoin().get<UpdateManager>().releaseNotesStore().put(version, notes) }
        TvWhatsNew.forgetSeenVersion(KoinPlatform.getKoin().get<Context>())
        logger.info { "Remote debug: stored release notes for $version (${highlights.features.size} features)" }
        return buildJsonObject {
            put("ok", true)
            put("version", version)
            put("features", highlights.features.size)
        }
    }
}
