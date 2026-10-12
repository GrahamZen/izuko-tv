/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * 整个安装过程, 每一步都等用户自己点: 挑包 → (没授权时) 先去设置允许安装应用 → 查最新版、下载 → 用户点「安装」交给系统的安装界面
 * (装不上时可以换成安装会话). 放在进程级单例里, 界面重建 (换语言、换主题) 不打断它; 状态变化在主线程通知 [listener].
 */
object InstallFlow {
    sealed interface State {
        data class TooOld(val sdk: Int, val minSdk: Int) : State
        data class UnsupportedAbi(val abis: List<String>) : State

        /**
         * 还没有「安装未知应用」的授权, 先请用户去设置里打开, 再下载. 放在最前面: 8.0~11 上打开授权会杀掉助手进程,
         * 放到安装那一步才问的话, 安装界面跟着关掉, 用户回来要从头再点一遍.
         * 这一步可以跳过 (「继续」): 有的电视没有按应用授权的设置页, 或开关不起作用, 这时由安装界面在安装时去问.
         * @property stillMissing 去过设置、回来仍没有授权 (有的电视开关在别处或不起作用), 这时「继续」排第一
         * @property settingsUnavailable 打不开授权的设置页, 同样「继续」排第一
         */
        data class NeedsPermission(val stillMissing: Boolean, val settingsUnavailable: Boolean = false) : State

        /**
         * 先选装哪个版本 (正式版 / 测试版), 选了才往下走 (授权 → 下载); 这一屏的同时在后台查好两个版本, 列出版本号.
         * 选过一次就记住 (授权时助手进程可能被杀), 之后不再问, 中途可以用「版本」按钮改.
         */
        data object ChooseVersion : State

        data object Checking : State

        /** @property probing 正在给各条线路测速 (开始下载前, 或用户点了重新测速) */
        data class Downloading(
            val version: String,
            val fileName: String,
            val progress: Downloader.Progress?,
            val probing: Boolean = false,
        ) : State

        /** 下载好了, 等用户点「安装」. */
        data class ReadyToInstall(val version: String, val apk: File) : State

        /**
         * 交给了系统的安装界面 (默认的方式): 确认之后它自己显示「正在安装」, 装好后给「打开」.
         * 装上的 Izuko TV 换掉助手, 这个进程随之结束; 所以安装界面关了而助手还在 ([closed]) 就是没装上: 取消了, 或者报了错.
         */
        data class SystemInstaller(val version: String, val apk: File, val closed: Boolean) : State

        /**
         * 用安装会话装 ([SessionInstaller], 「换一种方式安装」).
         * @property awaitingConfirmation 系统的确认界面已经弹出. 之后不一定收得到结果: Android 7.1 上「未知来源」提示按取消,
         * 会话不回任何状态, 所以这时界面也给出重试与「用系统安装器打开」
         */
        data class Installing(val version: String, val awaitingConfirmation: Boolean, val apk: File) : State

        /**
         * 装上了而助手还在: 正式版装上来会换掉助手 (进程随之结束), 所以只有调试包 (包名不同) 或系统不结束要替换的进程时才看得到.
         * 给个打开的入口, 不停在「正在安装」.
         */
        data class Installed(val version: String) : State

        /**
         * @property apk 下好的安装包; 有的话界面多一个「用系统安装器打开」
         * @property detail 原因的原文 (各条线路的报错、系统给的安装结果), 给人截图报障
         */
        data class Failed(val reason: Reason, val detail: String?, val apk: File?) : State
    }

    sealed interface Reason {
        data object Lookup : Reason
        data object Download : Reason
        data class Storage(val requiredBytes: Long, val availableBytes: Long) : Reason

        /**
         * 系统安装的结果, [status] 是 [PackageInstaller] 的 STATUS_FAILURE_*.
         * @property permissionRejected 取消在「允许安装未知应用」那一步 (系统的原文是 `User rejected permissions`), 不是放弃安装
         */
        data class Install(val status: Int, val permissionRejected: Boolean = false) : Reason

        /** 没能开始安装 (建会话或写入时出错). */
        data object InstallStart : Reason
    }

    private const val TAG = "IzukoInstaller"
    private const val PREFS = "installer"
    private const val KEY_CHANNEL = "channel"

    /** 安装包大小不知道时按这个估存储空间 (正式版 universal 约 70 MB). */
    private const val UNKNOWN_SIZE_ESTIMATE = 100L * 1024 * 1024

    /** 下载的一份 + 安装时系统再复制一份 + 解出的原生库与编译产物. */
    private const val STORAGE_FACTOR = 2.5

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var state: State = State.Checking
        private set

