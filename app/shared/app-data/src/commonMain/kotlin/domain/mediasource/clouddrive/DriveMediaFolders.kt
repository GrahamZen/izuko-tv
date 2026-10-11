/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * 网盘资源所在的文件夹.
 *
 * 网盘资源的条目名直接标成当前条目、线路是数据源名, 同一个源里各个分享、各个文件夹在选择器眼里是同一「页」.
 * 拆分季要按一个文件夹里的集号判断它装的是本篇还是整季 (见 `SplitSeasonDrivePages`), 所以另外记下每条资源的文件夹.
 */
object DriveMediaFolders {
    /**
     * @param key 文件夹的标识 (分享 id 加文件夹 id, 或自己网盘的文件夹 id)
     * @param name 文件夹名; 文件直接在分享根上、或搜索直接命中文件时为 `null`
     */
    class Folder(val key: String, val name: String?)

    private val lock = SynchronizedObject()
    private val folders = LinkedHashMap<String, Folder>()

    fun record(mediaId: String, folder: Folder) {
        synchronized(lock) {
            folders.remove(mediaId)
            folders[mediaId] = folder
            while (folders.size > MAX_ENTRIES) folders.remove(folders.keys.first())
        }
    }

    /** 资源 [mediaId] 所在的文件夹, 不是网盘资源或没登记时为 null. */
    fun of(mediaId: String): Folder? = synchronized(lock) { folders[mediaId] }

    /** 最多记多少条, 旧的先丢: 一次搜索几十到几百条, 留够几部番的量 (同 [DriveVideoBitrates]). */
    private const val MAX_ENTRIES = 5000
}
