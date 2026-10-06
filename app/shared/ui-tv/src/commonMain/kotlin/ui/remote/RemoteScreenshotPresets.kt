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
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform

/**
 * 截图水印的预设 (手机网页上每个水印行的书签按钮, 见 SHOT_SCRIPT): 位置、角度、大小, 文字水印还有显示哪几样, 起个名字存下来, 以后套到
 * 别的水印上.
 *
 * **存在电视上** (SharedPreferences, 一份 JSON), 不在手机浏览器里: 换手机、换浏览器、几个人各用各的手机都能用; iPhone 的 Safari
 * 一阵子不打开某个网站就会清掉它的本地存储, 存在那边的预设靠不住. 网页每次改动只提交一条 (加 / 删), 几台手机同时用也不会互相覆盖.
 *
 * 一条预设: `{ id, name, kind: logo | text, x, y, deg, scale, preset?, align?, title? / ep? / time? (text) }`. 大小存成相对
 * 「标准」的倍数 (`scale`), 文字与 logo 的大小量纲不同, 套过去也合适. 进来的每一条都按这个格式重新拼过, 不认识的字段丢掉.
 *
 * - `GET  api/player/screenshot/presets`: 全部
 * - `POST api/player/screenshot/presets/add` (表单 `preset` = 一条的 JSON)
 * - `POST api/player/screenshot/presets/delete` (表单 `id`)
 *
 * 都回 `{ ok, presets }`.
 */
internal object RemoteScreenshotPresets {
    private val logger = logger<RemoteScreenshotPresets>()
    private val lock = Any()

    private val prefs: SharedPreferences
        get() = KoinPlatform.getKoin().get<Context>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 处理 `presets` 下的请求 ([path] 是 `api/player/screenshot` 之后那段); 不认识返回 null. */
    fun handle(path: String, request: LanHttpRequest): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return when {
            path == "/presets" && get -> state()
            path == "/presets/add" && post -> add(request.formFields()["preset"].orEmpty())
            path == "/presets/delete" && post -> delete(request.formFields()["id"].orEmpty())
            else -> null
        }
    }

    private fun state(): JsonObject = result(true, null, synchronized(lock) { read() })

    private fun add(raw: String): JsonObject = synchronized(lock) {
        val preset = runCatching { sanitize(Json.parseToJsonElement(raw).jsonObject) }.getOrNull()
            ?: return result(false, tr("预设的格式不对"), read())
        val list = read().filterNot { it["id"] == preset["id"] }
        if (list.size >= MAX_PRESETS) return result(false, tr("预设最多存 {0} 个，先删掉几个", MAX_PRESETS), list)
        val updated = list + preset
        write(updated)
        result(true, null, updated)
    }

    private fun delete(id: String): JsonObject = synchronized(lock) {
        val list = read()
        val updated = list.filterNot { it["id"]?.jsonPrimitive?.contentOrNull == id }
        if (updated.size == list.size) return result(false, tr("没有这个预设"), list)
        write(updated)
        result(true, null, updated)
    }

    /** 按约定的格式重新拼一条; 缺名字 / id / 类型的不收. */
    internal fun sanitize(o: JsonObject): JsonObject? {
        fun text(key: String) = o[key]?.jsonPrimitive?.contentOrNull
        fun number(key: String, min: Double, max: Double, default: Double) =
            (o[key]?.jsonPrimitive?.doubleOrNull ?: default).coerceIn(min, max)
        val id = text("id")?.takeIf { ID_REGEX.matches(it) } ?: return null
        val name = text("name")?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() } ?: return null
        val kind = text("kind")?.takeIf { it == "logo" || it == "text" } ?: return null
        return buildJsonObject {
            put("id", id)
            put("name", name)
            put("kind", kind)
            put("x", number("x", 0.0, 1.0, 0.5))
            put("y", number("y", 0.0, 1.0, 0.5))
            put("deg", number("deg", -180.0, 180.0, 0.0))
            put("scale", number("scale", 0.05, 40.0, 1.0))
            text("preset")?.takeIf { it in POSITIONS }?.let { put("preset", it) }
            if (kind == "text") {
                text("align")?.takeIf { it in ALIGNS }?.let { put("align", it) }
                for (part in TEXT_PARTS) put(part, o[part]?.jsonPrimitive?.booleanOrNull == true)
            }
        }
    }

    private fun read(): List<JsonObject> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        (Json.parseToJsonElement(raw) as JsonArray).map { it.jsonObject }
    }.getOrElse {
        logger.warn(it) { "Remote screenshot presets unreadable, starting over" }
        emptyList()
    }

    private fun write(list: List<JsonObject>) {
        prefs.edit().putString(KEY, JsonArray(list).toString()).apply()
    }

    private fun result(ok: Boolean, message: String?, list: List<JsonObject>): JsonObject = buildJsonObject {
        put("ok", ok)
        message?.let { put("message", it) }
        put("presets", buildJsonArray { list.forEach { add(it) } })
    }

    private const val PREFS_NAME = "tv_remote_screenshot"
    private const val KEY = "presets"
    private const val MAX_PRESETS = 50
    private const val MAX_NAME = 40
    private val ID_REGEX = Regex("[a-z0-9]{1,32}")
    private val POSITIONS = setOf("tl", "tc", "tr", "ml", "mc", "mr", "bl", "bc", "br")
    private val ALIGNS = setOf("l", "c", "r")
    private val TEXT_PARTS = listOf("title", "ep", "time")
}
