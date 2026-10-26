/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network.mapper

import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.bangumi.models.BangumiEpisode
import me.him188.ani.utils.serialization.BigNum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BangumiEpisodeMappersTest {
    private fun episode(duration: String, durationSeconds: Int?) = BangumiEpisode(
        id = 850264,
        type = 0,
        name = "",
        nameCn = "",
        sort = BigNum(1),
        airdate = "2019-01-12",
        comment = 0,
        duration = duration,
        desc = "",
        disc = 0,
        ep = BigNum(1),
        durationSeconds = durationSeconds,
    )

    private fun BangumiEpisode.storedDuration() = toEntity(1, UnifiedCollectionType.DONE, lastFetched = 0).durationSeconds

    @Test
    fun `the duration of the episode is stored`() {
        assertEquals(1440, episode("00:24:00", 1440).storedDuration())
        // 服务器解析不出时给 0, 按原始的时长文本解析
        assertEquals(1440, episode("24m", 0).storedDuration())
        assertNull(episode("", 0).storedDuration())
    }
}
