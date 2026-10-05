/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.data.models.preference.AnitorrentConfig
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_media_alliance
import me.him188.ani.app.ui.lang.settings_media_alliance_description
import me.him188.ani.app.ui.lang.settings_media_any
import me.him188.ani.app.ui.lang.settings_media_excluded_alliance
import me.him188.ani.app.ui.lang.settings_media_excluded_alliance_description
import me.him188.ani.app.ui.lang.settings_media_excluded_alliance_none
import me.him188.ani.app.ui.lang.settings_media_torrent_extra_trackers
import me.him188.ani.app.ui.lang.settings_media_torrent_extra_trackers_dialog_description
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * Web 控制台「设置」里的通用设置项清单: 登记一行 (哪个设置、哪个字段、用设置页的哪条文案), 网页上就有对应的卡片,
 * 不用另写网页代码, 也不用补译文 (标题与说明用设置页的文案, 本来就有各语言).
 *
 * 只登记改了就生效、不用另做校验或确认的字段; 要测试连接、检查格式、二次确认的 (代理、tracker、镜像) 在 [RemoteSettings] 里单独写.
 * 字段名写错或改名时 `RemoteSettingsCatalogTest` 会失败.
 */
internal object RemoteSettingsCatalog {
    val items: List<RemoteSettingSpec<*>> = listOf(
        RemoteSettingSpec(
            key = "extraTrackers",
            settings = { anitorrentConfig },
            serializer = AnitorrentConfig.serializer(),
            fieldName = "extraTrackers",
            title = Lang.settings_media_torrent_extra_trackers,
            description = Lang.settings_media_torrent_extra_trackers_dialog_description,
            // BT 下载开始前与内置 tracker 一起添加 (见 AnitorrentEngine)
            editor = RemoteSettingEditor.Lines(accepts = ::isTrackerUrl, invalidMessage = "这一行不像 tracker 地址：{0}"),
        ),
        RemoteSettingSpec(
            key = "preferredAlliances",
            settings = { defaultMediaPreference },
            serializer = MediaPreference.serializer(),
            fieldName = "alliancePatterns",
            title = Lang.settings_media_alliance,
            description = Lang.settings_media_alliance_description,
            placeholder = Lang.settings_media_any,
            // 设置页那一条用逗号分隔, 两种都认; 顺序就是优先级
            editor = RemoteSettingEditor.Lines(splitCommas = true),
        ),
        RemoteSettingSpec(
            key = "excludedAlliances",
            settings = { defaultMediaPreference },
            serializer = MediaPreference.serializer(),
            fieldName = "excludedAlliancePatterns",
            title = Lang.settings_media_excluded_alliance,
            description = Lang.settings_media_excluded_alliance_description,
            placeholder = Lang.settings_media_excluded_alliance_none,
            editor = RemoteSettingEditor.Lines(splitCommas = true),
        ),
    )

    /**
     * 只在 debug 包「设置 → 调试」里出现的已有设置项 (见 [RemoteDebugSettings]): 开发时要反复切的设置临时登记在这里, 写法同 [items],
     * 不用去电视设置页一层层找; 用完删掉. key 不能与 [items] 重名.
     */
    val debugItems: List<RemoteSettingSpec<*>> = listOf()

    private val TRACKER_SCHEMES = listOf("udp://", "http://", "https://", "ws://", "wss://")

    private fun isTrackerUrl(line: String): Boolean = TRACKER_SCHEMES.any { line.startsWith(it, ignoreCase = true) }
}

/** 网页上怎么改这一项. 不写时按字段类型定 (见 [RemoteSettingSpec.resolvedEditor]). */
internal sealed interface RemoteSettingEditor {
    /** 网页上的类型名, 网页按它画输入框. */
    val type: String

    /**
     * 每行一项的多行输入, [splitCommas] 时逗号也算分隔. 空白项不留, 重复的只留一个.
     * 字段可以是文字列表, 也可以是按行拼起来的一个字符串.
     *
     * @param accepts 每行要满足的格式; 有一行不满足就整次不存, 提示 [invalidMessage] (简体原文, 走网页译文表, `{0}` 是那一行).
     */
    class Lines(
        val splitCommas: Boolean = false,
        val accepts: ((String) -> Boolean)? = null,
        val invalidMessage: String = "格式不对：{0}",
    ) : RemoteSettingEditor {
        override val type: String get() = "lines"
    }

    /** 一行文字. 字段可为 null 时, 留空存 null. */
    data object Text : RemoteSettingEditor {
        override val type: String get() = "text"
    }

    /** 开关, 改了立即保存. */
    data object Toggle : RemoteSettingEditor {
        override val type: String get() = "toggle"
    }

    /** 数字, 可以限定范围. */
    data class Number(val min: Double? = null, val max: Double? = null) : RemoteSettingEditor {
        override val type: String get() = "number"
    }