    var listener: ((State) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(state)
        }

    /** 查版本、下载的线程; 每开一次 (或手机传来安装包) [runId] 加一, 旧线程发的状态作废. */
    @Volatile
    private var downloadWorker: Thread? = null

    @Volatile
    private var runId = 0

    @Volatile
    private var downloadWorkerRun = -1

    @Volatile
    private var sessionWorker: Thread? = null

    @Volatile
    private var downloader: Downloader? = null

    @Volatile
    private var installingVersion: String? = null

    @Volatile
    private var installingApk: File? = null

    @Volatile
    private var installingSession = -1

    /** 给本机挑的安装包 (文件名里版本号之后那段) 与查到的版本, 诊断信息里写出来, 方便用户自己去下这个文件. */
    @Volatile
    private var chosenSuffix: String? = null

    /** 本机可用的安装包 (文件名里版本号之后那段), 按优先顺序; 手机网页查不到电视给的地址时自己按它找. */
    @Volatile
    var packageSuffixes: List<String> = emptyList()
        private set

    @Volatile
    private var lastVersion: String? = null

    /** 最近一次下载用的线路 (域名). */
    @Volatile
    var lastSourceHost: String? = null
        private set

    /** 安装包是手机传来的 (见 [UploadServer]), 不是助手自己下的. */
    @Volatile
    var uploadedFromPhone = false
        private set

    /** 查到的版本与安装包 (还没查到时为 `null`), 手机网页上给出下载地址. */
    @Volatile
    var target: Target? = null
        private set

    /** 下载镜像清单 (还没取到时为 `null`). */
    @Volatile
    var mirrors: List<String>? = null
        private set

    /** 装正式版还是测试版; 用户选过就记在助手自己的偏好里 (授权时助手进程可能被杀). */
    @Volatile
    var channel: ReleaseChannel = ReleaseChannel.STABLE
        private set

    @Volatile
    private var channelLoaded = false

    /** 用户选过频道 (记在偏好里); 没选过时先停在 [State.ChooseVersion]. */
    @Volatile
    private var channelChosen = false

    /** 两个频道各自最新的版本与安装包: 选版本时写出版本号, 选了直接下, 不再查一次; 手机网页也按它给两个版本的地址. */
    @Volatile
    var channelTargets: Map<ReleaseChannel, Target> = emptyMap()
        private set

    val channelVersions: Map<ReleaseChannel, String> get() = channelTargets.mapValues { it.value.version }

    @Volatile
    private var prefetchWorker: Thread? = null

    /** 适合本机的安装包文件名; 没查到版本时版本号那段写成 `*`. 设备装不了时为 `null`. */
    fun suggestedPackage(): String? =
        chosenSuffix?.let { PackageChoice.assetName(BuildConfig.ASSET_PREFIX, lastVersion ?: "*", it) }

    /** 是不是最近一次提交的会话; 之前作废的会话回来的结果不算. */
    fun isCurrentSession(sessionId: Int) = sessionId == installingSession

    fun log(message: String) {
        Log.i(TAG, message)
    }

    /**
     * 下载目录. 放在缓存目录: 装好之后这个目录归 Izuko TV 所有 (同包名, 数据目录保留), 它启动时会删掉;
     * 系统空间紧张时也会清缓存.
     */
    fun downloadDir(context: Context): File = File(context.cacheDir, "install-izuko")

    private fun post(newState: State) {
        state = newState
        main.post { listener?.invoke(newState) }
    }

    /** 第 [id] 次的下载线程发的状态; 已经作废 (重新开始了, 或手机传来了安装包) 就不发. */
    private fun postFrom(id: Int, newState: State) {
        if (id == runId) post(newState)
    }

    /** 用户在授权那一步选了「继续」: 不再拦, 安装时由系统再问. */
    @Volatile
    private var permissionSkipped = false

    /** 这个进程里从授权那一步去过设置页. */
    @Volatile
    private var permissionSettingsOpened = false

    fun onPermissionSettingsOpened() {
        permissionSettingsOpened = true
    }

    fun onPermissionSettingsUnavailable() {
        if (state !is State.NeedsPermission) return
        post(State.NeedsPermission(stillMissing = true, settingsUnavailable = true))
    }

    /** 回到前台时调: 停在授权那一步、刚从设置页回来, 就重新看一次授权, 有了就往下走. */
    fun recheckPermission(context: Context) {
        if (state is State.NeedsPermission && permissionSettingsOpened) start(context)
    }

