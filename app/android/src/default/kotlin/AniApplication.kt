/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.analytics.analytics
import dev.gitlive.firebase.initialize
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.him188.ani.android.activity.MainActivity
import me.him188.ani.android.provider.ExternalContentProviderFactoryImpl
import me.him188.ani.app.data.persistent.dataStores
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheEpisodeEntity
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoEntity
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.data.repository.user.UserRepository
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.media.cache.storage.MediaSaveDirProvider
import me.him188.ani.app.domain.torrent.service.AniTorrentService
import me.him188.ani.app.domain.torrent.service.PikPakCacheServiceController
import me.him188.ani.app.domain.torrent.service.TorrentServiceConnectionManager
import me.him188.ani.app.platform.AndroidLoggingConfigurator
import me.him188.ani.app.platform.AppStartupTasks
import me.him188.ani.app.platform.JvmLogHelper
import me.him188.ani.app.platform.StartupTimeMonitor
import me.him188.ani.app.platform.StepName
import me.him188.ani.app.platform.create
import me.him188.ani.app.platform.createAppRootCoroutineScope
import me.him188.ani.app.platform.getCommonKoinModule
import me.him188.ani.app.platform.startCommonKoinModule
import me.him188.ani.app.platform.trace.recordAppStart
import me.him188.ani.app.ui.foundation.aniSharedSketch
import me.him188.ani.app.ui.settings.tabs.log.getLogsDir
import me.him188.ani.utils.analytics.Analytics
import me.him188.ani.utils.analytics.AnalyticsConfig
import me.him188.ani.utils.analytics.AnalyticsImpl
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import okio.Path.Companion.toPath
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.openani.mediamp.ffmpeg.FFmpegKit
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi


class AniApplication : Application() {

    companion object {
        init {
            if (BuildConfig.DEBUG) {
                System.setProperty("kotlinx.coroutines.debug", "on")
                System.setProperty("kotlinx.coroutines.stacktrace.recovery", "true")
            }
//            @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
//            val v = kotlinx.coroutines.RECOVER_STACK_TRACES
//            println(v)
        }

        lateinit var instance: Instance
            private set

        /**
         * Only use torrent service at Android 8.1 (27) or above.
         * Our minimal support is Android 8.0 (26).
         */
        val FEATURE_USE_TORRENT_SERVICE = true
    }

    inner class Instance()

    @OptIn(ExperimentalUuidApi::class)
    override fun onCreate() {
        super.onCreate()
        val startupTimeMonitor = StartupTimeMonitor()

        val logsDir = applicationContext.getLogsDir().absolutePath
        AndroidLoggingConfigurator.configure(logsDir)
        AppStartupTasks.printVersions()
        startupTimeMonitor.mark(StepName.Logging)

        val defaultUEH = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            logger<AniApplication>().error(e) { "!!!ANI FATAL EXCEPTION!!! ($e)" }
            Thread.sleep(500)
            defaultUEH?.uncaughtException(t, e)
        }
        startupTimeMonitor.mark(StepName.UncaughtExceptionHandler)

        val currentProcess = processName()
        if (currentProcess.contains("torrent_service") || currentProcess.contains("codecprobe")) {
            // In service process, we don't need any dependency which is use in app process.
            return
        }

        instance = Instance() // set instance

        val scope = createAppRootCoroutineScope()

        val anitorrentTorrents: MutableStateFlow<Flow<List<TorrentCacheInfoEntity>>?> = MutableStateFlow(null)
        val anitorrentEpisodes: MutableStateFlow<Flow<List<TorrentCacheEpisodeEntity>>?> = MutableStateFlow(null)
        val mediaCacheBaseSaveDir: MutableStateFlow<File?> = MutableStateFlow(null)
        val connectionManager = TorrentServiceConnectionManager(
            this,
            serviceTorrentsFlow = anitorrentTorrents,
            serviceEpisodesFlow = anitorrentEpisodes,
            mediaCacheBaseSaveDirFlow = mediaCacheBaseSaveDir,
            startServiceImpl = ::startAniTorrentService,
            stopServiceImpl = ::stopService,
            processLifecycle = ProcessLifecycleOwner.get().lifecycle,
            parentCoroutineContext = scope.coroutineContext,
        )

        startupTimeMonitor.mark(StepName.WindowAndContext)

        scope.launch(Dispatchers.IO_) {
            runCatching {
                JvmLogHelper.deleteOldLogs(File(logsDir))
            }.onFailure {
                Log.e("AniApplication", "Failed to delete old logs", it)
            }
        }

