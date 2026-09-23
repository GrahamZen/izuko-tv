/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.migration

import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.him188.ani.android.migration.SettingsMigrationProvider.Companion.KEY_CACHE_COUNT
import me.him188.ani.android.migration.SettingsMigrationProvider.Companion.METHOD_CACHE_SUMMARY
import me.him188.ani.android.migration.SettingsMigrationProvider.Companion.METHOD_DELETE_CACHE_FILE
import me.him188.ani.android.migration.SettingsMigrationProvider.Companion.METHOD_FINISH_CACHE_EXPORT
import me.him188.ani.android.migration.SettingsMigrationProvider.Companion.METHOD_PREPARE_CACHE_EXPORT
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.dataStores
import me.him188.ani.app.domain.media.cache.migration.CacheMigrationManifest
import me.him188.ani.app.domain.media.cache.migration.CacheMigrationPlanner
import me.him188.ani.app.domain.media.cache.migration.CacheMigrationReceipt
import me.him188.ani.app.domain.media.cache.storage.MediaSaveDirProvider
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * 新包这边把旧包的缓存搬过来, 在设置与登录接管 ([SettingsMigration]) 之后进行.
 *
 * 分两步, 中间重启一次应用:
 *
 * 1. **搬运** ([run]): 逐个文件从旧包取过来, 放进缓存根目录下的暂存区 ([STAGING_DIR_NAME]), 取完一个就让旧包删掉
 *    自己那份 —— 只需多出一个文件的空间. 暂存区不在任何清理范围内, 也不会被正在运行的缓存系统看见.
 *    旧包那边先停掉 BT 引擎, 取到的是静止的文件.
 * 2. **提交** ([commitIfStaged]): 下次启动、缓存系统恢复之前, 把暂存区的文件挪到正式位置 (同一目录树内改名,
 *    瞬间完成), 把记录写进缓存索引与数据库. 放在恢复之前是因为恢复后会清理对不上记录的文件.
 *
 * 搬运中途断掉 (应用被关、空间不够) 下次启动接着搬; 旧包中途被卸载, 就提交已经搬到的那部分.
 */
object CacheMigrationImport {
    private val logger = logger<CacheMigrationImport>()
    private val json = Json { ignoreUnknownKeys = true }

    /** 界面看的搬运状态, 见 [CacheMigrationOverlay]. */
    sealed interface UiState {
        data object Idle : UiState
        data object Preparing : UiState
        data class Transferring(val doneFiles: Int, val totalFiles: Int, val doneBytes: Long, val totalBytes: Long) : UiState
        data class NeedSpace(val neededBytes: Long) : UiState
        data class Failed(val message: String) : UiState
        data object Restarting : UiState
    }

    /** 持久化的进度. */
    enum class Phase {
        /** 还没开始. */
        NONE,

        /** 搬运中 (含中途停下), 下次启动接着搬. */
        TRANSFERRING,

        /** 已搬完, 等下次启动提交. */
        STAGED,

        /** 结束: 提交完了, 或者没有要搬的. */
        DONE,
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _phase = MutableStateFlow<Phase?>(null)

    /** [resumeIfNeeded] 时记下, 给界面上的「重试」用. 搬运要比界面活得久, 不能用界面的 scope. */
    private var appScope: CoroutineScope? = null
    private var appKoin: Koin? = null

    /** 同 [phase], 可订阅. 第一次读之前是 `null`. */
    val phaseFlow: StateFlow<Phase?> = _phase.asStateFlow()

    private const val PREFS = "settings_migration"
    private const val KEY_PHASE = "cache_phase"
    private const val KEY_STAGING_DIR = "cache_staging_dir"
    private const val KEY_BASE_DIR = "cache_base_dir"
    private const val KEY_IMPORTED_COUNT = "cache_imported_count"

    /** 暂存区, 在缓存根目录下. 两种缓存各自只清理自己的子目录 (`anitorrent/pieces`、`web-m3u`), 碰不到这里. */
    private const val STAGING_DIR_NAME = ".cache-migration"
    private const val MANIFEST_FILE = "manifest.json"
    private const val PART_SUFFIX = ".part"
    private const val COPY_CHUNK_BYTES = 1 shl 20

    /**
     * 每写这么多就刷一次盘. 整个文件写完才刷, 上 GB 的缓存会在内存里攒下大量脏页, 电视上会拖住整机 I/O
     * (2026-09-23 真机: 搬运中途新包的 BT 服务启动超时 ANR, 同 project-cache-freeze-bt-remote).
     */
    private const val SYNC_EVERY_BYTES = 32L shl 20

