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
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 把别处复制来的数据源配置导入成本地源. 手机控制台的「导入 JSON」与平板设置里「添加数据源」的「导入配置」共用这一份.
 *
 * 认的内容见 [parse] (单个、一组、订阅内容); 也认 AniBaka 规则 (JSON, 或规则 / 规则库索引的地址, 地址由 [download] 下载),
 * 转换成规则源 (见 [AniBakaRuleImporter]). 每一个新建为本地源; 有的导不进时照样导入其余的, 导不进的各带原因.
 */
class MediaSourceImporter(
    private val manager: MediaSourceManager,
    private val codec: MediaSourceCodecManager,
    private val download: suspend (url: String) -> String,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend fun import(text: String): MediaSourceImportResult {
        val aniBaka = try {
            AniBakaRuleImporter.import(text, download)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 只有贴的是地址时才会走到这里 (下载失败)
            logger.warn(e) { "Failed to download AniBaka rules" }
            AniBakaRuleImporter.ImportResult(emptyList(), listOf("下载失败: ${e.message ?: e::class.simpleName}"))
        }
        val list = aniBaka?.sources?.map { codec.encode(it) }
            ?: parse(text)
            ?: return MediaSourceImportResult.NotRecognized
        if (list.isEmpty() && aniBaka == null) return MediaSourceImportResult.Empty

        val present = manager.allInstances.first().mapTo(HashSet()) { it.factoryId }
        val failures = aniBaka?.failures.orEmpty().mapTo(mutableListOf<MediaSourceImportFailure>()) { MediaSourceImportFailure.Other(it) }
        var added = 0
        for (data in list) {
            val factory = manager.allFactories.find { it.factoryId == data.factoryId }
            if (factory == null) {
                failures += MediaSourceImportFailure.UnsupportedType(data.factoryId.value)
                continue
            }
            if (!factory.allowMultipleInstances && data.factoryId in present) {
                failures += MediaSourceImportFailure.OnlyOneAllowed(factory.info.displayName)
                continue
            }
            try {
                val normalized = codec.encode(codec.decode(data))
                val id = Uuid.random().toString()
                manager.addInstance(id, id, data.factoryId, MediaSourceConfig(serializedArguments = normalized.arguments))
                present += data.factoryId
                added++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += MediaSourceImportFailure.Other(e.message ?: e::class.simpleName.orEmpty())
            }
        }
        logger.info { "Imported $added media sources, ${failures.size} failed" }
        return MediaSourceImportResult.Done(added, failures)
    }

    companion object {
        private val logger = logger<MediaSourceImporter>()

        /**
         * 认三种形状: 单个 (`{factoryId, version, arguments}`)、一组 (`{mediaSources: [...]}` 或直接数组)、
         * 订阅内容 (`{exportedMediaSourceDataList: {mediaSources: [...]}}`). 解析不了返回 `null`.
         */
        fun parse(text: String): List<ExportedMediaSourceData>? {
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
}

/** [MediaSourceImporter.import] 的结果 */
sealed interface MediaSourceImportResult {
    /** 既不是数据源配置, 也不是 AniBaka 规则 */
    data object NotRecognized : MediaSourceImportResult

    /** 是数据源配置, 但里面一个都没有 */
    data object Empty : MediaSourceImportResult

    /** 导入了 [added] 个; 导不进的每个一条 [failures] */
    data class Done(val added: Int, val failures: List<MediaSourceImportFailure>) : MediaSourceImportResult
}

/** 一个数据源导不进的原因 */
sealed interface MediaSourceImportFailure {
    /** 这一版应用不认识的类型 */
    data class UnsupportedType(val factoryId: String) : MediaSourceImportFailure

    /** 只能有一个的类型 (如内置的 BT 源), 已经有了 */
    data class OnlyOneAllowed(val name: String) : MediaSourceImportFailure

    /** 其他原因 (配置不对、AniBaka 规则转换不了、下载失败), [message] 原样给用户看 */
    data class Other(val message: String) : MediaSourceImportFailure
}