    /** 枚举下拉, 改了立即保存; [labels] 是各个枚举名对应的设置页文案, 没给的显示枚举名. */
    data class Choice(val labels: Map<String, StringResource> = emptyMap()) : RemoteSettingEditor {
        override val type: String get() = "choice"
    }
}

/**
 * 清单里的一项.
 *
 * @param key 网页与接口里用的名字, 清单里唯一.
 * @param fieldName 数据类里的字段名 (序列化名, 一般就是属性名).
 */
internal class RemoteSettingSpec<T>(
    val key: String,
    val settings: SettingsRepository.() -> Settings<T>,
    val serializer: KSerializer<T>,
    val fieldName: String,
    val title: StringResource,
    val description: StringResource? = null,
    val placeholder: StringResource? = null,
    val editor: RemoteSettingEditor? = null,
) {
    /** 字段的序列化描述; 没有这个字段时抛异常. */
    val fieldDescriptor: SerialDescriptor
        get() {
            val descriptor = serializer.descriptor
            val index = descriptor.getElementIndex(fieldName)
            require(index != CompositeDecoder.UNKNOWN_NAME) { "${descriptor.serialName} 没有字段 $fieldName" }
            return descriptor.getElementDescriptor(index)
        }

    /** 网页上的输入方式: 写了的先检查与字段类型对得上, 没写的按字段类型定. */
    val resolvedEditor: RemoteSettingEditor
        get() {
            val d = fieldDescriptor
            val inferred = when {
                d.kind == PrimitiveKind.BOOLEAN -> RemoteSettingEditor.Toggle
                d.kind == PrimitiveKind.STRING -> RemoteSettingEditor.Text
                d.kind in NUMBER_KINDS -> RemoteSettingEditor.Number()
                d.kind == SerialKind.ENUM -> RemoteSettingEditor.Choice()
                d.kind == StructureKind.LIST && d.getElementDescriptor(0).kind == PrimitiveKind.STRING -> RemoteSettingEditor.Lines()
                else -> throw IllegalArgumentException("$key: 字段 $fieldName 的类型 ${d.serialName} 网页上没法改")
            }
            val declared = editor ?: return inferred
            if (declared is RemoteSettingEditor.Lines && d.kind == PrimitiveKind.STRING) return declared // 按行拼起来的字符串
            require(declared::class == inferred::class) { "$key: 字段 $fieldName 是 ${d.serialName}, 不能用 ${declared.type} 改" }
            return declared
        }

    /** 当前值里这个字段的 JSON. */
    fun read(value: T): JsonElement = RemoteGenericSettings.json.encodeToJsonElement(serializer, value).jsonObject[fieldName] ?: JsonNull

    /** 只换掉这个字段, 其余照旧. */
    fun write(value: T, element: JsonElement): T {
        val json = RemoteGenericSettings.json
        val fields = json.encodeToJsonElement(serializer, value).jsonObject
        return json.decodeFromJsonElement(serializer, JsonObject(fields + (fieldName to element)))
    }

    private companion object {
        val NUMBER_KINDS = setOf(
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG,
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE,
        )
    }
}

/**
 * [RemoteSettingsCatalog] 的读写: 读出各项给网页 (标题 / 说明 / 输入方式 / 当前值), 网页改了按 key 写回.
 *
 * 不靠反射: 设置都是 @Serializable 的数据类 (DataStore 本来就这么存), 按字段名把当前值编成 JSON、换掉这一个字段再解回去写入.
 */
internal object RemoteGenericSettings {
    /** 带上默认值编码, 解码时字段齐全; 与 DataStore 存的格式无关, 只用来换字段. */
    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** 给网页的清单 ([specs] 默认是正式清单; 调试区传 [RemoteSettingsCatalog.debugItems]). */
    suspend fun describe(repository: SettingsRepository, specs: List<RemoteSettingSpec<*>> = RemoteSettingsCatalog.items): JsonArray {
        val items = specs.map { describe(it, repository) }
        return JsonArray(items)
    }