    /** 每个文件复制前额外留的余量: 电视把存储写满会连系统一起卡住 (见 project-shield-storage-full-anr). */
    private const val SPACE_RESERVE_BYTES = 200L * 1024 * 1024

    fun phase(context: Context): Phase =
        runCatching { Phase.valueOf(prefs(context).getString(KEY_PHASE, null) ?: "") }.getOrDefault(Phase.NONE)
            .also { _phase.value = it }

    /** 上一次提交进来的缓存条数. */
    fun importedCount(context: Context): Int = prefs(context).getInt(KEY_IMPORTED_COUNT, 0)

    /**
     * 启动时调用 (不阻塞): 设置已经接管过、旧包还在、缓存没搬完, 就开始或接着搬.
     */
    fun resumeIfNeeded(context: Context, koin: Koin, scope: CoroutineScope) {
        appScope = scope
        appKoin = koin
        val phase = phase(context)
        if (phase != Phase.NONE && phase != Phase.TRANSFERRING) return
        if (!SettingsMigration.wasImported(context)) return
        if (!SettingsMigration.isLegacyInstalled(context)) {
            if (phase == Phase.NONE) setPhase(context, Phase.DONE) // 旧包已经没了, 无从搬起
            return
        }
        scope.launch { run(context, koin) }
    }

    /** 界面上的「重试」. */
    fun retry(context: Context) {
        val scope = appScope ?: return
        val koin = appKoin ?: return
        scope.launch { run(context, koin) }
    }

    /** 界面上的「以后再说」: 进度留着, 下次启动接着搬. */
    fun postpone() {
        _uiState.value = UiState.Idle
    }

    private suspend fun run(context: Context, koin: Koin) {
        if (_uiState.value !is UiState.Idle && _uiState.value !is UiState.NeedSpace && _uiState.value !is UiState.Failed) {
            return // 已经在搬
        }
        val authority = SettingsMigration.legacyPackageOf(context) + SettingsMigrationProvider.AUTHORITY_SUFFIX
        val client = context.contentResolver.acquireUnstableContentProviderClient(authority) ?: run {
            logger.info { "migration: 旧包没有迁移通道, 缓存不搬" }
            setPhase(context, Phase.DONE)
            return
        }
        try {
            _uiState.value = UiState.Preparing
            withContext(Dispatchers.IO) { transfer(context, koin, client, authority) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "migration: 搬运缓存中断" }
            _uiState.value = UiState.Failed(e.message ?: e::class.simpleName.orEmpty())
        } finally {
            client.close()
        }
    }

