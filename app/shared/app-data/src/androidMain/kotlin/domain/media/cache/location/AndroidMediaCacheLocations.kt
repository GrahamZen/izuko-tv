/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.toKtPath
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.io.File

/**
 * 安卓上缓存可以放的位置: 内部存储 ([primaryRoot], 旧版本缓存所在) 与每个外接盘上本应用的私有目录
 * (`getExternalFilesDirs(MOVIES)`). 只用应用私有目录: 不用申请权限, 原生库 (BT) 能直接按路径读写; 代价是卸载应用时系统会一起删掉.
 *
 * 盘插拔时 (系统的 MEDIA_* 广播) 重新列一遍. 收到「正要卸载」(EJECT 等) 就当它不在了, 让它上面的下载先停下;
 * 列出来的盘按文件系统看能不能放新缓存: FAT32 (单个文件最大 4 GB) 与只读的不行.
 */
class AndroidMediaCacheLocations(
    private val context: Context,
    private val primaryRoot: SystemPath,
    private val selectedLocation: Flow<String?>,
    private val scope: CoroutineScope,
) : MediaCacheLocations {
    /** 收到卸载类广播、还没重新挂上的盘 (按卷 UUID) */
    private val leaving = mutableSetOf<String>()
    private val scanLock = Mutex()

    // 第一次读时当场列, 不先当成只有内部存储: 启动恢复要按它判断哪些缓存的盘在 (不然外接盘上的缓存会先变成占位再变回来).
    // 不在构造时列: 这个对象在启动时的主线程上建, 列盘要读写外接盘
    private val state: MutableStateFlow<List<MediaCacheLocation>> by lazy { MutableStateFlow(scan()) }
    override val locations: StateFlow<List<MediaCacheLocation>> get() = state

    override val moving: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    override val moveLock: Mutex = Mutex()

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = onMediaEvent(intent)
            },
            filter,
            // 这几个都是只有系统能发的广播
            ContextCompat.RECEIVER_EXPORTED,
        )
        scope.launch(Dispatchers.IO) {
            logger.info { "Cache locations at startup: ${describe(locations.value)}" }
            refresh()
        }
    }

    private fun onMediaEvent(intent: Intent) {
        val uuid = intent.data?.lastPathSegment
        logger.info { "Media event ${intent.action} for ${intent.data}" }
        if (uuid != null) {
            synchronized(leaving) {
                if (intent.action == Intent.ACTION_MEDIA_MOUNTED) leaving.remove(uuid) else leaving.add(uuid)
            }
        }
        scope.launch { refresh() }
    }

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            scanLock.withLock {
                val scanned = scan()
                if (scanned != state.value) logger.info { "Cache locations: ${describe(scanned)}" }
                state.value = scanned
            }
        }
    }

    override suspend fun locationForNewCache(): String? {
        val id = selectedLocation.first() ?: return null
        val location = locations.value.firstOrNull { it.id == id }
        if (location == null || location.unusable != null) {
            logger.warn { "Selected cache location $id is ${location?.unusable ?: "not connected"}, new caches go to the internal storage" }
            return null
        }
        return id
    }

    private fun scan(): List<MediaCacheLocation> {
        val storage = context.getSystemService(StorageManager::class.java)
        val leavingNow = synchronized(leaving) { leaving.toSet() }
        val facts = volumeFacts(storage)
        val external = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES).filterNotNull().mapNotNull { dir ->
            val volume = storage.getStorageVolume(dir) ?: return@mapNotNull null
            if (volume.isPrimary) return@mapNotNull null
            val uuid = volume.uuid ?: return@mapNotNull null
            val fact = facts[uuid]
            // 只要 U 盘、SD 卡、移动硬盘这类直接挂上的盘; 厂商的远程存储 (如 Shield 的) 也会列出来, 不要
            if (fact != null && !fact.isPublic) return@mapNotNull null
            val state = volume.state
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
            if (uuid in leavingNow) return@mapNotNull null
            val (free, total) = spaceOf(dir)
            MediaCacheLocation(
                id = uuid,
                label = volume.getDescription(context),
                root = dir.toKtPath().inSystem,
                freeBytes = free,
                totalBytes = total,
                unusable = problemOf(uuid, dir, fact?.fsType, readOnly = state == Environment.MEDIA_MOUNTED_READ_ONLY),
            )
        }
        return listOf(internalLocation()) + external
    }

    /** 系统记的卷信息: 文件系统 (`vfat` / `exfat` / `ntfs` …) 与是不是直接挂上的盘 */
    private class VolumeFacts(val fsType: String?, val isPublic: Boolean)

    /**
     * 按卷 UUID 取系统记的卷信息. 列卷用 `StorageManager.getVolumes` (不公开的接口, 反射调), 每个卷的字段从它的 `toString()` 读
     * (同 `dumpsys mount` 的格式: `type=PUBLIC …` / `fsType=vfat fsUuid=99C6-1920 …`): 字段本身新系统上反射拿不到.
     * 取不到时为空, 退回读挂载表. 应用进程里多半看不到盘的真实挂载 (`/mnt/media_rw/<uuid>`), 看到的 `/storage/<uuid>`
     * 是系统包了一层的, 文件系统看不出来.
     */
    private fun volumeFacts(storage: StorageManager): Map<String, VolumeFacts> = try {
        val volumes = StorageManager::class.java.getMethod("getVolumes").invoke(storage) as List<*>
        volumes.filterNotNull().mapNotNull { info ->
            val text = info.toString()
            fun field(name: String) = Regex("""(?<![A-Za-z])$name=(\S+)""").find(text)?.groupValues?.get(1)?.takeIf { it != "null" }
            val uuid = field("fsUuid") ?: return@mapNotNull null
            uuid to VolumeFacts(fsType = field("fsType"), isPublic = field("type") == "PUBLIC")
        }.toMap()
    } catch (e: Exception) {
        logger.warn { "Cannot read volume info, falling back to mounts: $e" }
        emptyMap()
    }

    private fun describe(list: List<MediaCacheLocation>): String =
        list.joinToString { "${it.id ?: "internal"}(${it.label}, ${it.unusable ?: "ok"}, free ${it.freeBytes})" }

    private fun internalLocation(): MediaCacheLocation {
        val dir = File(primaryRoot.absolutePath)
        val (free, total) = spaceOf(dir)
        val label = runCatching {
            context.getSystemService(StorageManager::class.java).primaryStorageVolume.getDescription(context)
        }.getOrNull().orEmpty()
        return MediaCacheLocation(id = null, label = label, root = primaryRoot, freeBytes = free, totalBytes = total)
    }

    private fun spaceOf(dir: File): Pair<Long?, Long?> = runCatching {
        val existing = generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() } ?: return null to null
        val stat = StatFs(existing.path)
        stat.availableBytes to stat.totalBytes
    }.getOrElse { null to null }

    private fun problemOf(uuid: String, dir: File, fsType: String?, readOnly: Boolean): MediaCacheLocationProblem? {
        if (readOnly) return MediaCacheLocationProblem.READ_ONLY
        val mount = mountOf(uuid)
        val type = fsType ?: mount?.fsType
        if (type == "vfat" || type == "msdos") return MediaCacheLocationProblem.FAT32
        if (mount != null && "ro" in mount.options) return MediaCacheLocationProblem.READ_ONLY
        return if (canWrite(dir)) null else MediaCacheLocationProblem.READ_ONLY
    }

    private class Mount(val fsType: String, val options: List<String>)

    /** 盘的真实挂载 (`/mnt/media_rw/<uuid>`); 多数设备上应用进程里看不到, 为 null */
    private fun mountOf(uuid: String): Mount? = runCatching {
        File("/proc/mounts").readLines().asSequence()
            .map { it.split(' ') }
            .filter { it.size >= 4 }
            .firstOrNull { it[1] == "/mnt/media_rw/$uuid" }
            ?.let { Mount(fsType = it[2], options = it[3].split(',')) }
    }.getOrNull()

    /** 真写一下: 有的电视把 NTFS 挂成能写, 写的时候才失败 */
    private fun canWrite(dir: File): Boolean = runCatching {
        dir.mkdirs()
        val probe = File(dir, ".izuko-write-probe")
        probe.writeBytes(byteArrayOf(1))
        probe.delete()
    }.getOrDefault(false)

    private companion object {
        private val logger = logger<AndroidMediaCacheLocations>()
    }
}
