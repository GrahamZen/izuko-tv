/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.ui.graphics.CompositingStrategy

/**
 * 透明度跟着状态变、又不想开离屏缓冲的图层用它, 不直接写 [CompositingStrategy.ModulateAlpha]: 安卓 (RenderNode 逐条绘制指令调制透明度)
 * 是 ModulateAlpha; Skia (iOS / 桌面) 的 ModulateAlpha 只在录制绘制内容时把透明度乘进去, 透明度再变不重录, 画面停在录制那一刻 ——
 * 那边用 Auto (透明度小于 1 时开离屏缓冲).
 */
expect val TvModulateAlphaCompositing: CompositingStrategy