    private suspend fun transfer(context: Context, koin: Koin, client: ContentProviderClient, authority: String) {
        val summary = client.call(METHOD_CACHE_SUMMARY, null, null)
        if (summary == null) {
            // 旧包不认这个方法 (它是没有缓存迁移的跳板包) 或出错: 设置已接管, 缓存就不强求了
            logger.info { "migration: 旧包不提供缓存, 跳过" }
            setPhase(context, Phase.DONE)
            _uiState.value = UiState.Idle
            return
        }
        if (summary.getInt(KEY_CACHE_COUNT) == 0) {
            logger.info { "migration: 旧包没有要搬的缓存" }
            setPhase(context, Phase.DONE)
            _uiState.value = UiState.Idle
            return
        }

        client.call(METHOD_PREPARE_CACHE_EXPORT, null, null)
        val manifest = openInput(client, uri(authority, SettingsMigrationProvider.PATH_CACHE_MANIFEST)).use {
            json.decodeFromString(CacheMigrationManifest.serializer(), it.readBytes().decodeToString())
        }

        val baseDir = File(koin.get<MediaSaveDirProvider>().saveDir)
        val staging = File(baseDir, STAGING_DIR_NAME)
        if (!staging.isDirectory && !staging.mkdirs()) throw IOException("无法创建暂存目录 $staging")
        // 每次都写最新的清单: 旧包在收尾之前一直留着全部记录, 这一份总是全的; 文件列表只剩还没搬的
        writeAtomically(File(staging, MANIFEST_FILE), json.encodeToString(CacheMigrationManifest.serializer(), manifest).encodeToByteArray())
        prefs(context).edit()
            .putString(KEY_STAGING_DIR, staging.absolutePath)
            .putString(KEY_BASE_DIR, baseDir.absolutePath)
            .apply()
        setPhase(context, Phase.TRANSFERRING)
        logger.info { "migration: 开始搬运 ${manifest.files.size} 个文件, ${manifest.totalBytes} 字节 -> $staging" }

        var doneFiles = 0
        var doneBytes = 0L
        val totalFiles = manifest.files.size
        val totalBytes = manifest.totalBytes
        _uiState.value = UiState.Transferring(0, totalFiles, 0, totalBytes)
        for (file in manifest.files) {
            if (!CacheMigrationPlanner.isSafeRelativePath(file.path)) continue
            val target = File(staging, file.path)
            if (!(target.isFile && target.length() == file.size)) {
                val free = StatFs(staging.path).availableBytes
                val need = file.size + SPACE_RESERVE_BYTES
                if (free < need) {
                    logger.info { "migration: 空间不够, 还差 ${need - free} 字节 (${file.path})" }
                    _uiState.value = UiState.NeedSpace(need - free)
                    return
                }
                copyFromLegacy(client, authority, file, target)
            }
            client.call(METHOD_DELETE_CACHE_FILE, file.path, null)
            doneFiles++
            doneBytes += file.size
            _uiState.value = UiState.Transferring(doneFiles, totalFiles, doneBytes, totalBytes)
        }

        setPhase(context, Phase.STAGED)
        // 回执按暂存区里实际有的算: 之前几次 (空间不够中断过) 已经搬来的也算在内
        val received = CacheMigrationPlanner.keepArrived(manifest) { File(staging, it).exists() }
        val receipt = CacheMigrationPlanner.receiptOf(received)
        client.call(METHOD_FINISH_CACHE_EXPORT, json.encodeToString(CacheMigrationReceipt.serializer(), receipt), null)
        logger.info { "migration: 缓存搬运完成, 重启后提交" }
        _uiState.value = UiState.Restarting
        delay(1_500) // 让「搬运完成」这句话被看到
        withContext(Dispatchers.Main) { restartApp(context) }
    }

    private fun copyFromLegacy(
        client: ContentProviderClient,
        authority: String,
        file: CacheMigrationManifest.CacheFile,
        target: File,
    ) {
        target.parentFile?.mkdirs()
        val part = File(target.path + PART_SUFFIX)
        val source = uri(authority, SettingsMigrationProvider.PATH_CACHE_FILE, file.path)
        openInput(client, source).use { input -> copyKeepingHoles(input, part) }
        if (part.length() != file.size) {
            part.delete()
            throw IOException("文件大小对不上: ${file.path} (${part.length()} != ${file.size})")
        }
        if (!part.renameTo(target)) throw IOException("无法放入暂存区: ${file.path}")
    }

    /**
     * 提交暂存区里的缓存. 在缓存系统恢复之前调用 (见 `startCommonKoinModule` 的 `beforeCacheRestore`).
     *
     * 旧包在搬运中途被卸载时, 也提交已经搬到的部分: 那些文件在旧包那边已经删了, 不提交就彻底没了.
     *
     * does not throw: 失败只影响搬来的缓存, 不能连带整个缓存系统起不来.
     */
    suspend fun commitIfStaged(context: Context, koin: Koin) {
        try {
            val phase = phase(context)
            val legacyGone = !SettingsMigration.isLegacyInstalled(context)
            if (!(phase == Phase.STAGED || (phase == Phase.TRANSFERRING && legacyGone))) return
            withContext(Dispatchers.IO) { commit(context, koin) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "migration: 提交搬来的缓存失败" }
        }
    }

