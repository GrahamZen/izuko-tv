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
import me.him188.ani.app.domain.media.fetch.MediaFetchSession

/**
 * 切集复用条目级查询 (见 [me.him188.ani.app.domain.episode.SubjectMediaFetchSessions]) 时要重查的网盘数据源.
 *
 * 短番打开时网盘源只查一次, 各集的候选都在这一次里给出, 切集不再查. 给出候选时还没读完文件头的视频 (见 [DriveVideoInfoProbe])
 * 只能按没有信息标 (文件名里没写分辨率就是 1080P); 后来读到了就记下这个源, 切集时让它重查一次:
 * 只列一次文件夹, 各集用记下的信息, 候选才标得对分辨率与码率.
 */
internal object DriveVideoInfoRefresh {
    private val lock = SynchronizedObject()
    private val stale = HashSet<String>()

    /** 数据源 [mediaSourceId] 给出的候选里有视频现在才读到信息. */
    fun markStale(mediaSourceId: String) {
        synchronized(lock) { stale.add(mediaSourceId) }
    }

    /** 数据源 [mediaSourceId] 正在重新给出候选 (用的是这时记下的信息). */
    fun clear(mediaSourceId: String) {
        synchronized(lock) { stale.remove(mediaSourceId) }
    }

    /** 数据源 [mediaSourceId] 要不要重查; 问过就清掉. */
    fun takeStale(mediaSourceId: String): Boolean = synchronized(lock) { stale.remove(mediaSourceId) }
}

/** 切集复用会话时: 候选里有视频后来才读到信息的网盘源重查一次 (见 [DriveVideoInfoRefresh]). */
internal fun MediaFetchSession.restartSourcesWithNewVideoInfo() {
    for (result in mediaSourceResults) {
        if (DriveVideoInfoRefresh.takeStale(result.mediaSourceId)) result.restart()
    }
}
