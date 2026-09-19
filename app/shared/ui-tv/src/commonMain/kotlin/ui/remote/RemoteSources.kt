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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.data.repository.media.MediaSourceInstanceRepository
import me.him188.ani.app.data.repository.media.MediaSourceSubscriptionRepository
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.media.fetch.updateMediaSourceArguments
import me.him188.ani.app.domain.mediasource.codec.ExportedMediaSourceData
import me.him188.ani.app.domain.mediasource.codec.MediaSourceCodecManager
import me.him188.ani.app.domain.mediasource.directapi.DirectApiMediaSource
import me.him188.ani.app.domain.mediasource.directapi.DirectApiMediaSourceArguments
import me.him188.ani.app.domain.mediasource.instance.MediaSourceInstance
import me.him188.ani.app.domain.mediasource.rss.RssMediaSourceArguments
import me.him188.ani.app.domain.mediasource.web.SelectorMediaSourceArguments
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.datasources.api.source.ConnectionStatus
import me.him188.ani.datasources.api.source.FactoryId
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.MediaSourceFactory
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.parameter.BooleanParameter
import me.him188.ani.datasources.api.source.parameter.MediaSourceParameter
import me.him188.ani.datasources.api.source.parameter.SimpleEnumParameter
import me.him188.ani.datasources.api.source.parameter.StringParameter
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.net.URLDecoder
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Web 控制台设置标签里的「数据源」页: 设置里数据源管理那一页的网页版, 主要解决「在电视上编辑数据源要填很多东西」.
 *
 * 全部走 [MediaSourceManager] 现成的持久化接口 (列表 / 新增 / 改配置 / 启用停用 / 删除 / 排序), 不依赖任何页面的组合,
 * 在 HTTP 线程上 `runBlocking` 即可. 与播放不冲突: 播放会话创建时对数据源列表取快照 (`createFetchFetchSession`),
 * 改动从下一个会话 (下一集 / 重新进播放器) 起生效, 所以不必禁止修改, 网页上提示一句.
 *
 * 三类编辑方式, 与电视上的设置页对应:
 * - **通用参数源** (Jellyfin 这类): 工厂声明了 [MediaSourceFactory.parameters], 网页按参数模型自动生成表单,
 *   保存方式同设置页的 `EditingMediaSource.createConfig` (参数值按名字存进 `arguments`).
 * - **RSS / 网页选择器源**: 电视上是手写的大表单 (选择器源的搜索配置顶层就有二十多项), 网页上改成直接编辑它的
 *   JSON —— 格式就是设置页「导出」出来的那种, 社区分享的配置也是这种. 保存前经编解码器解码校验并升级到当前版本.
 * - **订阅来的源只读**: 订阅每次更新都会用远端配置覆盖本地修改、排序也拉回远端顺序 (见 `MediaSourceSubscriptionUpdater`),
 *   改了也白改. 只给启用停用, 想改就「复制为本地源」.
 */
internal object RemoteSources {
    private val logger = logger<RemoteSources>()

    private val manager: MediaSourceManager get() = KoinPlatform.getKoin().get()
    private val codec: MediaSourceCodecManager get() = KoinPlatform.getKoin().get()
    private val subscriptions: MediaSourceSubscriptionRepository get() = KoinPlatform.getKoin().get()
    private val repository: MediaSourceInstanceRepository get() = KoinPlatform.getKoin().get()

    /** 用 JSON 编辑的三类 (编解码器只认它们). */
    private val RSS = FactoryId("rss")
    private val SELECTOR = FactoryId("web-selector")
    private val DIRECT_API = DirectApiMediaSource.FactoryId
    private val JSON_FACTORIES = setOf(RSS, SELECTOR, DIRECT_API)

