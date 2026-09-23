/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.migration

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.migration.LegacyUserData
import me.him188.ani.app.data.repository.user.SettingsBackupService
import me.him188.ani.app.domain.media.cache.migration.CacheMigrationManifest
import me.him188.ani.app.domain.media.cache.migration.CacheMigrationReceipt
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin
import org.koin.mp.KoinPlatform
import java.io.FileNotFoundException
import kotlin.concurrent.thread

/**
 * 把本应用的设置备份与缓存交给**同签名**的另一个包.
 *
 * 换分发包名之后, 新包对系统来说是另一个应用, 读不到旧包的私有数据. 这个 provider 是唯一的通道:
 * 旧包 (跳板包) 导出, 新包首次启动时读一次就完成迁移, 用户不用手动导出导入 (见 `SettingsMigration`、
 * `CacheMigrationImport`).
 *
 * **为什么不用公共目录中转**: 备份里含登录凭据, 写到 Downloads 等于让同机任何应用都能读; 缓存文件放进公共目录,
 * Android 11 起新包还看不到别的应用放进去的非媒体文件 (续传数据、索引). 这里改成按调用方签名放行 ——
 * 只有我们自己签的包能拿到, 数据始终留在两个应用的私有空间里.
 *
 * **为什么不用 signature 级 permission**: 那要两个包各自声明同名 permission, 安装顺序不同还会撞;
 * 在 [call] / [openFile] 里当场比签名更直接, 也少一份 manifest 约定.
 */
