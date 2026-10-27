/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 分集会话一建好就开查: 查询会话的结果是共享的 ([me.him188.ani.app.domain.media.fetch.MediaFetchSession.cumulativeResults]),
 * 先订上就开始搜, 不等播放页画好、扩展任务启动 ([EpisodeFetchSelectPlayState] 等界面就绪才启动扩展, 电视上进页要半秒到一秒).
 * 扩展启动后订的是同一份结果. 跟着会话 ([EpisodeSession.sessionScope]) 结束: 切集、离开播放页时停.
 */
internal fun EpisodeSession.startSearchEarly() {
    sessionScope.launch {
        fetchSelectFlow.collectLatest { bundle -> bundle?.mediaFetchSession?.cumulativeResults?.collect {} }
    }
}
