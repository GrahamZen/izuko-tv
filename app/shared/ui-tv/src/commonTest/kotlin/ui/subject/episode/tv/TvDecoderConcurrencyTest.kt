/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 播放时能不能用系统取帧, 以及启动时要不要检测 (见 [TvDecoderConcurrency]).
 */
class TvDecoderConcurrencyTest {
    private val allOk = mapOf(ProbeTier.FHD to "ok", ProbeTier.UHD to "ok")
    private val uhdFailed = mapOf(ProbeTier.FHD to "ok", ProbeTier.UHD to "fail")

    @Test
    fun `没检测过不用`() {
        assertFalse(decideSystemFrameExtraction(emptyMap(), reclaimed = false, videoWidth = 1920, videoHeight = 1080))
    }

    @Test
    fun `按视频所在的分辨率档判断`() {
        assertTrue(decideSystemFrameExtraction(allOk, reclaimed = false, videoWidth = 1920, videoHeight = 1080))
        assertTrue(decideSystemFrameExtraction(uhdFailed, reclaimed = false, videoWidth = 1920, videoHeight = 1080))
        assertTrue(decideSystemFrameExtraction(uhdFailed, reclaimed = false, videoWidth = 1280, videoHeight = 720))
        assertFalse(decideSystemFrameExtraction(uhdFailed, reclaimed = false, videoWidth = 3840, videoHeight = 2160))
        // 竖屏 1080p 也是 1080p 档
        assertTrue(decideSystemFrameExtraction(uhdFailed, reclaimed = false, videoWidth = 1080, videoHeight = 1920))
    }

    @Test
    fun `不知道尺寸时所有档都要通过`() {
        assertTrue(decideSystemFrameExtraction(allOk, reclaimed = false, videoWidth = null, videoHeight = null))
        assertFalse(decideSystemFrameExtraction(uhdFailed, reclaimed = false, videoWidth = null, videoHeight = null))
    }

    @Test
    fun `出过解码器被收回就不再用`() {
        assertFalse(decideSystemFrameExtraction(allOk, reclaimed = true, videoWidth = 1920, videoHeight = 1080))
    }

    @Test
    fun `要不要检测`() {
        assertTrue(isProbeNeeded(emptyMap(), reclaimed = false, failedProbes = 0))
        assertFalse(isProbeNeeded(allOk, reclaimed = false, failedProbes = 0))
        // 没通过的复查几次, 防启动时正好在播造成误判
        assertTrue(isProbeNeeded(uhdFailed, reclaimed = false, failedProbes = 1))
        assertFalse(isProbeNeeded(uhdFailed, reclaimed = false, failedProbes = MAX_FAILED_PROBES))
        assertFalse(isProbeNeeded(emptyMap(), reclaimed = true, failedProbes = 0))
    }
}