    private suspend fun commit(context: Context, koin: Koin) {
        val prefs = prefs(context)
        val staging = prefs.getString(KEY_STAGING_DIR, null)?.let(::File)
        val baseDir = prefs.getString(KEY_BASE_DIR, null)?.let(::File)
        val manifestFile = staging?.let { File(it, MANIFEST_FILE) }
        if (staging == null || baseDir == null || manifestFile?.isFile != true) {
            setPhase(context, Phase.DONE)
            return
        }
        val manifest = json.decodeFromString(CacheMigrationManifest.serializer(), manifestFile.readText())

        var moved = 0
        staging.walkTopDown().filter { it.isFile }.forEach { file ->
            if (file == manifestFile) return@forEach
            if (file.name.endsWith(PART_SUFFIX)) { // 没复制完的
                file.delete()
                return@forEach
            }
            val relative = file.relativeTo(staging).invariantSeparatorsPath
            if (!CacheMigrationPlanner.isSafeRelativePath(relative)) return@forEach
            val target = File(baseDir, relative)
            if (target.exists()) { // 新包自己已有同名文件: 留着它的
                file.delete()
                return@forEach
            }
            target.parentFile?.mkdirs()
            if (!file.renameTo(target)) {
                file.copyTo(target)
                file.delete()
            }
            moved++
        }

        val arrived = CacheMigrationPlanner.keepArrived(manifest) { File(baseDir, it).exists() }
        context.dataStores.mediaCacheMetadataStore.updateData { existing ->
            existing + arrived.mediaCacheSaves.filter { save ->
                existing.none {
                    it.origin.mediaId == save.origin.mediaId &&
                            it.metadata.episodeId == save.metadata.episodeId && it.engine == save.engine
                }
            }
        }
        val db = koin.get<AniDatabase>()
        arrived.torrentCaches.forEach { db.torrentCacheInfoDao().upsert(it.toEntity()) }
        arrived.torrentEpisodes.forEach { db.torrentCacheInfoDao().upsertEpisode(it.toEntity()) }
        arrived.httpDownloads.forEach { db.httpCacheDownloadStateDao().upsert(it) }

        staging.deleteRecursively()
        prefs.edit().putInt(KEY_IMPORTED_COUNT, arrived.mediaCacheSaves.size).apply()
        setPhase(context, Phase.DONE)
        logger.info { "migration: 提交搬来的缓存 ${arrived.mediaCacheSaves.size} 条 (清单 ${manifest.mediaCacheSaves.size} 条), 文件 $moved 个" }
    }

    /**
     * 重启本应用: 缓存系统在启动时恢复一次, 搬来的缓存要重启后才会出现.
     * 应用这时在前台, 从这里启动界面不受后台启动限制.
     */
    private fun restartApp(context: Context) {
        val pm = context.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(context.packageName)
            ?: pm.getLaunchIntentForPackage(context.packageName)
            ?: return
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }

    /**
     * 复制时跳过全零的块, 在目标文件里留成空洞.
     *
     * 没下完的种子文件是预先按全长占位的稀疏文件 (2026-09-22 真机: 列出来 3.5 GB, 实际只占 2.5 GB).
     * 原样按字节流复制会把空洞写成真实的零, 搬完反而多占一截空间 —— 电视存储本来就紧.
     */
    private fun copyKeepingHoles(input: InputStream, target: File) {
        val buffer = ByteArray(COPY_CHUNK_BYTES)
        RandomAccessFile(target, "rw").use { file ->
            file.setLength(0)
            val channel = file.channel
            var position = 0L
            var unsynced = 0L
            while (true) {
                val read = input.readFully(buffer)
                if (read <= 0) break
                if (!buffer.isAllZero(read)) {
                    channel.write(ByteBuffer.wrap(buffer, 0, read), position)
                    unsynced += read
                    if (unsynced >= SYNC_EVERY_BYTES) {
                        channel.force(false)
                        unsynced = 0
                    }
                }
                position += read
            }
            // 末尾是空洞时文件还没有到应有的长度
            if (file.length() < position) file.setLength(position)
            channel.force(true)
        }
    }

    /** 尽量读满 [buffer], 流结束时返回实际读到的长度 (可能为 0). */
    private fun InputStream.readFully(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun ByteArray.isAllZero(length: Int): Boolean {
        for (i in 0 until length) if (this[i] != 0.toByte()) return false
        return true
    }

    /** 由这个流负责关掉文件描述符 (再包一层 FileInputStream 会关两次). */
    private fun openInput(client: ContentProviderClient, uri: Uri): InputStream =
        ParcelFileDescriptor.AutoCloseInputStream(
            client.openFile(uri, "r") ?: throw IOException("旧包没有交出 $uri"),
        )

    private fun uri(authority: String, path: String, file: String? = null): Uri =
        Uri.Builder().scheme("content").authority(authority).appendPath(path)
            .apply { if (file != null) appendQueryParameter(SettingsMigrationProvider.QUERY_PATH, file) }
            .build()

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val tmp = File(file.path + PART_SUFFIX)
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("无法写入 $file")
        }
    }

    private fun setPhase(context: Context, phase: Phase) {
        prefs(context).edit().putString(KEY_PHASE, phase.name).commit()
        _phase.value = phase
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
