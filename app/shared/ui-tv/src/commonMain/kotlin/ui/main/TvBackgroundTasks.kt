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
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocations
import me.him188.ani.app.domain.media.cache.library.MediaLibraryPublisher
import me.him188.ani.app.domain.media.cache.location.MediaCacheMover
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.theme.glassContainerColor
import me.him188.ani.app.ui.foundation.tv.tvTouchFocusOnTap
import me.him188.ani.app.ui.foundation.tvLongPressKey
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_storage_cache_location_internal
import me.him188.ani.app.ui.lang.tv_background_task_cache_move
import me.him188.ani.app.ui.lang.tv_background_task_cache_move_cancel
import me.him188.ani.app.ui.lang.tv_background_task_cache_move_resume
import me.him188.ani.app.ui.lang.tv_background_task_cache_move_stop
import me.him188.ani.app.ui.lang.tv_background_task_cache_move_waiting
import me.him188.ani.app.ui.lang.tv_background_task_media_library
import me.him188.ani.app.ui.lang.tv_background_task_next
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * 动作面板顶上那一格里的一条: 一个在后台跑的任务.
 *
 * @property progress 0..1; `null` = 估不出来, 画不定式的进度条
 * @property open 长按任务本身去哪里看详情; `null` = 不能打开
 * @property hint 任务本身聚焦时面板底下的标签行写的 (说长按去哪)
 * @property resume 右端「继续」: 停在半路、等着什么的任务让它接着跑; `null` = 不画 (没在等, 或此刻接不上)
 * @property resumeLabel 「继续」聚焦时标签行写的
 * @property stop 右端 ✕ 停下 (取消) 它; `null` = 不画 ✕ (功能本身停不了, 或此刻停不了)
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
    val resume: (() -> Unit)? = null,
    val resumeLabel: String? = null,
    val stop: (() -> Unit)? = null,
    val stopLabel: String? = null,
)

/**
 * 正在后台跑的任务 (把缓存搬到别的盘、下完的缓存放入片库、导入收藏、换电视): 各功能自己跑、自己出进度, 这里汇成一个列表给动作面板顶上显示,
 * 用户离开那一页也看得到还在不在跑、跑到哪了. 结束时各功能照旧弹提示. 加一种任务: 在这里加一个来源.
 * 能停的给 ✕ (搬缓存; 换电视在下载阶段), 停的结果同那个功能自己停下时一样提示; 导入收藏停不了, 不给.
 *
 * 搬缓存有一部分因为在播先不搬时, 那一批搬完后这一条照样留着, 进度停在已经搬好的地方 ([MediaCacheMover.waiting]):
 * 「继续」关掉播放器 (停播后自己接着搬), ✕ 取消 (剩下的留在原处).
 *
 * @param closePlayer 关掉后台保留着的播放器; `null` = 此刻没有 (「继续」画成按不了)
 */
