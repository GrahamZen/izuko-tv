/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.diagnostics

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.app.ui.remote.tr
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 「性能诊断」: 在 Web 控制台「设置 → 维护 → 性能诊断」里点, 电视上「设置 → 日志 → 性能诊断」是入口 (显示码与状态).
 *
 * - 设备体检 ([TvPerfHealth]): 一次性读状态, 几秒出结论.
 * - 录制 ([TvPerfRecorder] + [PerfAnalysis]): 用户在电视上照常操作 N 秒, 结束后把每个慢帧对到 GC / 主线程缺页 / 主线程卡住 /
 *   内存不足通知上, 再看主线程、RenderThread、GPU 各段耗时, 给出卡顿原因.
 *
 * 平时什么都不跑: 只有点了才采集, 结束就停. 报告存在 `files/perf-diagnostics/` (留最近 [MAX_REPORTS] 份), 应用重启后还能下载.
 * 窗口由 Web 控制台装到界面上时 ([attach]) 登记 (逐帧统计要挂在 Activity 的窗口上).
 */
internal object TvPerfDiagnostics {
    private val logger = logger<TvPerfDiagnostics>()
    private const val MAX_REPORTS = 12
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prettyJson = Json { prettyPrint = true }

    sealed interface Status {
        data object Idle : Status

        /** [endsAtMillis] = System.currentTimeMillis 时钟. */
        data class Recording(val seconds: Int, val endsAtMillis: Long) : Status

        data object CheckingHealth : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 最近一份报告的第一条结论 (电视上的入口显示), 没有报告时为 null. */
    private val _latestHeadline = MutableStateFlow<String?>(null)
    val latestHeadline: StateFlow<String?> = _latestHeadline.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    // 只在主线程读写
    private var activityRef: WeakReference<Activity>? = null
    private var recorder: TvPerfRecorder? = null
    private var recordingJob: Job? = null

    /** 当前页面名 (导航栈顶), 由 Web 控制台登记 (它手里有导航器). */
    @Volatile
    var routeName: (() -> String?)? = null

    /**
     * 界面装上时 (Web 控制台的 install, 传进来的是 Activity) 登记: 录制时把逐帧统计挂到它的窗口上.
     * 界面销毁后弱引用里可能还是旧的 Activity, 开始录制前按生命周期判断它还在不在前台.
     */
    fun attach(context: Context) {
        val activity = context.findActivity()
        appContext = context.applicationContext
        onMain { activityRef = activity?.let { WeakReference(it) } }
        scope.launch { refreshLatestHeadline() }
    }

    // ------------------------------------------------------------------ 录制

