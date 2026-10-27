/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.library

import androidx.datastore.core.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.media.cache.LocalFileMediaCache
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.cache.engine.MediaStats
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocations
import me.him188.ani.app.domain.media.cache.location.UnavailableMediaCache
import me.him188.ani.app.domain.media.cache.location.isConnected
import me.him188.ani.app.domain.media.cache.storage.AbstractDataStoreMediaCacheStorage
import me.him188.ani.app.domain.media.cache.storage.MediaCacheSave
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.MediaExtraFiles
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.logging.info
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

/**
 * 建片库存储.
 *
 * @param indexFile 本机索引存在哪 (应用私有目录, 见 [MediaLibraryIndex]); `null` = 只在内存里
 * @param access 平台给的公开片库位置与权限; `null` = 平台不支持公开片库
 * @param enabled 用户打开了片库 (设置「存储 → 缓存放在哪」)
 */
fun createLibraryMediaCacheStorage(
    mediaSourceId: String,
    datastore: DataStore<List<MediaCacheSave>>,
    indexFile: SystemPath?,
    locations: MediaCacheLocations,
    access: MediaLibraryAccess?,
    enabled: suspend () -> Boolean,
    parentCoroutineContext: CoroutineContext,
): LibraryMediaCacheStorage = LibraryMediaCacheStorage(
    mediaSourceId, datastore, MediaLibraryIndex(indexFile), locations, access, enabled, parentCoroutineContext,
)

/**
 * 片库: 文件夹里按约定命名 (见 [MediaLibraryLayout]) 的视频, 每个当成一条下完的缓存 ([LocalFileMediaCache]).
 *
 * 每个位置 (盘) 上可以有两个片库 ([LibraryPlace]):
 * - 公开的: [access] 给的位置 (安卓: 盘根目录下 `Izuko/`, 要「所有文件访问」), 卸载、清除数据都保留, 电脑上能直接看. 只在打开片库 ([enabled]) 时扫描;
 *   关掉片库后索引里有的 (还没移回应用目录的) 照常列出, 选了留在文件夹里的从索引里去掉 ([forgetPublic]), 不再列出.
 *   权限被关掉时给「不可用」的占位, 不删记录.
 * - 应用目录里的: 那个位置的缓存目录下 [PRIVATE_DIR_NAME], 不要权限, 卸载时一起删. 关掉片库时公开片库里的移回这里 (见 [MediaStorageSetup]), 总是列出.
 *
 * 片库文件夹才是准的: 每次恢复 ([refreshCache], 启动、插拔盘、开关或权限变了) 先扫一遍能用的片库, 按扫到的更新本机索引 ([MediaLibraryIndex])
 * 与缓存元数据, 再照常恢复. 所以在电脑上删掉、拷进来的视频, 下次扫描就跟着变; 卸载重装或插到别的电视上, 扫一遍就认回来.
 * 元数据里有记录, 开播的快速通道 (`EpisodeCacheRecords`) 照常认得这一集. 盘没接上时那些记录给占位, 同盘没接上的缓存.
 *
 * 片库不下载: 不支持任何资源 ([MediaCacheEngine.supports] 为 `false`), 内容只由发布 ([MediaLibraryPublisher]: 下完的缓存挪进来)、
 * 搬家 ([LibraryCacheMover]) 与用户放进来. 删除就是删文件 (连说明文件与字幕, 条目文件夹空了也删). 启动清扫不碰片库.
 */
