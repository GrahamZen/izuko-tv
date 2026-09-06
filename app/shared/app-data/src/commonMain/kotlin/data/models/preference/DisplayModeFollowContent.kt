/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

/**
 * [VideoScaffoldConfig.displayModeId] 取这个值 = 播放时按视频帧率切到整数倍的刷新率 (24 fps → 24 Hz, 25 fps → 50 Hz),
 * 离开播放页换回原来的 (见 ContentFrameRateEffect). 不是屏幕的哪个显示模式, 固定刷新率的那段逻辑认不出它, 什么都不做.
 */
const val DISPLAY_MODE_FOLLOW_CONTENT: Int = -1
