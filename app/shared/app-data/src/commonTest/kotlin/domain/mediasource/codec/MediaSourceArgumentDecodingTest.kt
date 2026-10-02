/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.codec

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveAddedShareArguments
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveAddedShareMediaSource
import me.him188.ani.app.domain.mediasource.instance.MediaSourceSave
import me.him188.ani.datasources.api.source.FactoryId
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.serializeArguments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 读全部数据源参数的地方 (如选源的阶级) 遇到这一版认不出的类型不能崩: 以前版本留下的数据源在新版本里可能已经没有对应的类型.
 */
class MediaSourceArgumentDecodingTest {
    private val codecs = createTestMediaSourceCodecManager()

    private fun save(factoryId: FactoryId, config: MediaSourceConfig) =
        MediaSourceSave("instance", "source", factoryId, isEnabled = true, config = config)

    @Test
    fun `unknown factories and broken arguments decode to null`() {
        val legacy = save(FactoryId("removed-source-type"), MediaSourceConfig(serializedArguments = buildJsonObject { put("name", "旧的") }))
        assertNull(legacy.getArgumentOrNull(codecs))

        val broken = save(CloudDriveAddedShareMediaSource.FactoryId, MediaSourceConfig(serializedArguments = buildJsonObject { put("name", "没有网盘") }))
        assertNull(broken.getArgumentOrNull(codecs))
    }

    @Test
    fun `added share sources have a codec`() {
        val config = MediaSourceConfig(
            serializedArguments = MediaSourceConfig.serializeArguments(
                CloudDriveAddedShareArguments.serializer(),
                CloudDriveAddedShareArguments("testdrive"),
            ),
        )
        val arguments = assertIs<CloudDriveAddedShareArguments>(save(CloudDriveAddedShareMediaSource.FactoryId, config).getArgumentOrNull(codecs))
        assertEquals("testdrive", arguments.drive)
    }
}
