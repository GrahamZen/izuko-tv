/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import me.him188.ani.app.data.network.TmdbCorrectedAiring
import me.him188.ani.app.data.network.correctAiringByTmdb
import me.him188.ani.app.ui.subject.AiringLabelState
import me.him188.ani.app.ui.subject.SubjectProgressState
import kotlin.time.Clock

/**
 * 同 [rememberAiringLabelState], 但 Bangumi 算出「未开播」或一集都没录、而 TMDB 显示已经开播时改成连载中 / 已完结
 * (见 [correctAiringByTmdb]): Bangumi 条目没人维护时, 分集没日期就算成未开播, 没录分集就只能按条目信息估成连载中.
 * 其余情况、TMDB 没匹配到或结果没到时就是 Bangumi 的.
 *
 * TV 详情页用: 只有它收集 [SubjectDetailsState.tmdbAiringFlow], 其他平台不发 TMDB 请求.
 */
@Composable
fun SubjectDetailsState.rememberTmdbAiringLabelState(uiState: SubjectDetailsUiState): AiringLabelState {
    val corrected = rememberTmdbCorrectedAiring(uiState)
    val airingInfo = corrected?.airingInfo ?: uiState.airingInfo
    val progressInfo = corrected?.progressInfo ?: uiState.progressInfo
    return remember(airingInfo, progressInfo) { AiringLabelState(airingInfo, progressInfo) }
}

/**
 * 同 [rememberSubjectProgressState], 但按 [rememberTmdbAiringLabelState] 改成已开播时, 按钮的「还未开播 / x 开播」
 * 改成「开始观看」. 要播的集不变.
 */
@Composable
fun SubjectDetailsState.rememberTmdbSubjectProgressState(uiState: SubjectDetailsUiState): SubjectProgressState {
    val progressInfo = rememberTmdbCorrectedAiring(uiState)?.progressInfo ?: uiState.progressInfo
    return remember(progressInfo) { SubjectProgressState(progressInfo) }
}

@Composable
private fun SubjectDetailsState.rememberTmdbCorrectedAiring(uiState: SubjectDetailsUiState): TmdbCorrectedAiring? {
    val tmdbAiring by tmdbAiringFlow.collectAsStateWithLifecycle(null)
    val airingInfo = uiState.airingInfo
    val progressInfo = uiState.progressInfo
    val totalEpisodes = info?.totalEpisodes ?: 0
    val hasEpisodes = uiState.episodeListUiState.allEpisodes.isNotEmpty()
    return remember(airingInfo, progressInfo, tmdbAiring, totalEpisodes, hasEpisodes) {
        airingInfo?.let {
            val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
            correctAiringByTmdb(
                it, progressInfo, tmdbAiring, today,
                subjectTotalEpisodes = totalEpisodes,
                bangumiHasEpisodes = hasEpisodes,
            )
        }
    }
}
