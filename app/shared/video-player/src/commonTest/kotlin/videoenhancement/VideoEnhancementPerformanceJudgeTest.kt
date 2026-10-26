/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.videoenhancement

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoEnhancementPerformanceJudgeTest {
    private val judge = VideoEnhancementPerformanceJudge()

    /** 一个 2 秒窗口; 默认是正常在播、24 帧片源. */
    private fun sample(
        rendered: Int,
        advance: Long = 2000,
        playing: Boolean = true,
        frameRate: Float = 24f,
        speed: Float = 1f,
    ): Boolean = judge.onSample(rendered, 2000, advance, playing, frameRate, speed)

    @Test
    fun `three slow windows in a row trigger`() {
        // 工单 32 的样子: 每 2 秒只出 1~3 帧
        assertFalse(sample(2))
        assertFalse(sample(3))
        assertTrue(sample(1))
    }

    @Test
    fun `healthy playback never triggers`() {
        repeat(10) { assertFalse(sample(48)) }
    }

    @Test
    fun `slightly low frame rate is not slow`() {
        // 掉到七成 (偶尔丢帧) 不算
        repeat(10) { assertFalse(sample(34)) }
    }

    @Test
    fun `a good window in between starts over`() {
        assertFalse(sample(2))
        assertFalse(sample(2))
        assertFalse(sample(48))
        assertFalse(sample(2))
        assertFalse(sample(2))
        assertTrue(sample(2))
    }

    @Test
    fun `buffering paused and seeking windows are ignored`() {
        assertFalse(sample(2))
        assertFalse(sample(0, advance = 0)) // 卡住缓冲: 位置不走
        assertFalse(sample(0, playing = false)) // 暂停
        assertFalse(sample(5, advance = 90_000)) // 跳转
        assertFalse(sample(2))
        assertTrue(sample(2))
    }

    @Test
    fun `startup window without position advance does not count`() {
        // 开播头一个窗口还在攒缓冲, 一帧没出也不算
        assertFalse(sample(0, advance = 300))
        assertFalse(sample(0, advance = 300))
        assertFalse(sample(0, advance = 300))
    }

    @Test
    fun `negative frame delta after decoder reset is ignored`() {
        assertFalse(sample(2))
        assertFalse(sample(-40))
        assertFalse(sample(2))
        assertTrue(sample(2))
    }

    @Test
    fun `expected frames follow the content frame rate`() {
        // 60 帧片源每 2 秒出 50 帧 (不到一半的 60) 才算慢; 24 帧片源出 50 帧绰绰有余
        assertFalse(sample(50, frameRate = 24f))
        assertFalse(sample(50, frameRate = 60f))
        assertFalse(sample(50, frameRate = 60f))
        assertTrue(sample(50, frameRate = 60f))
    }

    @Test
    fun `unknown frame rate falls back to 24`() {
        assertFalse(sample(30, frameRate = -1f))
        assertFalse(sample(10, frameRate = -1f))
        assertFalse(sample(10, frameRate = -1f))
        assertTrue(sample(10, frameRate = -1f))
    }

    @Test
    fun `faster playback expects more frames`() {
        // 2 倍速: 2 秒走 4 秒内容, 24 帧片源要 96 帧; 出 40 帧算慢
        assertFalse(sample(40, advance = 4000, speed = 2f))
        assertFalse(sample(40, advance = 4000, speed = 2f))
        assertTrue(sample(40, advance = 4000, speed = 2f))
    }

    @Test
    fun `reset clears the count`() {
        assertFalse(sample(2))
        assertFalse(sample(2))
        judge.reset()
        assertFalse(sample(2))
        assertFalse(sample(2))
        assertTrue(sample(2))
    }
}
