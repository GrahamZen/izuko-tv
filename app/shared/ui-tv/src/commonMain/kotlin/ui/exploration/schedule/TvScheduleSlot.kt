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
import me.him188.ani.app.data.models.preference.TvScheduleLayout
import me.him188.ani.app.ui.exploration.schedule.grid.TvScheduleGridPage
import me.him188.ani.app.ui.foundation.session.TvTouchBackButton
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.ProvideTvScrollActivity
import me.him188.ani.app.ui.foundation.tv.TvPosterWallTheme

/**
 * 新番时间表插槽 ([SchedulePageVariant]) 的电视版, 电视包与 iPad 两套外壳共用: 两版版式按 [TvScheduleLayout] 分流 (上游那一档在
 * ScheduleScreen 就挡住了, 到不了这里; 改版换掉的东西未必人人都想要, 所以旧版留着可选). 海报墙那版换上海报墙那套灰阶底色.
 *
 * 时间表是单独一页 (从探索页进来), 电视版没有侧边栏也没有返回: 触屏上 ([LocalTvTouchInputEnabled]) 左上角补一颗 ([onBack], 同侧边栏上的返回).
 */
@Composable
fun TvScheduleSlot(presentation: SchedulePagePresentation, onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        ProvideTvScrollActivity {
            when (LocalThemeSettings.current.tvScheduleLayout) {
                TvScheduleLayout.Grid -> TvPosterWallTheme { TvScheduleGridPage(presentation, onRetry, Modifier.fillMaxSize()) }
                else -> TvSchedulePage(presentation, onRetry, Modifier.fillMaxSize())
            }
        }
        if (LocalTvTouchInputEnabled.current) TvTouchBackButton(onBack)
    }
}
