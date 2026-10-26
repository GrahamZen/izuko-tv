/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.ui.foundation.widgets.BackNavigationIconButton
import kotlin.time.Duration.Companion.seconds

/**
 * 播放页的状态还没算出来 (整页是空的) 时的出口: 空了 [SHOW_AFTER] 还没出来就在左上角放一颗返回.
 * 只在没有返回键的设备 (iPad 等) 上显示 (见 [BackNavigationIconButton]); 正常进页只空一瞬间, 不等的话每次进页都闪一下.
 */
@Composable
internal fun EpisodePageLoadingExit(modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SHOW_AFTER)
        show = true
    }
    if (!show) return
    val navigator = LocalNavigator.current
    BackNavigationIconButton({ navigator.popBackStack() }, modifier.safeDrawingPadding().padding(8.dp))
}

private val SHOW_AFTER = 1.seconds
