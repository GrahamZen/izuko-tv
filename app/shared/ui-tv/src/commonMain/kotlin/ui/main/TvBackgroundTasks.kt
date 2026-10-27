/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocations
import me.him188.ani.app.domain.media.cache.location.MediaCacheMover
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.theme.glassContainerColor
import me.him188.ani.app.ui.foundation.tv.tvTouchFocusOnTap
import me.him188.ani.app.ui.foundation.tvLongPressKey
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_storage_cache_location_internal
import me.him188.ani.app.ui.lang.tv_background_task_cache_move
import me.him188.ani.app.ui.lang.tv_background_task_cache_move_hint
import me.him188.ani.app.ui.lang.tv_background_task_cache_move_stop
import me.him188.ani.app.ui.lang.tv_background_task_device_migration
import me.him188.ani.app.ui.lang.tv_background_task_device_migration_stop
import me.him188.ani.app.ui.lang.tv_background_task_device_migration_stopped
import me.him188.ani.app.ui.lang.tv_background_task_next
import me.him188.ani.app.ui.lang.tv_background_task_profile_import
import me.him188.ani.app.ui.lang.tv_background_task_profile_import_waiting
import me.him188.ani.app.ui.profile.ProfileImportSession
import me.him188.ani.app.ui.remote.RemoteDeviceMigration
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

/**
 * 动作面板顶上那一格里的一条: 一个在后台跑的任务.
 *
 * @property progress 0..1; `null` = 估不出来, 画不定式的进度条
 * @property open 长按任务本身去哪里看详情; `null` = 不能打开
 * @property hint 任务本身聚焦时面板底下的标签行写的 (说长按去哪)
 * @property stop 右端 ✕ 停下它; `null` = 不画 ✕ (功能本身停不了, 或此刻停不了)
 * @property stopLabel ✕ 聚焦时标签行写的
 */
internal class TvBackgroundTask(
    /** 同一种任务恒定 (认出显示的那一条还在不在) */
    val key: String,
    val title: String,
    val detail: String?,
    val progress: Float?,
    val open: (() -> Unit)? = null,
    val hint: String? = null,
    val stop: (() -> Unit)? = null,
    val stopLabel: String? = null,
)

/**
 * 正在后台跑的任务 (把缓存搬到别的盘、导入收藏、换电视): 各功能自己跑、自己出进度, 这里汇成一个列表给动作面板顶上显示,
 * 用户离开那一页也看得到还在不在跑、跑到哪了. 结束时各功能照旧弹提示. 加一种任务: 在这里加一个来源.
 * 能停的给 ✕ (搬缓存; 换电视在下载阶段), 停的结果同那个功能自己停下时一样提示; 导入收藏停不了, 不给.
 *
 * @param openCacheMove 缓存搬家那一条按下去: 去设置页「存储」(那一行能看详情、停止)
 */
@OptIn(FlowPreview::class)
@Composable
internal fun rememberTvBackgroundTasks(openCacheMove: () -> Unit): List<TvBackgroundTask> {
    val mover = remember { GlobalKoin.getOrNull<MediaCacheMover>() }
    // 搬家每写 256 KB 报一次进度, 面板上半秒更新一次就够
    val moveFlow = remember { mover?.progress?.sample(500.milliseconds) ?: flowOf(null) }
    val move by moveFlow.collectAsState(mover?.progress?.value)
    val import by ProfileImportSession.state.collectAsState()
    val migration by RemoteDeviceMigration.progress.collectAsState()
    val toaster = LocalToaster.current

    val internalName = stringResource(Lang.settings_storage_cache_location_internal)
    val moveHint = stringResource(Lang.tv_background_task_cache_move_hint)
    val tasks = mutableListOf<TvBackgroundTask>()
    move?.let { p ->
        val target = p.target?.let { id ->
            GlobalKoin.getOrNull<MediaCacheLocations>()?.locations?.value?.firstOrNull { it.id == id }?.label?.takeIf { it.isNotBlank() }
        } ?: internalName
        tasks += TvBackgroundTask(
            key = "cache-move",
            title = stringResource(Lang.tv_background_task_cache_move, target),
            detail = "${p.done + p.failed + p.skipped}/${p.total} · ${p.bytesDone.bytes} / ${p.bytesTotal.bytes}",
            progress = if (p.bytesTotal > 0) (p.bytesDone.toFloat() / p.bytesTotal).coerceIn(0f, 1f) else null,
            open = openCacheMove,
            hint = moveHint,
            // 停下的结果 (已搬的几条留在新位置) 由搬家结束的提示说 (见 TvLowSpaceToasts)
            stop = mover?.let { { it.cancel() } },
            stopLabel = stringResource(Lang.tv_background_task_cache_move_stop),
        )
    }
    (import as? ProfileImportSession.State.Running)?.let { s ->
        tasks += TvBackgroundTask(
            key = "profile-import",
            title = stringResource(Lang.tv_background_task_profile_import, s.sourceName),
            detail = if (s.waitingForRateLimit) {
                stringResource(Lang.tv_background_task_profile_import_waiting)
            } else {
                "${s.done}/${s.total}"
            },
            progress = if (s.total > 0) s.done.toFloat() / s.total else null,
        )
    }
    migration?.let { m ->
        val stoppedText = stringResource(Lang.tv_background_task_device_migration_stopped)
        tasks += TvBackgroundTask(
            key = "device-migration",
            title = stringResource(Lang.tv_background_task_device_migration),
            detail = if (m.total > 0) "${m.text} ${m.done.bytes} / ${m.total.bytes}" else m.text,
            progress = if (m.total > 0) (m.done.toFloat() / m.total).coerceIn(0f, 1f) else null,
            // 开始写入之后停不了 (停在中间会留下一半), 那时不给 ✕. 手机上的进度页自己会看到「已停止」
            stop = if (m.cancellable) {
                { if (RemoteDeviceMigration.stop()) toaster.toast(stoppedText) }
            } else {
                null
            },
            stopLabel = stringResource(Lang.tv_background_task_device_migration_stop),
        )
    }
    return tasks
}

