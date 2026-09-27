/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.text.ProvideTextStyleContentColor

@Composable
fun VideoLoadingIndicator(
    showProgress: Boolean,
    text: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    /** 不为 `null` 时画定量的进度环 (0..1, 在绘制阶段读), 否则转圈. 只在 [showProgress] 时有效. */
    progress: (() -> Float)? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (showProgress) {
            if (progress != null) {
                CircularProgressIndicator(progress = progress, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
            } else {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            }
        }

        Row(Modifier.padding(top = 8.dp)) {
            ProvideTextStyleContentColor(textStyle, color = MaterialTheme.colorScheme.onSurface) {
                text()
            }
        }
    }
}