        OkHttp // survive R8

        startKoin {
            androidContext(this@AniApplication)
            modules(getCommonKoinModule({ this@AniApplication }, scope))

            modules(getCommonAndroidModules(scope))
            modules(getAndroidModules(connectionManager, scope))
        }.startCommonKoinModule(this@AniApplication, scope)
        startupTimeMonitor.mark(StepName.Modules)

        val koin = getKoin()
        // 图片磁盘缓存第一次打开要把整份 journal 读一遍 (几千条), 读完之前所有图片请求都在等 —— 冷启动时这就是封面全灰的那几秒,
        // 还没编译优化的包上要好几秒. 进程一起来就在后台先打开, 与界面启动并行; 用主界面与屏保共用的那个实例 (见 aniSharedSketch)
        scope.launch(Dispatchers.IO_) {
            val start = TimeSource.Monotonic.markNow()
            val size = aniSharedSketch(
                this@AniApplication,
                koin.get<HttpClientProvider>().get(ScopedHttpClientUserAgent.ANI),
                appCacheDirectory = cacheDir.absolutePath.toPath(),
            ).downloadCache.size
            logger<AniApplication>().info {
                "Image disk cache opened in ${start.elapsedNow().inWholeMilliseconds}ms (${size / 1024 / 1024} MiB)"
            }
        }
        val analyticsInitializer = scope.launch {
            val settingsRepository = koin.get<SettingsRepository>()
            val userRepository = koin.get<UserRepository>()
            val settings = settingsRepository.analyticsSettings.flow.first()
            settingsRepository.analyticsSettings.update { settings }
            if (settings.isBugReportEnabled) {
                AppStartupTasks.initializeSentry(settings.deviceId)
            }
            if (settings.isAnalyticsEnabled) {
                AppStartupTasks.initializeAnalytics {
                    AnalyticsImpl(
                        AnalyticsConfig.create(),
                    ).apply {
                        Firebase.initialize(applicationContext) // Use google-services.json
                        init()

                        userRepository.selfInfoFlow.first()?.id?.let {
                            Firebase.analytics.setUserId(it.toString())
                        }
                        scope.launch {
                            userRepository.selfInfoFlow.map { it?.id }.collect {
                                Firebase.analytics.setUserId(it?.toString())
                            }
                        }
                    }
                }
            }
        }

        // torrent_cache rows carry no engine; MediaCacheSave.engine says which engine owns a media.
        val anitorrentMediaIds = dataStores.mediaCacheMetadataStore.data.map { saves ->
            saves.filter { it.engine == MediaCacheEngineKey.Anitorrent }.map { it.origin.mediaId }.toSet()
        }
        val torrentCacheInfoDao = koin.get<AniDatabase>().torrentCacheInfoDao()
        anitorrentTorrents.value = combine(
            torrentCacheInfoDao.getAll(),
            anitorrentMediaIds,
        ) { entities, ids -> entities.filter { it.mediaId in ids } }
        anitorrentEpisodes.value = combine(
            torrentCacheInfoDao.getAllEpisodes(),
            anitorrentMediaIds,
        ) { entities, ids -> entities.filter { it.mediaId in ids } }
        mediaCacheBaseSaveDir.value = File(koin.get<MediaSaveDirProvider>().saveDir)
        // 27 以下 BT 引擎跑在应用进程内, 没有独立进程服务可连, 不启动这个循环 (否则它会去起服务).
        if (supportsTorrentServiceProcess) {
            connectionManager.launchCheckLoop()
        }

        // The BT service above runs in :torrent_service and does nothing for this process, so PikPak
        // caches get their own foreground service here.
        PikPakCacheServiceController(
            context = this,
            downloadManager = koin.get(),
            processLifecycle = ProcessLifecycleOwner.get().lifecycle,
            scope = scope,
        ).start()

        runBlocking { analyticsInitializer.join() }
        ExternalContentProviderFactoryImpl.initializeApp(this)
        startupTimeMonitor.mark(StepName.Analytics)
        FFmpegKit.initialize(this)
        FFmpegKit.useDefaultRuntimeLibraryDirectory()

