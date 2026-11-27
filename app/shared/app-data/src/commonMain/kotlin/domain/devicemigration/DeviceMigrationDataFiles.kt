/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.devicemigration

import kotlinx.serialization.Serializable
import me.him188.ani.app.data.persistent.JsonUserDataFile
import me.him188.ani.app.data.repository.subject.SubjectRewatch
import me.him188.ani.app.domain.media.continuation.SubjectSourceTable
import me.him188.ani.app.domain.profile.PendingLocalCollections
import me.him188.ani.app.domain.profile.UserProfile
import me.him188.ani.app.domain.profile.UserProfileRegistry
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.isRegularFile
import me.him188.ani.utils.io.list
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.readBytes
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeBytes
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.io.encoding.Base64

/** 数据目录里按文件搬的一份: [name] 是文件名 (按人的是不带用户后缀的名字, 见 [UserProfile.scopedFileName]), [content] 是内容的 Base64. */
@Serializable
data class DeviceMigrationFile(val name: String, val content: String) {
    val bytes: ByteArray get() = Base64.decode(content)

    companion object {
        fun of(name: String, bytes: ByteArray) = DeviceMigrationFile(name, Base64.encode(bytes))
    }
}

/** 数据目录里的一个文件归谁 (见 [DeviceMigrationDataFiles.ownerOf]). */
internal sealed interface DataFileOwner {
    /** 整机的 */
    data object Device : DataFileOwner

    /** [profileId] 号用户的, 不带用户后缀叫 [baseName] */
    data class Profile(val profileId: Int, val baseName: String) : DataFileOwner

    /** 不搬 */
    data object Excluded : DataFileOwner
}

/**
 * 数据目录 (`files/`) 顶层的文件按文件搬: 默认都搬, 不搬的列在 [EXCLUDED] (缓存、下载来的表、跟着设备走的、另有搬法的);
 * 新功能往数据目录里加的文件不用再改换电视. 子目录不搬 (下载来的目录、日志、字体缓存).
 *
 * - 按人的跟着用户走 ([ownerOf]): 名字带 `_user<N>` 的是 N 号用户的, 1 号用户那份是不带后缀的同名文件; 到新电视上按新编号改名.
 * - 番 × 源的表 ([SubjectSourceTable.FILE_NAME]) 并进新电视的表, 两边都有的番线索取最近播过的那边 ([DeviceMigrationMerge.subjectSources]).
 * - 新电视上先写进暂存目录 ([STAGED_DIR]), 重启后、各处读文件之前换上 ([applyStaged]): 番 × 源的表这些开着时在内存里有一份,
 *   当场写的会被它们写回去.
 */
object DeviceMigrationDataFiles {
    /** 不搬的 (按不带用户后缀的名字) */
    val EXCLUDED: Set<String> = setOf(
        // 用户列表: 换电视自己排编号
        UserProfileRegistry.FILE_NAME,
        // 重温: 库的快照里按开始时的看过算, 重温不跟去
        SubjectRewatch.FILE_NAME,
        // 下载来的表与推荐缓存: 新电视自己下
        "bgm-series-graph.tsv",
        "bgm-sequel-seasons.tsv",
        "tmdb-subject-map.tsv",
        "subject-recs.json",
        // 本地片库的索引、搬缓存的进度: 跟着这台的盘
        "media-library-index.json",
        "cache-move-waiting.json",
        "profileInstalled",
    )

    /** 按人的文件里 1 号用户那份 (不带后缀): 只有 1 号用户时没有带后缀的同名文件可比, 列在这里 */
    val PROFILE_FILES: Set<String> = setOf(PendingLocalCollections.FILE_NAME, SubjectRewatch.FILE_NAME)

    /** 新电视上暂存搬来的文件的目录 (在数据目录下) */
    const val STAGED_DIR = "device-migration-staged"

    private val SCOPED_NAME = Regex("""^(.+)_user(\d+)(\.[^._]+)?$""")