    fun continueWithoutPermission(context: Context) {
        permissionSkipped = true
        start(context)
    }

    /** 开始 (或失败后重来). 已经在跑时什么都不做. */
    @Synchronized
    fun start(context: Context) {
        if (downloadWorker?.isAlive == true && downloadWorkerRun == runId) return
        val app = context.applicationContext
        loadChannel(app)
        val choice = PackageChoice.choose(
            sdk = Build.VERSION.SDK_INT,
            abis = deviceAbis(),
            normalMinSdk = BuildConfig.NORMAL_MIN_SDK,
            legacyMinSdk = BuildConfig.LEGACY_MIN_SDK,
        )
        log("API ${Build.VERSION.SDK_INT} (reported release ${Build.VERSION.RELEASE}), ABIs ${deviceAbis()} -> $choice")
        packageSuffixes = (choice as? PackageChoice.Result.Packages)?.suffixes.orEmpty()
        chosenSuffix = packageSuffixes.firstOrNull()
        when (choice) {
            is PackageChoice.Result.TooOld -> post(State.TooOld(choice.sdk, choice.minSdk))
            is PackageChoice.Result.UnsupportedAbi -> post(State.UnsupportedAbi(choice.abis))
            is PackageChoice.Result.Packages -> {
                if (!channelChosen) {
                    post(State.ChooseVersion)
                    prefetch(app, choice.suffixes)
                    return
                }
                // 读不到授权 (null) 时不拦, 由系统安装界面去问
                if (!permissionSkipped && DeviceReport.installAllowed(app) == false) {
                    log("Install permission missing (settings opened: $permissionSettingsOpened)")
                    post(State.NeedsPermission(stillMissing = permissionSettingsOpened))
                    return
                }
                post(State.Checking)
                uploadedFromPhone = false
                val id = ++runId
                downloadWorkerRun = id
                downloadWorker = thread(name = "install-flow") {
                    try {
                        run(app, choice.suffixes, id)
                    } catch (e: InterruptedException) {
                        // 作废了: 手机传来了安装包
                        log("Download run $id stopped")
                    }
                }
            }
        }
    }

    /** 选版本那一屏的同时在后台查两个频道的最新版本; 查到了重画一次 (列出版本号). */
    private fun prefetch(context: Context, suffixes: List<String>) {
        if (prefetchWorker?.isAlive == true || channelTargets.size == ReleaseChannel.entries.size) return
        prefetchWorker = thread(name = "install-prefetch") {
            val lookup = ReleaseLookup(BuildConfig.REPOSITORY, BuildConfig.ASSET_PREFIX, ::log)
            val stable = try {
                lookup.find(suffixes, ReleaseChannel.STABLE)
            } catch (e: Exception) {
                log("Prefetch failed: ${e.describe()}")
                null
            }
            remember(lookup, ReleaseChannel.STABLE, stable)
            if (state == State.ChooseVersion) post(State.ChooseVersion)
        }
    }

    /** 记下查到的版本: GitHub 接口给的两个频道, 加上回落来源查到的 [channel] 那个. */
    private fun remember(lookup: ReleaseLookup, channel: ReleaseChannel, found: Target?) {
        val targets = channelTargets.toMutableMap()
        targets.putAll(lookup.latest)
        if (found != null && channel !in lookup.latest) targets[channel] = found
        channelTargets = targets
    }

    private fun run(context: Context, suffixes: List<String>, id: Int) {
        val target = channelTargets[channel]?.also { log("Using ${channel.name.lowercase()} ${it.version} found earlier") }
            ?: lookup(suffixes, id)
            ?: return
        if (id != runId) return
        lastVersion = target.version
        this.target = target
        val mirrors = DownloadMirrors.load(BuildConfig.REPOSITORY, ::log)
        this.mirrors = mirrors
        if (id != runId) return
        val dir = downloadDir(context).apply { mkdirs() }
        // 别的版本、别的架构留下的半截文件 (手机传来的那份由 UploadServer 管)
        dir.listFiles()?.filter { file -> target.files.none { it.name == file.name } && !UploadServer.owns(file) }?.forEach { it.delete() }

        val reasons = mutableListOf<String>()
        for (file in target.files) {
            val required = ((file.size ?: UNKNOWN_SIZE_ESTIMATE) * STORAGE_FACTOR).toLong()
            val available = StatFs(dir.path).availableBytes
            if (available < required) {
                postFrom(id, State.Failed(Reason.Storage(required, available), file.name, apk = null))
                return
            }
            val dest = File(dir, file.name)
            postFrom(id, State.Downloading(target.version, file.name, progress = null))
            val downloader = Downloader(::log)
            this.downloader = downloader
            var last: Downloader.Progress? = null
            try {
                downloader.download(
                    file = file,
                    sources = sourcesOf(file, mirrors),
                    dest = dest,
                    onProgress = {
                        last = it
                        lastSourceHost = it.host
                        postFrom(id, State.Downloading(target.version, file.name, it))
                    },
                    onProbing = { postFrom(id, State.Downloading(target.version, file.name, last, probing = it)) },
                )
            } catch (e: Downloader.Failure) {
                reasons += "${file.name}:"
                reasons += e.reasons.map { "  $it" }
                continue
            } finally {
                if (this.downloader === downloader) this.downloader = null
            }
            if (id != runId) return
            installingVersion = target.version
            installingApk = dest
            post(State.ReadyToInstall(target.version, dest))
            return
        }
        postFrom(id, State.Failed(Reason.Download, reasons.joinToString("\n"), apk = null))
    }

