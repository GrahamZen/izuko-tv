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

        data object Checking : State
        data class Downloading(val version: String, val fileName: String, val progress: Downloader.Progress?) : State

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

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var installingVersion: String? = null

    @Volatile
    private var installingApk: File? = null

    @Volatile
    private var installingSession = -1

    /** 给本机挑的安装包 (文件名里版本号之后那段) 与查到的版本, 诊断信息里写出来, 方便用户自己去下这个文件. */
    @Volatile
    private var chosenSuffix: String? = null

    @Volatile
    private var lastVersion: String? = null

    /** 最近一次下载用的线路 (域名). */
    @Volatile
    var lastSourceHost: String? = null
        private set

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
        if (worker?.isAlive == true) return
        val app = context.applicationContext
        val choice = PackageChoice.choose(
            sdk = Build.VERSION.SDK_INT,
            abis = deviceAbis(),
            normalMinSdk = BuildConfig.NORMAL_MIN_SDK,
            legacyMinSdk = BuildConfig.LEGACY_MIN_SDK,
        )
        log("API ${Build.VERSION.SDK_INT} (reported release ${Build.VERSION.RELEASE}), ABIs ${deviceAbis()} -> $choice")
        chosenSuffix = (choice as? PackageChoice.Result.Packages)?.suffixes?.firstOrNull()
        when (choice) {
            is PackageChoice.Result.TooOld -> post(State.TooOld(choice.sdk, choice.minSdk))
            is PackageChoice.Result.UnsupportedAbi -> post(State.UnsupportedAbi(choice.abis))
            is PackageChoice.Result.Packages -> {
                // 读不到授权 (null) 时不拦, 由系统安装界面去问
                if (!permissionSkipped && DeviceReport.installAllowed(app) == false) {
                    log("Install permission missing (settings opened: $permissionSettingsOpened)")
                    post(State.NeedsPermission(stillMissing = permissionSettingsOpened))
                    return
                }
                post(State.Checking)
                worker = thread(name = "install-flow") { run(app, choice.suffixes) }
            }
        }
    }

    private fun run(context: Context, suffixes: List<String>) {
        val target = try {
            ReleaseLookup(BuildConfig.REPOSITORY, BuildConfig.ASSET_PREFIX, ::log).find(suffixes)
        } catch (e: LookupException) {
            post(State.Failed(Reason.Lookup, e.failures.joinToString("\n"), apk = null))
            return
        } catch (e: Exception) {
            post(State.Failed(Reason.Lookup, e.describe(), apk = null))
            return
        }
        lastVersion = target.version
        val mirrors = DownloadMirrors.load(BuildConfig.REPOSITORY, ::log)
        val dir = downloadDir(context).apply { mkdirs() }
        // 别的版本、别的架构留下的半截文件
        dir.listFiles()?.filter { file -> target.files.none { it.name == file.name } }?.forEach { it.delete() }

        val reasons = mutableListOf<String>()
        for (file in target.files) {
            val required = ((file.size ?: UNKNOWN_SIZE_ESTIMATE) * STORAGE_FACTOR).toLong()
            val available = StatFs(dir.path).availableBytes
            if (available < required) {
                post(State.Failed(Reason.Storage(required, available), file.name, apk = null))
                return
            }
            val dest = File(dir, file.name)
            post(State.Downloading(target.version, file.name, progress = null))
            try {
                Downloader(::log).download(
                    file = file,
                    sources = listOf(file.url) + mirrors.map { DownloadMirrors.resolve(it, file.url) },
                    dest = dest,
                    onProgress = {
                        lastSourceHost = it.host
                        post(State.Downloading(target.version, file.name, it))
                    },
                    cancelled = { false },
                )
            } catch (e: Downloader.Failure) {
                reasons += "${file.name}:"
                reasons += e.reasons.map { "  $it" }
                continue
            }
            installingVersion = target.version
            installingApk = dest
            post(State.ReadyToInstall(target.version, dest))
            return
        }
        post(State.Failed(Reason.Download, reasons.joinToString("\n"), apk = null))
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

    fun onSystemInstallerClosed() {
        val current = state as? State.SystemInstaller ?: return
        post(current.copy(closed = true))
    }

    /** 换成安装会话装下好的包; 包没了 (校验没过被删) 就从头来. */
    @Synchronized
    fun installWithSession(context: Context) {
        if (worker?.isAlive == true) return
        val version = installingVersion
        val apk = installingApk?.takeIf { it.exists() }
        if (version == null || apk == null) {
            start(context)
            return
        }
        val app = context.applicationContext
        worker = thread(name = "install-session") { install(app, version, apk) }
    }

    private fun install(context: Context, version: String, apk: File) {
        installingVersion = version
        installingApk = apk
        pendingConfirmation = null
        post(State.Installing(version, awaitingConfirmation = false, apk = apk))
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
     * 会话的最终结果. 成功时多半收不到: 装上的 Izuko TV 换掉了助手, 这个进程随之结束.
     * 包坏了 (校验没过) 就删掉, 重试时重新下载.
     */
    fun onInstallResult(status: Int, message: String?) {
        if (status == PackageInstaller.STATUS_SUCCESS) {
            log("Installed")
            return
        }
        val apk = installingApk
        if (status == PackageInstaller.STATUS_FAILURE_INVALID) apk?.delete()
        val permissionRejected = status == PackageInstaller.STATUS_FAILURE_ABORTED &&
            message?.contains("permission", ignoreCase = true) == true
        post(State.Failed(Reason.Install(status, permissionRejected), message, apk?.takeIf { it.exists() }))
    }
}
