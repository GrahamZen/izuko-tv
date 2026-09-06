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
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.utils.platform.Uuid

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