class SettingsMigrationProvider : ContentProvider() {
    private val logger = logger<SettingsMigrationProvider>()
    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method !in METHODS) return null
        if (!callerHasSameSignature()) {
            logger.info { "migration: 拒绝一个签名对不上的调用方" }
            return null
        }
        val context = context ?: return null
        return try {
            val koin = awaitKoin() ?: run {
                logger.info { "migration: 等不到依赖注入就绪, 这次不给" }
                return null
            }
            when (method) {
                METHOD_EXPORT_SETTINGS -> {
                    val data = runBlocking { koin.get<SettingsBackupService>().export() }
                    logger.info { "migration: 导出设置备份 ${data.length} 字节" }
                    Bundle().apply { putString(KEY_BACKUP_JSON, data) }
                }

                METHOD_CACHE_SUMMARY -> {
                    val manifest = runBlocking { CacheMigrationExport.manifest(context, koin) }
                    logger.info { "migration: 缓存 ${manifest.mediaCacheSaves.size} 条, 文件 ${manifest.files.size} 个, ${manifest.totalBytes} 字节" }
                    Bundle().apply {
                        putInt(KEY_CACHE_COUNT, manifest.mediaCacheSaves.size)
                        putInt(KEY_FILE_COUNT, manifest.files.size)
                        putLong(KEY_TOTAL_BYTES, manifest.totalBytes)
                    }
                }

                METHOD_PREPARE_CACHE_EXPORT -> {
                    CacheMigrationExport.stopTorrentEngine(context)
                    Bundle()
                }

                METHOD_DELETE_CACHE_FILE -> {
                    val deleted = arg != null && CacheMigrationExport.deleteFile(koin, arg)
                    Bundle().apply { putBoolean(KEY_OK, deleted) }
                }

                METHOD_FINISH_CACHE_EXPORT -> {
                    val receipt = arg?.let { json.decodeFromString(CacheMigrationReceipt.serializer(), it) }
                        ?: return null
                    runBlocking { CacheMigrationExport.forgetReceived(context, koin, receipt) }
                    // 内存里还留着这些缓存的对象, 用户打开旧包时会按它们把种子重新下回来. 结束进程,
                    // 下次启动从已清理的记录恢复. 先让这次调用的结果送回去
                    Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 1_000)
                    Bundle()
                }

                else -> null
            }
        } catch (e: Exception) {
            logger.error(e) { "migration: $method 失败" }
            null
        }
    }

    /**
     * 交出缓存清单或单个缓存文件, 只读. 调用方同样要过签名校验.
     *
     * - `…/cache-manifest`: JSON 清单 ([CacheMigrationManifest]), 经管道流式写出 —— 种子数据与文件列表可能远超
     *   binder 单次事务上限 (约 1 MB), 放不进 [call] 的返回值.
     * - `…/cache-file?path=<相对路径>`: 缓存根目录里的一个文件, 越出根目录的路径一律拒绝.
     * - `…/user-data`: 设置备份与缓存之外的用户数据 ([LegacyUserData], 数据源、播放记录等), 同样经管道写出.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r" || !callerHasSameSignature()) throw FileNotFoundException("denied")
        val context = context ?: throw FileNotFoundException("no context")
        val koin = awaitKoin() ?: throw FileNotFoundException("not ready")
        return when (uri.lastPathSegment) {
            PATH_CACHE_MANIFEST -> {
                val manifest = runBlocking { CacheMigrationExport.manifest(context, koin) }
                pipe(json.encodeToString(CacheMigrationManifest.serializer(), manifest).encodeToByteArray())
            }

            PATH_USER_DATA -> {
                val data = runBlocking { UserDataMigration.export(context, koin) }
                logger.info { "migration: 导出用户数据, 数据源 ${data.mediaSources.instances.size} 个" }
                pipe(json.encodeToString(LegacyUserData.serializer(), data).encodeToByteArray())
            }

            PATH_CACHE_FILE -> {
                val relative = uri.getQueryParameter(QUERY_PATH) ?: throw FileNotFoundException("no path")
                val file = CacheMigrationExport.openFile(koin, relative) ?: throw FileNotFoundException(relative)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }

            else -> throw FileNotFoundException(uri.toString())
        }
    }

    private fun pipe(bytes: ByteArray): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        thread(name = "migration-pipe-writer") {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) }
        }
        return read
    }

    /**
     * 等依赖注入装好.
     *
     * **provider 会先于 `Application.onCreate` 跑起来**: 别的进程来读时, 系统是为了这个 provider 才拉起
     * 我们的进程的, 那一刻 Koin 还没装 —— 直接取会抛, 于是备份永远是空的 (2026-09-22 真机踩到).
     * 这里在 binder 线程上等一会儿, 主线程那边装完就能拿到.
     */
    private fun awaitKoin(): Koin? {
        val deadline = System.currentTimeMillis() + AWAIT_KOIN_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            try {
                return KoinPlatform.getKoin().also { it.get<SettingsBackupService>() }
            } catch (_: Throwable) {
                Thread.sleep(AWAIT_KOIN_INTERVAL_MILLIS)
            }
        }
        return null
    }

    /**
     * 调用方是不是用同一个证书签的.
     *
     * 一个 uid 下可能有多个包 (共享 uid), 只要其中有一个同签名就放行 —— 共享 uid 本身就要求同签名.
     */
    private fun callerHasSameSignature(): Boolean {
        val context = context ?: return false
        val pm = context.packageManager
        val callerPackages = pm.getPackagesForUid(Binder.getCallingUid()) ?: return false
        return callerPackages.any {
            pm.checkSignatures(it, context.packageName) == PackageManager.SIGNATURE_MATCH
        }
    }

    // 只提供 call() 与 openFile(), 其余一概不支持
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        /** [call] 的方法名. */
        const val METHOD_EXPORT_SETTINGS = "exportSettings"
        const val METHOD_CACHE_SUMMARY = "cacheSummary"
        const val METHOD_PREPARE_CACHE_EXPORT = "prepareCacheExport"
        const val METHOD_DELETE_CACHE_FILE = "deleteCacheFile"
        const val METHOD_FINISH_CACHE_EXPORT = "finishCacheExport"
        private val METHODS = setOf(
            METHOD_EXPORT_SETTINGS, METHOD_CACHE_SUMMARY, METHOD_PREPARE_CACHE_EXPORT,
            METHOD_DELETE_CACHE_FILE, METHOD_FINISH_CACHE_EXPORT,
        )

        /** 返回的 [Bundle] 里的键. */
        const val KEY_BACKUP_JSON = "backupJson"
        const val KEY_CACHE_COUNT = "cacheCount"
        const val KEY_FILE_COUNT = "fileCount"
        const val KEY_TOTAL_BYTES = "totalBytes"
        const val KEY_OK = "ok"

        /** [openFile] 的路径. */
        const val PATH_CACHE_MANIFEST = "cache-manifest"
        const val PATH_CACHE_FILE = "cache-file"
        const val PATH_USER_DATA = "user-data"
        const val QUERY_PATH = "path"

        /** authority 的后缀, 前面是各自的 applicationId (见 AndroidManifest). */
        const val AUTHORITY_SUFFIX = ".migration"

        private const val AWAIT_KOIN_TIMEOUT_MILLIS = 20_000L
        private const val AWAIT_KOIN_INTERVAL_MILLIS = 100L
    }
}
