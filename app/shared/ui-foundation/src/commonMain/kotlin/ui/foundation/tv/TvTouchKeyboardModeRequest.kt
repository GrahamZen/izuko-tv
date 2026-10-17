/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.runtime.Composable

/**
 * [tvTouchKeyboardMode] 每次指针按下调的那一下: 把输入模式请求回 Keyboard, **不挪焦点**.
 *
 * Compose 的 `requestInputMode(Keyboard)` 在 Android 上是对整个 Compose 根视图 `requestFocusFromTouch()`, 等于按「向下」
 * 重新找一次焦点. 焦点在 Compose 节点上时它落回原处; 焦点在嵌着的原生视图 (原生页的卡片、人物行) 上时它会被挪到下方的另一个
 * 原生视图 —— 被按的那张卡抬起时发现自己没焦点, 只抢回焦点不点击, 点多少下都打不开.
 */
@Composable
internal expect fun rememberTvTouchKeyboardModeRequest(): () -> Unit
