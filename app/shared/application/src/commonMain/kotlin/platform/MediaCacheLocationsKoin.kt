/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path
import me.him188.ani.app.domain.media.cache.engine.HttpMediaCacheEngine
import me.him188.ani.app.domain.media.cache.location.InternalOnlyMediaCacheLocations
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocationRefresher
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocations
import me.him188.ani.app.domain.media.cache.location.MediaCacheMover
import me.him188.ani.app.domain.media.cache.storage.MediaSaveDirProvider
import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.app.domain.media.player.PlaybackActivity
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.resolve
import org.koin.core.module.Module
import org.koin.core.scope.Scope

/** 缓存可以放的位置: 平台有实现 (安卓: 外接盘) 时用它, 否则只有内部存储 */
internal fun Scope.mediaCacheLocations(): MediaCacheLocations =
    getOrNull<MediaCacheLocations>()
        ?: InternalOnlyMediaCacheLocations(Path(get<MediaSaveDirProvider>().saveDir).inSystem)

/** 网页缓存在 [location] 位置上的目录; 那个盘没接上时为 `null` */
internal fun MediaCacheLocations.httpCacheDirOf(location: String): Path? =
    rootOf(location)?.let { Path(it.absolutePath).resolve(HttpMediaCacheEngine.MEDIA_CACHE_DIR) }

/** 盘插拔时让各存储重新恢复缓存 (见 [MediaCacheLocationRefresher]); 把已有缓存搬到别的位置 (见 [MediaCacheMover]) */
internal fun Module.mediaCacheLocationRefresher(coroutineScope: CoroutineScope) {
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
        )
    }
}
