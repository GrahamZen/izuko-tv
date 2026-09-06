/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import me.him188.ani.app.data.models.preference.TvTitleLogoDisplay
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TvTitleLogoFlip
import me.him188.ani.app.ui.foundation.tv.TvTitleLogoLookup
import me.him188.ani.app.ui.foundation.tv.rememberTvTitleLogoBox
import me.him188.ani.app.ui.foundation.tv.rememberTvTitleLogoLookup
import me.him188.ani.app.ui.subject.episode.SubjectPresentation

/**
 * 播放器左上角的作品标题: 有标题 logo (见 ThemeSettings.tvTitleLogoDisplay, 与详情页首屏同一张) 时显示 logo, 设置里关了、这部没有
 * logo、列表页或详情页判过看不清且设置为「看不清时显示文字」(见 TvTitleLogoUnreadable), 或图加载失败时显示文字. 画面一直在变, 不按画面判:
 * 自动调色时照白字底按色调翻, 别的档原样. 播放器里的内嵌详情页不用 logo (见 SubjectDetailsTvPage 的 videoBackground).
 *
 * 大小同列表页与详情页 (见 rememberTvTitleLogoBox); 标题块的下缘是浮出面板往上长的边界, 标题块越高面板越矮.
 * 还不知道有没有 logo 时 (多半是直接进的播放页, 没经过详情页与列表页的预取) 先空着最多 [TV_PLAYER_TITLE_LOGO_WAIT_MILLIS],
 * 不先出文字再换成 logo.
 */
@Composable
internal fun TvPlayerTitle(subject: SubjectPresentation, modifier: Modifier = Modifier) {
    val lookup = if (subject.isPlaceholder) {
        TvTitleLogoLookup.None
    } else {
        rememberTvTitleLogoLookup(subject.info.subjectId, subject.info.name)
    }
    val logo = lookup.logo
    var logoFailed by remember(logo) { mutableStateOf(false) }
    val box = rememberTvTitleLogoBox()
    if (logo != null && box != null && !logoFailed) {
        val size = box.sizeOf(logo.aspectRatio)
        val density = LocalDensity.current
        AsyncImage(
            logo.url(size.widthPx),
            contentDescription = subject.title,
            modifier.size(with(density) { size.widthPx.toDp() }, with(density) { size.heightPx.toDp() }),
            onError = { logoFailed = true },
            alignment = Alignment.BottomStart,
            contentScale = ContentScale.Fit,
            crossfade = false,
            transformations = if (LocalThemeSettings.current.tvTitleLogoDisplay == TvTitleLogoDisplay.Auto) {
                listOf(TvTitleLogoFlip(lightText = true))
            } else {
                emptyList()
            },
        )
        return
    }
    var waited by remember { mutableStateOf(false) }
    if (lookup.pending) {
        LaunchedEffect(Unit) {
            delay(TV_PLAYER_TITLE_LOGO_WAIT_MILLIS)
            waited = true
        }
    }
    Text(
        subject.title,
        // 等 logo 那一会儿照样占着一行文字的高, 下面的集号与数据源不跳
        modifier.alpha(if (lookup.pending && !waited) 0f else 1f),
        color = Color.White,
        style = MaterialTheme.typography.headlineLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}


/** 还不知道有没有 logo 时最多等多久再显示文字. */
private const val TV_PLAYER_TITLE_LOGO_WAIT_MILLIS = 1000L