/**
 * 后台任务那一格 (动作面板最上面, 卡片上方), 没有任务时什么都不画. 同时只显示一个任务, 几个任务一起跑时也占这一格、高度不变
 * (同 iOS 的小组件叠放): 名字右边一排小圆点说一共几个、这是第几个. 照播放卡片的样子分成横排的两块 (同一块玻璃、同一个圆角):
 *
 * - 任务本身 (名字、进度): 按确定换到下一个 (最后一个之后回到第一个), 长按打开 ([TvBackgroundTask.open]);
 *   不止一个任务或能打开时才吃焦点;
 * - 右端 ✕, 能停 ([TvBackgroundTask.stop]) 才有: 按下去就停, 不再确认 (同播放卡片的 ✕).
 *
 * 左右键在两块之间走, 哪一块都按「下」回播放卡片 (由 [partModifier] 接上), 按「上」不动.
 * 一块能吃焦点的都没有时 (只有一个打不开、停不了的任务) 整格不吃焦点.
 * 显示的那个结束时换到相邻那个 (见 [tvBackgroundTaskShownIndex]); 聚焦着的那块跟着没了时, 另一块能聚焦就接住, 不能就交回面板.
 *
 * @param onFocused 某块聚焦时 (聚焦着换了显示的任务也算), 面板底下标签行写的字, 以及要不要用错误色 (✕ 那块)
 * @param onFocusedRowGone 聚焦着时整格没有能聚焦的了: 节点没了 Compose 不会自动改派焦点, 由面板接回卡片上
 * @param partModifier 加在每一块能聚焦的上面: 面板用它把「下」接到卡片
 */