    /** 开始录制 [seconds] 秒; 返回 null = 已开始, 否则是不能开始的原因. */
    fun startRecording(seconds: Int): String? {
        val context = appContext ?: return tr("电视上的 Izuko 还没打开界面")
        val error = onMain {
            if (recorder != null) return@onMain tr("正在录制中")
            val activity = activityRef?.get()
                ?: return@onMain tr("电视上的 Izuko 还没打开界面")
            val resumed = (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true
            if (!resumed) return@onMain tr("电视当前没有显示 Izuko，切回 Izuko 再录制")
            val window = activity.window ?: return@onMain tr("电视上的 Izuko 还没打开界面")
            val decor = window.decorView
            val r = TvPerfRecorder(
                context = context,
                window = window,
                refreshHz = currentDisplay(activity)?.refreshRate ?: 0f,
                windowSize = "${decor.width}x${decor.height}",
            )
            r.start()
            recorder = r
            null
        }
        if (error != null) return error
        val pageAtStart = currentRoute()
        _status.value = Status.Recording(seconds, System.currentTimeMillis() + seconds * 1000L)
        recordingJob = scope.launch {
            delay(seconds * 1000L)
            finishRecording(seconds, pageAtStart)
        }
        return null
    }

    /** 提前结束 (录到的照样分析). */
    fun stopRecording() {
        val job = recordingJob ?: return
        if (!job.isActive) return
        job.cancel()
        val status = _status.value as? Status.Recording ?: return
        val elapsed = status.seconds - ((status.endsAtMillis - System.currentTimeMillis()) / 1000).toInt()
        scope.launch { finishRecording(elapsed.coerceIn(1, status.seconds), currentRoute()) }
    }

    private fun finishRecording(seconds: Int, pageAtStart: String?) {
        val r = onMain { recorder.also { recorder = null } } ?: return
        try {
            val recording = r.stop()
            val result = PerfAnalysis.analyze(recording)
            val pageAtEnd = currentRoute()
            val page = listOfNotNull(pageAtStart, pageAtEnd).distinct().joinToString(" → ").ifEmpty { "?" }
            save("recording", seconds, page, result.json)
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to finish perf recording" }
        } finally {
            _status.value = Status.Idle
        }
    }

    // ------------------------------------------------------------------ 体检

    /** 跑一次设备体检 (几秒), 返回整份报告. */
    fun runHealthCheck(): JsonObject? {
        val context = appContext ?: return null
        if (_status.value is Status.Recording) return null
        _status.value = Status.CheckingHealth
        return try {
            val activity = onMain { activityRef?.get() }
            val body = TvPerfHealth.run(context, activity)
            save("health", 0, currentRoute() ?: "?", body)
        } finally {
            _status.value = Status.Idle
        }
    }

    // ------------------------------------------------------------------ 报告

    private fun dir(): File? = appContext?.let { File(it.filesDir, "perf-diagnostics").apply { mkdirs() } }

    private fun save(kind: String, seconds: Int, page: String, body: JsonObject): JsonObject {
        val now = Date()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(now) + "-" + kind
        val report = buildJsonObject {
            put("id", id)
            put("kind", kind)
            put("time", SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(now))
            put("seconds", seconds)
            put("page", page)
            putJsonObject("app") {
                put("version", currentAniBuildConfig.versionName)
                put("package", appContext?.packageName)
                put("debug", currentAniBuildConfig.isDebug)
            }
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            putJsonObject("settings") { settingsSnapshot() }
            for ((k, v) in body) put(k, v)
        }
        runCatching {
            val d = dir() ?: return@runCatching
            File(d, "$id.json").writeText(prettyJson.encodeToString(JsonObject.serializer(), report))
            d.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.name }?.drop(MAX_REPORTS)?.forEach { it.delete() }
        }.onFailure { logger.warn(it) { "Failed to save perf report" } }
        cachedReports = null
        _latestHeadline.value = headlineOf(report)
        return report
    }

    /** 报告列表的缓存: 录制时网页每秒读一次列表, 别每次都去读盘解析; 存了新报告才作废. */
    @Volatile
    private var cachedReports: List<JsonObject>? = null

    private fun JsonObjectBuilder.settingsSnapshot() {
        runCatching {
            val settings = KoinPlatform.getKoin().get<SettingsRepository>()
            runBlocking {
                withTimeoutOrNull(2_000) {
                    val theme = settings.themeSettings.flow.first()
                    put("visualEffects", theme.visualEffects.name)
                    put("uiScale", theme.effectiveUiScale)
                    put("tmdbImagesOff", settings.tmdbImagesDisabled.flow.first())
                }
            }
        }
    }

    /** 报告列表 (新的在前): 网页的列表只要摘要. */
    fun reports(): List<JsonObject> {
        cachedReports?.let { return it }
        val files = dir()?.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.name }.orEmpty()
        return files.mapNotNull { f -> runCatching { Json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() }
            .also { cachedReports = it }
    }

    fun reportFile(id: String): File? =
        dir()?.listFiles { f -> f.name == "$id.json" }?.firstOrNull()

    private fun refreshLatestHeadline() {
        _latestHeadline.value = reports().firstOrNull()?.let(::headlineOf)
    }

    /** 第一条结论 (已按严重程度排过) 的文字. */
    fun headlineOf(report: JsonObject): String? =
        runCatching { report["findings"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content }.getOrNull()

    // ------------------------------------------------------------------ 工具

    private fun currentRoute(): String? = runCatching { routeName?.invoke() }.getOrNull()

    /** 在主线程上跑 [block] 并等结果 (调用方在后台线程; 已在主线程就直接跑). */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        mainHandler.post {
            result = runCatching(block)
            done.countDown()
        }
        if (!done.await(5, TimeUnit.SECONDS)) error("main thread did not respond")
        return result!!.getOrThrow()
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun currentDisplay(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay
}