    /** 数据目录里叫 [name] 的文件归谁; [names] 是数据目录里全部的文件名 (认 1 号用户那份要看有没有带后缀的同名文件) */
    internal fun ownerOf(name: String, names: Set<String>): DataFileOwner {
        if (name.endsWith(".tmp") || name.endsWith(JsonUserDataFile.UNREADABLE_SUFFIX) || name == STAGED_DIR) return DataFileOwner.Excluded
        val scoped = SCOPED_NAME.matchEntire(name)?.let { match ->
            val (base, id, extension) = match.destructured
            DataFileOwner.Profile(id.toInt(), base + extension)
        }
        val owner = when {
            scoped != null && scoped.profileId != UserProfile.PRIMARY_ID -> scoped
            name in PROFILE_FILES -> DataFileOwner.Profile(UserProfile.PRIMARY_ID, name)
            names.any { other -> (SCOPED_NAME.matchEntire(other)?.destructured)?.let { (base, _, extension) -> base + extension == name } == true } ->
                DataFileOwner.Profile(UserProfile.PRIMARY_ID, name)

            else -> DataFileOwner.Device
        }
        val baseName = (owner as? DataFileOwner.Profile)?.baseName ?: name
        return if (baseName in EXCLUDED) DataFileOwner.Excluded else owner
    }

    /** 整机的那些 (旧电视交出) */
    fun deviceFiles(dataDir: SystemPath): List<DeviceMigrationFile> =
        filesOf(dataDir) { it == DataFileOwner.Device }.map { (file, _) -> DeviceMigrationFile.of(file.name, file.readBytes()) }

    /** [profileId] 号用户的那些, 名字不带用户后缀 (旧电视交出) */
    fun profileFiles(dataDir: SystemPath, profileId: Int): List<DeviceMigrationFile> =
        filesOf(dataDir) { it is DataFileOwner.Profile && it.profileId == profileId }
            .map { (file, owner) -> DeviceMigrationFile.of((owner as DataFileOwner.Profile).baseName, file.readBytes()) }

    private fun filesOf(dataDir: SystemPath, accept: (DataFileOwner) -> Boolean): List<Pair<SystemPath, DataFileOwner>> {
        if (!dataDir.exists()) return emptyList()
        val files = dataDir.list().map { it.inSystem }.filter { it.isRegularFile() }
        val names = files.mapTo(HashSet()) { it.name }
        return files.map { it to ownerOf(it.name, names) }.filter { accept(it.second) }
    }

    /** 新电视上把 [name] 暂存起来, 重启后换上 ([applyStaged]) */
    fun stage(dataDir: SystemPath, name: String, bytes: ByteArray) {
        val dir = dataDir.resolve(STAGED_DIR)
        dir.createDirectories()
        dir.resolve(name).writeBytes(bytes)
    }

    /** 暂存的都删掉 (搬到一半失败时) */
    fun clearStaged(dataDir: SystemPath) {
        val dir = dataDir.resolve(STAGED_DIR)
        if (!dir.exists()) return
        dir.list().forEach { it.inSystem.delete() }
        dir.delete()
    }

    /**
     * 启动时、各处读数据目录之前调一次 (应用入口里一行): 换电视暂存的文件换上. 没有暂存的什么都不做.
     * 不抛错 (这是应用入口, 抛了每次启动都崩): 换不上的那个文件记一行、丢掉 —— 留着的话以后某次启动才换上, 会盖掉这期间新写的.
     */
    fun applyStaged(dataDir: SystemPath) {
        val dir = dataDir.resolve(STAGED_DIR)
        val files = try {
            if (!dir.exists()) return
            dir.list().map { it.inSystem }
        } catch (e: Exception) {
            logger.warn(e) { "Device migration: failed to list the staged data files" }
            return
        }
        var applied = 0
        for (file in files) {
            try {
                val target = dataDir.resolve(file.name)
                if (target.exists()) target.delete()
                file.moveTo(target)
                applied++
            } catch (e: Exception) {
                logger.warn(e) { "Device migration: failed to apply the staged file ${file.name}, dropping it" }
                runCatching { file.delete() }
            }
        }
        runCatching { dir.delete() }.onFailure { logger.warn(it) { "Device migration: failed to remove the staged directory" } }
        logger.info { "Device migration: applied $applied of ${files.size} staged data files" }
    }

    private val logger = logger<DeviceMigrationDataFiles>()
}
