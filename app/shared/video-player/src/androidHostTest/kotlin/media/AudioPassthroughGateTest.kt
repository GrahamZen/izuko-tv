/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@UnstableApi
class AudioPassthroughGateTest {
    private val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    private val hdmi = AudioDeviceInfo.TYPE_HDMI
    private val arc = AudioDeviceInfo.TYPE_HDMI_ARC
    private val bus = AudioDeviceInfo.TYPE_BUS
    private val usb = AudioDeviceInfo.TYPE_USB_DEVICE

    @Test
    fun `routed device decides when it is known`() {
        // 电视自带喇叭: 即使接着 ARC 设备也算
        assertTrue(AudioOutputRoute.isBuiltInSpeaker(routed = listOf(speaker), outputs = listOf(speaker, arc)))
        assertFalse(AudioOutputRoute.isBuiltInSpeaker(routed = listOf(arc), outputs = listOf(speaker, arc)))
        // 盒子的 HDMI 输出
        assertFalse(AudioOutputRoute.isBuiltInSpeaker(routed = listOf(hdmi), outputs = listOf(hdmi)))
    }

    @Test
    fun `without the routed device only a speaker with no other outputs counts`() {
        // 电视: 只有自带喇叭
        assertTrue(AudioOutputRoute.isBuiltInSpeaker(routed = null, outputs = listOf(speaker)))
        // 电视接了回音壁
        assertFalse(AudioOutputRoute.isBuiltInSpeaker(routed = null, outputs = listOf(speaker, arc)))
        // Shield: HDMI 报成 总线 + USB + 喇叭
        assertFalse(AudioOutputRoute.isBuiltInSpeaker(routed = emptyList(), outputs = listOf(bus, usb, speaker)))
        assertFalse(AudioOutputRoute.isBuiltInSpeaker(routed = null, outputs = listOf(hdmi)))
        assertFalse(AudioOutputRoute.isBuiltInSpeaker(routed = null, outputs = emptyList()))
    }

    @Test
    fun `encoded formats are refused only when passthrough is not allowed`() {
        val ac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).setChannelCount(2).setSampleRate(48_000).build()
        val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2).setSampleRate(48_000).build()

        val blocked = PassthroughGatedAudioSink(supportingEverything(), allowsPassthrough = { false })
        assertFalse(blocked.supportsFormat(ac3))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, blocked.getFormatSupport(ac3))
        assertTrue(blocked.supportsFormat(pcm))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, blocked.getFormatSupport(pcm))

        val allowed = PassthroughGatedAudioSink(supportingEverything(), allowsPassthrough = { true })
        assertTrue(allowed.supportsFormat(ac3))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, allowed.getFormatSupport(ac3))

        // 闸门按格式判断: 这里只放行多声道
        val surroundOnly = PassthroughGatedAudioSink(supportingEverything(), allowsPassthrough = { it.channelCount > 2 })
        assertFalse(surroundOnly.supportsFormat(ac3))
        assertTrue(surroundOnly.supportsFormat(ac3.buildUpon().setChannelCount(6).build()))
    }

    /** 什么格式都说能直接放的输出; 其余调用返回默认值. */
    private fun supportingEverything(): AudioSink = Proxy.newProxyInstance(
        AudioSink::class.java.classLoader,
        arrayOf(AudioSink::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "supportsFormat" -> true
            "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        }
    } as AudioSink
}