class LibraryMediaCacheStorage internal constructor(
    mediaSourceId: String,
    private val datastore: DataStore<List<MediaCacheSave>>,
    private val index: MediaLibraryIndex,
    private val locations: MediaCacheLocations,
    private val access: MediaLibraryAccess?,
    private val enabled: suspend () -> Boolean,
    parentCoroutineContext: CoroutineContext,
    private val libraryEngine: LibraryMediaCacheEngine = LibraryMediaCacheEngine(index, locations),
) : AbstractDataStoreMediaCacheStorage(mediaSourceId, datastore, libraryEngine, "Library", parentCoroutineContext) {
    private val refreshLock = Mutex()

    init {
        libraryEngine.mover = LibraryCacheMover(this)
    }

    override suspend fun restorePersistedCaches() {
        refreshCache()
    }

    override suspend fun refreshCache(): List<MediaCache> = refreshLock.withLock {
        rescan()
        // 片库的缓存都是本地文件, 每次都按刚扫的结果重新恢复 (平时恢复过的本地文件不再管, 这里要跟着文件夹变)
        forgetRestoredLocalFiles { true }
        super.refreshCache()
    }

    /** 此刻扫描的片库: 各接着的位置上应用目录里的片库, 打开了片库且有权限时加上公开的 */
    internal suspend fun availableRoots(): Map<LibraryPlace, SystemPath> =
        if (enabled()) privateRoots() + publicRoots() else privateRoots()

    /** 公开片库的根目录 (有权限时, 不管片库开没开: 算打开后会放进去多少、关掉时移回来); 没权限或平台不支持时为空 */
    internal fun publicRoots(): Map<LibraryPlace, SystemPath> {
        val access = access ?: return emptyMap()
        if (!access.isGranted()) return emptyMap()
        return locations.locations.value.mapNotNull { location ->
            access.libraryRootOf(location)?.let { LibraryPlace(location.id, public = true) to it }
        }.toMap()
    }

    /** 各接着的位置上应用目录里的片库 */
    internal fun privateRoots(): Map<LibraryPlace, SystemPath> =
        locations.locations.value.associate { LibraryPlace(it.id, public = false) to it.root.resolve(PRIVATE_DIR_NAME) }

    /** [place] 的根目录, 不管片库开没开; 那块盘没接上、公开片库没权限时为 `null` */
    internal fun rootOf(place: LibraryPlace): SystemPath? = if (place.public) publicRoots()[place] else privateRoots()[place]

    /** 本机索引里的全部视频 (给搬家列出可以搬的) */
    internal val entries: List<MediaLibraryIndexEntry> get() = index.all

    /** 这一集这个资源在片库里的那份 (发布时片库认下了、原缓存还没删, 两边都有; 见 [MediaLibraryPublisher]) */
    internal fun publishedEntry(mediaId: String, metadata: MediaCacheMetadata): MediaLibraryIndexEntry? = index.find(mediaId, metadata)

    /** 收下发布进来的视频: [relativePath] 在 [place] 的片库里, 说明文件已经写好. 记进索引与缓存元数据, 恢复出来 */
    internal suspend fun adopt(place: LibraryPlace, relativePath: String, save: MediaCacheSave) {
        refreshLock.withLock {
            updateEngine()
            val metadata = save.metadata
            index.add(MediaLibraryIndexEntry(save.origin.mediaId, metadata.subjectId, metadata.episodeId, place.location, relativePath, place.public))
            withContext(Dispatchers.IO_) {
                datastore.updateData { list ->
                    list.filterNot {
                        it.engine == LibraryEngineKey && it.origin.mediaId == save.origin.mediaId &&
                                it.metadata.subjectId == metadata.subjectId && it.metadata.episodeId == metadata.episodeId
                    } + save
                }
            }
            super.refreshCache()
        }
    }

    /** 搬家把 [entry] 搬到了 [place] 的 [relativePath] (见 [LibraryCacheMover]); 缓存元数据不变 */
    internal fun moved(entry: MediaLibraryIndexEntry, place: LibraryPlace, relativePath: String) {
        index.add(entry.copy(location = place.location, public = place.public, relativePath = relativePath))
    }

    /** 不再列出公开片库里的视频 (关掉片库、留在文件夹里): 文件不动, 从索引里去掉; 片库关着时不扫公开片库, 以后打开片库扫描时认回 */
    internal suspend fun forgetPublic() {
        refreshLock.withLock { index.removeAll { it.public } }
        refreshCache()
    }

    /** 更新引擎能读的片库 (扫描的加上有权限时的公开片库: 片库关掉后还没移回的照常能用), 返回要扫描的 */
    private suspend fun updateEngine(): Map<LibraryPlace, SystemPath> {
        val roots = availableRoots()
        libraryEngine.roots = privateRoots() + publicRoots()
        return roots
    }

    private suspend fun rescan() {
        val roots = updateEngine()
        val scanned = withContext(Dispatchers.IO_) {
            // 删掉、在电脑上挪走之后空了的条目文件夹 (片库关着时公开片库也清)
            (roots.values + publicRoots().values).toSet().forEach { removeEmptySubjectDirs(it) }
            roots.flatMap { (place, root) -> scanMediaLibrary(root, place) }
        }
        index.replacePlaces(roots.keys, scanned.map { it.entry })
        val scannedSaves = scanned.map { it.save }
        withContext(Dispatchers.IO_) {
            datastore.updateData { list ->
                val (library, others) = list.partition { it.engine == LibraryEngineKey }
                // 没扫的片库 (盘没接上、片库关着、权限关了) 上的记录照留; 扫过的换成扫到的
                val kept = library.filter { save ->
                    val entry = index.find(save.origin.mediaId, save.metadata)
                    entry != null && entry.place !in roots.keys
                }
                others + kept + scannedSaves
            }
        }
        if (scanned.isNotEmpty()) logger.info { "Scanned media library at ${roots.values.joinToString()}: ${scanned.size} videos" }
    }

    internal companion object {
        /** 应用目录里的片库在缓存目录下的名字 (启动清扫只管 BT 与在线源自己的目录, 不碰它) */
        const val PRIVATE_DIR_NAME = "izuko-library"
    }
}

