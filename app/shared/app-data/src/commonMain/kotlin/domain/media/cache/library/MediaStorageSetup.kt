/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.MediaCacheSettings
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.media.cache.location.MediaCacheMoveTarget
import me.him188.ani.app.domain.media.cache.location.MediaCacheMoveUnit
import me.him188.ani.app.domain.media.cache.location.MediaCacheMover
import me.him188.ani.app.domain.media.cache.location.MoveFilter
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 缓存放在哪, 一处说了算: 哪块盘 ([Choice.location], 即 [MediaCacheSettings.defaultLocation]) 与放不放进片库 ([Choice.library]).
 * 片库永远在那块盘上, 只有一个. 换盘、开关片库、把已有的缓存搬过去都由同一份计划 ([plan]) 算出要搬什么, [apply] 一次做完:
 *
 * - 下载的缓存 (BT、在线源) 搬到那块盘的缓存目录 ([MediaCacheMover]). 放进片库时下完的不在这里搬, 由发布 ([MediaLibraryPublisher])
 *   直接复制进那块盘的片库 (只复制一次);
 * - 片库里的视频搬到那块盘的片库 ([LibraryCacheMover]): 放进片库时是公开的片库; 不放时移回那块盘应用目录里的片库 (还没移回的照常列出),
 *   或者留在公开片库的文件夹里 ([apply] 的 `keepLibraryInFolder`: 不再列出, 以后打开片库认回);
 * - 打开片库时一并关掉「标记看过后删除缓存」(开着片库时它删的就是片库里的视频).
 *
 * 搬的时候在播的番跳过, 那部番不再播之后 (或重开应用后) 接着搬 ([start]). 设置引导与控制台都经这里改.
 */
