/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.him188.ani.app.domain.media.MediaAvFeatures
import me.him188.ani.app.domain.media.MediaDynamicRange
import me.him188.ani.app.domain.media.ProbedMediaAvFeatures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * 网盘给的 HDR / 杜比视界信息: 列目录里的杜比视界标记, 播放接口响应里原文件的信息 (见 [DriveVideoMetaFields]).
 */
class CloudDriveVideoMetaTest {
    private val protocol = TestDrive.protocol.copy(
        file = TestDrive.protocol.file.copy(dolbyVision = "dv"),
        api = TestDrive.protocol.api.copy(
            transcoded = TestDrive.protocol.api.transcoded!!.copy(
                meta = DriveVideoMetaFields(
                    width = "data.meta.width",
                    height = "data.meta.height",
                    duration = "data.meta.duration",
                    codec = "data.meta.codec",
                    hdr = "data.meta.hdr",
                    dolbyVisionProfile = "data.meta.dolby_vision.profile",
                ),
            ),
        ),
    )

    private fun meta(id: String): String = when (id) {
        "dv" -> """{"code":0,"data":{"meta":{"width":3840,"height":2160,"duration":30,"codec":"hevc","hdr":1,"dolby_vision":{"profile":8,"level":9}}}}"""
        "dv5" -> """{"code":0,"data":{"meta":{"width":1920,"height":1080,"duration":30,"codec":"hevc","dolby_vision":{"profile":5,"level":5}}}}"""
        "hdr", "meta-once" -> """{"code":0,"data":{"meta":{"width":1920,"height":1080,"duration":30,"codec":"hevc","hdr":1}}}"""
        else -> """{"code":0,"data":{"meta":{"width":1920,"height":1080,"duration":20,"codec":"h264"}}}"""
    }

    @Test
    fun `listing marks dolby vision files`() = runTest {
        val service = testDriveService(accountsOf(TestDrive.loggedIn), protocol) {
            reply("""{"code":0,"data":{"items":[
                {"id":"a","name":"a.mp4","kind":"video","height":2160,"dv":true},
                {"id":"b","name":"b.mp4","kind":"video","height":2160}
            ],"total":2}}""")
        }
        val files = service.api.listFolder(TestDrive.ROOT).files
        assertEquals(MediaDynamicRange.DolbyVision, files[0].avFeatures.dynamicRange)
        assertEquals(MediaAvFeatures.NONE, files[1].avFeatures)
    }

    @Test
    fun `video meta reads hdr and the dolby vision profile`() = runTest {
        val service = testDriveService(accountsOf(TestDrive.loggedIn), protocol) { request -> reply(meta(request.jsonBody().string("id"))) }
        val dv = service.api.videoMeta("dv")!!
        assertEquals(MediaDynamicRange.DolbyVision, dv.dynamicRange)
        assertEquals(8, dv.dolbyVisionProfile)
        assertEquals(3840, dv.width)
        assertEquals(30, dv.durationSeconds)
        // profile 5 没有 HDR10 底层, 网盘也不标 hdr
        assertEquals(5, service.api.videoMeta("dv5")!!.dolbyVisionProfile)
        assertEquals(MediaDynamicRange.Hdr, service.api.videoMeta("hdr")!!.dynamicRange)
        val sdr = service.api.videoMeta("sdr")!!
        assertNull(sdr.dynamicRange)
        assertNull(sdr.dolbyVisionProfile)
    }

    @Test
    fun `a protocol without meta fields sends nothing`() = runTest {
        val service = testDriveService(accountsOf(TestDrive.loggedIn)) { error("不该发请求") }
        assertNull(service.api.videoMeta("x"))
        assertTrue(service.videoInfoOf(listOf(video("x", "x - 01.mp4")), emptyList()).isEmpty())
    }

    @Test
    fun `current episode files are read once in the background`() = realTimeTest {
        val requests = MutableStateFlow(emptyList<String>())
        val service = testDriveService(accountsOf(TestDrive.loggedIn), protocol) { request ->
            val id = request.jsonBody().string("id")
            requests.update { it + id }
            reply(meta(id))
        }
        val file = video("meta-once", "x - 01.mp4").let {
            DriveFile(it.fid, it.fileName, it.parentFid, size = it.size, videoHeight = 2160, isVideo = true, durationSeconds = 30)
        }
        // 不等: 第一次给不出, 读到了记进 ProbedMediaAvFeatures
        assertTrue(service.videoInfoOf(listOf(file), emptyList()).isEmpty())
        val mediaId = "${protocol.driveMediaSourceId}.${file.fid}"
        withTimeout(5.seconds) { ProbedMediaAvFeatures.changes.first { ProbedMediaAvFeatures.of(mediaId) != null } }
        assertEquals(MediaDynamicRange.Hdr, ProbedMediaAvFeatures.of(mediaId)!!.dynamicRange)
        // 第二次: 记下的直接给 (高度按读到的), 不再请求
        val second = service.videoInfoOf(listOf(file), emptyList()).getValue(file.fid)
        assertEquals(MediaDynamicRange.Hdr, second.avFeatures.dynamicRange)
        assertEquals(1080, second.videoHeight)
        assertEquals(listOf("meta-once"), requests.value)
    }
}
