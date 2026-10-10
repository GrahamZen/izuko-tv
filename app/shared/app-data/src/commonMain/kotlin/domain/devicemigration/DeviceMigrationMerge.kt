/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.devicemigration

import me.him188.ani.app.data.models.danmaku.DanmakuRegexFilter
import me.him188.ani.app.data.repository.media.ManualBrowseMemories
import me.him188.ani.app.data.repository.media.MediaSourceSaves
import me.him188.ani.app.domain.mediasource.instance.MediaSourceSave
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscription
import me.him188.ani.app.domain.torrent.peer.PeerFilterSubscription

/**
 * 整机共用的数据怎么并进新电视: 旧电视的全要, 新电视自己的只留旧电视上没有的.
 *
 * 认「同一个」不能靠编号: 订阅的 subscriptionId 与数据源的 instanceId 是各台电视自己随机生成的, 同一个订阅地址、
 * 同一个内置数据源在两台电视上编号不同. 所以订阅按地址认, 本地加的数据源按种类与配置认, 弹幕屏蔽词按正则认.
 */
internal object DeviceMigrationMerge {
    /**
     * 跟着设备走的设置, 留新电视自己的: 缓存目录 (`MediaCacheSettings`, 路径是这台设备上的).
     */
    val DEVICE_PREFERENCE_KEYS = setOf("cachePreferences")

    /** 设置: 旧电视的整份, 只有 [keep] 里的键留新电视自己的. */
    fun preferences(
        incoming: List<PreferenceEntry>,
        existing: List<PreferenceEntry>,
        keep: Set<String> = DEVICE_PREFERENCE_KEYS,
    ): List<PreferenceEntry> = incoming.filter { it.key !in keep } + existing.filter { it.key in keep }

    /** 按键合并: 两边都有的取旧电视的. */
    fun keyed(incoming: List<PreferenceEntry>, existing: List<PreferenceEntry>): List<PreferenceEntry> {
        val incomingKeys = incoming.mapTo(HashSet()) { it.key }
        return existing.filter { it.key !in incomingKeys } + incoming
    }

    /**
     * @property kept 合并后的订阅
     * @property replacedIds 新电视上被旧电视同一地址的订阅换掉的那些 (它们带来的数据源随之去掉, 换成旧电视那份)
     */
    class Subscriptions(val kept: List<MediaSourceSubscription>, val replacedIds: Set<String>)

    /** 数据源订阅按地址认: 旧电视的全要, 新电视上地址不同的留下. */
    fun subscriptions(incoming: List<MediaSourceSubscription>, existing: List<MediaSourceSubscription>): Subscriptions {
        val urls = incoming.mapTo(HashSet()) { it.url }
        val (replaced, own) = existing.partition { it.url in urls }
        return Subscriptions(incoming + own, replaced.mapTo(HashSet()) { it.subscriptionId })
    }

    /**
     * 数据源: 旧电视的全要 (保留 instanceId —— 搬过来的用户库里「记住的源」按它认). 新电视的:
     * - 订阅带来的: 订阅被换掉 ([replacedSubscriptionIds]) 就去掉, 否则留下;
     * - 本地加的: 旧电视上有同种类、同配置的 (比如两边都有的内置数据源) 就去掉, 否则留下.
     */
    fun mediaSources(incoming: MediaSourceSaves, existing: MediaSourceSaves, replacedSubscriptionIds: Set<String>): MediaSourceSaves {
        val ids = incoming.instances.mapTo(HashSet()) { it.instanceId }
        val localKeys = incoming.instances.filter { it.config.subscriptionId == null }.mapTo(HashSet()) { it.localKey() }
        val own = existing.instances.filter { save ->
            if (save.instanceId in ids) return@filter false
            val subscriptionId = save.config.subscriptionId
            if (subscriptionId != null) subscriptionId !in replacedSubscriptionIds else save.localKey() !in localKeys
        }
        return MediaSourceSaves(incoming.instances + own)
    }

    private fun MediaSourceSave.localKey() = Triple(factoryId, mediaSourceId, config)

    /** Peer 规则订阅按地址认. */
    fun peerFilterSubscriptions(
        incoming: List<PeerFilterSubscription>,
        existing: List<PeerFilterSubscription>,
    ): List<PeerFilterSubscription> {
        val urls = incoming.mapTo(HashSet()) { it.url }
        return incoming + existing.filter { it.url !in urls }
    }

    /** 弹幕屏蔽词按正则认. */
    fun danmakuFilters(incoming: List<DanmakuRegexFilter>, existing: List<DanmakuRegexFilter>): List<DanmakuRegexFilter> {
        val regexes = incoming.mapTo(HashSet()) { it.regex }
        val ids = incoming.mapTo(HashSet()) { it.id }
        return incoming + existing.filter { it.regex !in regexes && it.id !in ids }
    }

    /** 每部番一条, 两边都有的取旧电视的. */
    fun manualBrowseMemories(incoming: ManualBrowseMemories, existing: ManualBrowseMemories): ManualBrowseMemories =
        ManualBrowseMemories(existing.bySubjectId + incoming.bySubjectId)

    fun nsfwSubjects(incoming: List<Int>, existing: List<Int>): List<Int> = (existing + incoming).distinct()
}
