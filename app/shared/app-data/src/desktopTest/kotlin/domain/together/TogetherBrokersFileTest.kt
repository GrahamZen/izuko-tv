/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 仓库根目录 `together-brokers.json` 里的原文条目 (从测试的工作目录往上找) */
private val repoListEntries: List<String> by lazy {
    val file = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "together-brokers.json") }
        .first { it.isFile }
    Json.parseToJsonElement(file.readText()).jsonObject.getValue("brokers").jsonArray.map { it.jsonPrimitive.content }
}

/** 连真实代理的测试用的代理: 仓库清单里的, 与应用拉到的是同一份 */
internal val LIVE_BROKERS: List<String> by lazy { repoListEntries.mapNotNull(TogetherBrokerList::normalizeBroker) }

/** 守着仓库里的代理清单: 改坏了的话, 拉到它的应用一家代理都连不上, 或者与老版本进不了同一个房间 */
class TogetherBrokersFileTest {
    @Test
    fun `every entry of the repo list is usable`() {
        assertTrue(repoListEntries.isNotEmpty())
        assertEquals(repoListEntries, LIVE_BROKERS)
    }

    @Test
    fun `repo list keeps a broker that older versions know`() {
        // 1.0.9-alpha04 及以前的包只认这三家, 不拉清单
        val olderVersions = setOf("wss://broker.emqx.io:8084/mqtt", "wss://broker.hivemq.com:8884/mqtt", "wss://test.mosquitto.org:8081/mqtt")
        assertTrue(LIVE_BROKERS.any { it in olderVersions }, "清单里要留着老版本认的代理: $LIVE_BROKERS")
    }
}
