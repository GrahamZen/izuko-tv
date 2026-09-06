/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediafetch

import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.MediaSourceKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [MediaSourceResultListPresentation] 的计数口径: 选源面板标题查询中写「查完数 / 启用数」.
 */
class MediaSourceResultListPresentationTest {
    private fun source(
        state: MediaSourceFetchState,
        kind: MediaSourceKind = MediaSourceKind.WEB,
    ) = MediaSourceResultPresentation(
        instanceId = "i",
        mediaSourceId = "s",
        state = state,
        info = MediaSourceInfo("s"),
        kind = kind,
        totalCount = 0,
        isPreferred = false,
    )

    @Test
    fun `finished counts only completed enabled sources`() {
        val list = MediaSourceResultListPresentation(
            listOf(
                source(MediaSourceFetchState.Working),
                source(MediaSourceFetchState.Idle),
                source(MediaSourceFetchState.Succeed(1)),
                source(MediaSourceFetchState.Failed(IllegalStateException(), 1)),
                // 暂停的放开后要重查, 不算查完
                source(MediaSourceFetchState.Paused(1)),
                // 停用的与本地缓存都不算参与搜索的源
                source(MediaSourceFetchState.Disabled),
                source(MediaSourceFetchState.Succeed(1), kind = MediaSourceKind.LocalCache),
            ),
        )

        assertEquals(2, list.finishedSourceCount)
        assertEquals(5, list.enabledSourceCount)
    }
}
