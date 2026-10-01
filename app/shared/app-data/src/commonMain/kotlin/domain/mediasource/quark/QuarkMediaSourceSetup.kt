/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.quark

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.utils.platform.Uuid
import kotlin.time.Duration.Companion.seconds

/**
 * 还没有「夸克网盘」数据源时添加一个 (启用状态). 登录成功后调用: 用户登录夸克就是为了用这个数据源.
 *
 * @return 是否新加了数据源
 */
suspend fun ensureQuarkMediaSourceAdded(manager: MediaSourceManager): Boolean {
    if (manager.allInstances.first().any { it.factoryId == QuarkMediaSource.FACTORY_ID }) return false
    val instanceId = Uuid.randomString()
    manager.addInstance(instanceId, instanceId, QuarkMediaSource.FACTORY_ID, MediaSourceConfig.Default)
    return true
}

/**
 * 确保有一个启用着的「夸克网盘」数据源 (控制台给条目指定了网盘里的位置时调用, 没有这个源指定的就用不上).
 *
 * 新建或重新启用后, 等到 [MediaSourceManager.allInstances] 里真有它才返回 (最多 [INSTANCE_VISIBLE_TIMEOUT]), 理由同
 * [ensureAddedShareMediaSource].
 *
 * @return 数据源的 instance id 与这次是否新建或重新启用了它 (是的话, 正在进行的搜索里没有它)
 */
suspend fun ensureQuarkMediaSourceEnabled(manager: MediaSourceManager): Pair<String, Boolean> {
    val existing = manager.allInstances.first().firstOrNull { it.factoryId == QuarkMediaSource.FACTORY_ID }
    if (existing != null && existing.isEnabled) return existing.instanceId to false
    val instanceId = existing?.instanceId ?: Uuid.randomString()
    if (existing != null) {
        manager.setEnabled(instanceId, true)
    } else {
        manager.addInstance(instanceId, instanceId, QuarkMediaSource.FACTORY_ID, MediaSourceConfig.Default)
    }
    withTimeoutOrNull(INSTANCE_VISIBLE_TIMEOUT) {
        manager.allInstances.first { list -> list.any { it.instanceId == instanceId && it.isEnabled } }
    }
    return instanceId to true
}

private val INSTANCE_VISIBLE_TIMEOUT = 5.seconds
