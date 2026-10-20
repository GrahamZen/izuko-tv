/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import kotlinx.serialization.Serializable
import me.him188.ani.app.data.persistent.DataStoreJson
import me.him188.ani.app.domain.media.MediaDynamicRange
import me.him188.ani.app.domain.media.MediaSurroundAudio
import kotlin.test.Test
import kotlin.test.assertEquals

/** 网盘账号设置里的 [DriveVideoInfo] 与已发布的各版本互相读得出来. */
class DriveVideoInfoTest {
    private fun encode(info: DriveVideoInfo) = DataStoreJson.encodeToString(DriveVideoInfo.serializer(), info)
    private fun decode(json: String) = DataStoreJson.decodeFromString(DriveVideoInfo.serializer(), json)

    @Test
    fun `features round trip`() {
        val info = DriveVideoInfo(1000, 2160, 1440, MediaDynamicRange.DolbyVision, MediaSurroundAudio.TrueHd, version = 1)
        assertEquals(info, decode(encode(info)))
        // 没认出特性的只多写一个版本号
        assertEquals("""{"size":1000,"height":1080,"version":1}""", encode(DriveVideoInfo(1000, 1080, version = 1)))
    }

    @Test
    fun `entries written without the new fields read as version 0`() {
        assertEquals(DriveVideoInfo(1000, 2160, 1440), decode("""{"size":1000,"height":2160,"durationSeconds":1440}"""))
    }

    @Test
    fun `unknown enum names read as null`() {
        val json = """{"size":1000,"height":2160,"dynamicRange":"Hdr12","audio":"Mpeg9","version":7}"""
        assertEquals(DriveVideoInfo(1000, 2160, version = 7), decode(json))
        val account = """{"videoInfo":{"k":$json}}"""
        assertEquals(
            DriveVideoInfo(1000, 2160, version = 7),
            DataStoreJson.decodeFromString(CloudDriveAccount.serializer(), account).videoInfo.getValue("k"),
        )
    }

    @Test
    fun `released versions skip the new fields`() {
        val json = encode(DriveVideoInfo(1000, 2160, 1440, MediaDynamicRange.Hdr10, MediaSurroundAudio.DtsHd, version = 1))
        assertEquals(ReleasedDriveVideoInfo(1000, 2160, 1440), DataStoreJson.decodeFromString(ReleasedDriveVideoInfo.serializer(), json))
    }

    /** 已发布版本里的 [DriveVideoInfo]. */
    @Serializable
    private data class ReleasedDriveVideoInfo(val size: Long, val height: Int = 0, val durationSeconds: Int = 0)
}
