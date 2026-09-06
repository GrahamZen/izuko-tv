/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.data.network.GitHubDownloadMirrors
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.update.UpdateManager
import me.him188.ani.app.tools.update.DefaultFileDownloader
import me.him188.ani.app.tools.update.DownloadPackage
import me.him188.ani.app.tools.update.SourceOutcome
import me.him188.ani.app.tools.update.FileDownloadStage
import me.him188.ani.app.tools.update.formatTransferProgress
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.foundation.lan.LanHttpServer
import me.him188.ani.app.ui.update.NewVersion
import me.him188.ani.app.ui.update.UpdateCheckProgress
import me.him188.ani.app.ui.update.UpdateChecker
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.toFile
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.util.UUID

/**
 * Web 控制台「应用更新」(`api/update/…`): 检查新版本、下载并安装, 或装手机传上来的安装包 (分块上传, 每块 [CHUNK_BYTES],
 * 按顺序追加进临时文件; 只给这一个接口放宽 LanHttpServer 的请求体上限, 见 [maxBodyBytes]).
 * 网页上一直显示最近上传的安装包名字与电视读出来的版本 ([uploaded]), 选错了文件看得出来. 上传的包没装成 (电视上误按了
 * 取消之类) 时包留在电视上, 网页可以直接再装一次 ([retryUpload]), 不用重新上传.
 *
 * 安装前先要有「安装未知应用」的授权 ([openPermission]): 没授权就提交的话, 系统先弹「禁止安装未知应用」, 在那里点取消安装器
 * 直接退出、不回结果; 去设置里授权则 Android 11 起会结束本进程, 安装器也不接着装 (Shield 实测). 打不开授权页的电视才直接提交, 由系统询问.
 *
 * 安装走系统的会话安装 ([PackageInstaller]), 不走电视更新提示那条 (把安装包交给系统安装界面, 结果拿不回来):
 * 会话装完回传结果, 失败带系统给的原因, 网页上显示、日志里也记. 电视上要用遥控器在系统的确认框里点一下 (按钮叫「安装」或「更新」, 看系统).
 *
 * 授权页与安装确认界面都只能在 Izuko 在前台时打开: 在后台时系统不报错、只是不打开 (日志 `Abort background activity starts`).
 * 不在前台就先叫回来 ([TvRemoteControl.bringToFrontForInstall]), 叫不回来 (没开「叫到前台」) 就等它回到前台再开 ([onTvForeground]).
 *
 * 只装本项目的包 (包名以 [PROJECT_PACKAGE_PREFIX] 开头: 正式包、测试包、加后缀的对比包), 用户有时会装测试版.
 * 装自己是更新, 签名要与已装的一致; 装成功时进程会被系统结束: 提交前记下时刻, 下次启动 ([attach]) 按应用的
 * 最后更新时间核对装没装上. 装另一个包 (另一个 Izuko TV) 先在网页上问一句; Android 11 起本应用看不到别的应用装没装
 * (包可见性), 它的签名由系统核对, 对不上时系统拒装、原因照常回传. 下载只装本应用自己的更新.
 */
