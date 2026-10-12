/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.audio

/**
 * 杜比 (AC3 / E-AC3 / TrueHD)、DTS 这类音轨要不要原样交给系统 (直通), 由接在后面的功放、回音壁或电视自己解码.
 * 不直通时播放器自己解码成 PCM 再输出, 多声道照样以多声道 PCM 送出, 只是杜比全景声这类原始码流没了.
 */
enum class AudioPassthroughMode {
    /**
     * 立体声与单声道一律解码 (直通它们没有好处); 多声道只在送到外接设备 (HDMI、ARC / eARC、光纤) 时直通, 自带喇叭、蓝牙等解码.
     * 有些电视声称能直通 AC3, 实际放出来没有声音.
     */
    AUTO,

    /** 系统说能直通就直通. */
    ALWAYS,

    /** 一律解码. */
    OFF,
}
