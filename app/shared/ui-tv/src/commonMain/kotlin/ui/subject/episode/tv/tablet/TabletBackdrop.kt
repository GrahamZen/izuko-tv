/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.subject.details.layout.TvPadDetailsBackdrop
import me.him188.ani.app.ui.subject.details.layout.tvPadPlayerBackdropBlur
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel

/**
 * 分栏播放页铺的模糊底用哪张图 (同平板详情页, 见 SubjectDetailsPadPage 的 rememberTvPadBackdropUrl): TMDB 横版背景图,
 * 确认没有才用竖版海报 ([info] 的); 结果没出来之前不给图 (免得先铺海报再换)。按 [subjectId] 先取进程内热缓存 (从详情页点进来时已经查过,
 * 作品信息还没到也拿得到), 没有再按 [info] 的原名去查。
 */
@Composable
internal fun rememberTabletBackdropUrl(subjectId: Int, info: SubjectInfo): String? {
    val tmdb = remember { GlobalKoin.get<TmdbImageService>() }
    var resolved by remember(subjectId) { mutableStateOf(tmdb.peekBackdropResolved(subjectId)) }
    var tmdbUrl by remember(subjectId) { mutableStateOf(tmdb.peekBackdropUrl(subjectId)) }
    LaunchedEffect(subjectId, info.name) {
        if (subjectId <= 0 || resolved || info.name.isBlank()) return@LaunchedEffect
        tmdbUrl = try {
            tmdb.getBackdropUrl(subjectId, info.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        resolved = true
    }
    return tmdbUrl ?: info.imageLarge.takeIf { resolved && it.isNotBlank() }
}

/**
 * 播放页还没拿到页面状态时先画的 (挂在 LocalEpisodeScreenPlaceholder): 平板模式铺分栏播放页的底 (页面底色 + 这一部的模糊底,
 * 同 TabletPlayerFrame), 从详情页点进来的那一刻底接得上 (同一张图, 从详情页那一档渐变到播放页这一档), 不露出外层底色;
 * 电视上不画 (播放器本来就是黑底)。
 */
@Composable
fun TvEpisodeScreenPlaceholder(vm: EpisodeViewModel, modifier: Modifier) {
    if (!LocalTvTouchInputEnabled.current) return
    // 数据没到就退出时会话照样留着、照样装片子: 在这里就定下停不停
    TabletStartHoldDecision(vm)
    val url = rememberTabletBackdropUrl(vm.subjectId, SubjectInfo.Empty)
    Box(modifier.background(AniThemeDefaults.pageContentBackgroundColor)) {
        if (url != null) TvPadDetailsBackdrop(url, tvPadPlayerBackdropBlur())
    }
}