@Composable
internal fun TvBackgroundTaskSlot(
    tasks: List<TvBackgroundTask>,
    onFocused: (label: String, danger: Boolean) -> Unit,
    onFocusedRowGone: () -> Unit,
    modifier: Modifier = Modifier,
    partModifier: Modifier = Modifier,
) {
    // 显示的是哪个 (key), 和它当时排第几 (它结束时按位置找邻居)
    var shownKey by remember { mutableStateOf<String?>(null) }
    var shownIndex by remember { mutableIntStateOf(0) }
    val index = tvBackgroundTaskShownIndex(tasks.map { it.key }, shownKey, shownIndex)
    val task = tasks.getOrNull(index)
    SideEffect {
        if (task != null) {
            shownKey = task.key
            shownIndex = index
        }
    }

    val multiple = tasks.size > 1
    val parts = buildSet {
        if (multiple || task?.open != null) add(TvBackgroundTaskPart.Body)
        if (task?.stop != null) add(TvBackgroundTaskPart.Stop)
    }
    var focusedPart by remember { mutableStateOf<TvBackgroundTaskPart?>(null) }
    val requesters = remember { TvBackgroundTaskPart.entries.associateWith { FocusRequester() } }
    // 聚焦着的那块没了 (显示的任务停了 / 换了 / 只剩一个): 另一块能聚焦就接住, 都不能就交回面板.
    // 放在组合作用域里起的协程中: 焦点被清掉的回调随时会到、把 focusedPart 清掉, 跟着条件进出组合的 LaunchedEffect 可能还没跑就被取消
    val scope = rememberCoroutineScope()
    if (focusedPart != null && focusedPart !in parts) {
        SideEffect {
            focusedPart = null
            val next = TvBackgroundTaskPart.HANDOVER_ORDER.firstOrNull { it in parts }
            scope.launch {
                if (next == null || runCatching { requesters.getValue(next).requestFocus() }.isFailure) onFocusedRowGone()
            }
        }
    }
    // 任务本身聚焦时: 有几个就说按确定看下一个, 能打开就说长按去哪, 都没有 (不会聚焦) 时退回名字
    val nextLabel = if (multiple) stringResource(Lang.tv_background_task_next, index + 1, tasks.size) else null
    val label = when (focusedPart) {
        TvBackgroundTaskPart.Body -> task?.let { t -> listOfNotNull(nextLabel, t.hint).joinToString(" · ").ifEmpty { t.title } to false }
        TvBackgroundTaskPart.Stop -> task?.stopLabel?.let { it to true }
        null -> null
    }
    LaunchedEffect(label) {
        label?.let { (text, danger) -> onFocused(text, danger) }
    }
    if (task == null) return

    fun Modifier.part(part: TvBackgroundTaskPart, tag: String): Modifier = this
        .then(partModifier)
        .focusRequester(requesters.getValue(part))
        .onFocusChanged {
            if (it.isFocused) focusedPart = part else if (focusedPart == part) focusedPart = null
        }
        .testTag(tag)

    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp)),
    ) {
        val showNext = {
            if (multiple) {
                val next = (index + 1) % tasks.size
                shownKey = tasks[next].key
                shownIndex = next
            }
        }
        // 任务本身: 同播放卡片的主体, 玻璃底, 聚焦时半透明主题色
        val bodyContainer by animateColorAsState(
            if (focusedPart == TvBackgroundTaskPart.Body) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
            } else {
                glassContainerColor()
            },
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .part(TvBackgroundTaskPart.Body, TV_BACKGROUND_TASK_TAG_BODY)
                .focusProperties { canFocus = TvBackgroundTaskPart.Body in parts }
                .background(bodyContainer)
                // 遥控器: 短按换下一个, 按住打开; 确认键由它认领, 不落到下面的 combinedClickable 上
                .tvLongPressKey(onLongPress = { task.open?.invoke() }, onShortPress = showNext)
                .tvTouchFocusOnTap()
                // 触屏: 点一下换下一个, 长按打开
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onLongClick = task.open,
                    onClick = showNext,
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            // 名字占一整行 (面板不宽, 和数字挤在一行会被截断), 右端是第几个的小圆点; 下面一行是进度条与数字
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (multiple) {
                    Spacer(Modifier.width(12.dp))
                    TvBackgroundTaskDots(count = tasks.size, current = index)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
                val progress = task.progress
                if (progress == null) {
                    LinearProgressIndicator(Modifier.weight(1f), trackColor = trackColor, strokeCap = StrokeCap.Round)
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.weight(1f),
                        trackColor = trackColor,
                        strokeCap = StrokeCap.Round,
                    )
                }
                task.detail?.let {
                    Spacer(Modifier.width(12.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        if (TvBackgroundTaskPart.Stop in parts) {
            TvBackgroundTaskStopButton(
                contentDescription = task.stopLabel,
                focused = focusedPart == TvBackgroundTaskPart.Stop,
                Modifier.part(TvBackgroundTaskPart.Stop, TV_BACKGROUND_TASK_TAG_STOP),
            ) { task.stop?.invoke() }
        }
    }
}

/** 格子里能聚焦的两块. */
internal enum class TvBackgroundTaskPart {
    Body, Stop;

    companion object {
        /** 聚焦着的那块没了时, 按这个顺序找一块接住 */
        val HANDOVER_ORDER = listOf(Body, Stop)
    }
}

internal const val TV_BACKGROUND_TASK_TAG_BODY = "tvBackgroundTaskBody"
internal const val TV_BACKGROUND_TASK_TAG_STOP = "tvBackgroundTaskStop"

/** 一共 [count] 个、这是第 [current] 个: 同探索页大图下面那排, 当前那个拉长成短条. */
@Composable
private fun TvBackgroundTaskDots(count: Int, current: Int) {
    val color = MaterialTheme.colorScheme.onSurface
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(width = if (i == current) 12.dp else 6.dp, height = 6.dp)
                    .background(color.copy(alpha = if (i == current) 0.8f else 0.3f), CircleShape),
            )
        }
    }
}

/**
 * 格子右端的 ✕: 同播放卡片右端那块, 平时与任务本身同一块玻璃, 聚焦时错误色实底 —— 与任务本身的半透明高亮是两种材质,
 * 焦点在哪一块一眼可辨.
 */
@Composable
private fun TvBackgroundTaskStopButton(
    contentDescription: String?,
    focused: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(if (focused) MaterialTheme.colorScheme.error else glassContainerColor())
    Box(
        modifier
            .width(56.dp)
            .fillMaxHeight()
            .background(container)
            .tvTouchFocusOnTap()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Close,
            contentDescription = contentDescription,
            tint = if (focused) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 后台任务那一格显示第几个: 记着的那个 ([shownKey]) 还在就是它; 它结束了就取它原来位置 ([shownIndex]) 上现在的那个
 * (即它后面那个), 原来是最后一个就取新的最后一个. 没有任务时 -1.
 */
internal fun tvBackgroundTaskShownIndex(keys: List<String>, shownKey: String?, shownIndex: Int): Int {
    if (keys.isEmpty()) return -1
    val found = keys.indexOf(shownKey)
    return if (found >= 0) found else shownIndex.coerceIn(0, keys.lastIndex)
}
