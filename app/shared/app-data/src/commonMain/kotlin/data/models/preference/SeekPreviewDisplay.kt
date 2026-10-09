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

/**
 * 电视上拖动预览 (拖动时暂停、由主播放器解出预览位置的画面) 的画面画在哪.
 *
 * 有的盒子的硬解经不起视频输出在全屏与小画面之间来回换, 拖动预览后解码出错时自动改成 [FULL_SCREEN]
 * (见 app-data 的 `SeekPreviewDecoderFault`).
 */
@Serializable
enum class SeekPreviewDisplay {
    /** 画在进度条上方浮窗的小画面里, 全屏停在开始拖之前的那一帧. 预览期间视频输出要换到小画面上, 结束时再换回全屏. */
    WINDOW,

    /** 直接画在全屏上, 浮窗只显示时间. 视频输出不换. */
    FULL_SCREEN,
}