    /** 查 [channel] 的最新版本; 查不到时发失败状态, 返回 `null`. */
    private fun lookup(suffixes: List<String>, id: Int): Target? {
        val channel = channel
        val lookup = ReleaseLookup(BuildConfig.REPOSITORY, BuildConfig.ASSET_PREFIX, ::log)
        val target = try {
            lookup.find(suffixes, channel)
        } catch (e: LookupException) {
            postFrom(id, State.Failed(Reason.Lookup, e.failures.joinToString("\n"), apk = null))
            null
        } catch (e: Exception) {
            postFrom(id, State.Failed(Reason.Lookup, e.describe(), apk = null))
            null
        }
        if (id == runId) remember(lookup, channel, target)
        return target
    }

    /** [file] 的全部来源: GitHub 原地址在前, 然后各镜像. */
    fun sourcesOf(file: RemoteFile, mirrors: List<String>): List<String> =
        listOf(file.url) + mirrors.map { DownloadMirrors.resolve(it, file.url) }

    /** 正在下载时各条线路的情况; 没在下载时为空. */
    fun lineStatuses(): List<Downloader.LineStatus> = downloader?.statuses().orEmpty()

    /** 当前线路是不是用户指定的. */
    fun isManualLine(): Boolean = downloader?.isManual == true

    /** 指定线路 ([url]), 或 `null` 回到自动. */
    fun chooseLine(url: String?) {
        downloader?.choose(url)
    }

    fun retestLines() {
        downloader?.retest()
    }

