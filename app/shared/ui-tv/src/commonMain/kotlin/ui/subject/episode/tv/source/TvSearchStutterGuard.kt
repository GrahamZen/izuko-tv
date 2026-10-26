/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import me.him188.ani.app.domain.settings.SettingAdjustment
import me.him188.ani.app.domain.settings.SettingAdjustments
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel

/**
 * 播放哨兵 (TvPlaybackSentinel) 判出「搜索导致画面卡顿」时报到这里 (见 PerfPlaybackAnalysis.searchBlamed), 播放页的 [TvSearchStutterGuard] 收.
 * 同一集只报一次: 改过设置、暂停过搜索之后再卡就不是搜索的事了.
 */
internal object TvSearchStutter {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: SharedFlow<Unit> get() = _events

    @Volatile
    private var reportedEpisode: String? = null

    /** 在哨兵线程上调. [episode] 是哨兵记的这一集 (null = 不知道是哪一集, 照样报). */
    fun report(episode: String?) {
        if (episode != null && episode == reportedEpisode) return
        reportedEpisode = episode
        _events.tryEmit(Unit)
    }
}

/**
 * 播放页上挂一个: 播放时搜索导致画面卡顿, 设置「完整搜索」没选过的改成「只手动」并当场暂停搜索 (见 [EpisodeViewModel.onSearchCausedStutter]),
 * 改了就经 [SettingAdjustments] 告知一声 (可在设置里改回).
 */
@Composable
fun TvSearchStutterGuard(vm: EpisodeViewModel) {
    val adjustments = remember { GlobalKoin.get<SettingAdjustments>() }
    LaunchedEffect(vm) {
        TvSearchStutter.events.collect {
            if (vm.onSearchCausedStutter()) adjustments.notify(SettingAdjustment.FullSearchStutter)
        }
    }
}
