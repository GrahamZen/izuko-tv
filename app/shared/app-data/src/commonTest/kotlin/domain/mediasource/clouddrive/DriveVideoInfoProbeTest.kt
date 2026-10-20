/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.CompletableDeferred
import me.him188.ani.app.data.models.preference.DriveVideoInfo
import me.him188.ani.app.domain.media.MediaAvFeatures
import me.him188.ani.app.domain.media.MediaDynamicRange
import me.him188.ani.app.domain.media.MediaSurroundAudio
import me.him188.ani.app.domain.media.probe.MediaStreamProbe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class DriveVideoInfoProbeTest {
    private fun video(fid: String, name: String = "$fid.mkv", size: Long = 1000) = DriveFile(fid, fileName = name, size = size, isVideo = true)

    private fun result(width: Int, height: Int, duration: Int) = MediaStreamProbe.Result("mkv", width, height, "HEVC", durationSeconds = duration)

    @Test
    fun `uses what was read before and reads again only when the file changed`() = realTimeTest {
        val stored = mapOf(
            DriveVideoInfoProbe.keyOf("a") to DriveVideoInfo(size = 1000, height = 2160, durationSeconds = 1440, version = DriveVideoInfoProbe.PROBE_VERSION),
        )
        val probed = mutableListOf<String>()
        val probe = DriveVideoInfoProbe(
            stored = { stored },
            save = { _, _ -> },
            probe = { probed += it.fid; result(1920, 1080, 1420) },
        )
        val filled = probe.fill(listOf(video("a"), video("b", size = 2000)))
        assertEquals(2160, filled.getValue("a").videoHeight)
        assertEquals(1440, filled.getValue("a").durationSeconds)
        assertEquals(1080, filled.getValue("b").videoHeight)
        assertEquals(listOf("b"), probed)

        // 同一个 id 换了文件 (大小变了) 才重读
        probe.fill(listOf(video("a", size = 3000)))
        assertEquals(listOf("b", "a"), probed)
    }

    @Test
    fun `skips files that already have the info and formats it cannot read`() = realTimeTest {
        val probed = mutableListOf<String>()
        val probe = DriveVideoInfoProbe(stored = { emptyMap() }, save = { _, _ -> }, probe = { probed += it.fid; null })
        val known = DriveFile("a", fileName = "a.mkv", isVideo = true, videoHeight = 1080, durationSeconds = 1440)
        assertEquals(emptyMap(), probe.fill(listOf(known, video("b", name = "b.avi"), DriveFile("c", fileName = "c.ass"))))
        assertEquals(emptyList(), probed)
    }

    @Test
    fun `a slow read is given up for now and remembered for next time`() = realTimeTest {
        val stored = HashMap<String, DriveVideoInfo>()
        val gate = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Unit>()
        var reads = 0
        val probe = DriveVideoInfoProbe(
            stored = { HashMap(stored) },
            save = { key, info -> stored[key] = info; saved.complete(Unit) },
            probe = { reads++; gate.await(); result(3840, 1608, 1440) },
        )
        // 等不到结果 (最多等 1 秒): 先按没有信息给出
        assertEquals(emptyMap(), probe.fill(listOf(video("a"))))
        gate.complete(Unit)
        saved.await()
        // 宽银幕的 3840×1608 按宽度折算成 2160
        assertEquals(2160, probe.fill(listOf(video("a"))).getValue("a").videoHeight)
        assertEquals(1, reads)
    }

    @Test
    fun `other episodes are read in the background and reported when done`() = realTimeTest {
        val gate = CompletableDeferred<Unit>()
        val late = CompletableDeferred<Unit>()
        val probe = DriveVideoInfoProbe(
            stored = { emptyMap() },
            save = { _, _ -> },
            probe = { gate.await(); result(3840, 2160, 1440) },
            onLateInfo = { late.complete(Unit) },
        )
        val mark = TimeSource.Monotonic.markNow()
        // 不等其余各集
        assertEquals(emptyMap(), probe.fill(current = emptyList(), others = listOf(video("b"))))
        assertTrue(mark.elapsedNow() < DriveVideoInfoProbe.WAIT)
        gate.complete(Unit)
        late.await()
    }

    @Test
    fun `info from an older probe version is used at once and read again in the background`() = realTimeTest {
        val key = DriveVideoInfoProbe.keyOf("a")
        val stored = HashMap(mapOf(key to DriveVideoInfo(size = 1000, height = 2160, durationSeconds = 1440)))
        val gate = CompletableDeferred<Unit>()
        val late = CompletableDeferred<Unit>()
        var reads = 0
        val probe = DriveVideoInfoProbe(
            stored = { HashMap(stored) },
            save = { k, info -> stored[k] = info },
            probe = {
                reads++
                gate.await()
                result(3840, 2160, 1440).copy(dynamicRange = MediaDynamicRange.DolbyVision, audio = MediaSurroundAudio.TrueHd)
            },
            onLateInfo = { late.complete(Unit) },
        )
        val mark = TimeSource.Monotonic.markNow()
        // 记下的照用, 不等重读
        val first = probe.fill(listOf(video("a"))).getValue("a")
        assertTrue(mark.elapsedNow() < DriveVideoInfoProbe.WAIT)
        assertEquals(2160, first.videoHeight)
        assertEquals(1440, first.durationSeconds)
        assertEquals(MediaAvFeatures.NONE, first.avFeatures)

        gate.complete(Unit)
        late.await()
        assertEquals(
            DriveVideoInfo(1000, 2160, 1440, MediaDynamicRange.DolbyVision, MediaSurroundAudio.TrueHd, version = DriveVideoInfoProbe.PROBE_VERSION),
            stored.getValue(key),
        )
        // 重读过的不再读, 带上认出的特性
        val second = probe.fill(listOf(video("a"))).getValue("a")
        assertEquals(MediaAvFeatures(MediaDynamicRange.DolbyVision, MediaSurroundAudio.TrueHd), second.avFeatures)
        assertEquals(1, reads)
    }

    @Test
    fun `the current episode does not queue behind the others`() = realTimeTest {
        val gate = CompletableDeferred<Unit>()
        val probe = DriveVideoInfoProbe(
            stored = { emptyMap() },
            save = { _, _ -> },
            probe = { if (it.fid == "current") result(3840, 2160, 1440) else { gate.await(); null } },
        )
        // 后台排队的把并发名额占满了, 当前这一集照样马上读
        val others = (1..5).map { video("other$it") }
        assertEquals(2160, probe.fill(listOf(video("current")), others).getValue("current").videoHeight)
        gate.complete(Unit)
    }

    @Test
    fun `height follows the width for wide pictures`() {
        assertEquals(2160, DriveVideoInfoProbe.heightOf(3840, 1608))
        assertEquals(1080, DriveVideoInfoProbe.heightOf(1920, 1080))
        assertEquals(1080, DriveVideoInfoProbe.heightOf(1440, 1080))
        assertEquals(0, DriveVideoInfoProbe.heightOf(null, null))
    }
}
