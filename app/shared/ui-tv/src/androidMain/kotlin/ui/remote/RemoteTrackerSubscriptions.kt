/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.repository.torrent.TrackerSubscriptionEntry
import me.him188.ani.app.data.repository.torrent.TrackerSubscriptionRepository
import org.koin.mp.KoinPlatform
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 控制台「Tracker 订阅」卡片 (登记在 [RemoteSettingsCatalog]) 的状态与「立即更新」: 订阅里一共多少个, 各个地址拉到多少、什么时候、没拉成的原因.
 */
internal object RemoteTrackerSubscriptions {
    private val repository: TrackerSubscriptionRepository get() = KoinPlatform.getKoin().get()

    /** 没填地址时不显示. */
    suspend fun status(): String? = statusText(repository.entries.first())

    /** 网页弹一句总数, 各地址的情况刷新后在卡片上看. */
    suspend fun refresh(): String =
        statusText(repository.refresh(reuseRecent = false))?.lineSequence()?.first() ?: tr("还没有填订阅地址")

    fun statusText(entries: List<TrackerSubscriptionEntry>, time: (Long) -> String = ::timeText): String? {
        if (entries.isEmpty()) return null
        val total = entries.flatMap { it.trackers }.distinct().size
        val lines = entries.map { e ->
            val reason = e.error?.let { if (it == TrackerSubscriptionEntry.EMPTY_LIST) tr("里面没有 tracker") else it }
            when {
                reason == null && e.fetchedAtMillis > 0 -> tr("{0}：{1} 个，{2} 拉取", e.url, e.trackers.size, time(e.fetchedAtMillis))
                reason == null -> tr("{0}：还没拉过", e.url)
                e.trackers.isNotEmpty() ->
                    tr("{0}：这次没拉到（{1}），继续用 {2} 拉到的 {3} 个", e.url, reason, time(e.fetchedAtMillis), e.trackers.size)

                else -> tr("{0}：没拉到（{1}）", e.url, reason)
            }
        }
        return (listOf(tr("订阅里共有 {0} 个 tracker，与内置、额外的一起用", total)) + lines).joinToString("\n")
    }

    private fun timeText(millis: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date(millis))
}