    private val pretty = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** 处理 `api/sources` 下的请求; 路径或方法不认识返回 null. */
    fun handle(request: LanHttpRequest): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return runCatching {
            when {
                request.path == "api/sources" && get -> list()
                request.path == "api/sources/export" && get -> export(request.queryParam("id").orEmpty())
                request.path == "api/sources/template" && get -> template(request.queryParam("factoryId").orEmpty())
                !post -> null
                request.path == "api/sources/test" -> test(request)
                request.path == "api/sources/enable" -> setEnabled(request)
                request.path == "api/sources/move" -> move(request)
                request.path == "api/sources/delete" -> delete(request)
                request.path == "api/sources/copy" -> copyAsLocal(request)
                request.path == "api/sources/import" -> import(request.field("text"))
                request.path == "api/sources/save-params" -> saveParams(request)
                request.path == "api/sources/save-json" -> saveJson(request)
                else -> null
            }
        }.getOrElse {
            logger.warn(it) { "Remote sources request failed: ${request.method} ${request.path}" }
            result(false, tr("操作失败：{0}", it.message ?: it::class.simpleName))
        }
    }

    // ============================ 读 ============================

    /**
     * 当前的数据源实例 (不含本地源), **保证已经反映了最近一次写入**.
     *
     * [MediaSourceManager.allInstances] 是在仓库数据流后面接 combine、重建全部实例、再 `shareIn(replay = 1)` 的共享流:
     * 写入落盘后要等实例重建完才发出新值. 网页每次操作成功后立刻重拉列表, 直接读它会拿到缓存里的旧列表 ——
     * 2026-09-11 真机实测「上移」后立刻重读位置没变、1.5 秒后才变, 用户看到的就是「改了没生效」, 连点两次上移
     * 时第二次还会拿旧顺序算. 所以先从仓库 (落盘的真值, 写完立刻可读) 取当前状态, 再等共享流追上它, 最多等
     * 3 秒, 超时就退回当前值并记日志.
     */
    private fun instances(): List<MediaSourceInstance> = runBlocking {
        val known = manager.allFactories.mapTo(HashSet()) { it.factoryId }
        // 工厂找不到的存档不会被建成实例 (createInstance 返回 null), 比对时一并排除, 否则永远对不上
        val want = repository.flow.first()
            .filter { it.factoryId in known && !manager.isLocal(it.factoryId) }
            .map { Triple(it.instanceId, it.isEnabled, it.config) }

        fun List<MediaSourceInstance>.key() =
            filter { !manager.isLocal(it.factoryId) }.map { Triple(it.instanceId, it.isEnabled, it.config) }

        val current = manager.allInstances.first()
        val list = if (current.key() == want) {
            current
        } else {
            val mark = TimeSource.Monotonic.markNow()
            withTimeoutOrNull(3.seconds) { manager.allInstances.first { it.key() == want } }
                ?.also {
                    val waited = mark.elapsedNow()
                    if (waited.inWholeMilliseconds > 100) logger.info { "Waited $waited for media source list to catch up" }
                }
                ?: current.also { logger.warn { "Media source list did not catch up with the repository within 3s" } }
        }
        list.filter { !manager.isLocal(it.factoryId) }
    }

    private fun factoryOf(id: FactoryId): MediaSourceFactory? = manager.allFactories.find { it.factoryId == id }

    private fun list(): JsonObject {
        val instances = instances()
        val subs = runBlocking { subscriptions.flow.first() }
        return buildJsonObject {
            putJsonArray("sources") {
                for (instance in instances) addJsonObject {
                    val factory = factoryOf(instance.factoryId)
                    val subscriptionId = instance.config.subscriptionId
                    put("id", instance.instanceId)
                    put("factoryId", instance.factoryId.value)
                    put("name", instance.source.info.displayName)
                    put("description", instance.source.info.description)
                    put("kind", kindLabel(instance.source.kind))
                    put("enabled", instance.isEnabled)
                    // 来自订阅: 值是订阅地址 (找不到订阅记录时为空串); 不是订阅来的为 null
                    put("subscription", subscriptionId?.let { id -> subs.find { it.subscriptionId == id }?.url.orEmpty() })
                    val editor = editorOf(instance.factoryId, factory, fromSubscription = subscriptionId != null)
                    put("editor", editor)
                    put("exportable", instance.factoryId in JSON_FACTORIES && instance.config.serializedArguments != null)
                    if (editor == "params" && factory != null) {
                        putJsonArray("params") {
                            for (p in factory.parameters.list) addJsonObject { putParam(p, instance.config.arguments[p.name]) }
                        }
                    }
                }
            }
            // 可新增的类型: 与设置页一致 —— 不允许多实例的工厂已经有一个了就不再列; 本地源不列
            putJsonArray("templates") {
                for (factory in manager.allFactories) {
                    if (manager.isLocal(factory.factoryId)) continue
                    if (!factory.allowMultipleInstances && instances.any { it.factoryId == factory.factoryId }) continue
                    addJsonObject {
                        val editor = editorOf(factory.factoryId, factory, fromSubscription = false)
                        put("factoryId", factory.factoryId.value)
                        put("name", factory.info.displayName)
                        put("description", factory.info.description)
                        put("editor", editor)
                        if (editor == "params") {
                            putJsonArray("params") { for (p in factory.parameters.list) addJsonObject { putParam(p, null) } }
                        }
                    }
                }
            }
        }
    }

    /** "json" = RSS / 选择器源编 JSON; "params" = 按参数模型出表单; "none" = 没什么可编 (或订阅来的只读). */
    private fun editorOf(factoryId: FactoryId, factory: MediaSourceFactory?, fromSubscription: Boolean): String = when {
        fromSubscription -> "none"
        factoryId in JSON_FACTORIES -> "json"
        factory != null && factory.parameters.list.isNotEmpty() -> "params"
        else -> "none"
    }

    private fun JsonObjectBuilder.putParam(p: MediaSourceParameter<*>, persisted: String?) {
        put("name", p.name)
        put("description", p.description)
        p.visibleWhen?.let { v ->
            putJsonObject("visibleWhen") {
                put("param", v.parameterName)
                putJsonArray("values") { v.acceptedValues.forEach { add(it) } }
            }
        }
        when (p) {
            is BooleanParameter -> {
                put("type", "boolean")
                put("value", persisted?.toBooleanStrictOrNull() ?: p.default())
            }

            is SimpleEnumParameter -> {
                put("type", "enum")
                putJsonArray("options") { p.oneOf.forEach { add(it) } }
                put("value", persisted ?: p.default())
            }

            is StringParameter -> {
                put("type", "string")
                put("placeholder", p.placeholder)
                put("required", p.isRequired)
                put("value", persisted ?: p.default())
            }
        }
    }

    /** 导出一个 RSS / 选择器源: 格式同设置页「导出单个」(带 factoryId 与版本号), 缩进好方便在手机上改. */
    private fun export(id: String): JsonObject {
        val instance = instances().find { it.instanceId == id } ?: return result(false, tr("数据源不存在，请刷新"))
        val arguments = instance.config.serializedArguments
        if (instance.factoryId !in JSON_FACTORIES || arguments == null) return result(false, tr("这个数据源不支持导出"))
        val data = codec.serialize(instance.factoryId, arguments)
        return buildJsonObject {
            put("ok", true)
            put("message", "")
            put("json", pretty.encodeToString(ExportedMediaSourceData.serializer(), data))
        }
    }

    /** 新增 RSS / 选择器源时的空白模板 (默认参数). */
    private fun template(factoryId: String): JsonObject {
        val data = when (FactoryId(factoryId)) {
            RSS -> codec.encode(RssMediaSourceArguments.Default)
            SELECTOR -> codec.encode(SelectorMediaSourceArguments.Default)
            // 给一份结构完整的示例, 比空模板好改
            DIRECT_API -> codec.encode(DirectApiMediaSourceArguments.Example)
            else -> return result(false, tr("这个类型没有 JSON 模板"))
        }
        return buildJsonObject {
            put("ok", true)
            put("message", "")
            put("json", pretty.encodeToString(ExportedMediaSourceData.serializer(), data))
        }
    }

    // ============================ 写 ============================

    /**
     * 「测试连接」: 同电视设置页数据源那一行的测试 (见 MediaSourceGroupState.createConnectionTester),
     * 都是问 [MediaSource.checkConnection] —— 各类源自己决定怎么算通 (BT 站拉一次首页, Jellyfin 打一次
     * 接口, 直链源请求一次 API)。
     *
     * 给足 [TEST_TIMEOUT]: 连不上的站多半是 TCP 卡到超时而不是立刻失败, 时间给短了会把"慢"误报成"坏"
     * (电视上的连通性探测就踩过这个, 见 memory project-connectivity-probe-false-red)。
     * 停用的源也能测 —— 想先试通再启用是常事。
     */
    private fun test(request: LanHttpRequest): JsonObject {
        val id = request.field("id")
        val instance = instances().find { it.instanceId == id } ?: return result(false, tr("数据源不存在，请刷新"))
        val name = instance.source.info.displayName
        val status = runBlocking {
            withTimeoutOrNull(TEST_TIMEOUT) {
                runCatching { instance.source.checkConnection() }
                    .onFailure { logger.warn(it) { "Remote source test failed for $id" } }
                    .getOrNull()
            }
        }
        logger.info { "Remote source test: $name ($id) -> ${status?.name ?: "timeout"}" }
        return when (status) {
            ConnectionStatus.SUCCESS -> result(true, tr("「{0}」连接正常", name))
            ConnectionStatus.FAILED -> result(false, tr("「{0}」连接失败，检查地址、网络或代理设置", name))
            null -> result(false, tr("「{0}」测试超时，可能是网络不通或站点很慢", name))
        }
    }

    private fun setEnabled(request: LanHttpRequest): JsonObject {
        val enabled = request.field("enabled") == "1"
        // 网页多选: `ids` (换行分隔) 一次改完, 数据源列表只重建一次
        val ids = request.field("ids").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isNotEmpty()) {
            runBlocking { manager.setEnabled(ids, enabled) }
            return result(true, "")
        }
        runBlocking { manager.setEnabled(request.field("id"), enabled) }
        return result(true, "")
    }

    /** 上移 / 下移一格: 按当前顺序交换相邻两项后整体重排 (本地源不在列表里, 由 partiallyReorder 保持原位). */
    private fun move(request: LanHttpRequest): JsonObject {
        val id = request.field("id")
        val ids = instances().map { it.instanceId }.toMutableList()
        val from = ids.indexOf(id)
        if (from < 0) return result(false, tr("数据源不存在，请刷新"))
        val to = if (request.field("dir") == "up") from - 1 else from + 1
        if (to !in ids.indices) return result(true, "")
        ids[from] = ids[to].also { ids[to] = ids[from] }
        runBlocking { manager.partiallyReorderInstances(ids) }
        return result(true, "")
    }

    private fun delete(request: LanHttpRequest): JsonObject {
        val instance = instances().find { it.instanceId == request.field("id") } ?: return result(false, tr("数据源不存在，请刷新"))
        // 订阅来的删了也会在下次更新时回来, 不给单个删; 要删就删整个订阅 (本标签顶部的订阅块, 见 RemoteSubscriptions)
        if (instance.config.subscriptionId != null) return result(false, tr("订阅来的源会随订阅更新恢复，要删请在上面的「订阅」里删掉整个订阅"))
        runBlocking { manager.removeInstance(instance.instanceId) }
        logger.info { "Remote control removed media source ${instance.factoryId.value} ${instance.instanceId}" }
        return result(true, tr("已删除"))
    }

    /** 订阅来的源复制一份不带订阅归属的本地源, 之后就能随意编辑 (原来那个可以停用). */
    private fun copyAsLocal(request: LanHttpRequest): JsonObject {
        val instance = instances().find { it.instanceId == request.field("id") } ?: return result(false, tr("数据源不存在，请刷新"))
        val factory = factoryOf(instance.factoryId) ?: return result(false, tr("不支持的数据源类型"))
        if (!factory.allowMultipleInstances) return result(false, tr("这个类型只能有一个实例，无法复制"))
        val newId = UUID.randomUUID().toString()
        runBlocking { manager.addInstance(newId, newId, instance.factoryId, instance.config.copy(subscriptionId = null)) }
        return result(true, tr("已复制为本地源，可以编辑它；原来的订阅源可以停用"))
    }

    /**
     * 通用参数源的新增 / 编辑. 带 `id` 是编辑, 带 `factoryId` 是新增. 表单字段名为 `p:<参数名>`, 复选框没勾时不提交.
     * 校验与设置页一致 ([StringParameter.validate]), 被 visibleWhen 藏起来的字段不校验.
     */
    private fun saveParams(request: LanHttpRequest): JsonObject {
        val id = request.field("id").takeIf { it.isNotEmpty() }
        val instance = id?.let { i -> instances().find { it.instanceId == i } ?: return result(false, tr("数据源不存在，请刷新")) }
        if (instance?.config?.subscriptionId != null) return result(false, tr("订阅来的源不能编辑"))
        val factoryId = instance?.factoryId ?: FactoryId(request.field("factoryId"))
        val factory = factoryOf(factoryId) ?: return result(false, tr("不支持的数据源类型"))
        if (instance == null && !factory.allowMultipleInstances && instances().any { it.factoryId == factoryId }) {
            return result(false, tr("这个类型只能有一个实例"))
        }
        val fields = request.formFieldList()
        fun raw(name: String) = fields.lastOrNull { it.first == "p:$name" }?.second
        val arguments: Map<String, String?> = factory.parameters.list.associate { p ->
            p.name to when (p) {
                is BooleanParameter -> (raw(p.name) == "true").toString()
                else -> raw(p.name) ?: ""
            }
        }
        val invalid = factory.parameters.list.filterIsInstance<StringParameter>().filter { p ->
            val cond = p.visibleWhen
            val visible = cond == null || arguments[cond.parameterName] in cond.acceptedValues
            visible && !p.validate(arguments[p.name].orEmpty())
        }
        if (invalid.isNotEmpty()) return result(false, tr("请检查：") + invalid.joinToString("、") { it.name })

        val config = MediaSourceConfig(arguments = arguments)
        return if (instance != null) {
            runBlocking { manager.updateConfig(instance.instanceId, config) }
            result(true, tr("已保存"))
        } else {
            val newId = UUID.randomUUID().toString()
            runBlocking { manager.addInstance(newId, newId, factoryId, config) }
            logger.info { "Remote control added media source ${factoryId.value} $newId" }
            result(true, tr("已添加"))
        }
    }

    /** RSS / 选择器源的 JSON 编辑. 必须是同一类型的单个数据源; 经编解码器解码校验并升级到当前版本后写回. */
    private fun saveJson(request: LanHttpRequest): JsonObject {
        val instance = instances().find { it.instanceId == request.field("id") } ?: return result(false, tr("数据源不存在，请刷新"))
        if (instance.config.subscriptionId != null) return result(false, tr("订阅来的源不能编辑"))
        val list = parseExported(request.field("text")) ?: return result(false, tr("不是有效的数据源 JSON"))
        val data = list.singleOrNull() ?: return result(false, tr("编辑时只能粘贴一个数据源（现在有 {0} 个）", list.size))
        if (data.factoryId != instance.factoryId) {
            return result(false, tr("类型不一致：这里是 {0}，粘贴的是 {1}", instance.factoryId.value, data.factoryId.value))
        }
        val normalized = codec.encode(codec.decode(data))
        val ok = runBlocking { manager.updateMediaSourceArguments(instance.instanceId, normalized.arguments) }
        return if (ok) result(true, tr("已保存")) else result(false, tr("保存失败"))
    }

    /** 导入: 单个 / 列表 / 订阅内容都认, 每一个新建为本地源. 部分失败时照样导入其余的, 并说明失败原因. */
    private fun import(text: String): JsonObject {
        val list = parseExported(text) ?: return result(false, tr("不是有效的数据源 JSON"))
        if (list.isEmpty()) return result(false, tr("里面没有数据源"))
        val existing = instances()
        var added = 0
        val errors = mutableListOf<String>()
        for (data in list) {
            runCatching {
                val factory = factoryOf(data.factoryId) ?: error(tr("不支持的类型 {0}", data.factoryId.value))
                if (!factory.allowMultipleInstances && existing.any { it.factoryId == data.factoryId }) {
                    error(tr("{0} 只能有一个", factory.info.displayName))
                }
                val normalized = codec.encode(codec.decode(data))
                val newId = UUID.randomUUID().toString()
                runBlocking {
                    manager.addInstance(newId, newId, data.factoryId, MediaSourceConfig(serializedArguments = normalized.arguments))
                }
                added++
            }.onFailure { errors += (it.message ?: it::class.simpleName.orEmpty()) }
        }
        logger.info { "Remote control imported $added media sources, ${errors.size} failed" }
        val message = buildString {
            append(if (added > 0) tr("已导入 {0} 个数据源", added) else tr("没有导入任何数据源"))
            if (errors.isNotEmpty()) append(tr("；{0} 个失败：", errors.size)).append(errors.distinct().joinToString("；"))
        }
        // added: 大文件在网页端会被拆成多次导入, 由网页把每次的数量加起来报给用户
        return buildJsonObject {
            put("ok", added > 0)
            put("message", message)
            put("added", added)
        }
    }

    /**
     * 认三种形状: 单个 (`{factoryId, version, arguments}`)、列表 (`{mediaSources: [...]}` 或直接数组)、
     * 订阅内容 (`{exportedMediaSourceDataList: {mediaSources: [...]}}`). 解析不了返回 null.
     */
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

    // ============================ 工具 ============================

    private fun kindLabel(kind: MediaSourceKind): String = when (kind) {
        MediaSourceKind.WEB -> tr("在线")
        MediaSourceKind.BitTorrent -> "BT"
        MediaSourceKind.LocalCache -> tr("本地")
    }

    /** 测试连接的上限, 见 [test]. */
    private val TEST_TIMEOUT = 20.seconds

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    private fun LanHttpRequest.field(name: String): String =
        formFieldList().lastOrNull { it.first == name }?.second.orEmpty()

    private fun LanHttpRequest.queryParam(name: String): String? =
        query.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }
}
