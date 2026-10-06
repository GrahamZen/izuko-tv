/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.him188.ani.app.ui.foundation.TextWithBorder
import me.him188.ani.app.ui.foundation.animation.AniAnimatedVisibility
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_player_sources_hint
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

/**
 * 加载指示下面那句「按 ▲ 选择数据源」. 选源面板只有纯视频态的上键能打开 (控制层上没有按钮), 这句话在最用得上的时候说出来:
 * 选源 / 解析 / 缓冲挂了一会儿还没好 ([loading] 持续满 [TV_SOURCES_HINT_DELAY]), 或者已经失败 / 卡在要手选 ([urgent], 当场出).
 * 秒开的不提示, 免得每次开播都闪一下. 只在纯视频态 ([enabled]) 出现: 控制层在场时上键是在控件之间走.
 */
@Composable
internal fun TvPlayerSourcesHint(
    loading: Boolean,
    urgent: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        waited = false
        if (loading) {
            delay(TV_SOURCES_HINT_DELAY)
            waited = true
        }
    }
    AniAnimatedVisibility(
        visible = enabled && loading && (urgent || waited),
        modifier = modifier,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        TextWithBorder(
            stringResource(Lang.tv_player_sources_hint),
            modifier = Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private val TV_SOURCES_HINT_DELAY = 3.seconds