@Composable
internal fun rememberTvBackgroundTasks(closePlayer: (() -> Unit)?): List<TvBackgroundTask> {
    val mover = remember { GlobalKoin.getOrNull<MediaCacheMover>() }
    // 搬家每写 256 KB 报一次进度, 面板上半秒更新一次就够
    val moveFlow = remember { mover?.progress?.atMostEvery(500.milliseconds) ?: flowOf(null) }
    val move by moveFlow.collectAsState(mover?.progress?.value)
    val waitingFlow = remember { mover?.waiting ?: flowOf(null) }
    val waiting by waitingFlow.collectAsState(mover?.waiting?.value)
    val publisher = remember { GlobalKoin.getOrNull<MediaLibraryPublisher>() }
    val publishFlow = remember { publisher?.progress?.atMostEvery(500.milliseconds) ?: flowOf(null) }
    val publish by publishFlow.collectAsState(publisher?.progress?.value)

    val internalName = stringResource(Lang.settings_storage_cache_location_internal)
    val tasks = mutableListOf<TvBackgroundTask>()
    fun driveName(id: String?): String = id?.let {
        GlobalKoin.getOrNull<MediaCacheLocations>()?.locations?.value?.firstOrNull { it.id == id }?.label?.takeIf { it.isNotBlank() }
    } ?: internalName
    move?.let { p ->
        tasks += TvBackgroundTask(
            key = "cache-move",
            title = stringResource(Lang.tv_background_task_cache_move, driveName(p.target)),
            // 在播先不搬的不算处理完: 一批走完时进度停在那里, 换成下面等停播的那一条接着显示.
            // 还在列要搬哪些时没有数, 进度条不定式地走
            detail = if (p.total > 0) "${p.done + p.failed}/${p.total} · ${p.bytesDone.bytes} / ${p.bytesTotal.bytes}" else null,
            progress = if (p.bytesTotal > 0) (p.bytesDone.toFloat() / p.bytesTotal).coerceIn(0f, 1f) else null,
            // 停下的结果 (已搬的几条留在新位置) 由搬家结束的提示说 (见 TvLowSpaceToasts)
            stop = mover?.let { { it.cancel() } },
            stopLabel = stringResource(Lang.tv_background_task_cache_move_stop),
        )
    }
    if (move == null) waiting?.let { w ->
        // 同一个 key: 搬着搬着变成等停播 (或反过来) 时还是这一条, 显示的不换到别的任务上
        val total = w.doneBytes + w.bytes
        tasks += TvBackgroundTask(
            key = "cache-move",
            title = stringResource(Lang.tv_background_task_cache_move_waiting, driveName(w.target.location)),
            detail = "${w.doneUnits}/${w.doneUnits + w.units} · ${w.doneBytes.bytes} / ${total.bytes}",
            progress = if (total > 0) (w.doneBytes.toFloat() / total).coerceIn(0f, 1f) else null,
            // 播放器一关, 停播就会让搬家接着搬 (见 MediaStorageSetup.start), 这一条随即变回进度
            resume = closePlayer,
            resumeLabel = stringResource(Lang.tv_background_task_cache_move_resume),
            stop = mover?.let { { it.forgetSkipped() } },
            stopLabel = stringResource(Lang.tv_background_task_cache_move_cancel),
        )
    }
    // 下完的缓存自动放入片库, 一份一份来; 停不了 (自动的, 不给 ✕), 没有详情页
    publish?.let { p ->
        tasks += TvBackgroundTask(
            key = "media-library-publish",
            title = stringResource(Lang.tv_background_task_media_library),
            detail = "${p.label} · ${p.bytesDone.bytes} / ${p.bytesTotal.bytes}",
            progress = if (p.bytesTotal > 0) (p.bytesDone.toFloat() / p.bytesTotal).coerceIn(0f, 1f) else null,
        )
    }
    // 导入收藏、换电视: 只有安卓上有 (见 rememberPlatformBackgroundTasks)
    tasks += rememberPlatformBackgroundTasks()
    return tasks
}

/**
 * 至多每 [period] 给一个值 (中间的只留最新的), 但隔了一阵之后的第一个值马上给: 任务开始、搬缓存从「等停播」变回进度时不晚半拍,
 * 否则那一下两样都没有, 整格消失再出现, 焦点被交回卡片.
 */
private fun <T> Flow<T>.atMostEvery(period: Duration): Flow<T> = conflate().transform {
    emit(it)
    delay(period)
}

/**
 * 后台任务那一格 (动作面板最上面, 卡片上方), 没有任务时什么都不画. 同时只显示一个任务, 几个任务一起跑时也占这一格、高度不变
 * (同 iOS 的小组件叠放): 名字右边一排小圆点说一共几个、这是第几个. 照播放卡片的样子分成横排的两块 (同一块玻璃、同一个圆角):
 *
 * - 任务本身 (名字、进度): 按确定换到下一个 (最后一个之后回到第一个), 长按打开 ([TvBackgroundTask.open]).
 *   有任务就吃焦点: 从卡片按上永远先落在这里, 不会一上来就停在右端的按钮上; 只有一个打不开的任务时按确定没反应, 标签行写它的名字;
 * - 右端「继续」(文件夹里一个箭头, 读作「移动」而不是「播放」, 下面就是播放卡片): 停在半路的任务接着跑 ([TvBackgroundTask.resume]);
 * - 最右端 ✕: 按下去就停 (取消), 不再确认 (同播放卡片的 ✕).
 *
 * 右端这两列各自: 有任务用得上就一直占着这一列, 切换时任务那块不跟着变宽变窄; 显示的那个用不上时那一颗变淡、不吃焦点、点不动.
 * 一个用得上的都没有时不画这一列 (同播放卡片的占位卡不画永远灰着的 ✕).
 *
 * 左右键在几块之间走, 哪一块都按「下」回播放卡片 (由 [partModifier] 接上), 按「上」不动.
 * 显示的那个结束时换到相邻那个 (见 [tvBackgroundTaskShownIndex]); 焦点在右端的按钮上而它变得按不了时交给任务本身, 任务都结束了交回面板.
 *
 * @param onFocused 某块聚焦时 (聚焦着换了显示的任务也算), 面板底下标签行写的字, 以及要不要用错误色 (✕ 那块)
 * @param onFocusedRowGone 聚焦着时任务都结束了 (整格没了): 节点没了 Compose 不会自动改派焦点, 由面板接回卡片上
 * @param partModifier 加在每一块能聚焦的上面: 面板用它把「下」接到卡片
 * @param bodyModifier 只加在任务本身上: 面板用它把打开时的焦点送到这里 (开始移动缓存之后自动弹出面板时)
 */
