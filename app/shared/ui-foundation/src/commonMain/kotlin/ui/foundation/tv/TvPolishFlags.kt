/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import kotlin.concurrent.Volatile

/**
 * TV 动效精修项的运行时开关 (2026-09-10 用户要的: 每项留一个能在运行时切的开关, 便于 A/B 录像对比).
 * 默认全开. 改法: `adb shell am start -n <pkg>/.MainActivity --ez ani_polish_press_dim false` 等,
 * MainActivity.handleStartIntent 读 extra 写进来. 都是 @Volatile 普通变量, 读点在 lambda / 协程 / 过渡规格
 * 里, 下一次用到就生效, 不触发重组.
 */
object TvPolishFlags {
    /** 按下方向键那一刻旧背景图先压暗、新图随后淡入 (Prime Video 式即时反馈). 两档都生效. */
    @Volatile
    var pressDim: Boolean = true

    /** hero 文字分行错落进场 (标题 → 元数据 → 简介各晚 40ms, Google TV 首页的味道). 只在完整视觉效果档. */
    @Volatile
    var textStagger: Boolean = true

    /** 点卡片进详情页时背景从列表页 hero 的框放大到全屏 (两页同一张图时才做, 见 TvHeroZoomHandoff). 两档都生效. */
    @Volatile
    var heroZoom: Boolean = true
}
