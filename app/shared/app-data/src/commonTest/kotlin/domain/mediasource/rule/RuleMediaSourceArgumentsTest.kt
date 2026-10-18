/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import kotlinx.serialization.json.Json
import me.him188.ani.datasources.api.source.MediaSourceTier
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleMediaSourceArgumentsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `channel tiers and tab pattern are read from rule json`() {
        val arguments = json.decodeFromString(
            RuleMediaSourceArguments.serializer(),
            """
                {
                  "name": "示例站",
                  "tier": 0,
                  "channelTiers": {"简中": 0, "繁中": 1},
                  "rule": {
                    "detail": [
                      {"op": "fetch"},
                      {"op": "episodes", "lists": [".eps"], "tabs": [".tabs a"], "tabPattern": "(?<ch>.+?)(\\d+?)"}
                    ]
                  }
                }
            """.trimIndent(),
        )
        assertEquals(mapOf("简中" to MediaSourceTier(0u), "繁中" to MediaSourceTier(1u)), arguments.channelTiers)
        val episodes = arguments.rule.detail[1] as RuleStep.Episodes
        assertEquals("""(?<ch>.+?)(\d+?)""", episodes.tabPattern)
    }
}