@Composable
internal fun TvBackgroundTaskSlot(
    tasks: List<TvBackgroundTask>,
    onFocused: (label: String, danger: Boolean) -> Unit,
    onFocusedRowGone: () -> Unit,
    modifier: Modifier = Modifier,
    partModifier: Modifier = Modifier,
    bodyModifier: Modifier = Modifier,
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
    val resumeColumn = tasks.any { it.resume != null }
    val stopColumn = tasks.any { it.stop != null }
    val parts = buildSet {
        if (task != null) add(TvBackgroundTaskPart.Body)
        if (task?.resume != null) add(TvBackgroundTaskPart.Resume)
        if (task?.stop != null) add(TvBackgroundTaskPart.Stop)
    }
    var focusedPart by remember { mutableStateOf<TvBackgroundTaskPart?>(null) }
    val requesters = remember { TvBackgroundTaskPart.entries.associateWith { FocusRequester() } }
    // 聚焦着的那块没了 (右端的按钮随显示的任务换了 / 停了而按不了, 或者任务都结束了): 任务本身还在就接住, 不在就交回面板.
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
    // 任务本身聚焦时: 有几个就说按确定看下一个, 能打开就说长按去哪, 都没有时 (只有一个打不开的) 写名字
    val nextLabel = if (multiple) stringResource(Lang.tv_background_task_next, index + 1, tasks.size) else null
    val label = when (focusedPart) {
        TvBackgroundTaskPart.Body -> task?.let { t -> listOfNotNull(nextLabel, t.hint).joinToString(" · ").ifEmpty { t.title } to false }
        TvBackgroundTaskPart.Resume -> task?.resumeLabel?.let { it to false }
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
                .then(bodyModifier)
                // 右端都按不了时按右不动: 同一行右边没有能去的, 空间搜索会落到右下方播放卡片右端那块上
                .focusProperties {
                    if (TvBackgroundTaskPart.Resume !in parts && TvBackgroundTaskPart.Stop !in parts) right = FocusRequester.Cancel
                }
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
        if (resumeColumn) {
            TvBackgroundTaskButton(
                Icons.AutoMirrored.Rounded.DriveFileMove,
                contentDescription = task.resumeLabel,
                focused = focusedPart == TvBackgroundTaskPart.Resume,
                enabled = TvBackgroundTaskPart.Resume in parts,
                danger = false,
                Modifier
                    .part(TvBackgroundTaskPart.Resume, TV_BACKGROUND_TASK_TAG_RESUME)
                    // ✕ 按不了时按右不动 (同任务本身那条)
                    .focusProperties { if (TvBackgroundTaskPart.Stop !in parts) right = FocusRequester.Cancel },
            ) { task.resume?.invoke() }
        }
        if (stopColumn) {
            TvBackgroundTaskButton(
                Icons.Rounded.Close,
                contentDescription = task.stopLabel,
                focused = focusedPart == TvBackgroundTaskPart.Stop,
                enabled = TvBackgroundTaskPart.Stop in parts,
                danger = true,
                Modifier.part(TvBackgroundTaskPart.Stop, TV_BACKGROUND_TASK_TAG_STOP),
            ) { task.stop?.invoke() }
        }
    }
}

/** 格子里能聚焦的几块. */
internal enum class TvBackgroundTaskPart {
    Body, Resume, Stop;

    companion object {
        /** 聚焦着的那块没了时, 按这个顺序找一块接住 */
        val HANDOVER_ORDER = listOf(Body, Resume, Stop)
    }
}

internal const val TV_BACKGROUND_TASK_TAG_BODY = "tvBackgroundTaskBody"
internal const val TV_BACKGROUND_TASK_TAG_RESUME = "tvBackgroundTaskResume"
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
 * 格子右端的按钮 (「继续」与 ✕): 同播放卡片右端那块, 平时与任务本身同一块玻璃, 聚焦时实底 (✕ 是错误色, 「继续」是主题色)
 * —— 与任务本身的半透明高亮是两种材质, 焦点在哪一块一眼可辨.
 */
@Composable
private fun TvBackgroundTaskButton(
    icon: ImageVector,
    contentDescription: String?,
    focused: Boolean,
    /** 显示的那个用不上: 变淡、不吃焦点、点不动 (同 TV 上灰掉的按钮选不中的惯例) */
    enabled: Boolean,
    danger: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        when {
            !focused -> glassContainerColor()
            danger -> colors.error
            else -> colors.primary
        },
    )
    Box(
        modifier
            .width(56.dp)
            .fillMaxHeight()
            .background(container)
            .tvTouchFocusOnTap()
            // enabled = false 时 clickable 自己就不吃焦点, 也点不动
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = when {
                focused -> if (danger) colors.onError else colors.onPrimary
                enabled -> colors.onSurfaceVariant
                else -> colors.onSurfaceVariant.copy(alpha = 0.3f)
            },
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
