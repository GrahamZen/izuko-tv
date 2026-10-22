/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.ui.foundation.tv.TV_TMDB_TEXT_WAIT
import me.him188.ani.app.ui.foundation.tv.tvPrefersTmdbText
import me.him188.ani.app.ui.subject.details.state.SubjectDetailsState

/**
 * 电视详情页 (遥控器版与平板版) 展示用的简介.
 *
 * 优先级: Ani 服务器 > bgm.tv (仅替代不合并) > TMDB 中文. Ani 有简介时用它 (全外文则被 TMDB 中文替换); Ani 为空时等 bgm.tv 结果
 * (未出结果先按空显示, 避免先闪 TMDB 再换成 bgm), bgm.tv 也没有才用 TMDB 中文兜底.
 * 非中文界面: 有讲本条目的 TMDB 简介就用它, 不等 bgm.tv 兜底 (见 SubjectDetailsStateFactory 的 summaryOverride);
 * TMDB 那段还没出结果时简介先空着 (最多等 [TV_TMDB_TEXT_WAIT]), 免得先闪 Bangumi 的再换成 TMDB 的.
 */
@Composable
internal fun rememberTvDetailsDisplaySummary(state: SubjectDetailsState, info: SubjectInfo): String {
    // 整段替换 Bangumi 简介的 TMDB 简介 (非中文界面是界面语言的; 中文界面仅当 Bangumi 简介整段无中文); null = 用原文
    val tmdbSummaryOverride by state.tmdbSummaryOverrideFlow.collectAsStateWithLifecycle(null)
    // Ani 服务器简介为空时的 bgm.tv 兜底 (null = 结果未出, "" = bgm 也没有, 非空 = bgm 简介)
    val bangumiSummaryFallback by state.bangumiSummaryFallbackFlow.collectAsStateWithLifecycle(null)
    val tmdbSubjectText by state.tmdbSubjectTextFlow.collectAsStateWithLifecycle(null)
    var tmdbWaitOver by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state) {
        delay(TV_TMDB_TEXT_WAIT)
        tmdbWaitOver = true
    }
    val tmdbPending = tvPrefersTmdbText() && tmdbSubjectText == null && !tmdbWaitOver
    return if (tmdbPending) {
        ""
    } else if (info.summary.isNotBlank() || (tvPrefersTmdbText() && tmdbSummaryOverride != null)) {
        tmdbSummaryOverride ?: info.summary
    } else when (bangumiSummaryFallback) {
        null -> ""
        "" -> tmdbSummaryOverride.orEmpty()
        else -> bangumiSummaryFallback.orEmpty()
    }
}
