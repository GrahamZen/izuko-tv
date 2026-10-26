/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.location

import kotlinx.coroutines.flow.MutableStateFlow
import me.him188.ani.utils.io.SystemPath

/** 测试用的缓存位置: 内部存储在 [internalRoot], 外接盘用 [connect] / [disconnect] 插拔. */
internal class TestMediaCacheLocations(internalRoot: SystemPath) : MediaCacheLocations {
    override val locations = MutableStateFlow(listOf(MediaCacheLocation(id = null, label = "", root = internalRoot)))

    /** [locationForNewCache] 给的值 */
    var forNewCache: String? = null

    override suspend fun locationForNewCache(): String? = forNewCache

    fun connect(id: String, root: SystemPath, unusable: MediaCacheLocationProblem? = null) {
        locations.value = locations.value.filter { it.id != id } + MediaCacheLocation(id = id, label = id, root = root, unusable = unusable)
    }

    fun disconnect(id: String) {
        locations.value = locations.value.filter { it.id != id }
    }
}
