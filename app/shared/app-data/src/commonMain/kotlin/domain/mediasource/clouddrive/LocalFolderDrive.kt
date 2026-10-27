/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import me.him188.ani.app.domain.media.cache.library.MediaLibraryAccess
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.isDirectory
import me.him188.ani.utils.io.list
import me.him188.ani.utils.io.metadataOrNull
import kotlin.random.Random

/**
 * 设备上的文件夹 (内部存储、接着的 U 盘与移动硬盘) 当网盘用: 协议的 [CloudDriveProtocol.type] 是 [TYPE], 不写请求模板, 也不用登录.
 *
 * - 列目录: 直接读盘上的文件夹 ([LocalFolderClient]). 根是此刻接着的各块盘 ([LocalFolderRoots]), 盘根下的系统目录与片库不列
 *   (片库里的视频本来就是缓存).
 * - 直链: `file://` 加文件路径, 播放时直接读文件 (见 `CloudDriveMediaResolver`), 旁边的外挂字幕同样.
 * - 没有按名搜索: 播放时在媒体文件夹 ([CloudDriveProtocol.library]) 的索引里按番名找 (见 [DriveLibraryIndex]),
 *   之后与 WebDAV 一样按路径认部、认集. 没有分享、转存、转码.
 *
 * 文件 id 是文件的绝对路径整个按 URL 参数编码一次 (占位地址里的文件 id 不能带 `/`, 见 [DrivePlaceholders.fileIdOf]).
 * 盘的挂载路径是系统按卷给的 (`/storage/<UUID>`), 换一块盘就是另一个路径; 盘没接上时它上面的文件夹列不出来, 媒体文件夹照留.
 */
object LocalFolderDrive {
    const val TYPE = "local"

    /** 根 (各块盘) 的 id */
    const val ROOT_ID = "root"

    /**
     * 本地文件夹不用登录, 账号里没有登录信息时按这个名字当作已登录 (见 `CloudDriveService.account`),
     * 「没登录就不查」「没登录要先登录」这些对网盘的判断对它都不成立.
     */
    const val ACCOUNT_NAME = "local"

    private const val FILE_URL_PREFIX = "file://"

    fun idOf(path: String): String = path.encodeURLParameter()

    fun pathOf(id: String): String = id.decodeURLQueryComponent()

    /** 文件 [id] 的直链 */
    fun urlOf(id: String): String = FILE_URL_PREFIX + pathOf(id)

    /** 直链 [url] 指向的文件路径; 不是本地文件夹的直链时为 null */
    fun pathOfUrl(url: String): String? = url.takeIf { it.startsWith(FILE_URL_PREFIX) }?.removePrefix(FILE_URL_PREFIX)

    fun nameOf(id: String): String = pathOf(id).trimEnd('/').substringAfterLast('/')

    fun parentIdOf(id: String): String {
        val path = pathOf(id).trimEnd('/')
        val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
        return if (parent.isEmpty()) ROOT_ID else idOf(parent)
    }

    /** 一个新的本地文件夹数据源的协议 (id 随机, 之后改名字不变). 加数据源、改媒体文件夹、删除同 [WebDavSources]. */
    fun newProtocol(name: String): CloudDriveProtocol {
        val id = "local-" + (1..8).map { ID_CHARS[Random.nextInt(ID_CHARS.length)] }.joinToString("")
        return CloudDriveProtocol(id = id, name = name.trim(), type = TYPE)
    }

    private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
}

/**
 * 本地文件夹能读哪些盘, 由平台提供. 安卓上读别的应用放的文件要「所有文件访问」(同片库, 见 [MediaLibraryAccess]).
 * 没有实现的平台 ([None]) 一块盘都没有.
 */
interface LocalFolderRoots {
    /** 此刻接着的盘, 内部存储在前 */
    fun roots(): List<LocalFolderRoot>

    /** 能读盘上的文件夹 (安卓上是「所有文件访问」开着) */
    fun isAccessible(): Boolean

    object None : LocalFolderRoots {
        override fun roots(): List<LocalFolderRoot> = emptyList()
        override fun isAccessible(): Boolean = false
    }
}

