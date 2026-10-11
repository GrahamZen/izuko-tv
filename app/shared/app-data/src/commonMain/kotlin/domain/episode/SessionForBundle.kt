/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 查询会话与配给它的那份条目信息. 选源器只用配对的两者建 (见 `CreateMediaFetchSelectBundleFlowUseCase`):
 * 条目信息刚变、它的会话还没取到的那一刻不建 —— 拿旧会话先建一个、马上又换掉, 自动选源会跑两遍, 选中的资源会装两次.
 */
internal class SessionForBundle(
    val bundle: SubjectEpisodeInfoBundle,
    val session: MediaFetchSession,
)

/**
 * 给每份条目信息配一个查询会话: 请求与「重新搜索」计数 (见 `MediaFetchSessionRefresh`) 都没变时沿用上一个会话, 不重新查.
 */
internal fun Flow<Triple<SubjectEpisodeInfoBundle, MediaFetchRequest, Int>>.sessionsForBundles(
    createFetchSession: suspend (MediaFetchRequest, refreshTick: Int) -> MediaFetchSession,
): Flow<SessionForBundle> = flow {
    var last: Triple<MediaFetchRequest, Int, MediaFetchSession>? = null
    emitAll(
        mapLatest { (bundle, request, tick) ->
            val session = last?.takeIf { it.first == request && it.second == tick }?.third
                ?: run {
                    logger.info { "MediaFetchRequest changed. Creating MediaFetchSession for request: $request" }
                    createFetchSession(request, tick).also { last = Triple(request, tick, it) }
                }
            SessionForBundle(bundle, session)
        },
    )
}

private val logger = logger<SessionForBundle>()