    private fun loadChannel(context: Context) {
        if (channelLoaded) return
        channelLoaded = true
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CHANNEL, null)
        channelChosen = saved != null
        channel = ReleaseChannel.entries.firstOrNull { it.name == saved } ?: ReleaseChannel.STABLE
    }

    private fun saveChannel(context: Context, value: ReleaseChannel) {
        channel = value
        channelChosen = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CHANNEL, value.name).apply()
    }

    /** 在选版本那一屏选了: 记住, 往下走 (授权 → 下载). */
    @Synchronized
    fun chooseChannel(context: Context, value: ReleaseChannel) {
        loadChannel(context)
        saveChannel(context, value)
        log("Channel chosen: $value")
        start(context)
    }

    /**
     * 换频道: 记下来; 还没交给系统安装时作废正在进行的查版本 / 下载 (和手机传来的包), 按新频道从头查.
     * 停在授权那一步时只重画 (按钮上写着频道).
     */
    @Synchronized
    fun setChannel(context: Context, value: ReleaseChannel) {
        loadChannel(context)
        if (value == channel) return
        saveChannel(context, value)
        log("Channel: $value")
        val current = state
        if (current is State.NeedsPermission) {
            post(current)
            return
        }
        if (current is State.SystemInstaller || current is State.Installing) return
        runId++
        downloader?.abort()
        target = null
        lastVersion = null
        installingVersion = null
        installingApk = null
        uploadedFromPhone = false
        start(context)
    }

    /**
     * 手机传来的安装包核对过了 (见 [UploadServer]): 作废正在进行的查版本 / 下载, 直接到「已经下载好了」.
     */
    @Synchronized
    fun acceptUploaded(context: Context, version: String, apk: File) {
        runId++
        downloader?.abort()
        uploadedFromPhone = true
        // 「版本」按钮跟着传来的包走: 版本号带后缀 (1.0.8-alpha05) 的是测试版
        loadChannel(context)
        saveChannel(context, if ('-' in version) ReleaseChannel.TEST else ReleaseChannel.STABLE)
        installingVersion = version
        installingApk = apk
        log("Accepted uploaded package $version (${apk.length()} bytes)")
        post(State.ReadyToInstall(version, apk))
    }

    /** 用户点了「安装」: 交给系统的安装界面; 7.0 以前的安装界面不认 content://, 用安装会话 (那些系统本来也装不了 Izuko TV). */
    fun installDownloaded(context: Context) {
        if (Build.VERSION.SDK_INT >= 24) useSystemInstaller() else installWithSession(context)
    }

    @Volatile
    private var systemInstallerLaunchPending = false

    /** 交给系统的安装界面. 由界面打开它 ([takeSystemInstallerLaunch]): 在前台时马上打开, 不在前台就等回到前台. */
    fun useSystemInstaller() {
        val version = installingVersion ?: return
        val apk = installingApk?.takeIf { it.exists() } ?: return
        systemInstallerLaunchPending = true
        post(State.SystemInstaller(version, apk, closed = false))
    }

    /** 界面这时要不要打开系统的安装界面; 每交一次只打开一次. */
    @Synchronized
    fun takeSystemInstallerLaunch(): Boolean = systemInstallerLaunchPending.also { systemInstallerLaunchPending = false }

    fun onSystemInstallerClosed(context: Context) {
        val current = state as? State.SystemInstaller ?: return
        if (targetInstalledSince(context, InstallAttempts.lastRecordTime(context))) {
            log("Installed (system installer)")
            post(State.Installed(current.version))
            return
        }
        post(current.copy(closed = true))
    }

    /** 要装的应用在 [since] 之后装上或更新过; 助手自己就是那个包 (正式版助手) 时一定没装上. */
    private fun targetInstalledSince(context: Context, since: Long): Boolean {
        if (context.packageName == BuildConfig.TARGET_PACKAGE || since <= 0) return false
        return runCatching { context.packageManager.getPackageInfo(BuildConfig.TARGET_PACKAGE, 0).lastUpdateTime >= since }.getOrDefault(false)
    }

    /** 换成安装会话装下好的包; 包没了 (校验没过被删) 就从头来. */
    @Synchronized
    fun installWithSession(context: Context) {
        if (sessionWorker?.isAlive == true) return
        val version = installingVersion
        val apk = installingApk?.takeIf { it.exists() }
        if (version == null || apk == null) {
            start(context)
            return
        }
        val app = context.applicationContext
        sessionWorker = thread(name = "install-session") { install(app, version, apk) }
    }

    private fun install(context: Context, version: String, apk: File) {
        installingVersion = version
        installingApk = apk
        pendingConfirmation = null
        post(State.Installing(version, awaitingConfirmation = false, apk = apk))
        InstallAttempts.record(context, InstallAttempts.METHOD_SESSION, version, apk)
        try {
            SessionInstaller.install(context, apk) { installingSession = it }
        } catch (e: Exception) {
            log("Session install failed: ${e.describe()}")
            post(State.Failed(Reason.InstallStart, e.describe(), apk))
        }
    }

    /**
     * 系统给的确认界面. 用户关掉它又回到助手时 (按了返回、去设置开授权后没回到它), 再打开它就行, 不必新建会话:
     * 新建会话会留下前一个没确认的窗口, 两个都确认就装了两次 (10-12 索尼上遇到).
     */
    @Volatile
    var pendingConfirmation: Intent? = null
        private set

    fun onAwaitingConfirmation(confirm: Intent) {
        pendingConfirmation = confirm
        val version = installingVersion ?: return
        val apk = installingApk ?: return
        post(State.Installing(version, awaitingConfirmation = true, apk = apk))
    }

    /**
     * 会话的最终结果. 成功时多半收不到: 装上的 Izuko TV 换掉了助手, 这个进程随之结束; 收到了就是助手还在, 转到 [State.Installed].
     * 包坏了 (校验没过) 就删掉, 重试时重新下载.
     */
    fun onInstallResult(context: Context, status: Int, message: String?) {
        InstallAttempts.recordResult(context, "status $status${message?.let { ": $it" }.orEmpty()}")
        if (status == PackageInstaller.STATUS_SUCCESS) {
            log("Installed")
            post(State.Installed(installingVersion.orEmpty()))
            return
        }
        val apk = installingApk
        if (status == PackageInstaller.STATUS_FAILURE_INVALID) apk?.delete()
        val permissionRejected = status == PackageInstaller.STATUS_FAILURE_ABORTED &&
            message?.contains("permission", ignoreCase = true) == true
        post(State.Failed(Reason.Install(status, permissionRejected), message, apk?.takeIf { it.exists() }))
    }
}
