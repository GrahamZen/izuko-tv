/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.exploration.schedule.grid.TvScheduleGridPage
import me.him188.ani.app.ui.foundation.session.TvTouchBackButton
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.ProvideTvScrollActivity
import me.him188.ani.app.ui.foundation.tv.TvPosterWallTheme

/**
 * 新番时间表插槽 ([SchedulePageVariant]) 的电视版, 电视包与 iPad 两套外壳共用: 海报墙版式, 换上海报墙那套灰阶底色.
 *
 * 时间表是单独一页 (从探索页进来), 电视版没有侧边栏也没有返回: 触屏上 ([LocalTvTouchInputEnabled]) 左上角补一颗 ([onBack], 同侧边栏上的返回).
 */
@Composable
fun TvScheduleSlot(presentation: SchedulePagePresentation, onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        ProvideTvScrollActivity {
            TvPosterWallTheme { TvScheduleGridPage(presentation, onRetry, Modifier.fillMaxSize()) }
        }
        if (LocalTvTouchInputEnabled.current) TvTouchBackButton(onBack)
    }
}
