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
import android.os.LocaleList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.domain.devicemigration.DeviceMigrationExporter
import me.him188.ani.app.domain.devicemigration.DeviceMigrationImporter
import me.him188.ani.app.domain.devicemigration.DeviceMigrationJson
import me.him188.ani.app.domain.devicemigration.DeviceMigrationManifest
import me.him188.ani.app.domain.devicemigration.DeviceMigrationProfile
import me.him188.ani.app.domain.devicemigration.DeviceMigrationProfileSummary
import me.him188.ani.app.domain.devicemigration.DeviceMigrationShared
import me.him188.ani.app.domain.profile.UserProfile
import me.him188.ani.app.domain.profile.UserProfileKind
import me.him188.ani.app.domain.profile.UserProfiles
import me.him188.ani.app.platform.AppLocales
import me.him188.ani.app.platform.AppRestarter
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.foundation.lan.LanHttpResponse
import me.him188.ani.app.ui.onboarding.TvOnboardingGate
import me.him188.ani.app.ui.profile.ProfileImportSession
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.toKtPath
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Web 控制台「设置 → 维护 → 换电视」: 新电视经局域网从旧电视取数据, 一次搬完用户、收藏与播放记录、设置、数据源与登录.
 *
 * 每台电视两头都有:
 * - 旧电视交数据 (`api/migrate/export/…`, 见 [DeviceMigrationExporter]): 清单、整机数据、每个用户的配置与库文件快照;
 * - 新电视取数据 (`api/migrate/preview|start|status|cancel`): 手机上把旧电视控制台的地址粘给新电视, 新电视自己去取 ——
 *   手机网页跨不了两台电视的地址 (浏览器的跨域限制), 电视对电视直接传也不怕手机中途锁屏. 地址里的 token 就是旧电视的授权, 只用这一次, 不存.
 *
 * 新电视先全部下载完 (中途失败什么都没写), 再写进自己 ([DeviceMigrationImporter]), 然后重启 (1 号的库是开着时换的).
 * 写之前要在前台: 重启要起界面, 不在前台时系统不许 (同控制台切换用户).
 */
internal object RemoteDeviceMigration {
    private val logger = logger<RemoteDeviceMigration>()

