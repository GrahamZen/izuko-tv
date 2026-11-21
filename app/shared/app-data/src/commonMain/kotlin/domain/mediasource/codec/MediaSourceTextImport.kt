/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.codec

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.mediasource.rule.AniBakaRuleImporter
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.utils.platform.Uuid
import kotlin.coroutines.cancellation.CancellationException

/**
 * 从一段文字导入数据源, 每一个新建为本地源; 部分失败时照样导入其余的.
 *
 * 认两类: AniBaka 规则 (规则 JSON、规则数组、规则库索引, 或它们的地址, 见 [AniBakaRuleImporter], 转换成规则源),
 * 与导出的数据源 JSON (单个 `{factoryId, version, arguments}`、列表 `{mediaSources: [...]}` 或数组、订阅内容 `{exportedMediaSourceDataList: …}`).
 * [download] 下载地址的内容 (AniBaka 规则或规则库给的是地址时用).
 */
class MediaSourceTextImport(
    private val manager: MediaSourceManager,
    private val codec: MediaSourceCodecManager,
    private val download: suspend (url: String) -> String,
) {
    /** 没导进来的一项. */
    sealed interface Failure {
        /** 这一版不认识的数据源类型. */
        data class UnsupportedType(val factoryId: String) : Failure

        /** 只能有一个的数据源 ([name]) 已经有了. */
        data class SingleInstance(val name: String) : Failure

        /** 贴的是地址, 下载失败. */
        data class Download(val message: String) : Failure

        /** 别的原因 (规则转换不了、参数解不开等), [message] 是说明. */
        data class Other(val message: String) : Failure
    }

    /** 导入了几个 ([added]) 与没导进来的 ([failures]); 两样都空 = 里面没有数据源. */
    class Result(val added: Int, val failures: List<Failure>)

    /** 认不出格式 (不是 AniBaka 规则也不是数据源 JSON) 时返回 null. */
    suspend fun import(text: String): Result? {
        val aniBaka = try {
            AniBakaRuleImporter.import(text, download)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 只有贴的是地址时才会走到这里
            return Result(0, listOf(Failure.Download(e.message ?: e::class.simpleName.orEmpty())))
        }
        val list = aniBaka?.sources?.map { codec.encode(it) } ?: parseExported(text) ?: return null
        val failures = aniBaka?.failures.orEmpty().mapTo(mutableListOf<Failure>()) { Failure.Other(it) }
        val existing = manager.allInstances.first()
        var added = 0
        for (data in list) {
            val factory = manager.allFactories.find { it.factoryId == data.factoryId }
            if (factory == null) {
                failures += Failure.UnsupportedType(data.factoryId.value)
                continue
            }
            if (!factory.allowMultipleInstances && existing.any { it.factoryId == data.factoryId }) {
                failures += Failure.SingleInstance(factory.info.displayName)
                continue
            }
            try {
                val normalized = codec.encode(codec.decode(data))
                val instanceId = Uuid.randomString()
                manager.addInstance(instanceId, instanceId, data.factoryId, MediaSourceConfig(serializedArguments = normalized.arguments))
                added++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += Failure.Other(e.message ?: e::class.simpleName.orEmpty())
            }
        }
        return Result(added, failures)
    }

    /** 导出的数据源 JSON 的三种形状; 解析不了返回 null. */
    private fun parseExported(text: String): List<ExportedMediaSourceData>? {
        val root = runCatching { MediaSourceCodecManager.json.parseToJsonElement(text.trim()) }.getOrNull() ?: return null
        val items: List<JsonElement> = when {
            root is JsonArray -> root
            root is JsonObject && "factoryId" in root -> listOf(root)
            root is JsonObject && "mediaSources" in root -> root["mediaSources"] as? JsonArray ?: return null
            root is JsonObject && "exportedMediaSourceDataList" in root ->
                root["exportedMediaSourceDataList"]?.jsonObject?.get("mediaSources") as? JsonArray ?: return null

            else -> return null
        }
        return runCatching {
            items.map { MediaSourceCodecManager.json.decodeFromJsonElement(ExportedMediaSourceData.serializer(), it) }
        }.getOrNull()
    }
}