class MediaStorageSetup(
    private val settings: Settings<MediaCacheSettings>,
    private val mover: MediaCacheMover,
    private val publisher: MediaLibraryPublisher,
    private val library: LibraryMediaCacheStorage,
) {
    /** 缓存放在 [location] (`null` = 内部存储), 下完的放不放进片库 */
    data class Choice(val location: String?, val library: Boolean)

    /** 一类要搬的: 几条、多大 */
    data class Group(val units: Int, val bytes: Long) {
        val isEmpty: Boolean get() = units == 0

        companion object {
            val EMPTY = Group(0, 0)
        }
    }

    /**
     * 选 [choice] 之后要搬的: 下载的缓存 ([downloads])、发布进片库的 ([published], 下完的, 只在放进片库时有)、片库里的视频 ([library]);
     * [otherDriveBytes] 是不在那块盘上、要跨盘复制的. [turnsOffDeleteWhenDone]: 会关掉「标记看过后删除缓存」.
     */
    data class Plan(
        val choice: Choice,
        val downloads: Group,
        val published: Group,
        val library: Group,
        val otherDriveBytes: Long,
        val turnsOffDeleteWhenDone: Boolean,
    ) {
        val total: Group get() = Group(downloads.units + published.units + library.units, downloads.bytes + published.bytes + library.bytes)
    }

    suspend fun current(): Choice = settings.flow.first().let { Choice(it.defaultLocation, it.libraryEnabled) }

    /** 正在搬 (这时不能再改, 等它搬完) */
    val isMoving: Boolean get() = mover.isRunning

    /** 各位置上现在有的缓存 (下载的与片库里的; 留在公开片库文件夹里的不列出, 不算) */
    suspend fun usage(): Map<String?, Group> =
        mover.units()
            .groupBy { it.location }
            .mapValues { (_, units) -> units.group() }

    /**
     * 选 [choice] 并把已有的都搬过去要搬哪些. [keepLibraryInFolder]: 不放进片库时公开片库里的留在文件夹里 (不搬).
     * 已经在 [choice] 上的不算.
     */
    suspend fun plan(choice: Choice, keepLibraryInFolder: Boolean = false): Plan {
        val units = mover.pending(choice.target, include(choice, keepLibraryInFolder))
        val (library, downloads) = units.partition { it.libraryPublic != null }
        val published = if (choice.library) publisher.pending(choice.location) else MediaLibraryPublisher.Pending(0, 0)
        return Plan(
            choice = choice,
            downloads = downloads.group(),
            published = Group(published.units, published.bytes),
            library = library.group(),
            otherDriveBytes = units.filter { it.location != choice.location }.sumOf { it.bytes } + published.otherDriveBytes,
            turnsOffDeleteWhenDone = choice.library && settings.flow.first().deleteWhenMarkedDone,
        )
    }

    /**
     * 按现在的设置还在别的地方的 (选了只放新的、搬的时候那块盘没接上、关掉片库时没移回的): 留在公开片库文件夹里的不列出, 不算;
     * 发布自己会做的不算
     */
    suspend fun leftovers(): Plan = plan(current()).let { it.copy(published = Group.EMPTY) }

    /**
     * 改成 [choice]: 写设置 (打开片库时一并关掉「标记看过后删除缓存」); [moveExisting] 时在后台把已有的搬过去 (见 [plan]),
     * 进度与结果同搬缓存 ([MediaCacheMover]). 正在搬时不再开一批, 返回 `false` (设置照样写了).
     * 不放进片库又 [keepLibraryInFolder] 时, 公开片库里的不再列出 ([LibraryMediaCacheStorage.forgetPublic]).
     */
    suspend fun apply(choice: Choice, moveExisting: Boolean, keepLibraryInFolder: Boolean = false): Boolean {
        settings.update {
            copy(
                defaultLocation = choice.location,
                libraryEnabled = choice.library,
                deleteWhenMarkedDone = deleteWhenMarkedDone && !choice.library,
            )
        }
        logger.info { "Storage set to $choice, moving existing: $moveExisting, keeping library in folder: $keepLibraryInFolder" }
        if (!choice.library && keepLibraryInFolder) library.forgetPublic()
        if (!moveExisting) {
            mover.forgetSkipped()
            return true
        }
        return mover.start(choice.target, include(choice, keepLibraryInFolder))
    }

    /** 把 [leftovers] 搬过去 */
    suspend fun moveLeftovers(): Boolean {
        val choice = current()
        return mover.start(choice.target, include(choice, keepLibraryInFolder = false))
    }

    /**
     * 在 [scope] 里一直跑: 在播的番 ([playing], 条目 id) 每变一次 (含启动时的第一个值: 上次没等到停播的这时接着搬),
     * 搬的时候因为在播跳过的接着搬 ([MediaCacheMover.resumeSkipped]); 设置在这期间改了就不搬了.
     */
    fun start(scope: CoroutineScope, playing: Flow<*>): Job = scope.launch {
        playing.collect {
            try {
                mover.resumeSkipped(current().target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to resume caches skipped while playing" }
            }
        }
    }

    private val Choice.target: MediaCacheMoveTarget get() = MediaCacheMoveTarget(location, libraryPublic = library)

    /**
     * 搬哪些: 不放进片库又要留在文件夹里时, 公开片库里的不搬; 放进片库时, 全部下完、能放进片库的下载 (BT 种子目录里各集都下完了)
     * 不在这里搬, 交给发布直接复制进片库.
     */
    private fun include(choice: Choice, keepLibraryInFolder: Boolean): MoveFilter = { unit: MediaCacheMoveUnit, caches ->
        when {
            unit.libraryPublic == true && !choice.library -> !keepLibraryInFolder
            unit.libraryPublic == null && choice.library -> caches.isEmpty() || !caches.all { publisher.isPublishable(it) }
            else -> true
        }
    }

    private fun List<MediaCacheMoveUnit>.group(): Group = Group(size, sumOf { it.bytes })

    private companion object {
        val logger = logger<MediaStorageSetup>()
    }
}
