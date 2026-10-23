/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchResult

/**
 * 用户在选源面板里单独放开 (继续查、重试) 的数据源, 按查询会话记: 之后开播、关选源面板、搜索导致卡顿时暂停搜索
 * (见 [PauseMediaFetchWhilePlayingExtension] / [pauseSearchingWhilePlaying]) 都不停它们, 让它们查完 —— 点了就是要这个源的结果.
 * 换了查询会话 (换集、重新搜索) 就不再认之前记的.
 */
class UserResumedSources {
    private val lock = SynchronizedObject()
    private var session: MediaFetchSession? = null
    private val instanceIds = HashSet<String>()

    /** 记下用户放开了 [session] 里的 [instanceId] 这个数据源. */
    fun add(session: MediaFetchSession, instanceId: String): Unit = synchronized(lock) {
        if (this.session !== session) {
            this.session = session
            instanceIds.clear()
        }
        instanceIds += instanceId
    }

    /** [result] 是用户在 [session] 里放开过的数据源. */
    fun contains(session: MediaFetchSession, result: MediaSourceFetchResult): Boolean = synchronized(lock) {
        this.session === session && result.instanceId in instanceIds
    }
}
