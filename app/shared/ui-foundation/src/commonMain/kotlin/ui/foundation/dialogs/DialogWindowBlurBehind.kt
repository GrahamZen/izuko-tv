/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * 把当前弹窗**窗口后面**的整个画面按 [radius] 模糊 (毛玻璃). 在 [androidx.compose.ui.window.Dialog] 的内容里调用.
 *
 * 返回这一次是否真的模糊了: 只有 Android 12 起、且系统开着跨窗口模糊 (省电模式、不少电视与盒子会关) 时才有;
 * 其余情况什么都不做、返回 false —— 调用方据此决定面板底色透多少 (没模糊时太透, 后面的字会直接透上来).
 * 与 [DialogWindowDimAmount] 一样只能问窗口要: 弹窗是独立窗口, 后面的画面不在它的画布里.
 */
@Composable
expect fun dialogWindowBlurBehind(radius: Dp): Boolean