/**
 * @param label 系统给这块盘的名字 (如「内部共享存储空间」、U 盘的卷标)
 * @param path 盘的根目录 (如 `/storage/emulated/0`), 不以 `/` 结尾
 */
data class LocalFolderRoot(val label: String, val path: String)

class LocalFolderUnavailableException(message: String) : CloudDriveApiException(message)

/** 读盘上的文件夹 (见 [LocalFolderDrive]). */
internal class LocalFolderClient(private val roots: LocalFolderRoots) {
    /** 文件夹 [folderId] 的直接子项; 根 ([LocalFolderDrive.ROOT_ID]) 是各块盘. 没有权限、文件夹不在了时抛 [LocalFolderUnavailableException]. */
    suspend fun list(folderId: String): List<DriveFile> = withContext(Dispatchers.IO_) {
        if (!roots.isAccessible()) throw LocalFolderUnavailableException("没有读取设备上文件夹的权限")
        val volumes = roots.roots()
        if (folderId == LocalFolderDrive.ROOT_ID) {
            return@withContext volumes.map { DriveFile(LocalFolderDrive.idOf(it.path), it.label, LocalFolderDrive.ROOT_ID, dir = true) }
        }
        val path = LocalFolderDrive.pathOf(folderId).trimEnd('/')
        val folder = Path(path).inSystem
        if (!folder.isDirectory()) throw LocalFolderUnavailableException("文件夹不在了（盘没接上或已删除）：$path")
        val atVolumeRoot = volumes.any { it.path == path }
        folder.list().mapNotNull { child ->
            val name = child.name
            if (name.startsWith('.') || name.startsWith('$') || name in SYSTEM_FOLDERS) return@mapNotNull null
            if (atVolumeRoot && name in VOLUME_ROOT_SKIPPED) return@mapNotNull null
            val metadata = child.inSystem.metadataOrNull() ?: return@mapNotNull null
            val dir = metadata.isDirectory
            DriveFile(
                fid = LocalFolderDrive.idOf("$path/$name"),
                fileName = name,
                parentFid = folderId,
                dir = dir,
                size = if (dir) 0 else metadata.size,
                isVideo = !dir && CloudDriveApi.isVideoFileName(name),
            )
        }
    }

    /** 文件 [id] 给人看的位置: 盘的名字加盘里的路径 (如「U 盘/动画/孤独摇滚」); 根是空. */
    fun displayPath(id: String): String {
        if (id == LocalFolderDrive.ROOT_ID) return ""
        val path = LocalFolderDrive.pathOf(id).trimEnd('/')
        val volume = roots.roots().filter { path == it.path || path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }
            ?: return path
        return (listOf(volume.label) + path.removePrefix(volume.path).split('/').filter { it.isNotEmpty() }).joinToString("/")
    }

    /** 文件夹 [id] 的上一级: 盘的根目录上一级是根 ([LocalFolderDrive.ROOT_ID]), 根没有上一级 (空). */
    fun parentIdOf(id: String): String {
        if (id == LocalFolderDrive.ROOT_ID) return ""
        val path = LocalFolderDrive.pathOf(id).trimEnd('/')
        if (roots.roots().any { it.path == path }) return LocalFolderDrive.ROOT_ID
        return LocalFolderDrive.parentIdOf(id)
    }

    fun isAccessible(): Boolean = roots.isAccessible()

    /** 各块盘根目录的 id (没选媒体文件夹时从这里往下建索引) */
    fun volumeIds(): List<String> = roots.roots().map { LocalFolderDrive.idOf(it.path) }

    private companion object {
        /** 各种文件系统放在盘里的系统目录 */
        val SYSTEM_FOLDERS = setOf("LOST.DIR", "System Volume Information")

        /** 盘根下不列的: 各应用的私有目录, 片库 (里面的视频是缓存, 缓存数据源已经列着) */
        val VOLUME_ROOT_SKIPPED = setOf("Android", MediaLibraryAccess.LIBRARY_DIR_NAME)
    }
}