internal object RemoteAppUpdate {
    private val logger = logger<RemoteAppUpdate>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("RemoteAppUpdate"))
    private val lock = Any()

    private enum class Phase { IDLE, DOWNLOADING, VERIFYING, PREPARING, CONFIRM, WAITING_FRONT, FAILED, SUCCESS }

    /** 下载 / 核对 / 写入安装会话时不接新的安装; 等确认的那一次可以被新的替换 (旧会话作废). */
    private val BUSY = setOf(Phase.DOWNLOADING, Phase.VERIFYING, Phase.PREPARING)

    /**
     * [pkg]: 装的是另一个包时它的包名, null = 装本应用自己.
     * [download]: 下载进行到哪一步 (挑线路 / 已下多少 / 换线路 / 校验), 只在 [Phase.DOWNLOADING] 时有;
     * [writing]: 写安装会话写到哪了, 只在 [Phase.PREPARING] 时有. 状态那一行按它们写细节, 见 [phaseText].
     */
    private data class State(
        val phase: Phase = Phase.IDLE,
        val progress: Float? = null,
        val version: String? = null,
        val error: String? = null,
        val pkg: String? = null,
        val download: FileDownloadStage? = null,
        val writing: Writing? = null,
    )

    /** 安装包往安装会话里写了 [written] / [total] 字节; [syncing] = 写完了, 在等系统把它落到存储上 (fsync). */
    private data class Writing(val written: Long, val total: Long, val syncing: Boolean = false)

    @Volatile
    private var context: Context? = null

    @Volatile
    private var state = State()

    @Volatile
    private var sessionId = -1

    /** 等 Izuko 回到前台再拉起的安装确认界面 */
    @Volatile
    private var pendingConfirm: Intent? = null

    /** 等 Izuko 回到前台再打开授权页 */
    @Volatile
    private var pendingPermission = false

    @Volatile
    private var checking = false

    /** 正在检查的那一次查到哪个来源了 (GitHub 连不上时逐个试镜像, 每个最长 20 秒) */
    @Volatile
    private var checkProgress: UpdateCheckProgress? = null

    @Volatile
    private var checked = false

    @Volatile
    private var latest: NewVersion? = null

    @Volatile
    private var checkError: String? = null

    /** 下载线路 (原地址与各镜像的域名, 原地址在前), 查到新版本时算好 */
    @Volatile
    private var lineHosts: List<String> = emptyList()

    /** 各条线路上次测出来的速度或没下成的原因, 见 [rememberLineNotes] */
    @Volatile
    private var lineNotes: Map<String, String> = emptyMap()

    /** 上次装自己时 (进程随之被结束) 的结果, 本次启动核对出来的: 成功与否 + 一句话 */
    @Volatile
    private var lastResult: Pair<Boolean, String>? = null

    private class Upload(val id: String, val file: File, val size: Long, val info: Uploaded) {
        var received = 0L
    }

    /**
     * 最近一次上传的安装包: 网页上一直显示它的名字与大小 (选错了文件在电视上点「安装」之前就看得出来), 电视核对过后补上
     * 读出来的包名与版本. 新的上传替换, 下载安装开始时清掉.
     */
    private class Uploaded(val name: String, val size: Long) {
        @Volatile
        var pkg: String? = null

        @Volatile
        var version: String? = null
    }

    @Volatile
    private var uploaded: Uploaded? = null

    /** 正在接收的上传, 一次只有一个; 读写都在 [lock] 里 */
    private var upload: Upload? = null

    /** 从上传的包发起的安装; 没装成时网页可以用它再装一次 ([retryUpload]). 新的上传 / 下载安装开始、装成了都清掉 */
    private class Retry(val file: File, val version: String?, val pkg: String?)

    @Volatile
    private var retry: Retry? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            onStatus(
                ctx,
                intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1),
                status,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) confirmIntentOf(intent) else null,
            )
        }
    }

    /** 进程起来时调 (见 [TvRemoteControl.ensureStarted]): 登记安装结果的接收, 核对上次装自己的结果, 清掉上次没装的上传. */
    fun attach(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (this.context != null) return
            this.context = app
        }
        registerReceiver(app)
        scope.launch {
            File(app.cacheDir, UPLOAD_DIR).deleteRecursively()
            checkPendingResult(app)
        }
    }

    /** 给 LanHttpServer 的按路径请求体上限: 只有分块上传那个接口收一整块 (再留点余量), 其余照默认 */
    fun maxBodyBytes(path: String): Long =
        if (path == CHUNK_PATH) CHUNK_BYTES + 64L * 1024 else LanHttpServer.MAX_BODY_BYTES

    fun handle(request: LanHttpRequest): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return runCatching {
            when {
                request.path == "api/update" && get -> status()
                !post -> null
                request.path == "api/update/check" -> startCheck()
                request.path == "api/update/install" -> startDownloadInstall(request)
                request.path == "api/update/permission" -> openPermission()
                request.path == "api/update/upload/start" -> uploadStart(request)
                request.path == "api/update/upload/chunk" -> uploadChunk(request)
                request.path == "api/update/upload/finish" -> uploadFinish(request)
                request.path == "api/update/retry" -> retryUpload(request)
                else -> null
            }
        }.getOrElse {
            logger.warn(it) { "Remote update request failed: ${request.method} ${request.path}" }
            result(false, tr("操作失败：{0}", updateErrorReason(it)))
        }
    }

    /** Izuko 回到前台 (见 [TvRemoteControl.setTvForeground]): 有等着打开的授权页 / 安装确认就打开. */
    fun onTvForeground() {
        val ctx = context ?: return
        if (pendingPermission) {
            scope.launch { if (canInstall(ctx)) pendingPermission = false else launchPermission(ctx) }
        }
        val confirm = pendingConfirm ?: return
        scope.launch { launchConfirm(ctx, confirm) }
    }

    // ============================ 状态 ============================

    private fun status(): JsonObject {
        val ctx = context
        val s = state
        return buildJsonObject {
            put("ok", true)
            put("current", ctx?.let(::installedVersion).orEmpty())
            put("canInstall", ctx != null && canInstall(ctx))
            put("permPending", pendingPermission)
            put("checking", checking)
            (checkProgress as? UpdateCheckProgress.Mirror)?.takeIf { checking }?.let {
                put("checkText", tr("GitHub 连不上，正在查镜像 {0}/{1}", it.index, it.total))
            }
            put("checked", checked)
            checkError?.let { put("checkError", it) }
            latest?.let { v ->
                putJsonObject("latest") {
                    put("name", v.name)
                    putJsonArray("notes") { v.majorChanges.forEach { add(it) } }
                }
            }
            putJsonObject("job") {
                put("phase", s.phase.name.lowercase())
                put("busy", s.phase in BUSY)
                s.progress?.let { put("progress", it) }
                put("text", phaseText(s))
            }
            // 下载线路 (有新版本、线路不止一条时): 第一条是原地址; note = 上次测出来的速度或没下成的原因
            if (latest != null && lineHosts.size > 1) {
                putJsonArray("lines") {
                    lineHosts.forEachIndexed { index, host ->
                        addJsonObject {
                            put("host", host)
                            put("official", index == 0)
                            lineNotes[host]?.let { put("note", it) }
                        }
                    }
                }
            }
            // 最近上传的是哪个安装包 (核对过的带上版本; 是另一个包时带上包名)
            uploaded?.let { u ->
                putJsonObject("upload") {
                    put("name", u.name)
                    put("size", u.size)
                    u.version?.let { put("version", it) }
                    u.pkg?.takeIf { it != ctx?.packageName }?.let { put("otherPkg", it) }
                }
            }
            // 上传的包没装成: 网页给「用这个安装包再装一次」
            retry?.takeIf { s.phase == Phase.FAILED && it.file.exists() }?.let { r ->
                putJsonObject("retry") { put("version", r.version.orEmpty()) }
            }
            lastResult?.let { (ok, text) ->
                putJsonObject("last") {
                    put("ok", ok)
                    put("text", text)
                }
            }
        }
    }

    private fun phaseText(s: State): String = when (s.phase) {
        Phase.IDLE -> ""
        Phase.DOWNLOADING -> when (val stage = s.download) {
            null -> tr("正在下载 {0}", s.version.orEmpty())
            is FileDownloadStage.Probing -> tr("正在挑选下载线路 {0}/{1}", stage.finished, stage.total)
            is FileDownloadStage.Transferring -> tr(
                "正在下载 {0}：{1}",
                s.version.orEmpty(),
                formatTransferProgress(stage.downloadedBytes, stage.totalBytes, stage.bytesPerSecond),
            )

            is FileDownloadStage.Switching -> tr("上一条线路失败，换第 {0} 条线路", stage.line)
            FileDownloadStage.Verifying -> tr("正在校验安装包…")
        }

        Phase.VERIFYING -> tr("正在核对安装包…")
        Phase.PREPARING -> when (val w = s.writing) {
            null -> tr("正在准备安装…")
            else -> if (w.syncing) tr("正在同步到存储…") else tr("正在写入安装包：{0}", formatTransferProgress(w.written, w.total))
        }

        Phase.CONFIRM -> if (s.pkg != null) {
            tr("请在电视上确认安装 {0}。", s.pkg)
        } else {
            tr("请在电视上确认安装。确认后 Izuko TV 会关闭并完成更新，之后在电视上重新打开。")
        }

        Phase.WAITING_FRONT -> tr("回到电视上的 Izuko TV 后会弹出安装确认。")
        Phase.FAILED -> s.error.orEmpty()
        Phase.SUCCESS -> if (s.pkg != null) tr("已安装 {0}（{1}）", s.pkg, s.version.orEmpty()) else tr("已安装 {0}", s.version.orEmpty())
    }

    private fun setState(s: State) {
        synchronized(lock) { state = s }
    }

    private fun fail(message: String) {
        setState(state.copy(phase = Phase.FAILED, progress = null, error = message, download = null, writing = null))
    }

    // ============================ 检查与下载 ============================

    private fun startCheck(): JsonObject {
        synchronized(lock) {
            if (checking) return result(true, "")
            checking = true
        }
        scope.launch {
            try {
                val koin = KoinPlatform.getKoin()
                val releaseClass = koin.get<SettingsRepository>().updateSettings.flow.first().releaseClass
                val mirrors = koin.get<GitHubDownloadMirrors>()
                val found = UpdateChecker(koin.get<HttpClientProvider>().get(), mirrors::sourcesOf)
                    .checkLatestVersion(releaseClass, onProgress = { checkProgress = it })
                // 下载线路 = 原地址与各个镜像, 按域名列给网页挑 (第一个是原地址)
                lineHosts = found?.downloadUrlAlternatives?.firstOrNull()?.let { url ->
                    runCatching { mirrors.sourcesOf(url).map(::hostOf).distinct() }.getOrNull()
                }.orEmpty()
                latest = found
                checkError = null
                logger.info { "Remote update check: latest=${found?.name}, lines=$lineHosts" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Remote update check failed" }
                // 抛出来的是查 GitHub 的那个错误, 这时镜像也都试过了 (见 UpdateChecker.checkLatestVersion)
                checkError = tr("检查失败：GitHub {0}，镜像也没查到", updateErrorReason(e))
            } finally {
                checked = true
                checking = false
                checkProgress = null
            }
        }
        return result(true, "")
    }

    /** `line` = 网页挑的下载线路 (域名); 空 = 自动 (各条一起测速, 从最快的下, 失败换下一条) */
    private fun startDownloadInstall(request: LanHttpRequest): JsonObject {
        val ver = latest ?: return result(false, tr("先检查更新"))
        val ctx = context ?: return result(false, NOT_READY)
        needPermission(ctx, request)?.let { return it }
        val line = request.formFields()["line"].orEmpty()
        if (line.isNotEmpty() && line !in lineHosts) return result(false, tr("这条下载线路用不了了，换一条再试"))
        synchronized(lock) {
            if (state.phase in BUSY) return result(false, tr("正在安装，等这次完成后再试"))
            abandonSession(ctx)
            lastResult = null
            retry = null
            uploaded = null
            // 进度条等真的开始收数据再出来: 挑线路那几秒进度不动, 停在 0% 看着像卡住了
            state = State(Phase.DOWNLOADING, version = ver.name)
        }
        scope.launch {
            val file = try {
                download(ver, line)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Remote update download failed (line: ${line.ifEmpty { "auto" }})" }
                fail(tr("下载失败：{0}", (e as? DownloadFailure)?.text ?: updateErrorReason(e)))
                return@launch
            }
            setState(state.copy(phase = Phase.VERIFYING, progress = null, download = null))
            val verified = verify(ctx, file, sameAppOnly = true)
            if (verified.error != null) {
                fail(verified.error)
                return@launch
            }
            install(ctx, file, verified.versionName, pkg = null)
        }
        return result(true, "")
    }

    /** 下载没成: [text] 是给网页的一句话 (每条线路各自的原因, 见 [downloadFailureText]) */
    private class DownloadFailure(val text: String, cause: Throwable?) : Exception(text, cause)

    /**
     * 同电视上的更新提示 (AppUpdateViewModel.downloadInApp): 同一个目录, 那边下好的这里直接用, 镜像与校验也一样.
     * [line] 不空时只从这条线路下 (不测速, 也不换线路), 结果与原因都是这一条的.
     */
    private suspend fun download(ver: NewVersion, line: String): File {
        val koin = KoinPlatform.getKoin()
        val downloader = DefaultFileDownloader(koin.get<HttpClientProvider>().get())
        val mirrors = koin.get<GitHubDownloadMirrors>()
        val dir = koin.get<UpdateManager>().saveDir
        val packages = ver.downloadUrlAlternatives.map { url ->
            val fileName = url.substringAfterLast("/", "")
            val sources = mirrors.sourcesOf(url).let { all -> if (line.isEmpty()) all else all.filter { hostOf(it) == line } }
            DownloadPackage(fileName, sources, ver.sha256ByFileName[fileName])
        }.filter { it.sources.isNotEmpty() }
        if (packages.isEmpty()) throw DownloadFailure(tr("这条下载线路用不了了，换一条再试"), null)
        // 状态那一行写到哪一步 (挑线路 3/5、38/79 MB · 2.1 MB/s、换线路、校验); 进度条只在知道总长、真在收数据时有
        val progress = scope.launch {
            downloader.stage.collect { stage ->
                val transferring = stage as? FileDownloadStage.Transferring
                val fraction = transferring?.totalBytes?.let { total -> transferring.downloadedBytes.toFloat() / total }
                synchronized(lock) {
                    if (state.phase == Phase.DOWNLOADING) state = state.copy(progress = fraction, download = stage)
                }
            }
        }
        val file = try {
            dir.createDirectories()
            downloader.download(packages, dir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DownloadFailure(downloadFailureText(e, downloader.lastOutcomes), e)
        } finally {
            progress.cancel()
            rememberLineNotes(downloader.lastOutcomes)
        }
        return file?.toFile() ?: throw DownloadFailure(tr("已有别的下载在进行"), null)
    }

    /**
     * 这次各条线路的结果标在网页的线路下拉框里 (没轮到的线路保留上次的): 真从它下完的写平均速度, 没下成的写原因,
     * 只测过速的写「能连上」. 测速那一小段折出来的速度不写 —— 时间大半花在建连接上, 比实际下载慢一二十倍, 看着像线路都很慢.
     */
    private fun rememberLineNotes(outcomes: List<SourceOutcome>) {
        val notes = outcomes.groupBy { hostOf(it.url) }.mapNotNull { (host, list) ->
            val downloaded = list.firstNotNullOfOrNull { it.downloadBytesPerSecond }
            val error = list.firstNotNullOfOrNull { it.error }
            val reachable = list.any { it.probeBytesPerSecond != null }
            val note = when {
                downloaded != null -> tr("上次 {0}", formatSpeed(downloaded))
                error != null -> updateErrorReason(error)
                reachable -> tr("能连上")
                else -> null
            }
            note?.let { host to it }
        }
        if (notes.isNotEmpty()) lineNotes = lineNotes + notes
    }

    // ============================ 上传 ============================

    private fun uploadStart(request: LanHttpRequest): JsonObject {
        val fields = request.formFields()
        val size = fields["size"]?.toLongOrNull() ?: 0L
        if (size <= 0 || size > MAX_UPLOAD_BYTES) return result(false, tr("文件为空或太大"))
        val ctx = context ?: return result(false, NOT_READY)
        needPermission(ctx, request)?.let { return it }
        synchronized(lock) {
            if (state.phase in BUSY) return result(false, tr("正在安装，等这次完成后再试"))
            val dir = File(ctx.cacheDir, UPLOAD_DIR)
            dir.deleteRecursively()
            dir.mkdirs()
            retry = null
            val info = Uploaded(fields["name"].orEmpty().ifBlank { "upload.apk" }.take(MAX_NAME_CHARS), size)
            val u = Upload(UUID.randomUUID().toString(), File(dir, "upload.apk"), size, info)
            u.file.createNewFile()
            upload = u
            uploaded = info
            logger.info { "Remote update upload started: ${fields["name"]} ($size bytes)" }
            return buildJsonObject {
                put("ok", true)
                put("id", u.id)
                put("chunk", CHUNK_BYTES)
            }
        }
    }

    /** 一块: 查询参数 `id` / `offset`, body 是原始字节. 重发已收过的块直接回 ok (网页超时重试时会出现). */
    private fun uploadChunk(request: LanHttpRequest): JsonObject {
        val id = request.param("id")
        val offset = request.param("offset")?.toLongOrNull() ?: return result(false, tr("上传出错，请重新选择文件"))
        val body = request.body
        synchronized(lock) {
            val u = upload?.takeIf { it.id == id } ?: return result(false, tr("上传已失效，请重新选择文件"))
            when {
                offset == u.received -> {
                    FileOutputStream(u.file, true).use { it.write(body) }
                    u.received += body.size
                }

                offset + body.size <= u.received -> Unit
                else -> return result(false, tr("上传出错，请重新选择文件"))
            }
            if (u.received > u.size) return result(false, tr("上传出错，请重新选择文件"))
            return buildJsonObject {
                put("ok", true)
                put("received", u.received)
            }
        }
    }

    /**
     * 收齐后核对. 装的是另一个包, 或者比已装的旧, 先回 `ask` 让网页问一句, 带 `force=1` 再来才装.
     * 核对不过时原因记进状态 ([rejectUpload]).
     */
    private fun uploadFinish(request: LanHttpRequest): JsonObject {
        val fields = request.formFields()
        val ctx = context ?: return result(false, NOT_READY)
        val u = synchronized(lock) { upload?.takeIf { it.id == fields["id"] } }
            ?: return result(false, tr("上传已失效，请重新选择文件"))
        if (u.received != u.size) return result(false, tr("文件没有传完整，请重试"))
        val verified = verify(ctx, u.file, sameAppOnly = false)
        verified.error?.let { return rejectUpload(it) }
        u.info.pkg = verified.packageName
        u.info.version = verified.versionName
        val other = verified.packageName.takeIf { it != ctx.packageName }
        if (fields["force"] != "1") {
            val current = installedVersion(ctx)
            val question = when {
                other != null -> tr(
                    "这个安装包是另一个 Izuko TV：{0}（{1}）。电视上已经装着它的话会更新它，没装的话会另外装一个；现在这个不受影响。确定要装吗？",
                    other, verified.versionName.orEmpty(),
                )

                isOlder(verified.versionName, current) ->
                    tr("这个安装包是 {0}，比现在的 {1} 旧，确定要装吗？", verified.versionName.orEmpty(), current)

                else -> null
            }
            if (question != null) {
                logger.info { "Remote update: asking before installing ${verified.packageName} ${verified.versionName} (this app: ${ctx.packageName} $current)" }
                return buildJsonObject {
                    put("ok", false)
                    put("ask", true)
                    put("message", question)
                }
            }
        }
        synchronized(lock) {
            if (state.phase in BUSY) return result(false, tr("正在安装，等这次完成后再试"))
            abandonSession(ctx)
            lastResult = null
            retry = Retry(u.file, verified.versionName, other)
            state = State(Phase.PREPARING, version = verified.versionName, pkg = other)
        }
        scope.launch { install(ctx, u.file, verified.versionName, other) }
        return result(true, "")
    }

    /**
     * 用刚才上传、没装成的包再装一次 (电视上误按了取消时不用重新上传). 上次已经核对过、问过, 这次直接装;
     * 授权照样要先有.
     */
    private fun retryUpload(request: LanHttpRequest): JsonObject {
        val ctx = context ?: return result(false, NOT_READY)
        needPermission(ctx, request)?.let { return it }
        val r = synchronized(lock) {
            val r = retry?.takeIf { it.file.exists() } ?: return result(false, tr("刚才上传的安装包已经不在了，请重新选择文件"))
            if (state.phase in BUSY) return result(false, tr("正在安装，等这次完成后再试"))
            abandonSession(ctx)
            lastResult = null
            state = State(Phase.PREPARING, version = r.version, pkg = r.pkg)
            r
        }
        logger.info { "Remote update: installing the uploaded package again (${r.pkg ?: "this app"} ${r.version})" }
        scope.launch { install(ctx, r.file, r.version, r.pkg) }
        return result(true, "")
    }

    /**
     * 上传的包核对不过: 原因记进状态, 网页上和安装失败一样一直显示. 正在下载、安装或等电视确认时不动那边的状态
     * (这次的原因只回给网页).
     */
    private fun rejectUpload(message: String): JsonObject {
        synchronized(lock) {
            if (state.phase !in BUSY && state.phase != Phase.CONFIRM && state.phase != Phase.WAITING_FRONT) {
                lastResult = null
                state = State(Phase.FAILED, error = message)
            }
        }
        return result(false, message)
    }

    // ============================ 安装授权 ============================

    /** 没有「安装未知应用」的授权时拦下 (见本文件开头); 网页在授权页打不开时带 `anyway=1` 再来. */
    private fun needPermission(ctx: Context, request: LanHttpRequest): JsonObject? {
        if (canInstall(ctx) || request.formFields()["anyway"] == "1") return null
        return buildJsonObject {
            put("ok", false)
            put("needPermission", true)
            put("message", tr("要先在电视上允许 Izuko TV 安装应用"))
        }
    }

    /**
     * 在电视上打开本应用的「安装未知应用」授权页 (同电视更新提示的「去设置」; Shield 上打开的是所有应用的列表).
     * Izuko 不在前台又叫不回来时等它回到前台再开 ([onTvForeground]); 打不开的电视回 ok=false.
     */
    private fun openPermission(): JsonObject {
        val ctx = context ?: return result(false, NOT_READY)
        if (canInstall(ctx)) return result(true, tr("已经允许了"))
        if (!TvRemoteControl.bringToFrontForInstall()) {
            pendingPermission = true
            logger.info { "Remote update: install permission settings deferred until the TV app is in front" }
            return result(true, tr("回到电视上的 Izuko TV 后会打开授权页。"))
        }
        val opened = launchPermission(ctx)
        return result(
            opened,
            if (opened) tr("已在电视上打开授权页：把 Izuko TV 的「允许」打开。") else tr("这台电视打不开授权页，可以直接安装，由系统询问。"),
        )
    }

    private fun launchPermission(ctx: Context): Boolean {
        pendingPermission = false
        val opened = runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { logger.warn(it) { "Remote update: cannot open the install permission settings" } }.isSuccess
        logger.info { "Remote update: install permission settings opened=$opened" }
        return opened
    }

    // ============================ 核对与安装 ============================

    /** 核对结果: [error] 不为 null 时不能装 */
    private class Checked(val packageName: String = "", val versionName: String? = null, val error: String? = null)

    /**
     * 只认本项目的包 (见本文件开头); [sameAppOnly]: 下载来的更新只能是本应用自己.
     * 装自己时签名要与已装的一致; 别的包看不到装没装, 签名交给系统核对.
     */
    @Suppress("DEPRECATION")
    private fun verify(ctx: Context, file: File, sameAppOnly: Boolean): Checked {
        val pm = ctx.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, SIGNATURE_FLAGS)
            ?: return Checked(error = tr("这不是有效的安装包"))
        val pkg = archive.packageName
        if (sameAppOnly && pkg != ctx.packageName) {
            logger.warn { "Remote update: rejected package $pkg (expected ${ctx.packageName})" }
            return Checked(error = tr("这个安装包是别的应用（{0}），这里只能装 Izuko TV 自己的更新", pkg))
        }
        if (!pkg.startsWith(PROJECT_PACKAGE_PREFIX)) {
            logger.warn { "Remote update: rejected package $pkg (not an Izuko TV package)" }
            return Checked(error = tr("这个安装包不是 Izuko TV 的（{0}），装不了", pkg))
        }
        val theirs = signersOf(archive)
        if (theirs.isNullOrEmpty()) return Checked(error = tr("读不出安装包的签名"))
        if (pkg == ctx.packageName && theirs != signersOf(pm.getPackageInfo(pkg, SIGNATURE_FLAGS))) {
            logger.warn { "Remote update: rejected ${archive.versionName}, signature differs from the installed app" }
            return Checked(error = tr("安装包的签名与已安装的 Izuko TV 不一致，装不上"))
        }
        return Checked(pkg, archive.versionName)
    }

    /** [pkg]: 要装的另一个包的包名, null = 装本应用自己 (更新) */
    private fun install(ctx: Context, file: File, version: String?, pkg: String?) {
        setState(State(Phase.PREPARING, version = version, pkg = pkg))
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(pkg ?: ctx.packageName)
            setSize(file.length())
        }
        val id = try {
            installer.createSession(params)
        } catch (e: Exception) {
            logger.warn(e) { "Remote install: cannot create session" }
            fail(tr("系统不让创建安装会话：{0}", systemErrorText(e)))
            return
        }
        sessionId = id
        try {
            installer.openSession(id).use { session ->
                val total = file.length()
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, total).use { out ->
                        // 七八十 MB 在电视上要写 6~15 秒 (Shield / 索尼实测), 网页上照实报写了多少, 落盘时另说一句
                        copyReportingProgress(input, out, total)
                        synchronized(lock) {
                            if (state.phase == Phase.PREPARING) {
                                state = state.copy(progress = null, writing = Writing(total, total, syncing = true))
                            }
                        }
                        session.fsync(out)
                    }
                }
                // 装自己成功时本进程会被结束, 结果留到下次启动核对; 装别的包本进程还在, 结果直接回传
                if (pkg == null) rememberPending(ctx, version)
                val intent = Intent(statusAction(ctx)).setPackage(ctx.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
            }
            logger.info { "Remote install: committed session $id (${file.name}, ${file.length()} bytes, ${pkg ?: "this app"}, version $version)" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Remote install: writing session $id failed" }
            runCatching { installer.abandonSession(id) }
            clearPending(ctx)
            fail(tr("写入安装包失败：{0}", systemErrorText(e)))
        }
    }

    /** 把安装包写进安装会话, 边写边更新 [State.writing] (最多每 [WRITE_REPORT_INTERVAL_MILLIS] 一次). */
    private fun copyReportingProgress(input: InputStream, out: OutputStream, total: Long) {
        val buffer = ByteArray(WRITE_BUFFER_BYTES)
        var written = 0L
        var reportedAt = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            written += n
            val now = System.currentTimeMillis()
            if (now - reportedAt >= WRITE_REPORT_INTERVAL_MILLIS) {
                reportedAt = now
                val done = written
                synchronized(lock) {
                    if (state.phase == Phase.PREPARING) {
                        state = state.copy(
                            progress = if (total > 0) (done.toFloat() / total).coerceAtMost(1f) else null,
                            writing = Writing(done, total),
                        )
                    }
                }
            }
        }
    }

    private fun onStatus(ctx: Context, id: Int, status: Int, message: String?, confirm: Intent?) {
        if (id != sessionId) {
            logger.info { "Remote install: ignoring status $status of stale session $id" }
            return
        }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) {
                    fail(tr("系统没有给出安装确认界面"))
                    return
                }
                logger.info { "Remote install: session $id waiting for confirmation on the TV" }
                scope.launch {
                    if (TvRemoteControl.bringToFrontForInstall()) {
                        launchConfirm(ctx, confirm)
                    } else {
                        pendingConfirm = confirm
                        setState(state.copy(phase = Phase.WAITING_FRONT, progress = null))
                    }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                logger.info { "Remote install: session $id installed" }
                clearPending(ctx)
                retry = null
                val done = synchronized(lock) { state.copy(phase = Phase.SUCCESS, progress = null).also { state = it } }
                // 装的是另一个包: 本应用不会被关, 电视上点完「安装」确认框一收就什么也看不出来, 弹一条系统提示 (回调在主线程)
                if (done.pkg != null) Toast.makeText(ctx, phaseText(done), Toast.LENGTH_LONG).show()
            }

            else -> {
                logger.warn { "Remote install: session $id failed, status=$status, message=$message" }
                clearPending(ctx)
                fail(failureText(status, message))
            }
        }
    }

    private fun launchConfirm(ctx: Context, confirm: Intent) {
        pendingConfirm = null
        try {
            ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            setState(state.copy(phase = Phase.CONFIRM, progress = null))
        } catch (e: Exception) {
            logger.warn(e) { "Remote install: cannot open the confirmation" }
            fail(tr("打不开安装确认界面：{0}", systemErrorText(e)))
        }
    }

    private fun failureText(status: Int, message: String?): String {
        val reason = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> tr("安装被取消了（电视上点了取消，或确认框被关掉）")
            PackageInstaller.STATUS_FAILURE_BLOCKED -> tr("安装被系统或电视厂商拦下了")
            PackageInstaller.STATUS_FAILURE_CONFLICT -> tr("和已安装的版本冲突")
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> tr("和这台电视不兼容（处理器架构或系统版本不符）")
            PackageInstaller.STATUS_FAILURE_INVALID -> tr("安装包无效或已损坏")
            PackageInstaller.STATUS_FAILURE_STORAGE -> tr("电视存储空间不够")
            else -> tr("安装失败")
        }
        return if (message.isNullOrBlank()) reason else tr("{0}（系统原话：{1}）", reason, message)
    }

    private fun abandonSession(ctx: Context) {
        val id = sessionId
        sessionId = -1
        pendingConfirm = null
        if (id != -1) runCatching { ctx.packageManager.packageInstaller.abandonSession(id) }
    }

    // ============================ 装自己之后的结果 ============================

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun rememberPending(ctx: Context, version: String?) {
        prefs(ctx).edit()
            .putString(KEY_VERSION, version.orEmpty())
            .putLong(KEY_AT, System.currentTimeMillis())
            .commit()
    }

    private fun clearPending(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    /** 应用的最后更新时间晚于提交那一刻 = 装上了 (同一版本重装也认得出) */
    @Suppress("DEPRECATION")
    private fun checkPendingResult(ctx: Context) {
        val p = prefs(ctx)
        val at = p.getLong(KEY_AT, 0L)
        if (at == 0L) return
        val version = p.getString(KEY_VERSION, null).orEmpty()
        p.edit().clear().apply()
        if (System.currentTimeMillis() - at > PENDING_TTL_MILLIS) return
        val updatedAt = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        lastResult = if (updatedAt >= at) {
            true to tr("已更新到 {0}", installedVersion(ctx))
        } else {
            false to tr("上次的安装没有完成（{0}）：电视上取消了，或者安装失败了。", version)
        }
        logger.info { "Remote install result after restart: ${lastResult?.second}" }
    }

    // ============================ 工具 ============================

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiver(ctx: Context) {
        val filter = IntentFilter(statusAction(ctx))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter)
        }
    }

    private fun statusAction(ctx: Context) = ctx.packageName + ".REMOTE_INSTALL_STATUS"

    private fun confirmIntentOf(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    @Suppress("DEPRECATION")
    private val SIGNATURE_FLAGS =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signersOf(info: PackageInfo): Set<String>? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.map { it.toCharsString() }?.toSet()
    }

    @Suppress("DEPRECATION")
    private fun installedVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()

    private fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** 只比开头的数字段 (`1.0.3-tv` → 1, 0, 3); 看不出来的不算旧 */
    private fun isOlder(candidate: String?, current: String): Boolean {
        fun numbers(v: String): List<Int>? = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
        val a = numbers(candidate ?: return false) ?: return false
        val b = numbers(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x < y
        }
        return false
    }

    private fun LanHttpRequest.param(name: String): String? =
        query.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    private val NOT_READY get() = tr("电视上的 Izuko TV 还没准备好，稍后再试")

    /** 本项目各个包的包名开头 (见 app/android/build.gradle.kts 的 applicationId): 正式包、测试包 (.debug2)、加后缀的对比包都以它开头 */
    private const val PROJECT_PACKAGE_PREFIX = "io.github.grahamzen.anime"

    private const val UPLOAD_DIR = "remote-update-upload"
    private const val PREFS_NAME = "tv_remote_update"
    private const val KEY_VERSION = "pending_version"
    private const val KEY_AT = "pending_at"

    /**
     * 每块字节数. 控制台一个请求一个连接、一问一答 (LanHttpServer 不做 keep-alive), 块小了时间都花在建连接和等回应上:
     * 60 KiB 一块时 78 MB 要一千三百多个请求, 手机传到 Shield 只有 1 MB/s 上下. 1 MiB 在普通 Wi-Fi 上一块一两百毫秒,
     * 碰不到 LanHttpServer 的慢请求 (3 秒) 与卡住 (10 秒) 报警.
     */
    private const val CHUNK_BYTES = 1024 * 1024
    private const val CHUNK_PATH = "api/update/upload/chunk"
    private const val MAX_UPLOAD_BYTES = 300L * 1024 * 1024

    /** 网页上显示的文件名最长多少字 (名字是手机传来的) */
    private const val MAX_NAME_CHARS = 200
    private const val PENDING_TTL_MILLIS = 30L * 60 * 1000

    /** 写安装会话时每次读多少, 以及多久报一次写了多少 (网页每秒拉一次状态) */
    private const val WRITE_BUFFER_BYTES = 256 * 1024
    private const val WRITE_REPORT_INTERVAL_MILLIS = 250L
}
