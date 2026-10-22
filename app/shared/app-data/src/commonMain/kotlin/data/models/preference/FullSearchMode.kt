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
 * 设置「完整搜索」: 开始播放后还没查完的数据源怎么办 (开播前三档都一样, 所有数据源同时查). 规则表见 docs/contributing/code/media/media-selector.md.
 */
@Serializable
enum class FullSearchMode {
    /** 始终: 一直查完, 开播也不暂停. */
    Always,

    /** 打开选源面板时: 开播后暂停还在查的; 打开选源面板时放开查完, 面板开着期间不暂停. */
    OnPanel,

    /**
     * 只手动: 开播后暂停还在查的, 打开选源面板也不放开, 只有在面板里按「完整搜索」才继续. 给内存小、搜索时会卡的设备:
     * 系统标成低内存的设备没选过时就是这一档; 播放时搜索导致画面卡顿的, 没选过也自动切到这一档.
     */
    Manual,
}

/** 「完整搜索」这一档; 没选过 ([MediaSelectorSettings.fullSearchMode] 为 null) 按设备定: 系统标成低内存的设备只手动, 其余打开选源面板时. */
fun MediaSelectorSettings.effectiveFullSearchMode(lowRamDevice: Boolean): FullSearchMode =
    fullSearchMode ?: if (lowRamDevice) FullSearchMode.Manual else FullSearchMode.OnPanel

/** 这台设备是不是系统标成的低内存设备 (Android `ActivityManager.isLowRamDevice`), 经 Koin 给按设备定默认值的地方. */
class LowRamDevice(val value: Boolean)