        scope.launch {
            Analytics.recordAppStart(startupTimeMonitor)
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun processName(): String {
        if (Build.VERSION.SDK_INT >= 28) return getProcessName()

        // Using the same technique as Application.getProcessName() for older devices
        // Using reflection since ActivityThread is an internal API
        try {
            @SuppressLint("PrivateApi") val activityThread = Class.forName("android.app.ActivityThread")

            // Before API 18, the method was incorrectly named "currentPackageName", but it still returned the process name
            // See https://github.com/aosp-mirror/platform_frameworks_base/commit/b57a50bd16ce25db441da5c1b63d48721bb90687
            val getProcessName: Method = activityThread.getDeclaredMethod("currentProcessName")
            return getProcessName.invoke(null) as String

        } catch (e: ClassNotFoundException) {
            throw RuntimeException(e)
        } catch (e: NoSuchMethodException) {
            throw RuntimeException(e)
        } catch (e: IllegalAccessException) {
            throw RuntimeException(e)
        } catch (e: InvocationTargetException) {
            throw RuntimeException(e)
        }
    }

    /**
     * 启动 BT 服务进程.
     *
     * **前台时刻意用 `startService` 而不是 `startForegroundService`**: 后者会武装系统的
     * `SERVICE_START_FOREGROUND_TIMEOUT`(AOSP 为 10 秒) —— 从这里调用起算, 服务必须在 10 秒内
     * 调到 `startForeground()`, 否则系统抛 RemoteServiceException 把进程杀掉, 表现为"BT 一直不下载"
     * (2026-08-11 真机复现两次).
     *
     * 而这 10 秒里绝大部分不在我们手上: `:torrent_service` 是独立进程, `startForeground()` 在
     * [AniTorrentService.onStartCommand] 里, 必须先等进程创建 + 类加载 + `Application.onCreate`
     * 返回. 实测这段在 Shield 的 debug 包上要 **6~10 秒**(跨 4 天日志, 与任何一次改动无关),
     * 本来就贴着死线; 主进程同时在跑 web 源搜索这类重活时就会顶过去.
     *
     * `startService` 没有这个死线 (AOSP 只在 `fgRequired` 时才挂 `SERVICE_START_FOREGROUND_TIMEOUT_MSG`),
     * 服务照旧在 `onStartCommand` 里 `startForeground()` 变成前台服务, 只是头上不再有秒表.
     * 代价是它只在应用处于前台时可用 —— 后台调 `startService` 会抛 IllegalStateException
     * (API 26 起的后台服务启动限制), 所以后台仍走 `startForegroundService`, 并额外留一层 catch 兜底.
     *
     * 这是绕开症状, 不是治本. 治本要让服务进程启动快起来 (那 6~10 秒在进程创建与日志配置上,
     * 全是上游代码), 值得另开一条给上游.
     */
    private fun startAniTorrentService(): ComponentName? {
        // 只在 supportsTorrentServiceProcess 为 true 时才会被调用 (见 launchCheckLoop 处), 这里只是兜底.
        if (!supportsTorrentServiceProcess) return null
        val intent = buildAniTorrentServiceIntent()
        // STARTED = 至少有一个 Activity 可见, 正是后台启动限制放行的那个状态
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            try {
                return startService(intent)
            } catch (e: IllegalStateException) {
                // 理论上到不了 (刚判过前台), 真到了就退回带死线的那条路, 别把启动整个丢掉
                logger<AniApplication>().error(e) { "startService for torrent service rejected, falling back" }
            }
        }
        return startForegroundService(intent)
    }

    private fun buildAniTorrentServiceIntent(): Intent {
        return Intent(this, AniTorrentService.actualServiceClass).apply {
            putExtra("app_name", me.him188.ani.R.string.app_name)
            putExtra("app_service_title_text_idle", me.him188.ani.R.string.app_service_title_text_idle)
            putExtra("app_service_title_text_working", me.him188.ani.R.string.app_service_title_text_working)
            putExtra("app_service_content_text", me.him188.ani.R.string.app_service_content_text)
            putExtra("app_service_stop_text", me.him188.ani.R.string.app_service_stop_text)
            putExtra("app_icon", me.him188.ani.R.mipmap.ic_launcher)
            putExtra("open_activity_intent", Intent(this@AniApplication, MainActivity::class.java))
        }
    }

    private fun stopService() {
        // 27 以下压根没启动过这个服务
        if (!supportsTorrentServiceProcess) return
        startService(
            Intent(this, AniTorrentService.actualServiceClass)
                .apply { putExtra(AniTorrentService.INTENT_STOP_EXTRA, true) },
        )

    }

}
