/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player

import me.him188.ani.app.domain.player.extension.PlayerExtension
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.logger

/**
 * [PlayerExtensionManager.call] 里某个扩展的回调出错: 只记日志, 其余扩展照常调用, 调用方的后续步骤照常进行.
 * 半路中断的代价比少一个扩展大: 换集停在已取消的旧会话上 (页面卡死), 关闭时不再停止播放, 开播时排在后面的扩展 (含装资源) 都不启动.
 */
@PublishedApi
internal fun logExtensionCallFailure(extension: PlayerExtension, e: Throwable) {
    logger.error(e) { "Error calling extension ${extension.name}, continuing with the others" }
}

private val logger = logger<PlayerExtensionManager>()