    private const val EXPORT_PREFIX = "api/migrate/export/"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("RemoteDeviceMigration"))

    private val koin get() = KoinPlatform.getKoin()
    private val context: Context get() = koin.get()

    /** 处理 `api/migrate/` 下的请求; 路径或方法不认识返回 null. */
    fun handle(request: LanHttpRequest): LanHttpResponse? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return when {
            request.path == EXPORT_PREFIX + "manifest" && get -> payload(DeviceMigrationManifest.serializer(), runBlocking { exporter.manifest() })
            request.path == EXPORT_PREFIX + "shared" && get -> exportShared()
            request.path == EXPORT_PREFIX + "profile" && get -> exportProfile(request)
            request.path == EXPORT_PREFIX + "database" && get -> exportDatabase(request)
            request.path == "api/migrate/preview" && post -> json(preview(request))
            request.path == "api/migrate/start" && post -> json(start(request))
            request.path == "api/migrate/status" && get -> json(status())
            request.path == "api/migrate/cancel" && post -> json(cancel())
            else -> null
        }
    }

    // ---------------------------- 旧电视: 交数据 ----------------------------

    private val exporter: DeviceMigrationExporter get() = koin.get()

    /** 整机数据, 加上应用内语言 (`LocaleList.toLanguageTags`). */
    private fun exportShared(): LanHttpResponse {
        val shared = runBlocking { exporter.shared() }.copy(appLanguage = AppLocales.get(context).toLanguageTags())
        logger.info { "Device migration: exporting shared data" }
        return payload(DeviceMigrationShared.serializer(), shared)
    }

    /** 一个用户, 加上控制台里删掉的播放记录 (见 [RemoteHistory.hiddenEntries]). */
    private fun exportProfile(request: LanHttpRequest): LanHttpResponse {
        val id = request.queryParam("id")?.toIntOrNull() ?: return LanHttpResponse.status(400, "Bad Request")
        val profile = runBlocking { exporter.profile(id) } ?: return LanHttpResponse.status(404, "Not Found")
        logger.info { "Device migration: exporting user profile $id" }
        return payload(DeviceMigrationProfile.serializer(), profile.copy(hiddenHistory = RemoteHistory.hiddenEntries(profile.profile)))
    }

    /** 库文件快照: 先拍到临时文件 (定长, 浏览器与新电视按长度判断收全没有), 发完删掉. */
    private fun exportDatabase(request: LanHttpRequest): LanHttpResponse {
        val id = request.queryParam("id")?.toIntOrNull() ?: return LanHttpResponse.status(400, "Bad Request")
        val file = File(workDir(), "export-$id-${System.nanoTime()}.db")
        val found = try {
            runBlocking { exporter.snapshotDatabase(id, file.toKtPath().inSystem) }
        } catch (e: Exception) {
            file.delete()
            logger.error(e) { "Device migration: failed to snapshot the database of user profile $id" }
            return LanHttpResponse.status(500, "Internal Server Error")
        }
        if (!found) return LanHttpResponse.status(404, "Not Found")
        val length = file.length()
        logger.info { "Device migration: exporting the database of user profile $id ($length bytes)" }
        return LanHttpResponse(200, "OK", "application/octet-stream", length) { output ->
            try {
                file.inputStream().use { it.copyTo(output, BUFFER_SIZE) }
            } finally {
                file.delete()
            }
        }
    }

    // ---------------------------- 新电视: 取数据 ----------------------------

    private val importer: DeviceMigrationImporter get() = koin.get()

    private sealed interface State {
        data object Idle : State

        /** @property cancellable 还在下载 (什么都没写), 能停 */
        data class Running(val text: String, val done: Long = 0, val total: Long = 0, val cancellable: Boolean = true) : State

        data class Finished(val ok: Boolean, val message: String) : State
    }

    @Volatile
    private var state: State = State.Idle

    @Volatile
    private var job: Job? = null

    /** 搬运过程中出的、要原样告诉用户的错. */
    private class MigrationException(message: String) : Exception(message)

    /** 旧电视的地址 (`http://IP:端口/token/`) 与它的清单, 手机上确认用. */
    private fun preview(request: LanHttpRequest): JsonObject {
        val (source, error) = sourceOf(request)
        if (source == null) return result(false, error.orEmpty())
        return try {
            val (manifest, takeOver) = runBlocking { inspect(source) }
            buildJsonObject {
                put("ok", true)
                putJsonArray("profiles") {
                    for (profile in manifest.profiles) addJsonObject {
                        put("name", profile.name.ifBlank { tr("用户 {0}", profile.id) })
                        put("detail", describe(profile, takeOver && profile.id == UserProfile.PRIMARY_ID))
                    }
                }
                putJsonArray("notes") {
                    add(tr("旧电视：Izuko {0}", manifest.appVersion))
                    add(tr("{0} 个数据源、{1} 个订阅，连同设置、网盘与 PikPak 账号、弹幕屏蔽词一起搬过来", manifest.mediaSources, manifest.subscriptions))
                    add(
                        if (takeOver) tr("这台电视还没有人用过，旧电视的 1 号用户直接放进这台的 1 号用户。")
                        else tr("这台电视原有的用户与收藏不动，旧电视的用户作为新用户加进来。"),
                    )
                    add(tr("这台电视的设置会换成旧电视的，数据源与订阅两边合并。缓存的视频不搬。"))
                    add(tr("搬完这台电视会重启。旧电视上的数据不动；同一个 Bangumi 账号在两台电视上都用的话，其中一台过几天可能要重新登录。"))
                }
            }
        } catch (e: MigrationException) {
            result(false, e.message.orEmpty())
        }
    }

    private fun describe(profile: DeviceMigrationProfileSummary, takeOver: Boolean): String {
        val kind = when {
            profile.kind == UserProfileKind.LOCAL -> tr("本地用户")
            profile.loggedIn -> tr("Bangumi，已登录")
            else -> tr("Bangumi，没登录")
        }
        val counts = tr("收藏 {0} 部，播放记录 {1} 条", profile.collections, profile.playbackRecords)
        val target = if (takeOver) tr("放进这台的 1 号用户") else tr("作为新用户加进来")
        return "$kind · $counts → $target"
    }

    private fun start(request: LanHttpRequest): JsonObject {
        val (source, error) = sourceOf(request)
        if (source == null) return result(false, error.orEmpty())
        synchronized(this) {
            if (job?.isActive == true) return result(false, tr("正在搬，搬完再试"))
            if (ProfileImportSession.isRunning) return result(false, tr("正在导入收藏，导完再搬"))
            if (!TvRemoteControl.bringToFrontForRestart()) {
                return result(false, tr("电视上没有显示 Izuko。先在电视上打开 Izuko 再试"))
            }
            state = State.Running(tr("正在连接旧电视…"))
            job = scope.launch { migrate(source) }
        }
        return result(true, tr("开始搬了"))
    }

    private fun cancel(): JsonObject = synchronized(this) {
        val running = state as? State.Running
        if (running == null || !running.cancellable) return result(false, tr("现在停不了"))
        job?.cancel()
        result(true, tr("已停止，这台电视上什么都没改"))
    }

    private fun status(): JsonObject = when (val s = state) {
        State.Idle -> buildJsonObject { put("state", "idle") }
        is State.Running -> buildJsonObject {
            put("state", "running")
            put("text", s.text)
            put("done", s.done)
            put("total", s.total)
            put("cancellable", s.cancellable)
        }

        is State.Finished -> buildJsonObject {
            put("state", if (s.ok) "done" else "failed")
            put("message", s.message)
        }
    }

    private suspend fun migrate(source: String) {
        val dir = File(workDir(), "import-${System.nanoTime()}").apply { mkdirs() }
        try {
            val (manifest, takeOver) = inspect(source)
            state = State.Running(tr("正在下载设置和数据源…"))
            val shared = fetch(source + EXPORT_PREFIX + "shared", DeviceMigrationShared.serializer())
            val profiles = manifest.profiles.map { summary ->
                val name = summary.name.ifBlank { tr("用户 {0}", summary.id) }
                val label = tr("正在下载「{0}」的数据…", name)
                state = State.Running(label)
                val payload = fetch(source + EXPORT_PREFIX + "profile?id=${summary.id}", DeviceMigrationProfile.serializer())
                val database = File(dir, "user-${summary.id}.db")
                download(source + EXPORT_PREFIX + "database?id=${summary.id}", database) { done, total ->
                    state = State.Running(label, done, total)
                }
                payload to database
            }
            // 从这里起不能停: 停在中间会留下一半. 与 cancel 互斥, 免得那边刚回了「什么都没改」这边又写了
            val coroutine = coroutineContext
            synchronized(this) {
                coroutine.ensureActive()
                state = State.Running(tr("正在写入…"), cancellable = false)
            }
            withContext(NonCancellable) {
                apply(shared, profiles, takeOver)
                logger.info { "Device migration finished: ${profiles.size} user profiles, takeOver=$takeOver" }
                state = State.Finished(true, tr("搬完了，电视马上重启"))
                restart()
            }
        } catch (e: CancellationException) {
            state = State.Finished(false, tr("已停止，这台电视上什么都没改"))
            logger.info { "Device migration cancelled" }
        } catch (e: MigrationException) {
            state = State.Finished(false, e.message.orEmpty())
            logger.warn { "Device migration failed: ${e.message}" }
        } catch (e: Exception) {
            state = State.Finished(false, tr("搬的时候出错了：{0}", e.message ?: e::class.simpleName))
            logger.error(e) { "Device migration failed" }
        } finally {
            withContext(NonCancellable) { dir.deleteRecursively() }
        }
    }

    /** 取旧电视的清单并检查能不能搬; 顺带定下旧电视的 1 号放不放进这台的 1 号. */
    private suspend fun inspect(source: String): Pair<DeviceMigrationManifest, Boolean> {
        val manifest = fetch(source + EXPORT_PREFIX + "manifest", DeviceMigrationManifest.serializer())
        if (manifest.format != DeviceMigrationManifest.FORMAT || manifest.version > DeviceMigrationManifest.VERSION) {
            throw MigrationException(tr("旧电视上的 Izuko 比这台新，先在这台的控制台「维护 → 应用更新」里更新"))
        }
        if (manifest.databaseVersion > importer.databaseVersion()) {
            throw MigrationException(tr("旧电视上的 Izuko 比这台新，先在这台的控制台「维护 → 应用更新」里更新"))
        }
        return manifest to importer.canTakeOverPrimary()
    }

    private suspend fun apply(shared: DeviceMigrationShared, profiles: List<Pair<DeviceMigrationProfile, File>>, takeOver: Boolean) {
        importer.applyShared(shared)
        for ((payload, database) in profiles) {
            val target = importer.applyProfile(
                payload,
                database.toKtPath().inSystem,
                takeOverPrimary = takeOver && payload.profile.isPrimary,
            )
            RemoteHistory.setHiddenEntries(target, payload.hiddenHistory)
        }
        // 设置换成了做完引导的那台的, 这台不用再走一遍
        TvOnboardingGate.markDoneNow(context)
        val language = LocaleList.forLanguageTags(shared.appLanguage)
        if (language != AppLocales.get(context)) AppLocales.set(context, language)
    }

    /** 重启 (1 号的库是开着时换的, 各处的内存缓存要重来). 叫不回前台就留给用户自己重开. */
    private suspend fun restart() {
        // 先让网页读到「搬完了」
        delay(RESTART_DELAY)
        val restarter = koin.getOrNull<AppRestarter>()
        if (restarter == null || !restarter.isSupported || !TvRemoteControl.bringToFrontForRestart()) {
            state = State.Finished(true, tr("搬完了。请在电视上退出 Izuko 再重新打开"))
            return
        }
        logger.info { "Device migration: restarting (current user profile ${UserProfiles.currentId})" }
        restarter.restart()
    }

    /** 网页粘过来的旧电视地址; 不对时直接回给网页的提示. */
    private fun sourceOf(request: LanHttpRequest): Pair<String?, String?> {
        val source = parseSource(request.formFields()["url"].orEmpty()) ?: return null to tr("请粘贴旧电视控制台的完整地址")
        if (tokenOf(source) == TvRemoteControl.url.value?.let(::tokenOf)) {
            return null to tr("这是这台电视自己的地址。请在旧电视的控制台里复制它的地址")
        }
        return source to null
    }

    /** 粘过来的文字里找控制台的地址, 整理成 `http://IP:端口/token/`; 找不到时为 null. */
    internal fun parseSource(text: String): String? {
        val match = URL_PATTERN.find(text) ?: return null
        val uri = try {
            URI(match.value)
        } catch (e: Exception) {
            return null
        }
        val host = uri.host ?: return null
        val token = tokenOf(match.value)?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "http://$host$port/$token/"
    }

    private fun tokenOf(url: String): String? =
        runCatching { URI(url).path.orEmpty().trim('/').substringBefore('/') }.getOrNull()

    private suspend fun <T> fetch(url: String, serializer: KSerializer<T>): T = withContext(Dispatchers.IO) {
        val connection = open(url)
        try {
            checkStatus(connection)
            val text = connection.inputStream.use { it.readBytes() }.decodeToString()
            try {
                DeviceMigrationJson.decodeFromString(serializer, text)
            } catch (e: SerializationException) {
                throw MigrationException(tr("旧电视给的数据读不懂，两台电视都更新到最新版再试"))
            }
        } catch (e: IOException) {
            throw unreachable(e)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun download(url: String, target: File, onProgress: (done: Long, total: Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val connection = open(url)
            try {
                checkStatus(connection)
                val total = connection.contentLengthLong
                var done = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                    }
                }
                if (total >= 0 && done != total) throw MigrationException(tr("没收全，网络断了一下，再试一次"))
            } catch (e: IOException) {
                throw unreachable(e)
            } finally {
                connection.disconnect()
            }
        }

    /** 局域网直连, 不走应用里设的代理. */
    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            // 旧电视拍库快照要几秒
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = false
        }

    private fun checkStatus(connection: HttpURLConnection) {
        when (val code = connection.responseCode) {
            200 -> Unit
            // token 不对 (地址重置过) 时控制台回 404
            404 -> throw MigrationException(tr("这个地址打不开，可能旧电视控制台的地址变了。在旧电视的控制台里重新复制"))
            // 有 token 但不认得这个接口: 旧电视的版本没有换电视
            405 -> throw MigrationException(tr("旧电视上的 Izuko 没有换电视功能，先在它的控制台「维护 → 应用更新」里更新"))
            else -> throw MigrationException(tr("旧电视出错了（HTTP {0}），稍后再试", code))
        }
    }

    private fun unreachable(e: IOException): MigrationException {
        logger.warn { "Device migration: source unreachable: ${e::class.simpleName}" }
        return MigrationException(tr("连不上旧电视。确认两台电视在同一个网络里，旧电视上 Izuko 开着"))
    }

    private fun workDir(): File = File(context.cacheDir, "device-migration").apply { mkdirs() }

    private fun <T> payload(serializer: KSerializer<T>, value: T): LanHttpResponse =
        LanHttpResponse.bytes(DeviceMigrationJson.encodeToString(serializer, value).toByteArray(), "application/json; charset=utf-8")

    private fun json(obj: JsonObject): LanHttpResponse =
        LanHttpResponse.bytes(obj.toString().toByteArray(), "application/json; charset=utf-8")

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    private fun LanHttpRequest.queryParam(name: String): String? =
        query.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }

    /** 到第一个不会出现在地址里的字符为止 (粘过来的文字里地址后面可能紧跟着中文标点). */
    private val URL_PATTERN = Regex("""https?://[A-Za-z0-9.\-:\[\]]+(?:/[A-Za-z0-9_\-]*)*""")
    private const val BUFFER_SIZE = 64 * 1024
    private const val CONNECT_TIMEOUT_MILLIS = 5_000
    private const val READ_TIMEOUT_MILLIS = 120_000
    private val RESTART_DELAY = 2500.milliseconds
}
