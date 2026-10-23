/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.logging.Logger
import me.him188.ani.utils.logging.info
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 自动选源等「这一集哪些缓存还不能播」最多等这么久. */
internal val CACHE_READINESS_TIMEOUT: Duration = 5.seconds

/**
 * 候选里有本地缓存、而这一集哪些缓存还不能播 ([MediaSelectorContext.unplayableCacheMediaIds]) 还没查到时, 先不把快照交给自动选源,
 * 等查到 (没下完的网页源缓存随之被筛掉) 再交, 最多等 [timeout] —— 到时还不知道就照常交出, 宁可选到坏缓存让它失败换源,
 * 也不让缓存过的集不走缓存.
 *
 * 这一项是各缓存 `canPlay` 都报过一次才有的, 生产者先发 `null`; 切到同一条目的下一集时缓存结果几乎立刻就在候选里,
 * 不等的话自动选源直接选中还在下的缓存, 文件还没合并出来就加载失败 (再拉黑、换源, 多等几秒).
 */
internal fun Flow<MediaAutoSelectSnapshot>.awaitCacheReadiness(
    logger: Logger,
    timeout: Duration = CACHE_READINESS_TIMEOUT,
): Flow<MediaAutoSelectSnapshot> {
    var held = false
    return combine(this, flow { emit(false); delay(timeout); emit(true) }) { snapshot, timedOut -> snapshot to timedOut }
        .transform { (snapshot, timedOut) ->
            if (!timedOut && snapshot.waitsForCacheReadiness()) {
                held = true
                return@transform
            }
            if (held) {
                held = false
                val unplayable = snapshot.context.unplayableCacheMediaIds
                logger.info {
                    if (unplayable == null) "Auto select: cache playability still unknown after $timeout, deciding anyway"
                    else "Auto select: waited for cache playability (unplayable=${unplayable.size})"
                }
            }
            emit(snapshot)
        }
}

private fun MediaAutoSelectSnapshot.waitsForCacheReadiness(): Boolean =
    context.unplayableCacheMediaIds == null && candidates.any { it.result.kind == MediaSourceKind.LocalCache }
