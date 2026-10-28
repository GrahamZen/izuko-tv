/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.io.files.Path
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.media.cache.engine.HttpMediaCacheEngine
import me.him188.ani.app.domain.media.cache.library.LibraryMediaCacheStorage
import me.him188.ani.app.domain.media.cache.library.MediaLibraryAccess
import me.him188.ani.app.domain.media.cache.library.MediaLibraryPublisher
import me.him188.ani.app.domain.media.cache.library.MediaStorageSetup
import me.him188.ani.app.domain.media.cache.library.VolumeLocalFolderRoots
import me.him188.ani.app.domain.media.cache.library.createLibraryMediaCacheStorage
import me.him188.ani.app.domain.media.cache.location.InternalOnlyMediaCacheLocations
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocationRefresher
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocations
import me.him188.ani.app.domain.media.cache.location.MediaCacheMover
import me.him188.ani.app.domain.media.cache.storage.MediaCacheSave
import me.him188.ani.app.domain.media.cache.storage.MediaSaveDirProvider
import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.app.domain.media.player.PlaybackActivity
import me.him188.ani.app.domain.mediasource.clouddrive.LocalFolderAccess
import me.him188.ani.app.domain.mediasource.clouddrive.LocalFolderRoots
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.resolve
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

/** 缓存可以放的位置: 平台有实现 (安卓: 外接盘) 时用它, 否则只有内部存储 */
internal fun Scope.mediaCacheLocations(): MediaCacheLocations =
    getOrNull<MediaCacheLocations>()
        ?: InternalOnlyMediaCacheLocations(Path(get<MediaSaveDirProvider>().saveDir).inSystem)

/** 本地文件夹数据源能读的盘: 同缓存位置的各块盘; 平台没有本地文件夹的权限实现 ([LocalFolderAccess]) 时一块都没有 */
internal fun Scope.localFolderRoots(): LocalFolderRoots {
    val access = getOrNull<LocalFolderAccess>() ?: return LocalFolderRoots.None
    val library = getOrNull<MediaLibraryAccess>() ?: return LocalFolderRoots.None
    return VolumeLocalFolderRoots(mediaCacheLocations(), library, access)
}

/** 网页缓存在 [location] 位置上的目录; 那个盘没接上时为 `null` */
internal fun MediaCacheLocations.httpCacheDirOf(location: String): Path? =
    rootOf(location)?.let { Path(it.absolutePath).resolve(HttpMediaCacheEngine.MEDIA_CACHE_DIR) }

/**
 * 片库存储 (见 [LibraryMediaCacheStorage]). 放在存储列表最后: 下完的缓存发布进片库时, 同一条短暂两边都有, 下载那边的先列.
 * 平台没有片库实现 ([MediaLibraryAccess]) 时它什么都不列.
 *
 * @param indexFile 片库的本机索引 (应用私有目录)
 */
internal fun Scope.mediaLibraryStorage(
    mediaSourceId: String,
    metadataStore: DataStore<List<MediaCacheSave>>,
    indexFile: SystemPath,
    parentCoroutineContext: CoroutineContext,
): LibraryMediaCacheStorage = createLibraryMediaCacheStorage(
    mediaSourceId = mediaSourceId,
    datastore = metadataStore,
    indexFile = indexFile,
    locations = mediaCacheLocations(),
    access = getOrNull<MediaLibraryAccess>(),
    enabled = { get<SettingsRepository>().mediaCacheSettings.flow.first().libraryEnabled },
    parentCoroutineContext = parentCoroutineContext,
)

/**
 * 盘插拔时让各存储重新恢复缓存 (见 [MediaCacheLocationRefresher]); 把已有缓存搬到别的位置 (见 [MediaCacheMover]); 下完的缓存发布进片库; 缓存放在哪.
 *
 * @param moveWaitingFile 搬家时因为在播先不搬、等停播的记在哪 (应用私有目录, 见 [MediaCacheMover.waiting])
 */
internal fun Module.mediaCacheLocationRefresher(coroutineScope: CoroutineScope, moveWaitingFile: () -> SystemPath) {
    single(createdAtStart = true) {
        MediaCacheLocationRefresher(
            locations = mediaCacheLocations(),
            storages = { get<MediaDownloadManager>().storages },
            scope = coroutineScope,
        ).also { it.start() }
    }
    single {
        MediaCacheMover(
            locations = mediaCacheLocations(),
            storages = { get<MediaDownloadManager>().storages },
            isPlayingSubject = { subjectId -> get<PlaybackActivity>().currentValue?.subjectId?.toString() == subjectId },
            scope = coroutineScope,
            waitingFile = moveWaitingFile(),
        )
    }
    mediaLibraryPublisher(coroutineScope)
    // 缓存放在哪 (设置引导与控制台都经它改, 见 MediaStorageSetup); 搬的时候在播跳过的, 停播后 (或重开应用后) 接着搬
    single(createdAtStart = true) {
        MediaStorageSetup(
            settings = get<SettingsRepository>().mediaCacheSettings,
            mover = get<MediaCacheMover>(),
            publisher = get<MediaLibraryPublisher>(),
            library = get<MediaDownloadManager>().storages.filterIsInstance<LibraryMediaCacheStorage>().single(),
        ).also { setup ->
            setup.start(coroutineScope, get<PlaybackActivity>().current.map { it?.subjectId }.distinctUntilChanged())
        }
    }
}

/** 下完的缓存发布进片库 (见 [MediaLibraryPublisher]); 平台不支持片库时不跑 */
private fun Module.mediaLibraryPublisher(coroutineScope: CoroutineScope) {
    single(createdAtStart = true) {
        val downloadManager = get<MediaDownloadManager>()
        val mover = get<MediaCacheMover>()
        val playback = get<PlaybackActivity>()
        val locations = mediaCacheLocations()
        MediaLibraryPublisher(
            library = downloadManager.storages.filterIsInstance<LibraryMediaCacheStorage>().single(),
            storages = { downloadManager.storages },
            locations = locations,
            isPlayingSubject = { subjectId -> playback.currentValue?.subjectId?.toString() == subjectId },
            isMoving = { mover.isRunning },
        ).also { publisher ->
            val access = getOrNull<MediaLibraryAccess>()?.takeIf { it.isSupported } ?: return@also
            // 权限在系统设置里改, 没有回调: 隔一阵看一次 (在系统设置里给完权限回来, 片库跟着能用)
            val granted = flow {
                while (true) {
                    emit(access.isGranted())
                    delay(10.seconds)
                }
            }.distinctUntilChanged()
            val changes = combine(
                MediaLibraryPublisher.completedCacheIds(downloadManager.storages),
                playback.current.map { it?.subjectId }.distinctUntilChanged(),
                mover.progress.map { it != null }.distinctUntilChanged(),
                get<SettingsRepository>().mediaCacheSettings.flow.map { it.libraryEnabled }.distinctUntilChanged(),
                combine(locations.locations, granted, ::Pair),
            ) { completed, playing, moving, enabled, mounted -> listOf(completed, playing, moving, enabled, mounted) }
            publisher.start(coroutineScope, changes)
        }
    }
}