    private suspend fun <T> describe(spec: RemoteSettingSpec<T>, repository: SettingsRepository): JsonObject {
        val editor = spec.resolvedEditor
        val stored = spec.read(spec.settings(repository).flow.first())
        // 多行一律给网页列表: 按行拼起来存的字符串在这里拆开
        val value = if (editor is RemoteSettingEditor.Lines && stored is JsonPrimitive && stored.isString) {
            JsonArray(splitLines(stored.content, splitCommas = false).map(::JsonPrimitive))
        } else {
            stored
        }
        val title = getString(spec.title)
        val description = spec.description?.let { getString(it) }
        val placeholder = spec.placeholder?.let { getString(it) }
        val choices = (editor as? RemoteSettingEditor.Choice)?.let { choice ->
            val d = spec.fieldDescriptor
            (0 until d.elementsCount).map { d.getElementName(it) }.map { name ->
                name to (choice.labels[name]?.let { getString(it) } ?: name)
            }
        }
        return buildJsonObject {
            put("key", spec.key)
            put("title", title)
            description?.let { put("description", it) }
            placeholder?.let { put("placeholder", it) }
            put("editor", editor.type)
            put("value", value)
            if (editor is RemoteSettingEditor.Number) {
                editor.min?.let { put("min", it) }
                editor.max?.let { put("max", it) }
                // 小数字段: 网页数字框默认只收整数
                if (spec.fieldDescriptor.kind == PrimitiveKind.FLOAT || spec.fieldDescriptor.kind == PrimitiveKind.DOUBLE) put("step", "any")
            }
            if (choices != null) {
                putJsonArray("choices") {
                    for ((name, label) in choices) addJsonObject {
                        put("value", name)
                        put("label", label)
                    }
                }
            }
        }
    }

    /** 网页提交的一项: [raw] 是表单里的原文 (多行 / 文字 / "1"=开 / 数字 / 枚举名), 在 [specs] 里按 key 找. */
    fun set(
        repository: SettingsRepository,
        key: String,
        raw: String,
        specs: List<RemoteSettingSpec<*>> = RemoteSettingsCatalog.items,
    ): JsonObject {
        val spec = specs.firstOrNull { it.key == key }
            ?: return result(false, tr("没有这一项设置：{0}", key))
        return runBlocking { setTyped(spec, repository, raw) }
    }

    private suspend fun <T> setTyped(spec: RemoteSettingSpec<T>, repository: SettingsRepository, raw: String): JsonObject {
        val element = when (val parsed = parse(spec, raw)) {
            is Parsed.Value -> parsed.element
            is Parsed.Error -> return result(false, parsed.message)
        }
        spec.settings(repository).update { spec.write(this, element) }
        return result(true, tr("已保存「{0}」", getString(spec.title)))
    }

    sealed interface Parsed {
        class Value(val element: JsonElement) : Parsed
        class Error(val message: String) : Parsed
    }

    /** 网页提交的原文换成字段的 JSON; 格式不对时给出提示. */
    fun parse(spec: RemoteSettingSpec<*>, raw: String): Parsed {
        val d = spec.fieldDescriptor
        return when (val editor = spec.resolvedEditor) {
            is RemoteSettingEditor.Lines -> {
                val lines = splitLines(raw, editor.splitCommas)
                val bad = editor.accepts?.let { accepts -> lines.firstOrNull { !accepts(it) } }
                when {
                    bad != null -> Parsed.Error(tr(editor.invalidMessage, bad))
                    d.kind == PrimitiveKind.STRING -> Parsed.Value(JsonPrimitive(lines.joinToString("\n")))
                    else -> Parsed.Value(JsonArray(lines.map(::JsonPrimitive)))
                }
            }

            RemoteSettingEditor.Text -> Parsed.Value(if (raw.isEmpty() && d.isNullable) JsonNull else JsonPrimitive(raw))
            RemoteSettingEditor.Toggle -> Parsed.Value(JsonPrimitive(raw == "1" || raw.equals("true", ignoreCase = true)))
            is RemoteSettingEditor.Number -> parseNumber(d, editor, raw.trim())
            is RemoteSettingEditor.Choice -> {
                val names = (0 until d.elementsCount).map { d.getElementName(it) }
                if (raw in names) Parsed.Value(JsonPrimitive(raw)) else Parsed.Error(tr("没有这个选项：{0}", raw))
            }
        }
    }

    private fun parseNumber(d: SerialDescriptor, editor: RemoteSettingEditor.Number, raw: String): Parsed {
        val integral = d.kind != PrimitiveKind.FLOAT && d.kind != PrimitiveKind.DOUBLE
        val number: kotlin.Number = (if (integral) raw.toLongOrNull() else raw.toDoubleOrNull())
            ?: return Parsed.Error(tr("不是有效的数字：{0}", raw))
        val v = number.toDouble()
        if ((editor.min != null && v < editor.min) || (editor.max != null && v > editor.max)) {
            return Parsed.Error(tr("要在 {0} 到 {1} 之间", editor.min ?: "", editor.max ?: ""))
        }
        return Parsed.Value(JsonPrimitive(number))
    }

    /** 每行一项 ([splitCommas] 时逗号也算), 去掉首尾空白与空项, 重复的只留第一个. */
    fun splitLines(raw: String, splitCommas: Boolean): List<String> {
        val separators = if (splitCommas) charArrayOf('\n', ',', '，') else charArrayOf('\n')
        return raw.split(*separators).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }
}
