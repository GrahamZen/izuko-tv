/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.library

import me.him188.ani.app.domain.media.cache.location.MediaCacheLocations
import me.him188.ani.app.domain.mediasource.clouddrive.LocalFolderAccess
import me.him188.ani.app.domain.mediasource.clouddrive.LocalFolderRoot
import me.him188.ani.app.domain.mediasource.clouddrive.LocalFolderRoots
import me.him188.ani.utils.io.absolutePath

/**
 * 本地文件夹数据源能读的盘 = 缓存位置里此刻接着的各块盘 (内部存储在前; 只读、FAT32 这类放不了缓存的盘也算),
 * 盘的根目录同片库的倒推 ([MediaLibraryAccess.volumeRootOf]); 权限见 [LocalFolderAccess].
 */
class VolumeLocalFolderRoots(
    private val locations: MediaCacheLocations,
    private val library: MediaLibraryAccess,
    private val access: LocalFolderAccess,
) : LocalFolderRoots {
    override fun roots(): List<LocalFolderRoot> =
        locations.locations.value.mapNotNull { location ->
            library.volumeRootOf(location)?.let { LocalFolderRoot(location.label, it.absolutePath.trimEnd('/')) }
        }.distinctBy { it.path }

    override fun isAccessible(): Boolean = access.isGranted()
}
