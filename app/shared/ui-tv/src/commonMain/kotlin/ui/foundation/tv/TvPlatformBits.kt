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
import androidx.compose.ui.text.PlatformTextStyle
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch

// 电视版页面里几处平台不同的小件: 安卓照原来的写法, 别的端给等价或省掉的做法.

/** 此刻软键盘弹着没有 (组合里读, 跟着变). */
@Composable
internal expect fun tvImeVisible(): Boolean

/** 关掉字体内边距 (文字行高按设定的算, 条子高度才准); 没有这个概念的平台给 null. */
internal expect val tvNoFontPadding: PlatformTextStyle?

/** 语言码 [language] (如 `ja`) 在界面语言 [uiLanguageTag] 下的名字 (「日文」); 认不出给原样. */
internal expect fun tvLanguageDisplayName(language: String, uiLanguageTag: String): String

/** 先把标题 logo 解好放进内存缓存 (邻居预取), 按 [widthPx] × [heightPx] 解. */
internal expect fun tvPreloadTitleLogo(sketch: Sketch, context: PlatformContext, url: String, widthPx: Int, heightPx: Int)

/** 追番页有没有「追更」页 (它的画面只有安卓的原生版, 见 TvCollectionProgressPane). */
internal expect val tvCollectionProgressAvailable: Boolean

/** 有没有 Web 控制台 (手机扫码遥控 / 输入, 见 TvRemoteControl): 没有时不画给手机扫的码 (搜索页的手机输入、动作面板角上的码). */
internal expect val tvWebConsoleAvailable: Boolean
