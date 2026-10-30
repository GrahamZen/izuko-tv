/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.him188.ani.app.data.repository.subject.RewatchStartResult
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.ui.foundation.widgets.AniDropdownMenuItem
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_collection_rewatch
import me.him188.ani.app.ui.lang.subject_collection_rewatch_pending_sync
import me.him188.ani.app.ui.lang.subject_collection_rewatching
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import kotlin.coroutines.cancellation.CancellationException

/**
 * 收藏菜单里的重温 (见 `SubjectRewatch`): 看过的番在收藏菜单末尾多一项「重温」, 点了从头记这一轮的进度;
 * 重温中画成选中项 (收藏类型那几项都不勾), 菜单里选任何收藏状态就结束重温.
 * 详情页的收藏胶囊、播放器的收藏按钮用 [rememberTvSubjectRewatch], 各页卡片长按的菜单用 [rememberTvRewatches].
 *
 * @param rewatching 这一部正在重温
 * @param start 开始重温; 在调用方 (胶囊 / 页面) 的协程里跑, 菜单关掉不会把它取消
 */
@Immutable
internal class TvSubjectRewatch(val rewatching: Boolean, val start: () -> Unit)

@Composable
internal fun rememberTvSubjectRewatch(subjectId: Int): TvSubjectRewatch {
    val repository = remember { KoinPlatform.getKoin().get<SubjectCollectionRepository>() }
    val rewatching by remember(repository, subjectId) { repository.rewatchingFlow(subjectId) }
        .collectAsStateWithLifecycle(false)
    val start = rememberRewatchStarter(repository)
    return remember(rewatching, subjectId, start) {
        TvSubjectRewatch(rewatching) { start.value(subjectId) {} }
    }
}

/**
 * 一页卡片长按的收藏菜单用的重温 (探索 / 搜索 / 时间表 / 追番). 正在重温的条目在页面上收着: 菜单长按才组合,
 * 在菜单里现收的话头几帧还没取到, 会先画成在看再变.
 */
@Stable
internal class TvRewatches(
    private val ids: State<Set<Int>>,
    private val start: State<(subjectId: Int, onFailed: () -> Unit) -> Unit>,
) {
    /**
     * @param beforeStart 点了「重温」、开始之前 (开始后条目改成在看; 追番页不在在看标签时卡片要离开, 先把焦点挪开)
     * @param onFailed 没开始成 (原因已经 toast 过)
     */
    fun of(subjectId: Int, beforeStart: () -> Unit = {}, onFailed: () -> Unit = {}): TvSubjectRewatch =
        TvSubjectRewatch(subjectId in ids.value) {
            beforeStart()
            start.value(subjectId, onFailed)
        }
}

@Composable
internal fun rememberTvRewatches(): TvRewatches {
    val repository = remember { KoinPlatform.getKoin().get<SubjectCollectionRepository>() }
    val ids = remember(repository) { repository.rewatchingSubjectsFlow() }.collectAsStateWithLifecycle(emptySet())
    val start = rememberRewatchStarter(repository)
    return remember(ids, start) { TvRewatches(ids, start) }
}

/** 开始重温的动作: 在调用方的协程里跑, 没开始成就 toast 原因再回调 onFailed. */
@Composable
private fun rememberRewatchStarter(
    repository: SubjectCollectionRepository,
): State<(subjectId: Int, onFailed: () -> Unit) -> Unit> {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val pendingSyncText = stringResource(Lang.subject_collection_rewatch_pending_sync)
    return rememberUpdatedState<(Int, () -> Unit) -> Unit> { subjectId, onFailed ->
        scope.launch {
            val result = try {
                repository.startRewatch(subjectId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toaster.showLoadError(LoadError.fromException(e))
                onFailed()
                return@launch
            }
            if (result == RewatchStartResult.PENDING_SYNC) toaster.toast(pendingSyncText)
            if (result != RewatchStartResult.STARTED) onFailed()
        }
    }
}

/** 胶囊上重温中时的图标与文字, 代替收藏类型的. */
@Composable
internal fun TvRewatchIcon() {
    Icon(Icons.Rounded.Replay, contentDescription = null)
}

@Composable
internal fun TvRewatchingLabel() {
    Text(stringResource(Lang.subject_collection_rewatching), softWrap = false)
}

/**
 * 收藏菜单末尾的「重温」: 只有看过的番有; 重温中画成选中项 (要结束就选别的收藏状态).
 *
 * @param collectionType 本地的收藏类型
 */
@Composable
internal fun TvRewatchMenuItem(
    rewatch: TvSubjectRewatch,
    collectionType: UnifiedCollectionType,
    onDismissRequest: () -> Unit,
) {
    if (!rewatch.rewatching && collectionType != UnifiedCollectionType.DONE) return
    AniDropdownMenuItem(
        text = { Text(stringResource(Lang.subject_collection_rewatch)) },
        leadingIcon = { TvRewatchIcon() },
        selected = rewatch.rewatching,
        // 同收藏类型那几项: 先动作再关菜单 (追番页要在菜单关掉之前把焦点挪开)
        onClick = {
            if (!rewatch.rewatching) rewatch.start()
            onDismissRequest()
        },
    )
}
