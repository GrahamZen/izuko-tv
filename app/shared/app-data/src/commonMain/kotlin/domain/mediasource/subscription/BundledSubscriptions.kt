/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.subscription

import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.repository.media.MediaSourceSubscriptionRepository
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.Uuid

/**
 * 应用自带的订阅: 网盘这类数据源 (见 `CloudDriveMediaSource`) 和挑过的几个在线源由它下发, 代码里不写具体是哪个网盘.
 *
 * 默认订阅 = 上游的默认订阅去掉 [retiredUrls], 再加上自带的 ([withDefaults]); 已经装过的在启动时对齐一次 ([reconcile]):
 * 补上自带的, 删掉 [retiredUrls] 连同它带来的数据源. 两件事每个地址都只做一次, 之后用户自己加回或删掉的都不再动.
 * 地址在 GitHub 上, 大陆直连不通时订阅更新会换加速镜像 (见 `MediaSourceSubscriptionRequesterImpl`).
 */
object BundledSubscriptions {
    private const val SOURCES_URL = "https://raw.githubusercontent.com/weixianweide/sources/main/subscription.json"

    val urls: List<String> = listOf(SOURCES_URL)

    /** 上游默认带、这里不再默认给的订阅: Animeko 的在线源订阅 (要的话用户自己加). 挑过的几个已放进 [SOURCES_URL]. */
    val retiredUrls: List<String> = listOf("https://sub.creamycake.org/v1/css1.json")

    private val logger = logger<BundledSubscriptions>()

    fun defaults(): List<MediaSourceSubscription> = urls.map { MediaSourceSubscription(subscriptionId = Uuid.randomString(), url = it) }

    /** 新装时的默认订阅: 上游的默认订阅 [upstream] 去掉 [retiredUrls], 加上自带的. */
    fun withDefaults(upstream: List<MediaSourceSubscription>): List<MediaSourceSubscription> =
        upstream.filterNot { it.url in retiredUrls } + defaults()

    suspend fun reconcile(
        repository: MediaSourceSubscriptionRepository,
        manager: MediaSourceManager,
        settings: SettingsRepository,
    ) = reconcile(repository, manager, settings::isMarked, settings::mark)

    internal suspend fun reconcile(
        repository: MediaSourceSubscriptionRepository,
        manager: MediaSourceManager,
        isMarked: suspend (key: String) -> Boolean,
        mark: suspend (key: String) -> Unit,
    ) {
        for (url in urls) {
            val key = "bundledSubscription:$url"
            if (isMarked(key)) continue
            if (repository.flow.first().none { it.url == url }) {
                repository.add(MediaSourceSubscription(subscriptionId = Uuid.randomString(), url = url))
                logger.info { "Added bundled subscription $url" }
            }
            mark(key)
        }
        for (url in retiredUrls) {
            val key = "retiredSubscription:$url"
            if (isMarked(key)) continue
            for (subscription in repository.flow.first().filter { it.url == url }) {
                // 同设置页删订阅: 先删它带来的数据源, 再删订阅本身 (仓库的 remove 不管数据源)
                manager.removeInstances(manager.getListBySubscriptionId(subscription.subscriptionId).map { it.instanceId })
                repository.remove(subscription)
                logger.info { "Removed retired default subscription $url" }
            }
            mark(key)
        }
    }
}
