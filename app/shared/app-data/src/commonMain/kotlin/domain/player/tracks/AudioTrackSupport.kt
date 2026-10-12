/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.tracks

import kotlinx.coroutines.flow.StateFlow

/**
 * 能说出当前媒体里哪些音轨这台设备放不了的播放器 (Android 的 ExoPlayer 后端).
 *
 * 选音轨的界面 (电视底栏、手机控制中心) 据此注明并拦下: 切到放不了的音轨播放器就报错, 自动换源会把整个资源换掉.
 */
interface AudioTrackSupport {
    /**
     * 当前媒体里设备放不了的音轨 (没有解码器、也不能直通): mediamp 音轨的 id → 格式名 (见 `MediaFormatNames`; 认不出为空串).
     * 没有媒体时为空.
     */
    val unplayableAudioTracks: StateFlow<Map<String, String>>

    /**
     * 当前媒体有音轨、但设备一条也放不了时是这些音轨的格式名 (去重, 认不出的不列, 可能为空); 否则 null.
     * 这时播放器不选音轨、照常放画面, 不报错 —— 只有画面没有声音, 界面要提示用户自己换个数据源.
     */
    val allAudioUnplayable: StateFlow<List<String>?>
}
