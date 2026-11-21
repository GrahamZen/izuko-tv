/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.collection

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import me.him188.ani.app.data.models.preference.TvBackdropBlurLevel
import me.him188.ani.app.data.models.preference.TvPosterConfirmAction
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageRows
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageRowsSpec
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeWallStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.toTvNativeTextStyle
import me.him188.ani.app.ui.foundation.tv.tvPosterWallHeaderStyle

/** 安卓上「追更」那份内容是原生的行式海报墙: 卡片样式 (带数字角标)、几何与组标题在这里建. */
@Composable
internal actual fun rememberTvCollectionProgressRows(key: Int): TvNativeGridPageRows {
    val density = LocalDensity.current
    val layout = tvCollectionProgressLayout()
    val settings = LocalThemeSettings.current
    // 铺着模糊背景时卡片的字照 vibrancy 画 (同网格, 见 TvNativeGridPageHost); 没看几集画在封面右上角 (见 TvProgressEntry.badgeCount)
    val heroBlur = settings.tvPosterConfirm == TvPosterConfirmAction.Hero && settings.tvWallBackdropBlur != TvBackdropBlurLevel.None
    val style = rememberTvNativeWallStyle(layout.cardWidth, layout.metrics.columns, labelVibrancy = heroBlur, countBadge = true)
    val headerStyle = tvPosterWallHeaderStyle().toTvNativeTextStyle(density, LocalContentColor.current)
    return remember(key, style, layout.metrics, layout.cardWidth, headerStyle) {
        TvNativeGridPageRowsSpec(key, style, layout.metrics, layout.cardWidth, headerStyle)
    }
}
