/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.runtime.Composable

/** 这一端有没有应用内通知 (安卓: 有, 见 TvNotices); 没有时侧边栏头像旁不放「通知」. */
internal expect val tvPlatformHasNotices: Boolean

/** 有新的通知 (头像与「通知」带红点); 没有通知的一端恒为 false. */
@Composable
internal expect fun rememberTvPlatformNoticesFresh(): Boolean

/** 打开通知列表. */
internal expect fun openTvPlatformNotices()
