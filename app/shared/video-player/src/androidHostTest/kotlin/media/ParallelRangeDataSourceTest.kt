/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 分块大小: 路数越多 (每路限速越低) 开播 / 跳转后的头一块越小.
 */
class ParallelRangeDataSourceTest {
    private fun firstChunkSize(connections: Int) =
        ParallelRangeDataSource.firstChunkSizeFor(connections, ParallelRangeDataSource.chunkSizeFor(connections))

    @Test
    fun `first chunks shrink as connections grow`() {
        // 非会员 16 路
        assertEquals(64 * 1024, firstChunkSize(16))
        // 会员 4 路
        assertEquals(1024 * 1024, firstChunkSize(4))
        // 路数少时不超过最大块的 1/4
        assertEquals(1024 * 1024, firstChunkSize(1))
        // 路数再多也不小于 32 KiB
        assertEquals(32 * 1024, firstChunkSize(64))
    }
}