/**
 * 片库这个「引擎」: 只恢复, 不下载. 恢复按本机索引找到视频: 那个片库能读就是下完的本地文件缓存; 读不了 (盘没接上、权限关了) 是占位;
 * 正在搬 ([MediaCacheLocations.moving]) 时是「正在移动」的占位. 列不列只看索引里有没有 (片库关着时公开片库不扫描, 见 [LibraryMediaCacheStorage]).
 */
class LibraryMediaCacheEngine internal constructor(
    private val index: MediaLibraryIndex,
    private val locations: MediaCacheLocations,
) : MediaCacheEngine {
    /** 此刻能读的片库的根目录, 由存储每次扫描时更新 */
    @Volatile
    internal var roots: Map<LibraryPlace, SystemPath> = emptyMap()

    /** 片库里的视频的搬法 (见 MediaCacheMover) */
    lateinit var mover: LibraryCacheMover
        internal set

    override val engineKey: MediaCacheEngineKey get() = LibraryEngineKey
    override val stats: Flow<MediaStats> = flowOf(MediaStats.Zero)

    override fun supports(media: Media): Boolean = false

    override suspend fun restore(origin: Media, metadata: MediaCacheMetadata, parentContext: CoroutineContext): MediaCache? {
        val entry = index.find(origin.mediaId, metadata) ?: return null
        if (libraryMoveKey(entry) in locations.moving.value) {
            return UnavailableMediaCache(origin, metadata, entry.location, moving = true) { index.remove(entry) }
        }
        val root = roots[entry.place]
        if (root == null) {
            // 盘没接上, 或者「所有文件访问」被关掉了 (这时盘接着也读不了, 不报「盘已接上」)
            val accessLost = entry.public && roots.keys.none { it.public }
            val reconnected = if (accessLost) flowOf(false) else locations.isConnected(entry.location)
            return UnavailableMediaCache(origin, metadata, entry.location, reconnected, accessLost = accessLost) { index.remove(entry) }
        }
        val file = root.resolve(entry.relativePath)
        if (!file.exists()) return null
        val subtitles = withContext(Dispatchers.IO_) { librarySubtitlesOf(file) }
        val withSubtitles = if (subtitles.isEmpty() || origin !is DefaultMedia) origin else {
            origin.copy(extraFiles = MediaExtraFiles(subtitles + origin.extraFiles.subtitles))
        }
        return LocalFileMediaCache(
            withSubtitles, metadata, file,
            locationId = entry.location,
            onDeletePersistedRows = { index.remove(entry) },
        ) { video -> withContext(Dispatchers.IO_) { deleteLibraryFile(video) } }
    }

    override suspend fun createCache(
        origin: Media,
        metadata: MediaCacheMetadata,
        episodeMetadata: EpisodeMetadata,
        parentContext: CoroutineContext,
    ): MediaCache = throw UnsupportedOperationException("The media library does not download, it only takes published caches")

    /** 片库里的文件不按记录清扫: 用户放进来的也在里面 */
    override suspend fun deleteUnusedCaches(all: List<MediaCache>) {}
}
