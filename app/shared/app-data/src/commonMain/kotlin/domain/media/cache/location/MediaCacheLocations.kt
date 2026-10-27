/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.location

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import me.him188.ani.utils.io.SystemPath

/**
 * 能放缓存的一个位置. 各位置根目录 ([root]) 下的布局一样 (`anitorrent/pieces/<种子>`、`web-m3u/…`),
 * 缓存记录只记它在哪个位置 ([id]) 与相对根目录的路径, 换默认位置不影响已有的缓存.
 */
data class MediaCacheLocation(
    /** `null` = 内部存储, 旧版本的缓存都在这里; 外接盘 = 系统给这个卷的 UUID (挂载路径会变, UUID 不变) */
    val id: String?,
    /** 系统给的名字 */
    val label: String,
    val root: SystemPath,
    val freeBytes: Long? = null,
    val totalBytes: Long? = null,
    /** 不能放新缓存的原因, `null` = 能用. 不能用的盘上已有的缓存照样能播 */
    val unusable: MediaCacheLocationProblem? = null,
)

enum class MediaCacheLocationProblem {
    /** FAT32: 单个文件最大 4 GB, 一集高码率的视频就放不下 */
    FAT32,

    /** 只读 (很多电视上 NTFS 只能读) */
    READ_ONLY,
}

/**
 * 缓存可以放在哪些位置. 外接盘插拔时 [locations] 跟着变: 盘不在时它上面的缓存不能播也不能下, 但记录照留,
 * 插回来接着用 —— 绝不能把「盘不在」当成「文件丢了」去删记录.
 */
interface MediaCacheLocations {
    /** 现在接着的位置, 内部存储总在第一个 */
    val locations: StateFlow<List<MediaCacheLocation>>

    /** 新缓存放在哪个位置: 设置里选的盘接着且能用时是它, 否则是内部存储 */
    suspend fun locationForNewCache(): String?

    /** [id] 位置的缓存根目录; 那个盘没接上时为 `null` (内部存储总有) */
    fun rootOf(id: String?): SystemPath? = locations.value.firstOrNull { it.id == id }?.root

    /** 重新列一遍 (剩余空间这类会变的值), 打开设置页时用 */
    suspend fun refresh() {}

    /**
     * 正在搬到别的位置的缓存 (键见 [MediaCacheMoveUnit.key], 由 [MediaCacheMover] 维护): 搬的期间存储恢复它们时给占位,
     * 不开下载、不读写文件.
     */
    val moving: MutableStateFlow<Set<String>>

    /**
     * 搬一份缓存时改文件的那一段 (复制 → 改名 → 改记录, 见 [MediaCacheMover]) 与启动清扫互斥. 清扫按记录认目录有没有主:
     * 搬到一半的临时目录、改好名还没改记录的新目录都会被它当成没人用删掉 —— 后者删掉之后记录照样改过去、原处照删, 两份都没了.
     * 清扫拿到锁之后再按最新的记录算归属.
     */
    val moveLock: Mutex
}

/** [id] 位置此刻是否接着 */
fun MediaCacheLocations.isConnected(id: String?): Flow<Boolean> =
    locations.map { list -> list.any { it.id == id } }.distinctUntilChanged()

/** 只有内部存储 (没有外接盘的平台与测试) */
class InternalOnlyMediaCacheLocations(root: SystemPath, label: String = "") : MediaCacheLocations {
    override val locations: StateFlow<List<MediaCacheLocation>> =
        MutableStateFlow(listOf(MediaCacheLocation(id = null, label = label, root = root)))

    override suspend fun locationForNewCache(): String? = null

    override val moving: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    override val moveLock: Mutex = Mutex()
}
